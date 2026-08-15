package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 后台管理工具类。
 * 负责：枚举后台进程（非前台/非可见）、统计后台数量、清理后台进程（跳过保护名单）。
 * <p>
 * <b>Android 5.0+ 的关键限制（重要）：</b>
 * {@link ActivityManager#getRunningAppProcesses()} 从 API 21 起被收紧，普通应用只会返回
 * 自身进程；{@link UsageStatsManager} 返回的是「使用记录」而非「存活进程」，会把已退出/冻结
 * 的应用误判为后台。因此 Android 5.0+ 一律要求 mini_shizuku（adb/shell 身份）执行
 * {@code ps -A} 获取真实存活进程列表，并用 {@code am force-stop} 清理——这是唯一准确的
 * 方案。未激活 mini_shizuku 时，后台管理功能不可用（UI 各处显示「未激活」提示）。
 * <p>
 * Android 4.4（API &lt; 21）不受此限制，仍沿用 {@link ActivityManager#getRunningAppProcesses()}
 * 与 {@link ActivityManager#killBackgroundProcesses(String)}。
 */
public final class NokiaBgManagerHelper {

	private static final String TAG = "BgManager";

	private NokiaBgManagerHelper() {}

	// ---- mini_shizuku 状态缓存（避免主线程 TCP 探测卡顿）----

	/** 后台探测线程：周期性更新 {@link #shizukuActivated} 缓存。 */
	private static final HandlerThread PROBE_THREAD;
	private static final Handler PROBE_HANDLER;
	private static final long PROBE_INTERVAL_MS = 3000L;
	private static volatile boolean shizukuActivated = false;

	private static final Runnable PROBE_RUNNABLE = new Runnable() {
		@Override
		public void run() {
			doProbe();
			PROBE_HANDLER.postDelayed(PROBE_RUNNABLE, PROBE_INTERVAL_MS);
		}
	};

	/** 实际探测逻辑（必须在后台线程调用，含 TCP 操作）。 */
	private static void doProbe() {
		boolean prev = shizukuActivated;
		try {
			if (needsShizuku()) {
				shizukuActivated = Shizuku.isRunning();
			} else {
				// 4.4 不需要 shizuku，视为永远可用
				shizukuActivated = true;
			}
		} catch (Exception e) {
			shizukuActivated = false;
		}
		if (prev != shizukuActivated) {
			NokiaLog.i(TAG, "mini_shizuku 状态变化: " + prev + " -> " + shizukuActivated);
		}
	}

	static {
		PROBE_THREAD = new HandlerThread("NokiaShizukuProbe");
		PROBE_THREAD.start();
		PROBE_HANDLER = new Handler(PROBE_THREAD.getLooper());
		// 首次立即探测一次，之后周期刷新
		PROBE_HANDLER.post(PROBE_RUNNABLE);
	}

	/**
	 * Android 5.0+ 是否需要 mini_shizuku 才能准确枚举/清理后台。
	 * 4.4（API &lt; 21）有可用的 getRunningAppProcesses，不需要。
	 */
	public static boolean needsShizuku() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
	}

	/**
	 * mini_shizuku 是否已激活（读缓存，不阻塞主线程）。
	 * 缓存由后台线程每 {@link #PROBE_INTERVAL_MS} 刷新一次；4.4 恒为 true。
	 */
	public static boolean isShizukuActivated() {
		return shizukuActivated;
	}

	/**
	 * 同步探测一次 mini_shizuku 状态并更新缓存。
	 * <b>必须在后台线程调用</b>（含 TCP 操作，主线程会抛 NetworkOnMainThreadException）。
	 * 用于进入页面后需要立即拿到准确值的场景。
	 */
	public static void probeShizukuSync() {
		doProbe();
	}

	/**
	 * 异步触发一次探测（主线程安全，投到后台探测线程立即执行）。
	 * 用于从激活页返回等需要立即刷新缓存的场景。
	 */
	public static void requestProbe() {
		PROBE_HANDLER.removeCallbacks(PROBE_RUNNABLE);
		PROBE_HANDLER.post(PROBE_RUNNABLE);
	}

	/**
	 * 后台管理功能是否可用：4.4 不需要 shizuku；5.0+ 需 shizuku 已激活。
	 */
	public static boolean isBgManagerAvailable() {
		if (!needsShizuku()) return true;
		return shizukuActivated;
	}

	/** 跳转系统「使用情况访问权限」设置页（API 21+，旧版降级保留，现已不再依赖）。 */
	public static boolean openUsageAccessSettings(Context ctx) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
		try {
			Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(intent);
			return true;
		} catch (Exception e) {
			NokiaLog.e(TAG, "打开使用情况访问权限设置失败", e);
			return false;
		}
	}

	// ---- 后台任务条目 ----

	/** 后台任务条目：包名 + 显示名 + 图标 + 保护状态。 */
	public static class BgTask {
		public final String pkg;
		public final String name;
		public Drawable icon;
		public boolean prot;

		public BgTask(String pkg, String name, Drawable icon, boolean prot) {
			this.pkg = pkg;
			this.name = name;
			this.icon = icon;
			this.prot = prot;
		}
	}

	/** 该进程是否算「后台进程」（仅 4.4 路径使用）。 */
	public static boolean isBackgroundProcess(ActivityManager.RunningAppProcessInfo p) {
		return p != null
				&& p.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
	}

	/** 排除桌面自身进程（主进程与 :midlet 子进程），这些永远不可清理。 */
	public static boolean isSelfProcess(Context ctx, String processName, String pkg) {
		String self = ctx.getPackageName();
		if (pkg != null && pkg.equals(self)) return true;
		return processName != null && processName.startsWith(self + ":");
	}

	/** 是否为不可清理的系统应用（纯系统应用，非用户更新过的系统应用）。 */
	private static boolean isSystemApp(ApplicationInfo ai) {
		if (ai == null) return false;
		if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false;
		return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
	}

	// ---- 后台枚举：版本分流 ----

	/**
	 * 枚举后台包名集合（去重，排除桌面自身与系统应用）。
	 * <ul>
	 *   <li>API ≥ 21 且 mini_shizuku 已激活：执行 {@code ps -A} 解析真实存活进程；</li>
	 *   <li>API ≥ 21 未激活：返回空（功能不可用，UI 显示「未激活」）；</li>
	 *   <li>API &lt; 21：{@link ActivityManager#getRunningAppProcesses()}。</li>
	 * </ul>
	 */
	private static Set<String> enumerateBackgroundPackages(Context ctx) {
		Set<String> out = new HashSet<>();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (!shizukuActivated) return out;
				out.addAll(enumerateViaPs(ctx));
				return out;
			}
			// API < 21：getRunningAppProcesses 可正常枚举全部后台
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return out;
			List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
			if (procs == null) return out;
			for (ActivityManager.RunningAppProcessInfo p : procs) {
				if (!isBackgroundProcess(p)) continue;
				if (p.pkgList == null || p.pkgList.length == 0) continue;
				if (isSelfProcess(ctx, p.processName, p.pkgList[0])) continue;
				out.add(p.pkgList[0]);
			}
		} catch (Exception e) {
			NokiaLog.e(TAG, "enumerateBackgroundPackages 失败", e);
		}
		return out;
	}

	/**
	 * 通过 mini_shizuku 执行 {@code ps -A} 解析存活进程包名集合（去重）。
	 * 解析 NAME 列（最后一列）：内核线程 {@code [xxx]} 跳过；子进程 {@code pkg:proc}
	 * 取主包名；非应用进程（init / 守护进程）由后续 PackageManager 查询天然过滤。
	 */
	private static Set<String> enumerateViaPs(Context ctx) {
		Set<String> out = new HashSet<>();
		String output;
		try {
			output = Shizuku.execWithOutput("ps -A");
		} catch (Exception e) {
			NokiaLog.e(TAG, "ps -A 执行失败", e);
			return out;
		}
		if (output == null || output.isEmpty()) {
			NokiaLog.w(TAG, "ps -A 无输出（mini_shizuku 服务异常？）");
			return out;
		}
		String self = ctx.getPackageName();
		for (String line : output.split("\n")) {
			if (line == null || line.isEmpty()) continue;
			line = line.trim();
			// 跳过表头
			if (line.startsWith("USER")) continue;
			String[] cols = line.split("\\s+");
			if (cols.length < 2) continue;
			String name = cols[cols.length - 1];
			if (name == null || name.isEmpty()) continue;
			// 内核线程 [xxx] 跳过
			if (name.startsWith("[")) continue;
			// 子进程 pkg:proc → 取主包名
			String pkg = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
			if (pkg.isEmpty()) continue;
			// 排除桌面自身
			if (pkg.equals(self)) continue;
			out.add(pkg);
		}
		return out;
	}

	/** 统计当前后台进程数（按包名去重，排除桌面自身与系统应用）。供桌面组件行实时显示。 */
	public static int countBackgroundProcesses(Context ctx) {
		Set<String> pkgs = enumerateBackgroundPackages(ctx);
		if (pkgs.isEmpty()) return 0;
		PackageManager pm = ctx.getPackageManager();
		if (pm == null) return pkgs.size();
		int n = 0;
		for (String pkg : pkgs) {
			try {
				ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
				if (isSystemApp(ai)) continue;
			} catch (PackageManager.NameNotFoundException e) {
				continue;
			}
			n++;
		}
		return n;
	}

	/**
	 * 枚举后台任务（含图标与保护状态），按名称排序。
	 * 可在后台线程调用（内部有 PackageManager 查询 / shizuku 命令）。已卸载的残留进程
	 * 与系统应用自动跳过。
	 *
	 * @param protectedSet 保护名单（包名集合）；可为 null
	 */
	public static List<BgTask> enumerateBackgroundTasks(Context ctx, Set<String> protectedSet) {
		List<BgTask> out = new ArrayList<>();
		try {
			PackageManager pm = ctx.getPackageManager();
			if (pm == null) return out;
			Set<String> pkgs = enumerateBackgroundPackages(ctx);
			if (pkgs.isEmpty()) return out;
			for (String pkg : pkgs) {
				try {
					ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
					// 排除系统应用：它们无法被清理，混在列表里只会误导用户
					if (isSystemApp(ai)) continue;
					String name = pm.getApplicationLabel(ai) != null
							? pm.getApplicationLabel(ai).toString() : pkg;
					Drawable icon = null;
					try {
						icon = pm.getApplicationIcon(ai);
					} catch (Exception ignored) {}
					boolean prot = protectedSet != null && protectedSet.contains(pkg);
					out.add(new BgTask(pkg, name, icon, prot));
				} catch (PackageManager.NameNotFoundException e) {
					// 非应用进程（init/守护进程）或已卸载残留，跳过
				}
			}
			Collections.sort(out, new Comparator<BgTask>() {
				@Override
				public int compare(BgTask a, BgTask b) {
					return a.name.compareToIgnoreCase(b.name);
				}
			});
		} catch (Exception e) {
			NokiaLog.e(TAG, "enumerateBackgroundTasks 失败", e);
		}
		return out;
	}

	/**
	 * 清理所有未保护的后台进程（跳过保护名单），返回实际清理数量。
	 * <b>必须在后台线程调用</b>（含 TCP / shell 命令）。
	 * <ul>
	 *   <li>API ≥ 21 且 mini_shizuku 已激活：批量 {@code am force-stop}（shell 身份，
	 *       能杀掉有服务在跑的应用，比 killBackgroundProcesses 彻底）；</li>
	 *   <li>API ≥ 21 未激活：返回 0 —— 普通应用无权限清理其它应用后台；</li>
	 *   <li>API &lt; 21：{@link ActivityManager#killBackgroundProcesses(String)}。</li>
	 * </ul>
	 */
	public static int clearBackgroundTasks(Context ctx, Set<String> protectedSet) {
		List<BgTask> tasks = enumerateBackgroundTasks(ctx, protectedSet);
		if (tasks.isEmpty()) return 0;
		int cleared = 0;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			// Android 5.0+ 只有 mini_shizuku（shell 身份）能清理其它应用的后台进程；
			// 未激活时 killBackgroundProcesses 只对自身进程生效，禁止走无效分支误报成功。
			if (!shizukuActivated) return 0;
			// 批量拼接 force-stop，一次 shizuku 调用完成，减少往返
			StringBuilder cmd = new StringBuilder();
			for (BgTask t : tasks) {
				if (t.prot) continue;
				cmd.append("am force-stop ").append(t.pkg).append(";");
				cleared++;
				NokiaLog.i(TAG, "已清理后台(force-stop): " + t.name + " (" + t.pkg + ")");
			}
			if (cleared > 0) {
				boolean ok = Shizuku.exec(cmd.toString());
				if (!ok) {
					NokiaLog.w(TAG, "force-stop 批量命令发送失败");
				}
			}
		} else {
			// 4.4 降级路径（该版本 getRunningAppProcesses/killBackgroundProcesses 可用）
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return 0;
			for (BgTask t : tasks) {
				if (t.prot) continue;
				try {
					am.killBackgroundProcesses(t.pkg);
					cleared++;
					NokiaLog.i(TAG, "已清理后台(kill): " + t.name + " (" + t.pkg + ")");
				} catch (Exception e) {
					NokiaLog.w(TAG, "清理失败: " + t.pkg + " -> " + e.getMessage());
				}
			}
		}
		return cleared;
	}
}
