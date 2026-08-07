package ru.playsoftware.mini_shizuku.server;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 处理单个客户端连接：读取一行命令，交给 {@link ShellUtil} 以 shell 身份执行。
 * <p>
 * 行协议（与客户端 {@code ShizukuClient} 保持一致，无共享类）：
 * <ul>
 *     <li>{"<@literal EXEC>"} + "|" + 命令 —— 静默执行，不回写输出（兼容旧 exec）。</li>
 *     <li>{"<@literal EXEC_OUT>"} + "|" + 命令 —— 执行后逐行回写 stdout/stderr，
 *         最后回写一行 {@code EXIT:<code>} 作为结束标记。</li>
 * </ul>
 */
public class MsgProcess implements Runnable {

    private static final String TAG = "MiniShizuku";
    private static final String PREFIX_SILENT = "EXEC|";
    private static final String PREFIX_OUTPUT = "EXEC_OUT|";
    private static final String EXIT_PREFIX = "EXIT:";
    private static final Charset UTF8 = Charset.forName("UTF-8");

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
                if (command.startsWith(PREFIX_OUTPUT)) {
                    handleExecWithOutput(command.substring(PREFIX_OUTPUT.length()));
                } else if (command.startsWith(PREFIX_SILENT)) {
                    String cmd = command.substring(PREFIX_SILENT.length()).trim();
                    Log.i(TAG, "exec(silent): " + cmd);
                    ShellUtil.execute(cmd);
                } else {
                    // 兼容旧客户端：未加前缀的命令按静默处理
                    Log.i(TAG, "exec(silent/legacy): " + command);
                    ShellUtil.execute(command);
                }
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

    /**
     * 执行命令并将输出与退出码回写客户端，最后以 {@code EXIT:<code>} 结束。
     */
    private void handleExecWithOutput(String command) {
        String cmd = command.trim();
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
}
