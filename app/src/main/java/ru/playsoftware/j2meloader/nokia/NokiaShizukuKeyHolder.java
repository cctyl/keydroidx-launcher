package ru.playsoftware.j2meloader.nokia;

import java.security.SecureRandom;

/**
 * mini_shizuku 密钥 K 的进程级持有者（仅 launcher 主进程）。
 * <p>
 * K 为 256-bit 随机值（hex 64 位），进程启动后惰性生成一次，进程生命周期内不变。
 * server（shell）与同签名第三方应用都经 {@link NokiaShizukuProvider} 向本持有者取同一 K，
 * 由此实现「server 与 launcher 共享同一 K」而无需落盘或文件系统传递。
 * <p>
 * 重启 launcher 进程会换新 K（server 端校验失败时自动重拉，平滑过渡）。
 */
public final class NokiaShizukuKeyHolder {

    private static String sKey;

    private NokiaShizukuKeyHolder() {
    }

    /** 返回进程级 K（首次调用惰性生成）。 */
    public static String get() {
        if (sKey == null) {
            synchronized (NokiaShizukuKeyHolder.class) {
                if (sKey == null) {
                    byte[] bytes = new byte[32];
                    new SecureRandom().nextBytes(bytes);
                    sKey = toHex(bytes);
                }
            }
        }
        return sKey;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
