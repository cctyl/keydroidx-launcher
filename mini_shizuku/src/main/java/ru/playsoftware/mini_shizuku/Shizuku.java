package ru.playsoftware.mini_shizuku;

import android.os.Build;

import ru.playsoftware.mini_shizuku.client.ShizukuClient;

/**
 * MiniShizuku 统一门面，供应用调用。
 * <p>
 * 本实现（app_process + TCP 的 mini_shizuku）是<strong>所有 Android 版本</strong>统一的
 * 权限通道，不再按系统版本分流（不依赖官方 Shizuku）。服务端由用户在电脑上通过 adb 以
 * shell 身份拉起，客户端通过本门面检测状态并执行命令。
 * <p>
 * 主 app 一律通过本门面调用，不直接接触底层 client/server 类。
 */
public final class Shizuku {

    private Shizuku() {
    }

    /**
     * 当前 Android 版本是否在本通道（mini_shizuku）的支持范围内。
     * <p>
     * mini_shizuku 通过 {@code app_process} + TCP 实现，链路在所有 Android 版本均成立，
     * 故恒返回 {@code true}（Android 4.4 至最新版本均支持）。
     *
     * @return 恒为 {@code true}，所有版本均支持 mini_shizuku。
     */
    public static boolean isSupported() {
        return true;
    }

    /**
     * 检测 MiniShizuku 服务是否在线。
     * <p>
     * 不受版本限制：即便 7.0+ 也可用于探测（探测不涉及权限），但调用方通常应先用
     * {@link #isSupported()} 判断。
     */
    public static boolean isRunning() {
        return ShizukuClient.isRunning();
    }

    /**
     * 发送 shell 命令由服务以 shell 身份静默执行（不回读输出）。
     *
     * @return 是否成功写入。
     */
    public static boolean exec(String command) {
        return ShizukuClient.exec(command);
    }

    /**
     * 发送 shell 命令由服务以 shell 身份执行，并返回合并的标准输出+标准错误。
     *
     * @param command 要执行的命令。
     * @return 命令输出；服务不可用或执行失败返回 {@code null}。
     */
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
        return ShizukuClient.execInterceptor(enable ? "INTERCEPTOR_START" : "INTERCEPTOR_STOP");
    }

    /**
     * 上报当前页面状态给拦截器，使其能区分诺基亚桌面主界面（待机屏）与子页面。
     * <p>
     * 拦截器状态机需要此信息来决定：亮屏+诺基亚时，主界面→锁屏，子页面→回桌面。
     * 服务端通过 JNI 更新 native 全局变量；服务离线时静默失败（不影响 UI）。
     *
     * @param isMain true=主界面（待机屏 NokiaDesktopFragment），false=子页面
     * @return 命令是否成功发送。
     */
    public static boolean setPageState(boolean isMain) {
        return ShizukuClient.exec("PAGE_STATE|" + (isMain ? "1" : "0"));
    }

    public static String execWithOutput(String command) {
        return ShizukuClient.execWithOutput(command);
    }
}

