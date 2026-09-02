package ru.playsoftware.mini_shizuku;

import android.content.Context;

import io.github.cctyl.nokia.shizuku.MiniShizuku;

/**
 * MiniShizuku 统一门面，供 launcher 自身调用。
 * <p>
 * 本实现（app_process + TCP 的 mini_shizuku）是<strong>所有 Android 版本</strong>统一的
 * 权限通道。服务端由用户在电脑上通过 adb 以 shell 身份拉起，客户端经本门面检测状态并执行命令。
 * <p>
 * 自 v3 起：传输仍为 TCP 10500，但每条命令前缀密钥 K（{@code <K>|<inner>}），K 由
 * {@code NokiaShizukuProvider} 按签名派发；底层 client 与第三方 SDK 共用
 * {@link MiniShizuku}（core 模块），本门面在其上封装 launcher 专属的拦截器/页面状态命令。
 * <p>
 * 主 app 一律通过本门面调用，不直接接触底层 client/server 类。
 */
public final class Shizuku {

    private Shizuku() {
    }

    /** 注入应用级 Context（在 Application.onCreate 调用一次）。 */
    public static void init(Context context) {
        MiniShizuku.init(context);
    }

    /**
     * 当前 Android 版本是否在本通道（mini_shizuku）的支持范围内。
     * mini_shizuku 通过 app_process + TCP 实现，链路在所有 Android 版本均成立，恒返回 true。
     */
    public static boolean isSupported() {
        return true;
    }

    /**
     * 检测 MiniShizuku 服务是否在线（TCP 端口可连）。不需 K，对任意应用开放。
     */
    public static boolean isRunning() {
        return MiniShizuku.isRunning();
    }

    /** 静默执行一条 shell 命令（shell 身份）。同签名才成功。 */
    public static boolean exec(String command) {
        return MiniShizuku.exec(command);
    }

    /** 执行并返回合并的 stdout+stderr；失败/鉴权拒绝返回 {@code null}。 */
    public static String execWithOutput(String command) {
        return MiniShizuku.execWithOutput(command);
    }

    /**
     * 开启/关闭电源键拦截（evdev grab 由服务端以 shell 身份执行）。
     * <p>
     * 开启后：power 键所在输入设备被 EVIOCGRAB 独占抓取，power 事件被消费丢弃，
     * 系统收不到 power 键，从而避免桌面因 power 键退出/回到系统桌面。
     * 注意：shell 无法写 /dev/uinput 时为纯消费模式，该设备上的其它按键（如音量键）
     * 也会一并被吞掉。
     *
     * @param enable true 开启拦截，false 关闭。
     * @return 命令是否成功发送（服务在线且写入成功）。
     */
    public static boolean enablePowerInterceptor(boolean enable) {
        return MiniShizuku.execAcked(enable ? "INTERCEPTOR_START" : "INTERCEPTOR_STOP");
    }

    /**
     * 上报当前页面状态给拦截器，使其能区分原键桌面主界面（待机屏）与子页面。
     * <p>
     * 拦截器状态机需要此信息来决定：亮屏+诺基亚时，主界面→锁屏，子页面→回桌面。
     * 服务端通过 JNI 更新 native 全局变量；服务离线时静默失败（不影响 UI）。
     *
     * @param isMain true=主界面（待机屏 NokiaDesktopFragment），false=子页面
     * @return 命令是否成功发送。
     */
    public static boolean setPageState(boolean isMain) {
        return MiniShizuku.exec("PAGE_STATE|" + (isMain ? "1" : "0"));
    }
}
