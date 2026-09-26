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
        // root 身份服务端（桌面内 root 激活）：必须在任何网络操作之前补齐补充组。
        // Android 内核对 socket() 创建的权限检查针对补充组（需含 inet=3003）而非 uid；
        // root 进程默认无补充组，不补组则 SocketService.bindWithTakeover(10500) EACCES。
        // shell 身份（adb 激活）自带 inet 组，不进此分支，行为不变。
        if (android.os.Process.myUid() == 0) {
            prepareAsRoot();
        }
        Looper.prepareMainLooper();
        // 读取 -Dapp.package（启动脚本注入），初始化 ServerEnv authority
        String hostPkg = System.getProperty("app.package");
        if (hostPkg != null && hostPkg.length() > 0) {
            ServerEnv.init(hostPkg);
        } else {
            Log.w(TAG, "app.package not set; K 鉴权将不可用");
        }
        // 在独立线程中启动 TCP 监听，避免阻塞主 Looper
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new SocketService().start();
                } catch (Throwable t) {
                    Log.e(TAG, "SocketService start failed", t);
                    Log.e(TAG, "exiting app_process due to SocketService failure");
                    System.exit(1);
                }
            }
        }, "MiniShizuku-Socket").start();
        Looper.loop();
    }

    /**
     * root 身份启动准备：加载 native 库，随后补齐 supplemental groups。
     * <p>
     * 时序约束（设计文档 §4.2.1）：库加载与补组必须先于一切网络操作。
     * <p>
     * 部署策略（4.4 真机实测）：root 身份的 Java 进程（app_process 所在 SELinux 域）
     * <b>无权写 /data/local/tmp</b>（FileOutputStream → 内核 EACCES，即使 uid=0），
     * 因此常规部署交给激活脚本以 root shell 完成（cat 重定向，实测可行）。
     * 这里只负责：库已存在 → 直接加载；不存在 → 尝试自行部署（多数会失败，
     * 失败即明确退出，不静默带病运行——没有补组能力后续 socket() 必然 EACCES）。
     */
    private static void prepareAsRoot() {
        String libPath = "/data/local/tmp/libnokiainterceptor.so";
        java.io.File lib = new java.io.File(libPath);
        boolean loaded;
        if (lib.exists() && lib.length() > 0) {
            loaded = InterceptorNative.loadLibrary(libPath);
        } else {
            loaded = InterceptorNative.prepareLibrary(libPath) && InterceptorNative.loadLibrary(libPath);
        }
        if (!loaded) {
            Log.e(TAG, "root server: native library unavailable, cannot set supplemental groups; exiting");
            System.exit(1);
        }
        InterceptorNative.nativeSetSuppGroups(InterceptorNative.SERVER_SUPP_GROUPS);
        Log.i(TAG, "root server: supplemental groups set (n=" + InterceptorNative.SERVER_SUPP_GROUPS.length
                + ", incl. inet 3003)");
    }
}
