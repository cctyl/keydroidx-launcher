package ru.playsoftware.mini_shizuku.server;

import android.os.Looper;
import android.util.Log;

/**
 * MiniShizuku 服务入口。
 * <p>
 * 该类的 {@link #main(String[])} 方法由 {@code app_process} 命令以 shell 用户
 * （UID 2000）身份加载执行。通过 {@code app_process} 会预先初始化 Android 运行时，
 * 因此这里可以使用 {@link Looper} / {@link Log} 等框架类，但没有任何 Activity /
 * Context 上下文。
 */
public final class AdbProcess {

    private static final String TAG = "MiniShizuku";

    private AdbProcess() {
    }

    public static void main(String[] args) {
        Log.i(TAG, "MiniShizuku server starting...");
        Looper.prepareMainLooper();
        // 在独立线程中启动 TCP 监听，避免阻塞主 Looper
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new SocketService().start();
                } catch (Throwable t) {
                    Log.e(TAG, "SocketService start failed", t);
                    // 绑定失败（端口被占且旧实例不退）等场景下，主 Looper 继续 loop
                    // 只会留下一个什么都不做的僵尸 app_process。直接退出进程。
                    Log.e(TAG, "exiting app_process due to SocketService failure");
                    System.exit(1);
                }
            }
        }, "MiniShizuku-Socket").start();
        Looper.loop();
    }
}
