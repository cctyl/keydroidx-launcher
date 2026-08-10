package ru.playsoftware.mini_shizuku.server;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InterceptorNative {
    private static final String TAG = "MiniShizuku";
    private static boolean isLoaded = false;

    /**
     * 获取当前进程支持的 ABI 列表（按优先级排序）。
     * <p>
     * Android 4.4 (API 19) 没有 {@code Build.SUPPORTED_ABIS}（API 21+），直接引用会抛
     * {@code NoSuchFieldError} 导致服务端进程崩溃，故用反射安全读取；失败时降级到
     * {@code Build.CPU_ABI} / {@code Build.CPU_ABI2}（API 8+ 可用）。
     */
    private static String[] getSupportedAbis() {
        try {
            Object abis = android.os.Build.class.getField("SUPPORTED_ABIS").get(null);
            if (abis instanceof String[] && ((String[]) abis).length > 0) {
                return (String[]) abis;
            }
        } catch (Throwable ignored) {
            // API 19 没有该字段，走降级
        }
        String abi = Build.CPU_ABI;
        String abi2 = Build.CPU_ABI2;
        if (abi2 != null && !abi2.isEmpty()) {
            return new String[]{abi, abi2};
        }
        return new String[]{abi};
    }

    /**
     * 从 APK 中解出 libnokiainterceptor.so 并部署到指定路径（/data/local/tmp）。
     * <p>
     * 服务端由 {@code app_process -Djava.class.path=<apk>} 启动，因此
     * {@code java.class.path} 属性即 APK 路径（可能含多个路径，取第一个存在的 .apk）。
     * so 在 APK 内位于 lib/&lt;abi&gt;/ 下。任何失败仅返回 false，不向调用方抛异常，
     * 避免服务端进程崩溃。
     *
     * @param outPath 部署目标绝对路径。
     * @return true 表示已就绪（已存在或解出成功）。
     */
    public static boolean prepareLibrary(String outPath) {
        try {
            File out = new File(outPath);
            if (out.exists() && out.length() > 0) {
                return true;
            }

            String apkPath = findApkPath();
            if (apkPath == null) {
                Log.e(TAG, "prepareLibrary: no APK in java.class.path");
                return false;
            }

            ZipFile zip = null;
            try {
                zip = new ZipFile(apkPath);
                for (String abi : getSupportedAbis()) {
                    ZipEntry entry = zip.getEntry("lib/" + abi + "/libnokiainterceptor.so");
                    if (entry == null) {
                        continue;
                    }
                    InputStream in = zip.getInputStream(entry);
                    FileOutputStream fos = new FileOutputStream(out);
                    copy(in, fos);
                    fos.close();
                    in.close();
                    // shell 身份运行，需保证可读可执行
                    if (!out.setReadable(true, false) || !out.setExecutable(true, false)) {
                        Log.w(TAG, "prepareLibrary: chmod failed, may still work");
                    }
                    Log.i(TAG, "prepareLibrary: extracted libnokiainterceptor.so (" + abi + ") to " + outPath);
                    return true;
                }
                Log.e(TAG, "prepareLibrary: libnokiainterceptor.so not found in APK");
                return false;
            } catch (IOException e) {
                Log.e(TAG, "prepareLibrary: failed to extract library", e);
                return false;
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            // 防御：任何异常都不允许击穿服务端进程
            Log.e(TAG, "prepareLibrary: unexpected error", t);
            return false;
        }
    }

    /**
     * 从 {@code java.class.path} 中找第一个存在的 .apk 路径。
     */
    private static String findApkPath() {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isEmpty()) {
            return null;
        }
        for (String part : cp.split(":")) {
            String p = part.trim();
            if (!p.isEmpty() && p.endsWith(".apk") && new File(p).exists()) {
                return p;
            }
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
    }

    public static void loadLibrary(String absolutePath) {
        if (isLoaded) return;
        try {
            System.load(absolutePath);
            isLoaded = true;
            Log.i(TAG, "Interceptor library loaded from " + absolutePath);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load interceptor library", e);
        }
    }

    public static native void startInterceptor();
    public static native void stopInterceptor();
    public static native void setInterceptEnabled(boolean enabled);
}
