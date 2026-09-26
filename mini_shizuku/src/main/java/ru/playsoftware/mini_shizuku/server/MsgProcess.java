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
    /** 身份查询（不需 K）：回 OK:uid=<myUid()>，客户端校验服务端真实身份用。 */
    private static final String CMD_WHOAMI = "WHOAMI";
    private static final String EXIT_PREFIX = "EXIT:";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /**
     * 服务端身份在进程启动时即已确定（root 激活 = uid 0，adb 激活 = uid 2000），
     * 以 myUid 自检为准，与客户端"模式选择"无关。
     */
    private static final boolean ROOT_SERVER = android.os.Process.myUid() == 0;
    /** 包名白名单：只允许字母/数字/下划线/点，杜绝包名参数注入。 */
    private static final java.util.regex.Pattern PKG_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_.]+$");

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
                // 身份查询，不需 K：返回服务端真实 uid，供客户端校验"所选模式 = 服务端身份"
                if (command.equals(CMD_WHOAMI)) {
                    reply("OK:uid=" + android.os.Process.myUid());
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
            //
            // 快路径：input tap / swipe 由本进程直接注入事件（InputInjector），
            // 省掉 ShellUtil 的 fork + Dalvik VM 冷启动（4.4 实测约 1.2s → 数十 ms）。
            // InputInjector 只认严格形态，处理不了或注入不了都返回 false，
            // 这里再退回原来的 shell 路径，行为与改动前一致。
            Log.i(TAG, "exec(silent): " + cmd);
            if (!InputInjector.handle(cmd)) {
                // root 服务端：InputInjector 处理不了的命令必须过白名单才允许以 root 身份执行，
                // 否则 K 泄露 = 任意 root 命令执行。shell 服务端爆炸半径有限，保持原行为。
                if (ROOT_SERVER && !isAllowedShellCommand(cmd)) {
                    Log.w(TAG, "root server: command rejected by whitelist: " + cmd);
                    reply("ERR:not allowed");
                    return;
                }
                ShellUtil.execute(cmd);
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
        // root 服务端白名单：只放行冻结/解冻/force-stop 预定义模板（见 isAllowedShellCommand），
        // 防止 K 泄露被升级为任意 root 命令执行。shell 服务端不加白名单，行为不变。
        if (ROOT_SERVER && !isAllowedShellCommand(cmd)) {
            Log.w(TAG, "root server: command rejected by whitelist: " + cmd);
            reply("ERR:not allowed");
            return;
        }
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

    /**
     * root 服务端 shell 命令白名单：整条命令按 " ; " 拆段后，每一段必须完整匹配
     * 预定义模板之一且包名过 {@link #PKG_PATTERN}，与客户端冻结/解冻命令模板严格对应：
     * <pre>
     *   am force-stop &lt;pkg&gt; | pm disable &lt;pkg&gt; | pm disable-user --user 0 &lt;pkg&gt;
     *   pm enable &lt;pkg&gt;      | pm unhide &lt;pkg&gt;
     * </pre>
     * 任何白名单外的段（如管道、反引号、任意 shell 语法）都会导致整条命令被拒绝。
     */
    private static boolean isAllowedShellCommand(String cmd) {
        for (String segment : cmd.split(" ; ")) {
            if (!isAllowedSegment(segment.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedSegment(String segment) {
        String pkg = null;
        if (segment.startsWith("am force-stop ")) {
            pkg = segment.substring("am force-stop ".length());
        } else if (segment.startsWith("pm disable-user --user 0 ")) {
            pkg = segment.substring("pm disable-user --user 0 ".length());
        } else if (segment.startsWith("pm disable ")) {
            pkg = segment.substring("pm disable ".length());
        } else if (segment.startsWith("pm enable ")) {
            pkg = segment.substring("pm enable ".length());
        } else if (segment.startsWith("pm unhide ")) {
            pkg = segment.substring("pm unhide ".length());
        }
        return pkg != null && PKG_PATTERN.matcher(pkg).matches();
    }
}
