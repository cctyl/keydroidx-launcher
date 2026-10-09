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
		/** 是否因为已有一次激活正在进行而被拒绝（此时 execOk/online 均为 false） */
		public final boolean busy;

		Result(boolean execOk, boolean online, int serverUid, boolean busy) {
			this.execOk = execOk;
			this.online = online;
			this.serverUid = serverUid;
			this.busy = busy;
		}

		/** 激活完全成功：脚本执行 + 服务上线 + 身份为 root */
		public boolean isFullyOk() {
			return online && serverUid == 0;
		}
	}

	/**
	 * 「服务端启动」互斥闸。
	 * <p>
	 * 启动脚本第一句就是 {@code kill -9} 掉<strong>所有</strong> app_process 进程，因此两次激活
	 * 并发执行等于互相拆台：后一次会把前一次刚拉起来的服务端杀掉，两边都等满 20 秒超时。
	 * 2026-09-28 的日志即为此形态（20:25:32.704 与 20:25:36.698 两次并发启动，
	 * 20:25:53.178 / 20:25:57.158 双双判失败），而当天唯一一次成功（09-29 18:23）是单次点击、
	 * 无并发。故整个「kill → 启动 → 轮询上线」过程必须串行。
	 */
	private static final java.util.concurrent.atomic.AtomicBoolean sActivating =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	/**
	 * 尝试占用激活闸。自定义激活流程（{@link ShizukuRootFragment}）应在发起前调用，
	 * 并在流程结束时（{@code finally}）调用 {@link #unlockActivation()} 释放。
	 * <p>
	 * {@link #activateRootServer(Context)} 内部已自带本互斥，其调用方<strong>不要</strong>再自行加锁，
	 * 只需处理 {@link Result#busy}。
	 *
	 * @return true=已占用，可以开始；false=已有激活正在进行
	 */
	public static boolean tryLockActivation() {
		return sActivating.compareAndSet(false, true);
	}

	/** 释放激活闸（必须在 finally 中调用，否则激活入口会永久被拒）。 */
	public static void unlockActivation() {
		sActivating.set(false);
	}

	/**
	 * 以 root 拉起 mini_shizuku 服务端（阻塞，须在后台线程调用）。
	 * 启动细节与失败原因见 {@link ShizukuRootFragment} 类注释与 minishizuku.log。
	 * <p>
	 * 自带 {@link #tryLockActivation()} 互斥：已有激活在进行时立刻返回 {@link Result#busy}。
	 */
	public static Result activateRootServer(Context ctx) {
		if (!tryLockActivation()) {
			KeydroidxLog.w(TAG, "已有 root 激活正在进行，忽略本次请求");
			return new Result(false, false, ServerIdentity.UID_UNKNOWN, true);
		}
		try {
			boolean execOk = execRootStartScript(ctx);
			if (!execOk) {
				return new Result(false, false, ServerIdentity.UID_UNKNOWN, false);
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
			if (online && uid == 0) {
				// 记录「曾成功激活过」：重启后是否值得提醒用户重新激活（adb 模式）依据该标志
				KeydroidxSettingsStorage.setShizukuEverActivated(ctx, true);
			}
			KeydroidxLog.i(TAG, "激活结果: online=" + online + " serverUid=" + uid);
			return new Result(true, online, uid, false);
		} finally {
			unlockActivation();
		}
	}

	/**
	 * 服务端日志候选路径表（shell 片段，启动脚本与诊断脚本共用同一份，避免两处漂移）：
	 * 1) {@code /data/local/tmp/minishizuku.log} —— 历史位置，旧包与 adb 脚本都写这里；
	 * 2) {@code /data/local/tmp/minishizuku.<uid>.log} —— 固定名被别的 uid 以不可覆盖的
	 *    标签占用时的退路（旧 mini_shizuku.sh 的同名约定）；
	 * 3) App 自身日志目录下的 {@code minishizuku_server.log} —— 该目录里的文件会随
	 *    自动上报 zip 一起上传，是唯一「不依赖复制也能寄回」的位置。
	 */
	public static String logPathCandidatesShell(Context ctx) {
		return "/data/local/tmp/minishizuku.log "
				+ "\"/data/local/tmp/minishizuku.$(id -u).log\" "
				+ "'" + appLogCandidatePath(ctx) + "'";
	}

	/** App 自身日志目录下的服务端日志候选路径（日志未初始化时退到外存默认目录）。 */
	public static String appLogCandidatePath(Context ctx) {
		File logDir = KeydroidxLog.getLogDir();
		if (logDir == null) {
			logDir = KeydroidxLog.getDefaultLogDir(ctx);
		}
		if (logDir == null) {
			return "/dev/null";
		}
		return new File(logDir, "minishizuku_server.log").getAbsolutePath();
	}

	/** 构建并 su -c 直执 root 启动脚本（脚本构建对两条激活入口共用，避免两处漂移）。 */
	public static boolean execRootStartScript(Context ctx) {
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
			// 服务端日志候选路径：/data/local/tmp 是历史位置（旧包/旧 adb 脚本都写这里），
			// 但它可能不可写（SELinux 域/被别的 uid 以不可覆盖的标签占用），此时退到 App 自己
			// 的日志目录——该目录下的文件会随自动上报 zip 一起上传；最后退 /dev/null。
			// 关键：绝不能像 1.3.2 那样把重定向写死 /data/local/tmp —— 重定向打不开时
			// app_process 根本不启动，而命令以 & 后台化会让脚本照样 exit 0，
			// 于是「激活失败但完全没有现场」（2026-09-29 上报即为此形态）。
			String candidates = logPathCandidatesShell(ctx);
			// 关键：app_process 用 su -cn u:r:shell:s0 切 shell SELinux 域拉起（root uid 保留）。
			// 4.4 实测：init:s0 域无法访问 /data/local/tmp；shell 域无此限制。
			// 只回显 SERVER_LOG（瞬时、零延迟）：启动那一刻选了哪条日志路径，直接进 App 日志。
			// 「启动后进程在不在 / 日志里写了什么」留给 collectActivationDiagnostics —— 它在
			// 20 秒轮询失败后采集同样的事实，且不占用本脚本的 15 秒超时预算（往关键路径塞 sleep
			// 会让慢设备上「服务端其实起来了」被误判成 exec 超时）。
			String script = "trap '' 1; "
					+ "ps | grep app_process | grep -v grep | while read -r line; do set -- $line; kill -9 $2 2>/dev/null; done; "
					+ deployLib
					+ "LOG=; for c in " + candidates + "; do if ( : >> \"$c\" ) 2>/dev/null; then LOG=\"$c\"; break; fi; done; "
					+ "[ -z \"$LOG\" ] && LOG=/dev/null; "
					+ "echo \"SERVER_LOG=$LOG\"; "
					+ "su -cn u:r:shell:s0 -c \"trap '' 1; app_process -Djava.class.path='" + apk
					+ "' -Dapp.package='" + pkg
					+ "' /system/bin ru.playsoftware.mini_shizuku.server.AdbProcess"
					+ " >> \\\"$LOG\\\" 2>&1 </dev/null &\"";
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
