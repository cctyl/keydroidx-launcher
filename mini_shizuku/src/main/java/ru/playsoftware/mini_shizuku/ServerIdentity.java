package ru.playsoftware.mini_shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

import io.github.cctyl.nokia.shizuku.MiniShizukuConst;

/**
 * 服务端身份探测（WHOAMI）。
 * <p>
 * 向 mini_shizuku 服务端发送无 K 命令 {@code WHOAMI}，读取 {@code OK:uid=&lt;n&gt;} 响应，
 * 用于校验「所选模式 = 服务端真实身份」：root 激活要求 uid==0，adb 激活要求 uid==2000。
 * 不一致说明有旧身份服务残留或激活未按预期生效，命令可能以错误身份执行，需提示重新激活。
 */
public final class ServerIdentity {

    /** 探测失败（服务离线 / 响应异常 / 旧版本服务端不认识 WHOAMI 命令）。 */
    public static final int UID_UNKNOWN = -1;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CMD = "WHOAMI";
    private static final String REPLY_PREFIX = "OK:uid=";

    private ServerIdentity() {
    }

    /** 探测服务端 uid；失败返回 {@link #UID_UNKNOWN}。不抛异常，可在任意线程调用。 */
    public static int fetchServerUid() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(MiniShizukuConst.HOST, MiniShizukuConst.PORT),
                    MiniShizukuConst.CONNECT_TIMEOUT);
            socket.setSoTimeout(MiniShizukuConst.READ_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write((CMD + "\n").getBytes(UTF8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), UTF8));
            String line = reader.readLine();
            if (line != null && line.startsWith(REPLY_PREFIX)) {
                return Integer.parseInt(line.substring(REPLY_PREFIX.length()).trim());
            }
        } catch (Throwable ignored) {
            // 离线/异常统一走 UID_UNKNOWN，调用方据 unknown 给出「无法确认身份」提示
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
        return UID_UNKNOWN;
    }
}
