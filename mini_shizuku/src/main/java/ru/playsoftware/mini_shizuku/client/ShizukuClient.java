package ru.playsoftware.mini_shizuku.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * MiniShizuku 客户端，连接本机 127.0.0.1:10500 的 app_process 服务，发送 shell 命令。
 * <p>
 * 与 {@code ru.playsoftware.mini_shizuku.server.MsgProcess} 约定同一行协议（无共享类）：
 * <ul>
 *     <li>{"<@literal EXEC>"} + "|" + 命令 —— 静默执行，不回读输出。</li>
 *     <li>{"<@literal EXEC_OUT>"} + "|" + 命令 —— 执行并读回 stdout/stderr，直到
 *         {@code EXIT:<code>} 结束标记。</li>
 * </ul>
 */
public final class ShizukuClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 10500;
    private static final int CONNECT_TIMEOUT = 500;
    private static final int READ_TIMEOUT = 3000;
    private static final String PREFIX_SILENT = "EXEC|";
    private static final String PREFIX_OUTPUT = "EXEC_OUT|";
    private static final String EXIT_PREFIX = "EXIT:";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private ShizukuClient() {
    }

    /**
     * 检测 MiniShizuku 服务是否在线（能否连上 TCP 端口）。
     */
    public static boolean isRunning() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 请求正在运行的服务端自行退出（通过 IPC 发送 {@code SERVER_STOP}，不依赖 kill 权限，
     * 跨 uid 可达）。用于切换激活方式前主动停掉旧实例，避免端口冲突。
     *
     * @return true 表示已连上并发出了停止命令。
     */
    public static boolean stopServer() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write((PREFIX_SILENT + "SERVER_STOP" + "\n").getBytes(UTF8));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 发送一条命令给服务端静默执行（不回读输出）。
     *
     * @return 是否成功写入（服务在线且发送成功）。
     */
    public static boolean exec(String command) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write((PREFIX_SILENT + command + "\n").getBytes(UTF8));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 发送拦截器控制命令（INTERCEPTOR_START/STOP），并读取服务端回写的一行结果。
     * <p>
     * 服务端处理完成后会回写 {@code OK:...} 或 {@code ERR:...}；老版本服务端不回写，
     * 读超时后视为成功（保持旧行为，仅保证写入成功）。这样客户端能感知拦截是否真正启动，
     * 避免「服务端部署失败但客户端误报成功」。
     *
     * @return true 表示服务端确认成功（或老服务端已收到命令）；false 表示服务端处理失败。
     */
    public static boolean execInterceptor(String command) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write((PREFIX_SILENT + command + "\n").getBytes(UTF8));
            out.flush();
            socket.setSoTimeout(READ_TIMEOUT);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), UTF8));
            try {
                String line = reader.readLine();
                if (line != null && line.startsWith("ERR:")) {
                    return false;
                }
                return true;
            } catch (java.net.SocketTimeoutException e) {
                // 老服务端不回写响应，写入成功即视为成功
                return true;
            }
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * 发送一条命令给服务端执行，并读取其合并输出，直到收到 {@code EXIT:<code>} 结束标记。
     *
     * @return 命令的标准输出+标准错误；失败（连接不上/超时）返回 {@code null}。
     */
    public static String execWithOutput(String command) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write((PREFIX_OUTPUT + command + "\n").getBytes(UTF8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), UTF8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(EXIT_PREFIX)) {
                    break;
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
