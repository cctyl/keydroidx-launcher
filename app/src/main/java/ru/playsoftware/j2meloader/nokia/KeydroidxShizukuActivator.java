package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.mini_shizuku.ServerIdentity;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * mini_shizuku root 激活核心流程（静态无 UI 版），供设置页一键激活与
 * {@link ShizukuRootFragment} 详情页共用。
 * <p>
 * 流程：解出最新 so 到 cache → su -c 直执启动脚本（kill 旧服务端 → cat 部署 so →
 * su -cn u:r:shell:s0 拉起 app_process）→ 轮询服务上线 → WHOAMI 校验 uid==0。
 */
public final class KeydroidxShizukuActivator {

	private static final String TAG = "ShizukuActivator";
	/** 轮询服务上线的总超时与间隔 */
	private static final long ACTIVATE_TIMEOUT_MS = 20000;
	private static final long POLL_INTERVAL_MS = 300;

	private KeydroidxShizukuActivator() {
	}

	/** 激活结果 */
	public static class Result {
		/** root 启动脚本是否执行成功（退出码 0） */
		public final boolean execOk;
		/** 服务是否上线 */
		public final boolean online;
		/** 上线服务端 uid（WHOAMI），未上线为 {@link ServerIdentity#UID_UNKNOWN} */
		public final int serverUid;

		Result(boolean execOk, boolean online, int serverUid) {
			this.execOk = execOk;
			this.online = online;
			this.serverUid = serverUid;
		}

		/** 激活完全成功：脚本执行 + 服务上线 + 身份为 root */
		public boolean isFullyOk() {
			return online && serverUid == 0;
		}
	}

	/**
	 * 以 root 拉起 mini_shizuku 服务端（阻塞，须在后台线程调用）。
	 * 启动细节与失败原因见 {@link ShizukuRootFragment} 类注释与 minishizuku.log。
	 */
	public static Result activateRootServer(Context ctx) {
		boolean execOk = execRootStartScript(ctx);
		if (!execOk) {
			return new Result(false, false, ServerIdentity.UID_UNKNOWN);
		}
		// 轮询等待新 root 服务上线（脚本已 kill 旧服务端，不会误报旧服务在线）
		long deadline = System.currentTimeMillis() + ACTIVATE_TIMEOUT_MS;
		boolean online = false;
		while (System.currentTimeMillis() < deadline) {
			if (Shizuku.isRunning()) {
				online = true;
				break;
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		int uid = online ? Shizuku.serverUid() : ServerIdentity.UID_UNKNOWN;
		KeydroidxLog.i(TAG, "激活结果: online=" + online + " serverUid=" + uid);
		return new Result(true, online, uid);
	}

	/** 构建并 su -c 直执 root 启动脚本。 */
	private static boolean execRootStartScript(Context ctx) {
		try {
			if (Build.VERSION.SDK_INT < 19) {
				KeydroidxLog.w(TAG, "root 启动跳过: SDK < 19");
				return false;
			}
			String apk = ctx.getApplicationInfo().sourceDir;
			String pkg = ctx.getPackageName();
			// root 身份的 Java 进程无权写 /data/local/tmp：App 侧解出最新 so 到 cache，
			// 由 root 脚本 cat 部署（保证服务端加载当前 APK 的 so 版本）。
			String deployLib = "";
			File soInCache = extractInterceptorLibToCache(ctx);
			if (soInCache != null) {
				deployLib = "cat '" + soInCache.getAbsolutePath()
						+ "' > /data/local/tmp/libnokiainterceptor.so; "
						+ "chmod 755 /data/local/tmp/libnokiainterceptor.so; ";
			} else {
				KeydroidxLog.w(TAG, "so 解出失败，服务端将尝试使用 /data/local/tmp 已有库");
			}
			// 关键：app_process 用 su -cn u:r:shell:s0 切 shell SELinux 域拉起（root uid 保留）。
			// 4.4 实测：init:s0 域无法访问 /data/local/tmp；shell 域无此限制。
			String script = "trap '' 1; "
					+ "ps | grep app_process | grep -v grep | while read -r line; do set -- $line; kill -9 $2 2>/dev/null; done; "
					+ deployLib
					+ "LOG=/data/local/tmp/minishizuku.log; "
					+ ": > \"$LOG\" 2>/dev/null; "
					+ "su -cn u:r:shell:s0 -c \"trap '' 1; app_process -Djava.class.path=" + apk
					+ " -Dapp.package=" + pkg
					+ " /system/bin ru.playsoftware.mini_shizuku.server.AdbProcess"
					+ " >> /data/local/tmp/minishizuku.log 2>&1 </dev/null &\"";
			KeydroidxLog.i(TAG, "执行 root 启动: " + script);
			// su -c 直执（libsu 在 4.4 + SuperSU 2.76 上 exec() 会挂死，见 ShizukuRootFragment 注释）
			KeydroidxRootShell.Result r = KeydroidxRootShell.exec(ctx, script, 15000);
			KeydroidxLog.i(TAG, "root 启动服务端退出码: " + r.code + " out=" + r.out.trim());
			return r.isSuccess();
		} catch (Exception e) {
			KeydroidxLog.e(TAG, "root 启动服务端异常", e);
			return false;
		}
	}

	/**
	 * 从 APK 解出 libnokiainterceptor.so 到 cacheDir（root 脚本再 cat 到 /data/local/tmp）。
	 * ABI：API 21+ 读 SUPPORTED_ABIS，4.4 降级 CPU_ABI/CPU_ABI2。失败返回 null。
	 */
	@Nullable
	private static File extractInterceptorLibToCache(Context ctx) {
		ZipFile zip = null;
		try {
			String apkPath = ctx.getApplicationInfo().sourceDir;
			zip = new ZipFile(apkPath);
			String[] abis;
			try {
				Object o = Build.class.getField("SUPPORTED_ABIS").get(null);
				abis = (o instanceof String[] && ((String[]) o).length > 0)
						? (String[]) o : new String[]{Build.CPU_ABI, Build.CPU_ABI2};
			} catch (Throwable t) {
				abis = new String[]{Build.CPU_ABI, Build.CPU_ABI2};
			}
			ZipEntry entry = null;
			for (String abi : abis) {
				if (abi == null || abi.isEmpty()) continue;
				entry = zip.getEntry("lib/" + abi + "/libnokiainterceptor.so");
				if (entry != null) break;
			}
			if (entry == null) {
				return null;
			}
			File out = new File(ctx.getCacheDir(), "libnokiainterceptor.so");
			InputStream in = zip.getInputStream(entry);
			try {
				java.io.OutputStream os = new java.io.FileOutputStream(out);
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) {
					os.write(buf, 0, n);
				}
				os.close();
			} finally {
				try {
					in.close();
				} catch (Exception ignored) {
				}
			}
			return out;
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "解出 so 失败: " + t.getMessage());
			return null;
		} finally {
			if (zip != null) {
				try {
					zip.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
}
