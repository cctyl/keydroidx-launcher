package ru.playsoftware.mini_shizuku.server;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@code input tap / input swipe} 命令的快速通道。
 * <p>
 * 背景：原路径 {@link ShellUtil#execute(String)} 会 fork {@code /system/bin/sh -c ...}，
 * 而 {@code /system/bin/input} 又是一个 shell 脚本，内部再
 * {@code exec app_process ... com.android.commands.input.Input}——即每条命令要付
 * 两次 fork + 一次完整的 Dalvik VM 冷启动（4.4 实测约 704ms） + input.jar 加载 + 进程销毁，
 * 于是一次点击要 1.2s 左右才有反应。
 * <p>
 * 本类改为：在<b>本进程</b>（{@code app_process}，uid 2000/0，已持有 INJECT_EVENTS）内直接
 * 构造 MotionEvent，通过隐藏 API {@code InputManager.injectInputEvent} 注入，
 * 事件参数对齐 AOSP {@code Input.java}（source 取 SOURCE_TOUCHSCREEN、deviceId/pressure/
 * precision/edgeFlags 取默认值、swipe 按 AOSP 的 lerp 插值补 MOVE）。
 * <p>
 * 设计红线（见 docs/mini_shizuku输入注入快速通道设计方案.md）：
 * <ul>
 *     <li><b>只认得下的命令</b>：严格白名单解析 {@code input tap <int> <int>} 与
 *         {@code input swipe <int>*4 [<int>]}，其余（含 {@code input keyevent} 的
 *         符号名/多键形态、{@code input text}、带引号的重定向等）一律返回 false
 *         交给 {@link ShellUtil} 原路径，绝不猜参数。</li>
 *     <li><b>出错就退</b>：反射失败、注入抛异常都返回 false，由调用方回退 shell；
 *         反射失败后本进程内<b>永久</b>关闭快路径，避免每条命令都重复付反射代价
 *         （服务端是常驻进程，别让它被反复拖累）。</li>
 *     <li><b>不阻塞</b>：注入用 ASYNC 模式（AOSP 用 WAIT_FOR_FINISH，那会让
 *         injectInputEvent 等到事件被处理完，若目标 App 卡住会占死线程池，
 *         参见 SocketService 的 CallerRunsPolicy——16 个线程耗尽后 accept 线程会被
 *         拖下水，整个服务端对所有生态应用失联）。</li>
 *     <li><b>不重复执行</b>：只有"第一个事件（DOWN）没注入成功"才返回 false
 *         允许回退 shell；后续事件失败只记日志——否则 shell 回退会把同一次点击做两遍。</li>
 * </ul>
 */
public final class InputInjector {

    private static final String TAG = "MiniShizuku";

    /** AOSP Input.java 的默认值，逐项对齐以保证事件语义一致。 */
    private static final float DEFAULT_SIZE = 1.0f;
    private static final int DEFAULT_META_STATE = 0;
    private static final float DEFAULT_PRECISION = 1.0f;
    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int DEFAULT_EDGE_FLAGS = 0;

    /** INJECT_INPUT_EVENT_MODE_ASYNC 在本进程内读取失败时的兜底值（自 JellyBean 起恒为 0）。 */
    private static final int MODE_ASYNC_FALLBACK = 0;

    /**
     * swipe 时长上限。AOSP 的 swipe 是在 while 循环里 sleep 并插值，时长由调用方给；
     * 服务端是常驻进程，超长时长会长时间占着一个线程，超过上限就退回 shell
     * （换成子进程去阻塞，代价由它自己承担）。
     */
    private static final int MAX_SWIPE_DURATION_MS = 2000;

    private static boolean initDone;
    private static boolean available;
    private static Object inputManager;
    private static Method injectMethod;
    private static int asyncMode = MODE_ASYNC_FALLBACK;

    private InputInjector() {
    }

    /**
     * 尝试用快路径执行一条 {@code input tap / input swipe} 命令。
     *
     * @param cmd 已剥掉 {@code EXEC|} 前缀的命令串
     * @return true = 已接管（注入完成，或至少 DOWN 已注入，<b>不要再回退 shell</b>）；
     *         false = 本类处理不了/未注入任何事件，调用方应回退 {@link ShellUtil}
     */
    public static boolean handle(String cmd) {
        if (cmd == null) {
            return false;
        }
        // 先用前缀挡一道：绝大多数命令不是 input，别为它们白做一次正则 split
        if (!cmd.startsWith("input ")) {
            return false;
        }
        String[] args = cmd.trim().split("\\s+");
        // 最短形态 "input tap x y" 也有 4 段
        if (args.length < 4 || !"input".equals(args[0])) {
            return false;
        }
        if ("tap".equals(args[1])) {
            if (args.length != 4) {
                return false;
            }
            Integer x = parseIntStrict(args[2]);
            Integer y = parseIntStrict(args[3]);
            if (x == null || y == null) {
                return false;
            }
            return tap(x.intValue(), y.intValue());
        }
        if ("swipe".equals(args[1])) {
            if (args.length != 6 && args.length != 7) {
                return false;
            }
            // input swipe x1 y1 x2 y2 [duration]
            int[] v = new int[4];
            for (int i = 0; i < 4; i++) {
                Integer n = parseIntStrict(args[i + 2]);
                if (n == null) {
                    return false;
                }
                v[i] = n.intValue();
            }
            int duration = 0;
            if (args.length == 7) {
                Integer d = parseIntStrict(args[6]);
                if (d == null || d.intValue() < 0 || d.intValue() > MAX_SWIPE_DURATION_MS) {
                    return false;
                }
                duration = d.intValue();
            }
            return swipe(v[0], v[1], v[2], v[3], duration);
        }
        // 其余 input 子命令（keyevent / text / press / roll ...）：不猜，走原路径
        return false;
    }

    /** 点击：DOWN + UP，时间戳与 AOSP sendTap 一致（同一时刻）。 */
    private static boolean tap(int x, int y) {
        if (!ensureReady()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (!injectMotion(MotionEvent.ACTION_DOWN, now, x, y, 1.0f)) {
            return false;
        }
        if (!injectMotion(MotionEvent.ACTION_UP, now, x, y, 0.0f)) {
            Log.w(TAG, "tap: up not injected (down already delivered)");
        }
        return true;
    }

    /** 滑动：DOWN → 按 AOSP 的 lerp 插值补 MOVE → UP。 */
    private static boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        if (!ensureReady()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (!injectMotion(MotionEvent.ACTION_DOWN, now, x1, y1, 1.0f)) {
            return false;
        }
        if (duration > 0) {
            long startTime = now;
            long endTime = startTime + duration;
            while (now < endTime) {
                long elapsed = now - startTime;
                float alpha = (float) elapsed / duration;
                if (!injectMotion(MotionEvent.ACTION_MOVE, now,
                        lerp(x1, x2, alpha), lerp(y1, y2, alpha), 1.0f)) {
                    Log.w(TAG, "swipe: move not injected, stop interpolating");
                    break;
                }
                now = SystemClock.uptimeMillis();
            }
        }
        if (!injectMotion(MotionEvent.ACTION_UP, now, x2, y2, 0.0f)) {
            Log.w(TAG, "swipe: up not injected (down already delivered)");
        }
        return true;
    }

    private static float lerp(float a, float b, float alpha) {
        return a + (b - a) * alpha;
    }

    private static boolean injectMotion(int action, long when, float x, float y, float pressure) {
        MotionEvent event = null;
        try {
            event = MotionEvent.obtain(when, when, action, x, y, pressure, DEFAULT_SIZE,
                    DEFAULT_META_STATE, DEFAULT_PRECISION, DEFAULT_PRECISION,
                    DEFAULT_DEVICE_ID, DEFAULT_EDGE_FLAGS);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            return inject(event);
        } catch (Throwable t) {
            Log.w(TAG, "injectMotion failed", t);
            return false;
        } finally {
            if (event != null) {
                event.recycle();
            }
        }
    }

    private static boolean inject(InputEvent event) {
        try {
            Boolean ok = (Boolean) injectMethod.invoke(inputManager, event, Integer.valueOf(asyncMode));
            return ok != null && ok.booleanValue();
        } catch (Throwable t) {
            // 注入异常（binder 挂了 / 签名变了 / 权限被撤）：本进程内永久退回 shell，
            // 避免每条命令都重试反射、也避免坏状态持续拖住线程池。
            Log.e(TAG, "injectInputEvent failed, disabling fast path", t);
            synchronized (InputInjector.class) {
                initDone = true;
                available = false;
                inputManager = null;
                injectMethod = null;
            }
            return false;
        }
    }

    /** 一次性自检：反射拿到 InputManager 与异步注入模式；失败则本进程内不再尝试。 */
    private static synchronized boolean ensureReady() {
        if (initDone) {
            return available;
        }
        initDone = true;
        available = false;
        try {
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = findMethod(imClass, "getInstance");
            Object manager = getInstance.invoke(null);
            Method inject = findMethod(imClass, "injectInputEvent", InputEvent.class, int.class);
            inject.setAccessible(true);
            inputManager = manager;
            injectMethod = inject;
            asyncMode = readAsyncMode(imClass);
            available = manager != null;
            Log.i(TAG, "InputInjector ready, asyncMode=" + asyncMode);
        } catch (Throwable t) {
            Log.w(TAG, "InputInjector unavailable, fallback to shell", t);
        }
        return available;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        try {
            return clazz.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            Method m = clazz.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        }
    }

    /**
     * 读取 INJECT_INPUT_EVENT_MODE_ASYNC 常量，避免把某个版本的数值写死。
     * 读不到时用 {@link #MODE_ASYNC_FALLBACK}。
     */
    private static int readAsyncMode(Class<?> imClass) {
        try {
            Field f = imClass.getDeclaredField("INJECT_INPUT_EVENT_MODE_ASYNC");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            Log.w(TAG, "read INJECT_INPUT_EVENT_MODE_ASYNC failed, use " + MODE_ASYNC_FALLBACK);
            return MODE_ASYNC_FALLBACK;
        }
    }

    /** 严格整数解析：只接受可选负号 + 纯数字，不接受 "+1"、空白、小数，避免误解析。 */
    private static Integer parseIntStrict(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        int i = 0;
        if (s.charAt(0) == '-') {
            if (s.length() == 1) {
                return null;
            }
            i = 1;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
