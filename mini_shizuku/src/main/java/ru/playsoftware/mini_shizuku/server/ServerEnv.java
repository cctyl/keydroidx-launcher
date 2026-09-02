package ru.playsoftware.mini_shizuku.server;

import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端运行期环境：通过 shell 的 `content call` 命令向 launcher 的 Provider 拉取密钥 K。
 * <p>
 * server 自身是 shell (UID 2000)，直接用 `content call` 调 launcher 的 provider；
 * launcher 端检查调用方 UID 是 2000 即返回 K。
 * 纯命令行交互，无需反射 ActivityThread.systemMain()，不污染运行期状态。
 */
public final class ServerEnv {

    private static final String TAG = "MiniShizuku";
    private static final String AUTHORITY_SUFFIX = ".shizuku";
    private static final String METHOD_GET_SERVER_KEY = "getServerKey";
    private static final Pattern KEY_PATTERN = Pattern.compile("k=([a-fA-F0-9]{32,})");

    private static String sAuthority;
    private static volatile String sCachedK;

    private ServerEnv() {
    }

    /** 在 AdbProcess.main 启动后尽早调用一次，建立 authority。 */
    public static void init(String hostPackage) {
        sAuthority = hostPackage + AUTHORITY_SUFFIX;
        Log.i(TAG, "ServerEnv ready: host=" + hostPackage + " authority=" + sAuthority);
    }

    /** 向 launcher provider 拉取 K（通过 shell 命令 content call，UID 2000 保证特权）。 */
    public static synchronized String fetchK() {
        if (sAuthority == null) {
            Log.w(TAG, "fetchK: ServerEnv not initialized");
            return null;
        }
        try {
            String cmd = "content call --uri content://" + sAuthority + " --method " + METHOD_GET_SERVER_KEY;
            String out = ShellUtil.execWithOutput(cmd);
            if (out != null) {
                Matcher m = KEY_PATTERN.matcher(out);
                if (m.find()) {
                    sCachedK = m.group(1);
                    Log.i(TAG, "fetchK success, key length=" + sCachedK.length());
                    return sCachedK;
                }
            }
            Log.w(TAG, "fetchK: parse key failed, raw output: " + out);
        } catch (Throwable t) {
            Log.w(TAG, "fetchK failed: " + t);
        }
        return null;
    }

    /**
     * 校验命令携带的 K。先比对缓存；不匹配则重拉一次（应对 launcher 重启换 K）后比对。
     */
    public static boolean verify(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (sCachedK == null) {
            fetchK();
        }
        if (candidate.equals(sCachedK)) {
            return true;
        }
        // 缓存不匹配，可能宿主重启重置了 K，强制拉一次再比对
        fetchK();
        return candidate.equals(sCachedK);
    }
}
