package ru.playsoftware.mini_shizuku.server;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * TCP 服务端，监听 127.0.0.1:10500，接收应用进程连接并投递到线程池处理。
 */
public class SocketService {

    private static final String TAG = "MiniShizuku";
    private static final int PORT = 10500;

    public void start() throws IOException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        ServerSocket server = bindWithTakeover(PORT);
        Log.i(TAG, "Listening on 127.0.0.1:" + PORT);
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(10000); // 设置单次连接超时防挂死
                executor.execute(new MsgProcess(socket));
            } catch (IOException e) {
                Log.e(TAG, "accept failed", e);
            }
        }
    }

    /**
     * 绑定监听端口；若端口被占（另一个 mini_shizuku 实例还活着，常见于 root/adb
     * 切换激活时旧实例脚本 kill 不掉），先通过 IPC 请旧实例自行退出，再重试绑定，
     * 实现跨 uid 平滑接管。若旧实例不退/不响应，抛出原异常由调用方处理（避免变成
     * 僵尸 app_process）。
     */
    private static ServerSocket bindWithTakeover(int port) throws IOException {
        try {
            return new ServerSocket(port);
        } catch (BindException be) {
            Log.i(TAG, "port " + port + " in use, asking existing server to stop");
            if (askExistingServerToStop()) {
                for (int i = 0; i < 30; i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                    try {
                        return new ServerSocket(port);
                    } catch (BindException stillBusy) {
                        // 旧实例尚未退出释放端口，继续等
                    }
                }
                Log.i(TAG, "took over port " + port + " after old server exited");
            }
            // 旧实例没退 / 不响应 IPC：抛原异常，调用方应退出进程，避免僵尸
            throw be;
        }
    }

    /**
     * 连接已在跑的旧服务实例（127.0.0.1:PORT），发送带密钥的 {@code SERVER_STOP} 命令
     * 请其自行退出。跨 uid 可达（TCP loopback，不依赖 kill 权限）。
     *
     * @return true 表示已连上并发出停止命令；false 表示连不上（可能没有旧实例）。
     */
    private static boolean askExistingServerToStop() {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
            OutputStream out = socket.getOutputStream();
            // v3：协议为 <K>|<inner>，接管停止命令同样需带 K（由本进程 ServerEnv 向 launcher 拉取）
            String k = ServerEnv.fetchK();
            String line = (k != null ? k : "") + "|EXEC|SERVER_STOP";
            out.write((line + "\n").getBytes(Charset.forName("UTF-8")));
            out.flush();
            socket.setSoTimeout(800);
            try {
                new BufferedReader(new InputStreamReader(socket.getInputStream(),
                        Charset.forName("UTF-8"))).readLine();
            } catch (IOException ignored) {
                // 老服务端不回写也无所谓，命令已发出
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "ask existing server to stop failed: " + e.getMessage());
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
