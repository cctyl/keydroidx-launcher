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
                // 探活握手，不需 K
                if (command.equals("PING")) {
                    reply("OK:pong");
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
            Log.i(TAG, "exec(silent): " + cmd);
            ShellUtil.execute(cmd);
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
