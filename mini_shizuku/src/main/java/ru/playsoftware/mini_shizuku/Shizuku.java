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
    public static String execWithOutput(String command) {
        return ShizukuClient.execWithOutput(command);
    }
}
