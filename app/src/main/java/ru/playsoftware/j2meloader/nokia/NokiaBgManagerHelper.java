package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import io.github.cctyl.nokia.common.log.NokiaLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.MidletStateStore;
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

	/** 后台探测线程：执行 Shizuku 在线探测（TCP）；缓存按需刷新，不再常驻轮询。 */
	private static final HandlerThread PROBE_THREAD;
	private static final Handler PROBE_HANDLER;
	private static volatile boolean shizukuActivated = false;

	private static final Runnable PROBE_RUNNABLE = new Runnable() {
		@Override
		public void run() {
			doProbe();
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
		// 首次立即探测一次；之后按需刷新（probeShizukuSync / requestProbe），不再常驻轮询
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
	 * 缓存不自动刷新；需准确值时由调用方主动探测刷新（probeShizukuSync / requestProbe），4.4 恒为 true。
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

	/** 统计当前后台进程数（按包名去重，排除桌面自身与系统应用，含挂机 jar）。供桌面组件行实时显示。 */
	public static int countBackgroundProcesses(Context ctx) {
		Set<String> pkgs = enumerateBackgroundPackages(ctx);
		int n = 0;
		if (!pkgs.isEmpty()) {
			PackageManager pm = ctx.getPackageManager();
			if (pm == null) {
				n = pkgs.size();
			} else {
				for (String pkg : pkgs) {
					try {
						ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
						if (isSystemApp(ai)) continue;
					} catch (PackageManager.NameNotFoundException e) {
						continue;
					}
					n++;
				}
			}
		}
		// 挂机 jar 计入后台进程数（不依赖 shizuku）
		if (MidletStateStore.getRunning(ctx) != null) {
			n++;
		}
		return n;
	}

	/**
	 * 枚举后台任务（含图标与保护状态），按名称排序。
	 * 可在后台线程调用（内部有 PackageManager 查询 / shizuku 命令）。已卸载的残留进程
	 * 与系统应用自动跳过。挂机 jar 条目（key 形如 {@code midlet:<appPath>}）不依赖
	 * mini_shizuku / 版本路径，4.4 与 5.0+ 行为一致。
	 *
	 * @param protectedSet 保护名单（包名或挂机条目 key 集合）；可为 null
	 */
	public static List<BgTask> enumerateBackgroundTasks(Context ctx, Set<String> protectedSet) {
		List<BgTask> out = new ArrayList<>();
		// 挂机 jar 条目优先加入（即使无其它后台应用也显示；自身进程全版本可枚举）
		appendMidletTask(ctx, protectedSet, out);
		try {
			PackageManager pm = ctx.getPackageManager();
			if (pm == null) return out;
			Set<String> pkgs = enumerateBackgroundPackages(ctx);
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

	/** 追加挂机 jar 条目（读跨进程状态文件 + 校验 :midlet 进程存活）。 */
	private static void appendMidletTask(Context ctx, Set<String> protectedSet, List<BgTask> out) {
		try {
			MidletStateStore.RunningInfo running = MidletStateStore.getRunning(ctx);
			if (running == null) return;
			String key = MidletStateStore.taskKey(running.appPath);
			boolean prot = protectedSet != null && protectedSet.contains(key);
			out.add(new BgTask(key, running.appName, loadMidletIcon(ctx, running.appPath), prot));
		} catch (Exception e) {
			NokiaLog.w(TAG, "追加挂机 jar 条目失败: " + e);
		}
	}

	/** 加载挂机 jar 图标（复用百宝箱 AppItem 图标），失败返回 null（UI 有兜底）。 */
	private static Drawable loadMidletIcon(Context ctx, String appPath) {
		try {
			AppItem item = AppUtils.findAppByPath(appPath);
			if (item == null) return null;
			String rel = item.getImagePathExt();
			if (rel == null || rel.isEmpty()) return null;
			Bitmap bmp = BitmapFactory.decodeFile(appPath + rel);
			return bmp == null ? null : new BitmapDrawable(ctx.getResources(), bmp);
		} catch (Exception e) {
			return null;
		}
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

		// 挂机 jar 清理：显式广播 → :midlet 进程内优雅销毁（END 键 → destroyApp(true) →
		// 清状态 → killProcess）。不依赖 mini_shizuku；严禁 force-stop/killBackgroundProcesses
		// —— 它们作用于整个包，会连桌面主进程一起杀。
		for (BgTask t : tasks) {
			if (t.prot) continue;
			if (MidletStateStore.isMidletTaskKey(t.pkg)) {
				Intent intent = new Intent(NokiaMidletControlReceiver.ACTION_DESTROY_MIDLET);
				intent.setClass(ctx, NokiaMidletControlReceiver.class);
				ctx.sendBroadcast(intent);
				cleared++;
				NokiaLog.i(TAG, "已清理挂机jar(广播销毁): " + t.name);
			}
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			// Android 5.0+ 其它应用清理只有 mini_shizuku（shell 身份）可用；
			// 未激活时仅挂机 jar 可清（上面已处理），直接返回。
			if (!shizukuActivated) {
				if (cleared > 0) {
					NokiaLog.w(TAG, "mini_shizuku 未激活，本次仅清理了挂机 jar");
				}
				return cleared;
			}
			// 批量拼接 force-stop，一次 shizuku 调用完成，减少往返
			StringBuilder cmd = new StringBuilder();
			for (BgTask t : tasks) {
				if (t.prot || MidletStateStore.isMidletTaskKey(t.pkg)) continue;
				cmd.append("am force-stop ").append(t.pkg).append(";");
				cleared++;
				NokiaLog.i(TAG, "已清理后台(force-stop): " + t.name + " (" + t.pkg + ")");
			}
			if (cmd.length() > 0) {
				boolean ok = Shizuku.exec(cmd.toString());
				if (!ok) {
					NokiaLog.w(TAG, "force-stop 批量命令发送失败");
				}
			}
		} else {
			// 4.4 降级路径（该版本 getRunningAppProcesses/killBackgroundProcesses 可用）
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return cleared;
			for (BgTask t : tasks) {
				if (t.prot || MidletStateStore.isMidletTaskKey(t.pkg)) continue;
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
