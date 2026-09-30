package ru.playsoftware.mini_shizuku.server;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 处理单个客户端连接：读取一行命令，先校验密钥 K，再交给 {@link ShellUtil} 以 shell 身份执行。
 * <p>
 * 行协议 v3（与客户端约定一致，无共享类）：
 * <ul>
 *     <li>每行 = {@code <K>|<inner>}，{@code <inner>} 为原协议串。</li>
 *     <li>{@code PING} → 回 {@code OK:pong}（可选探活，不需 K）。</li>
 *     <li>{@code EXEC} + "|" + 命令 —— 静默执行，不回写输出（兼容旧 exec）。</li>
 *     <li>{@code EXEC_OUT} + "|" + 命令 —— 执行后逐行回写 stdout/stderr，
 *         最后回写一行 {@code EXIT:<code>} 作为结束标记。</li>
 * </ul>
 */
public class MsgProcess implements Runnable {

    private static final String TAG = "MiniShizuku";
    private static final String PREFIX_SILENT = "EXEC|";
    private static final String PREFIX_OUTPUT = "EXEC_OUT|";
    private static final String CMD_INTERCEPTOR_START = "INTERCEPTOR_START";
    private static final String CMD_INTERCEPTOR_STOP = "INTERCEPTOR_STOP";
    private static final String CMD_PAGE_STATE = "PAGE_STATE|";
    /** 请求服务端自行退出（跨 uid 切换激活时，新实例通过 IPC 调用，绕开 kill 权限）。 */
    private static final String CMD_SERVER_STOP = "SERVER_STOP";
    /** 身份查询（不需 K）：回 OK:uid=<myUid()>，客户端校验服务端真实身份用。 */
    private static final String CMD_WHOAMI = "WHOAMI";
    private static final String EXIT_PREFIX = "EXIT:";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /**
     * 服务端身份在进程启动时即已确定（root 激活 = uid 0，adb 激活 = uid 2000），
     * 以 myUid 自检为准，与客户端"模式选择"无关。
     */
    private static final boolean ROOT_SERVER = android.os.Process.myUid() == 0;
    // ==================== root 服务端 shell 命令白名单 ====================
    //
    // 为什么必须做：root 服务端（app_process, uid 0）一旦泄露 K，等价于「任意 root 命令
    // 执行」，因此只放行下面登记过的命令骨架（见 docs/权限通道双轨制设计 §6）。
    //
    // ⚠️ 维护契约（新增能力前必读）：任何要经 mini_shizuku 下发的 shell 命令，都必须在这里
    //    登记一条模板；漏登记不会抛任何异常，只在 root 模式下被服务端静默拒绝——客户端
    //    `Shizuku.exec()` 是「即发即忘」（写成功即返回 true），表现为「点了没反应/列表为空」。
    //    2026-09「root 模式最近任务显示为空」的根因就是 dumpsys / ps -A 没登记。
    //    排查入口：`adb logcat -s MiniShizuku:* | grep "rejected by whitelist"`。
    //
    // 参数注入防线：模板 = 具体命令骨架 + 受限参数（包名/整数/枚举/组件），因此分段内一旦
    // 出现反引号、$(、管道、重定向、注入用的引号等 shell 语法，就匹配不上任何模板 → 拒绝。

    /** 包名参数：只允许字母/数字/下划线/点，杜绝包名参数注入。 */
    private static final String P_PKG = "[a-zA-Z0-9_.]+";
    /** 整数参数（taskId / 坐标 / 时长等），允许负号。 */
    private static final String P_INT = "-?[0-9]+";
    /** 组件参数：{@code pkg/.Cls} 或 {@code pkg/Cls$Inner}（QS Tile 的 ComponentName 形态）。 */
    private static final String P_COMPONENT = "[a-zA-Z0-9_.$-]+/[a-zA-Z0-9_.$-]+";

    /**
     * 白名单模板表：命令被 {@code ;} / {@code &&} / {@code ||} 拆段后，
     * 每一段都必须<b>完整匹配</b>其中一条。
     */
    private static final String[] ALLOWED_TEMPLATES = {
            // —— 只读查询 ——
            "dumpsys activity recents",                                   // 最近任务
            "dumpsys activity activities",                                // 最近任务（recents 无输出时的兜底）
            "ps -A",                                                      // 后台管理：存活应用枚举
            // —— 进程 / 任务 ——
            "am force-stop " + P_PKG,
            "am stack remove " + P_INT,                                   // 最近任务：抹掉任务卡片
            // —— 冻结 / 解冻（KeydroidxFreezeManager）——
            "pm disable-user --user 0 " + P_PKG,
            "pm disable " + P_PKG,
            "pm enable " + P_PKG,
            "pm unhide " + P_PKG,
            // —— 快捷开关（KeydroidxQuickToggleManager）——
            "svc wifi (?:enable|disable)",
            "svc data (?:enable|disable)",
            "svc bluetooth (?:enable|disable)",
            "cmd bluetooth_manager (?:enable|disable)",
            "cmd location set-location-enabled (?:true|false)",
            "cmd power set-mode [01]",
            "settings put global (?:mobile_data|airplane_mode_on|low_power) [01]",
            "settings put system accelerometer_rotation [01]",
            "settings put system screen_brightness_mode [01]",
            "settings put system screen_brightness [0-9]{1,3}",
            "settings put secure location_mode [0-3]",
            "settings put secure location_providers_allowed \"[a-zA-Z_,]*\"",
            "am broadcast -a android\\.intent\\.action\\.AIRPLANE_MODE --ez state (?:true|false)",
            // —— 状态栏磁贴（桌面「快捷开关」组件）——
            "cmd statusbar expand-settings",
            "cmd statusbar click-tile " + P_COMPONENT,
            "sleep [0-9]{1,3}(?:\\.[0-9]{1,3})?",                         // 复合命令里的等待（expand-settings 之后）
            // —— 输入注入（InputInjector 快路径未接管时的 shell 回退）——
            "input tap " + P_INT + " " + P_INT,
            "input swipe " + P_INT + " " + P_INT + " " + P_INT + " " + P_INT + "(?: " + P_INT + ")?",
            "input keyevent " + P_INT,
            // —— 电源（KeydroidxQuickToggleManager.execPowerCommand）——
            "reboot(?: -p| recovery| bootloader)?",
            "setprop sys\\.powerctl (?:shutdown|reboot(?:,recovery|,bootloader)?)",
    };

    /** 编译后的模板（静态初始化一次，避免每条命令重复编译正则）。 */
    private static final java.util.regex.Pattern[] ALLOWED_SEGMENTS = compileTemplates();

    /**
     * 复合命令分隔符：{@code ;} / {@code &&} / {@code ||}。
     * 单个 {@code |} 是管道、不在此列——它会留在分段里，从而匹配不上任何模板被拒绝。
     */
    private static final java.util.regex.Pattern SEGMENT_SEPARATOR =
            java.util.regex.Pattern.compile("\\s*(?:;|&&|\\|\\|)\\s*");

    private static java.util.regex.Pattern[] compileTemplates() {
        java.util.regex.Pattern[] patterns = new java.util.regex.Pattern[ALLOWED_TEMPLATES.length];
        for (int i = 0; i < ALLOWED_TEMPLATES.length; i++) {
            patterns[i] = java.util.regex.Pattern.compile(ALLOWED_TEMPLATES[i]);
        }
        return patterns;
    }

    private final Socket socket;

    public MsgProcess(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }
                // 探活握手，不需 K
                if (command.equals("PING")) {
                    reply("OK:pong");
                    continue;
                }
                // 身份查询，不需 K：返回服务端真实 uid，供客户端校验"所选模式 = 服务端身份"
                if (command.equals(CMD_WHOAMI)) {
                    reply("OK:uid=" + android.os.Process.myUid());
                    continue;
                }
                // v3：行 = <K>|<inner>。取首段 K 校验，过则处理 inner。
                int sep = command.indexOf('|');
                if (sep < 0) {
                    reply("ERR:unauthorized");
                    break;
                }
                String k = command.substring(0, sep);
                String inner = command.substring(sep + 1);
                if (!ServerEnv.verify(k)) {
                    Log.w(TAG, "unauthorized command, rejecting");
                    reply("ERR:unauthorized");
                    break;
                }
                dispatch(inner);
            }
        } catch (IOException e) {
            Log.e(TAG, "read command failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 处理通过鉴权后的 inner 命令（即原协议串）。 */
    private void dispatch(String command) {
        if (command.startsWith(PREFIX_OUTPUT)) {
            handleExecWithOutput(command.substring(PREFIX_OUTPUT.length()));
            return;
        }
        // 先剥掉 EXEC| 前缀，再判断是否拦截器命令
        String cmd = command;
        if (cmd.startsWith(PREFIX_SILENT)) {
            cmd = cmd.substring(PREFIX_SILENT.length()).trim();
        }
        if (cmd.equals(CMD_INTERCEPTOR_START)) {
            handleInterceptorStart();
        } else if (cmd.equals(CMD_INTERCEPTOR_STOP)) {
            handleInterceptorStop();
        } else if (cmd.startsWith(CMD_PAGE_STATE)) {
            handlePageState(cmd.substring(CMD_PAGE_STATE.length()));
        } else if (cmd.equals(CMD_SERVER_STOP)) {
            handleServerStop();
        } else {
            // 普通 Shell 命令（静默执行）
            //
            // 快路径：input tap / swipe 由本进程直接注入事件（InputInjector），
            // 省掉 ShellUtil 的 fork + Dalvik VM 冷启动（4.4 实测约 1.2s → 数十 ms）。
            // InputInjector 只认严格形态，处理不了或注入不了都返回 false，
            // 这里再退回原来的 shell 路径，行为与改动前一致。
            Log.i(TAG, "exec(silent): " + cmd);
            if (!InputInjector.handle(cmd)) {
                // root 服务端：InputInjector 处理不了的命令必须过白名单才允许以 root 身份执行，
                // 否则 K 泄露 = 任意 root 命令执行。shell 服务端爆炸半径有限，保持原行为。
                // 注意：被拒时只回 ERR（客户端 exec() 即发即忘不会读），真实表现是「静默无效果」。
                if (ROOT_SERVER && !isAllowedShellCommand(cmd)) {
                    Log.w(TAG, "root server: command rejected by whitelist: " + cmd);
                    reply("ERR:not allowed");
                    return;
                }
                ShellUtil.execute(cmd);
            }
        }
    }

    /**
     * 处理拦截器启动：先确保原生库部署到 /data/local/tmp，再加载并启动。
     */
    private void handleInterceptorStart() {
        Log.i(TAG, "interceptor start requested");
        if (InterceptorNative.prepareLibrary("/data/local/tmp/libnokiainterceptor.so")) {
            InterceptorNative.loadLibrary("/data/local/tmp/libnokiainterceptor.so");
            InterceptorNative.applyCachedPageState();
            InterceptorNative.startInterceptor();
            Log.i(TAG, "interceptor started");
            reply("OK:interceptor started");
        } else {
            Log.e(TAG, "interceptor start failed: library not deployed");
            reply("ERR:library not deployed");
        }
    }

    /**
     * 处理拦截器停止。
     */
    private void handleInterceptorStop() {
        Log.i(TAG, "interceptor stop requested");
        InterceptorNative.stopInterceptor();
        reply("OK:interceptor stopped");
    }

    /**
     * 处理页面状态上报：App 通过 TCP 发送 "PAGE_STATE|0" 或 "PAGE_STATE|1"，
     * 服务端调用 JNI 更新 native 全局变量，供拦截器状态机区分主界面/子页面。
     *
     * @param value "1"=主界面（待机屏），"0"=子页面
     */
    private void handlePageState(String value) {
        boolean isMain = "1".equals(value.trim());
        Log.i(TAG, "page state: " + (isMain ? "main" : "sub"));
        InterceptorNative.setPageState(isMain);
    }

    /**
     * 处理服务端停止请求：回写确认后，延时一小段时间（让回复刷盘）再
     * {@link System#exit(int)} 结束整个 app_process，释放监听端口。
     * <p>用于跨 uid 切换激活场景：新启动的 app_process 发现端口被占时，
     * 通过本命令请旧实例（可能是别的 uid，脚本 kill 不到）自行退出。
     */
    private void handleServerStop() {
        Log.i(TAG, "server stop requested");
        reply("OK:server stopping");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                System.exit(0);
            }
        }, "MiniShizuku-Exit").start();
    }

    /**
     * 向客户端回写一行处理结果（新协议），供客户端感知拦截命令是否真正成功。
     * 老版本客户端不回读响应，本方法无副作用。
     */
    private void reply(String message) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write((message + "\n").getBytes(UTF8));
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "reply failed", e);
        }
    }

    /**
     * 执行命令并将输出与退出码回写客户端，最后以 {@code EXIT:<code>} 结束。
     */
    private void handleExecWithOutput(String command) {
        String cmd = command.trim();
        // root 服务端白名单：只放行 ALLOWED_TEMPLATES 登记过的命令骨架，
        // 防止 K 泄露被升级为任意 root 命令执行。shell 服务端不加白名单，行为不变。
        if (ROOT_SERVER && !isAllowedShellCommand(cmd)) {
            Log.w(TAG, "root server: command rejected by whitelist: " + cmd);
            reply("ERR:not allowed");
            return;
        }
        Log.i(TAG, "exec(output): " + cmd);
        ShellUtil.Result result = ShellUtil.execWithOutputAndCode(cmd);
        try {
            OutputStream out = socket.getOutputStream();
            out.write((result.output + EXIT_PREFIX + result.exitCode + "\n").getBytes(UTF8));
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "write output back failed", e);
        }
    }

    /**
     * root 服务端 shell 命令白名单：整条命令按 {@code ;} / {@code &&} / {@code ||} 拆段
     * （同时兼容 {@code "a ; b"} 与批量拼接的 {@code "a;b;"} 两种形态），
     * 每一段必须完整匹配 {@link #ALLOWED_TEMPLATES} 之一。
     * <p>
     * 任何白名单外的段（管道、重定向、反引号、任意 shell 语法）都会导致整条命令被拒绝。
     */
    private static boolean isAllowedShellCommand(String cmd) {
        String[] segments = SEGMENT_SEPARATOR.split(cmd.trim());
        boolean hasSegment = false;
        for (String segment : segments) {
            String s = segment.trim();
            if (s.isEmpty()) {
                // 批量拼接命令（"am force-stop a;am force-stop b;"）会切出空段，跳过
                continue;
            }
            hasSegment = true;
            if (!isAllowedSegment(s)) {
                return false;
            }
        }
        return hasSegment;
    }

    /** 单段命令是否完整命中白名单模板。 */
    private static boolean isAllowedSegment(String segment) {
        for (java.util.regex.Pattern pattern : ALLOWED_SEGMENTS) {
            if (pattern.matcher(segment).matches()) {
                return true;
            }
        }
        return false;
    }
}
