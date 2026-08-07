package ru.playsoftware.mini_shizuku;

import android.os.Build;

import ru.playsoftware.mini_shizuku.client.ShizukuClient;

/**
 * MiniShizuku 统一门面，供应用调用。
 * <p>
 * 版本分流：本实现（app_process + TCP 的 mini_shizuku）仅用于 Android 7.0 以下
 * （API &lt; 24）。Android 7.0+（API &ge; 24）应改用官方 Shizuku 授权，本次不集成，
 * 由 {@link #isSupported()} 返回 {@code false} 占位，供将来扩展。
 * <p>
 * 主 app 一律通过本门面调用，不直接接触底层 client/server 类。
 */
public final class Shizuku {

    /** 官方 Shizuku 的最低 API（Android 7.0）。 */
    private static final int SHIZUKU_MIN_API = 24;

    private Shizuku() {
    }

    /**
     * 当前 Android 版本是否在本通道（mini_shizuku）的支持范围内。
     * <p>
     * mini_shizuku 仅用于 Android 7.0 以下；7.0+ 需官方 Shizuku（本次不集成），返回 false。
     *
     * @return true 表示 API &lt; 24，可使用 mini_shizuku。
     */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT < SHIZUKU_MIN_API;
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
    public static String execWithOutput(String command) {
        return ShizukuClient.execWithOutput(command);
    }
}
