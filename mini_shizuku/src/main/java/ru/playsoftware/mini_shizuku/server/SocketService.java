package ru.playsoftware.mini_shizuku.server;

import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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
        Log.i(TAG, "Listening on 127.0.0.1:" + PORT);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        ServerSocket server = new ServerSocket(PORT);
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
}
