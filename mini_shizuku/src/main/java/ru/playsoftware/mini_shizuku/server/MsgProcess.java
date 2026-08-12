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
    private static final String CMD_INTERCEPTOR_START = "INTERCEPTOR_START";
    private static final String CMD_INTERCEPTOR_STOP = "INTERCEPTOR_STOP";
    private static final String CMD_PAGE_STATE = "PAGE_STATE|";
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
                } else {
                    // 先剥掉 EXEC| 前缀（客户端 exec() 会带上前缀），再判断是否拦截器命令
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
                    } else if (cmd.startsWith(PREFIX_SILENT)) {
                        String real = cmd.substring(PREFIX_SILENT.length()).trim();
                        Log.i(TAG, "exec(silent): " + real);
                        ShellUtil.execute(real);
                    } else {
                        // 兼容旧客户端：未加前缀的命令按静默处理
                        Log.i(TAG, "exec(silent/legacy): " + cmd);
                        ShellUtil.execute(cmd);
                    }
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
