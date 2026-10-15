package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityManager;
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
import android.os.SystemClock;
import android.provider.Settings;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconResolver;
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
public final class KeydroidxBgManagerHelper {

	private static final String TAG = "BgManager";

	private KeydroidxBgManagerHelper() {}

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
			KeydroidxLog.w(TAG, "doProbe failed: " + e.getMessage());
			shizukuActivated = false;
		}
		if (prev != shizukuActivated) {
			KeydroidxLog.i(TAG, "mini_shizuku 状态变化: " + prev + " -> " + shizukuActivated);
		}
	}

	static {
		PROBE_THREAD = new HandlerThread("KeydroidxShizukuProbe");
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

	/**
	 * 是否为不可清理的系统应用（纯系统应用，非用户更新过的系统应用）。
	 * <p>包级可见：供「最近任务」页复用，保证两处对"系统应用"的口径完全一致
	 * （见 {@link KeydroidxRecentTasksHelper#buildTasks}）。
	 */
	private static boolean isSystemApp(ApplicationInfo ai) {
		if (ai == null) return false;
		if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false;
		return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
	}

	/**
	 * 当前默认输入法的包名（读不到返回 null）。
	 * <p>用户安装的第三方输入法（如搜狗）不带 FLAG_SYSTEM，{@link #isSystemApp} 拦不住它；
	 * 而输入法进程常驻内存，把它算进「N 个后台」只会让数字虚高（实测组件显示 2、
	 * 实际可清后台为 0，差的那 1 个就是输入法）。输入法由系统管理生命周期，
	 * 不是用户语义里的"后台应用"，后台枚举统一排除。
	 */
	private static String getDefaultImePackage(Context ctx) {
		try {
			String ime = Settings.Secure.getString(
					ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
			if (ime == null || ime.isEmpty()) return null;
			// 形如 "com.sogou.inputmethod.iot/.GBIme"，取斜杠前的包名
			int slash = ime.indexOf('/');
			return slash > 0 ? ime.substring(0, slash) : ime;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "读取默认输入法失败: " + e.getMessage());
			return null;
		}
	}

	// ---- 后台枚举：版本分流 ----

	/**
	 * 枚举后台包名集合（去重，排除桌面自身与系统应用）。
	 * <ul>
	 *   <li>API ≥ 21 且 mini_shizuku 已激活：执行 {@code dumpsys activity processes}
	 *       解析真实存活进程；</li>
	 *   <li>API ≥ 21 未激活：返回空（功能不可用，UI 显示「未激活」）；</li>
	 *   <li>API &lt; 21：{@link ActivityManager#getRunningAppProcesses()}。</li>
	 * </ul>
	 */
	private static Set<String> enumerateBackgroundPackages(Context ctx) {
		Set<String> out = new HashSet<>();
		try {
			String imePkg = getDefaultImePackage(ctx);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (!shizukuActivated) return out;
				Set<String> ps = enumerateAlivePackages(ctx);
				if (ps != null) out.addAll(ps);
				// 输入法进程常驻但不算"后台应用"
				if (imePkg != null) out.remove(imePkg);
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
				if (p.pkgList[0].equals(imePkg)) continue;
				out.add(p.pkgList[0]);
			}
		} catch (Exception e) {
			KeydroidxLog.e(TAG, "enumerateBackgroundPackages 失败", e);
		}
		return out;
	}

	/** 存活进程枚举命令：走 binder 经 system_server，不受 /proc 挂载参数与 SELinux capable 限制。 */
	private static final String CMD_ALIVE_PROCESSES = "dumpsys activity processes";
	/** {@code ProcessRecord{268f37d 23109:com.bilibili.app.in/u0a172}} → 进程名（包名或 pkg:子进程）。 */
	private static final Pattern P_PROCESS_RECORD =
			Pattern.compile("ProcessRecord\\{[0-9a-fA-F]+ \\d+:([a-zA-Z0-9_.]+)");

	/**
	 * 通过 mini_shizuku 执行 {@code dumpsys activity processes} 解析存活应用包名集合（去重）。
	 * <p>
	 * <b>为什么不用 {@code ps -A}</b>（2026-10-15 q968 / Android 13 实测教训）：
	 * 该 ROM 的 /proc 以 {@code hidepid=2,gid=3009} 挂载，不属于 readproc(3009) 组就看不到
	 * 其它 uid 的进程；root 服务端被 su 拉起时补充组被清空，补组又因 SELinux shell 域无
	 * {@code setgid} capability 许可而 EPERM——结果 {@code ps -A} 只能看到内核线程与原生
	 * 守护进程，<b>任何应用进程都不可见</b>（adb 激活因继承 shell 的 3009 组而一直正常）。
	 * {@code dumpsys} 走 binder 经 system_server 输出进程表，与 /proc 可见性无关，
	 * root/adb 两种激活模式行为一致。
	 * <p>
	 * 解析 {@code ProcessRecord{hash pid:pkg[:子进程]/uid}} 行取主包名；子进程
	 * {@code pkg:proc} 归并到主包名；非应用进程天然不出现 ProcessRecord，无需过滤。
	 *
	 * @return 包名集合；<b>探测异常或解析结果为空时返回 null（=「无法判断」）</b>，
	 *         调用方必须按 fail-open 处理（宁可多显示，不可误判为已死）
	 */
	private static Set<String> enumerateAlivePackages(Context ctx) {
		// 结果缓存：dumpsys activity processes 输出很大（q968 实测 ~260KB，约 6 倍于 ps -A），
		// 而桌面计数 / 最近任务页 / 音乐组件经常在同一秒内并发触发；低端机（msm8909）上
		// 连发会把 loadavg 打到 10+、整机卡顿。同 TTL 内共用一次结果，失败（null）不缓存。
		synchronized (ALIVE_CACHE_LOCK) {
			long now = SystemClock.elapsedRealtime();
			if (sAliveCache != null && now - sAliveCacheAtMs < ALIVE_CACHE_TTL_MS) {
				return sAliveCache;
			}
			Set<String> out = fetchAlivePackages(ctx);
			if (out != null) {
				sAliveCache = out;
				sAliveCacheAtMs = now;
			}
			return out;
		}
	}

	/** 存活集合缓存有效期（毫秒）。 */
	private static final long ALIVE_CACHE_TTL_MS = 5000L;
	private static final Object ALIVE_CACHE_LOCK = new Object();
	private static Set<String> sAliveCache;
	private static long sAliveCacheAtMs;

	/** 真正执行进程表查询并解析（无缓存）。 */
	private static Set<String> fetchAlivePackages(Context ctx) {
		String output;
		try {
			output = Shizuku.execWithOutput(CMD_ALIVE_PROCESSES);
		} catch (Exception e) {
			KeydroidxLog.e(TAG, "存活进程枚举执行失败", e);
			return null;
		}
		if (output == null || output.isEmpty()) {
			KeydroidxLog.w(TAG, "存活进程枚举无输出（mini_shizuku 服务异常？），按未知处理");
			return null;
		}
		Set<String> out = new HashSet<>();
		String self = ctx.getPackageName();
		Matcher m = P_PROCESS_RECORD.matcher(output);
		while (m.find()) {
			String name = m.group(1);
			if (name == null || name.isEmpty()) continue;
			// 子进程 pkg:proc → 取主包名
			String pkg = name.contains(":") ? name.substring(0, name.indexOf(':')) : name;
			if (pkg.isEmpty()) continue;
			// 排除桌面自身
			if (pkg.equals(self)) continue;
			out.add(pkg);
		}
		if (out.isEmpty()) {
			// 正常设备不可能一个应用进程都没有：走到这里说明输出格式异常/被截断，
			// 空集会被误当成「全部已死」（曾造成被清过的应用被永久隐藏），按未知处理
			KeydroidxLog.w(TAG, "进程表解析到 0 个包（疑似输出异常），按未知处理");
			return null;
		}
		// 诊断日志：核对「已清理」标记的包是否真被进程表看见。
		// 仅在存在「已清理」标记时输出，属低频枚举路径，不刷屏。
		if (!CLEARED_PKGS.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (String cleared : CLEARED_PKGS.keySet()) {
				sb.append(cleared).append(out.contains(cleared) ? "(存活)" : "(未见)").append(' ');
			}
			KeydroidxLog.i(TAG, "进程表诊断: 包数=" + out.size()
					+ " 已清理标记命中: " + sb.toString().trim());
		}
		return out;
	}

	// ---- 清理结果记忆（带自愈信息） ----

	/**
	 * 「已清理」标记的最长存活时间（TTL 保险丝）。
	 * <p>此前标记只加不撤，撤销依赖存活探测命中；而 root 通道的 {@code ps -A} 受
	 * /proc hidepid + SELinux 限制完全看不到应用进程（见 {@link #enumerateAlivePackages}），
	 * 撤销永不触发，导致被清过的应用被<b>永久隐藏</b>（2026-10-15 真机日志：
	 * 07:00:50 启动哔哩哔哩 → 07:00:56 仍「跳过已清理任务」，持续到 08:58）。
	 * TTL 保证任何探测异常下最多隐藏这么久。
	 */
	private static final long CLEARED_TTL_MS = 10 * 60 * 1000L;

	/**
	 * 已清理记录（仅进程内存，桌面进程重启即清空）。
	 * <p>除包名外还记录清理时刻（uptime 与墙上时钟<b>双时间基</b>）与清理时的系统任务栈 id，
	 * 供「最近任务」页用 dumpsys 任务记录自身的 {@code lastActiveTime} 判定「用户是否重新用过它」
	 * ——该判定<b>不依赖 {@code ps -A}</b>，与最近任务数据同一次 dumpsys 调用、零额外开销。
	 */
	static final class ClearedRecord {
		/** 清理时的系统任务栈 id；未知为 &lt;=0。 */
		final int taskId;
		/** {@link SystemClock#uptimeMillis()} 时间基的清理时刻（不含深睡）。 */
		final long clearedElapsedMs;
		/** {@link SystemClock#elapsedRealtime()} 时间基的清理时刻（含休眠，与 dumpsys lastActiveTime 同基）。 */
		final long clearedRealtimeMs;
		/** {@link System#currentTimeMillis()} 时间基的清理时刻。 */
		final long clearedWallMs;
		/** TTL 到期时刻（uptime 基）。 */
		final long expireElapsedMs;

		ClearedRecord(int taskId, long clearedElapsedMs, long clearedRealtimeMs, long clearedWallMs) {
			this.taskId = taskId;
			this.clearedElapsedMs = clearedElapsedMs;
			this.clearedRealtimeMs = clearedRealtimeMs;
			this.clearedWallMs = clearedWallMs;
			this.expireElapsedMs = clearedElapsedMs + CLEARED_TTL_MS;
		}
	}

	/** pkg → 已清理记录。并发访问（枚举线程 / 清理线程 / 主线程音乐组件），用并发表。 */
	private static final Map<String, ClearedRecord> CLEARED_PKGS =
			new ConcurrentHashMap<String, ClearedRecord>();

	/** 记录某个包已被本应用清理（未知 taskId 传 &lt;=0）。 */
	private static void markCleared(String pkg, int taskId) {
		if (pkg == null || pkg.isEmpty()) return;
		CLEARED_PKGS.put(pkg, new ClearedRecord(taskId,
				SystemClock.uptimeMillis(), SystemClock.elapsedRealtime(), System.currentTimeMillis()));
	}

	/** 便捷重载：未知 taskId。 */
	private static void markCleared(String pkg) {
		markCleared(pkg, -1);
	}

	/** 清除「已清理」标记：确认目标包又活过来时调用。 */
	public static void unmarkCleared(String pkg) {
		if (pkg != null) CLEARED_PKGS.remove(pkg);
	}

	/** 目标包是否刚被本应用清理、且尚未确认重新启动（过期记录视同未清理并自动清除）。 */
	public static boolean wasCleared(String pkg) {
		return getClearedRecord(pkg) != null;
	}

	/**
	 * 取未过期的已清理记录；过期即清除并返回 null。
	 * 包级可见：供「最近任务」页做「清理时刻 vs 任务 lastActiveTime」的自愈判定。
	 */
	static ClearedRecord getClearedRecord(String pkg) {
		if (pkg == null || pkg.isEmpty()) return null;
		ClearedRecord rec = CLEARED_PKGS.get(pkg);
		if (rec == null) return null;
		if (SystemClock.uptimeMillis() > rec.expireElapsedMs) {
			// TTL 保险丝：无论存活探测是否可靠，到期一律不再隐藏
			CLEARED_PKGS.remove(pkg);
			KeydroidxLog.i(TAG, "已清理标记过期自动失效: " + pkg);
			return null;
		}
		return rec;
	}

	/**
	 * 当前存活的应用包名集合（只读，绝不拉起任何进程）。
	 * <p>供「最近任务」页判定任务是否为空壳：{@code am force-stop} 在 Android 13 上只杀进程，
	 * <b>系统任务记录不会删除</b>（任务变成 sz=0 的空壳留在 recents 里），若不做存活校验，
	 * 页面会显示一堆已死应用（实测「桌面后台 1、最近任务 4」）。本集合与桌面组件的
	 * 后台计数用的是同一套存活判据，两处口径一致。
	 * <b>必须在后台线程调用</b>（含 shell 命令 / IPC）。
	 *
	 * @return 存活包名集合；<b>无法判断时返回 null</b>（5.0+ 未激活 mini_shizuku 或探测异常），
	 *         调用方应据此跳过过滤，不要当成"全部已死"
	 */
	public static Set<String> getAlivePackages(Context ctx) {
		if (ctx == null) return null;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (!shizukuActivated) return null;
				return enumerateAlivePackages(ctx);
			}
			// 4.4：getRunningAppProcesses 可枚举全部进程
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return null;
			List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
			if (procs == null) return null;
			Set<String> alive = new HashSet<>();
			for (ActivityManager.RunningAppProcessInfo p : procs) {
				if (p.pkgList != null) Collections.addAll(alive, p.pkgList);
			}
			return alive;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "getAlivePackages 探测失败: " + e.getMessage());
			return null;
		}
	}

	/**
	 * 目标包当前是否有存活进程（<b>只读，绝不拉起进程</b>）。
	 * <ul>
	 *   <li>5.0+：走 {@code ps -A}（需 mini_shizuku）；shizuku 未激活时无法判断，返回 true；</li>
	 *   <li>4.4：走 {@link ActivityManager#getRunningAppProcesses()}。</li>
	 * </ul>
	 * 无法判断时按「存活」处理——宁可多查一次，也不能把正在播放的应用误判成已停止。
	 */
	public static boolean isPackageAlive(Context ctx, String pkg) {
		if (ctx == null || pkg == null || pkg.isEmpty()) return true;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (!shizukuActivated) return true;
				Set<String> alive = enumerateAlivePackages(ctx);
				// 探测失败/未知（null）时按「存活」处理——宁可多查一次，也不能把正在
				// 播放的应用误判成已停止（与 getAlivePackages 的 fail-open 契约一致）
				return alive == null || alive.contains(pkg);
			}
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return true;
			List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
			if (procs == null) return true;
			for (ActivityManager.RunningAppProcessInfo p : procs) {
				if (p.pkgList == null) continue;
				for (String name : p.pkgList) {
					if (pkg.equals(name)) return true;
				}
			}
			return false;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "isPackageAlive 判断失败: " + e.getMessage());
			return true;
		}
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
						// 图标包优先（单应用覆盖 → 全局图标包），未命中再用应用自身图标
						icon = KeydroidxIconResolver.resolvePackIcon(ctx, pkg, null, name);
						if (icon == null) icon = pm.getApplicationIcon(ai);
					} catch (Exception ignored) {
						KeydroidxLog.w(TAG, "加载图标失败: " + ignored.getMessage());
					}
					boolean prot = protectedSet != null && protectedSet.contains(pkg);
					out.add(new BgTask(pkg, name, icon, prot));
				} catch (PackageManager.NameNotFoundException e) {
					KeydroidxLog.w(TAG, "add failed: " + e.getMessage());
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
			KeydroidxLog.e(TAG, "enumerateBackgroundTasks 失败", e);
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
			KeydroidxLog.w(TAG, "追加挂机 jar 条目失败: " + e);
		}
	}

	/** 加载挂机 jar 图标（复用百宝箱 AppItem 图标），失败返回 null（UI 有兜底）。包级可见：最近任务页复用。 */
	static Drawable loadMidletIcon(Context ctx, String appPath) {
		try {
			AppItem item = AppUtils.findAppByPath(appPath);
			if (item == null) return null;
			String rel = item.getImagePathExt();
			if (rel == null || rel.isEmpty()) return null;
			Bitmap bmp = BitmapFactory.decodeFile(appPath + rel);
			return bmp == null ? null : new BitmapDrawable(ctx.getResources(), bmp);
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "loadMidletIcon failed: " + e.getMessage());
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
				Intent intent = new Intent(KeydroidxMidletControlReceiver.ACTION_DESTROY_MIDLET);
				intent.setClass(ctx, KeydroidxMidletControlReceiver.class);
				ctx.sendBroadcast(intent);
				cleared++;
				KeydroidxLog.i(TAG, "已清理挂机jar(广播销毁): " + t.name);
			}
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			// Android 5.0+ 其它应用清理只有 mini_shizuku（shell 身份）可用；
			// 未激活时仅挂机 jar 可清（上面已处理），直接返回。
			if (!shizukuActivated) {
				if (cleared > 0) {
					KeydroidxLog.w(TAG, "mini_shizuku 未激活，本次仅清理了挂机 jar");
				}
				return cleared;
			}
			// 批量拼接 force-stop，一次 shizuku 调用完成，减少往返
			StringBuilder cmd = new StringBuilder();
			for (BgTask t : tasks) {
				if (t.prot || MidletStateStore.isMidletTaskKey(t.pkg)) continue;
				cmd.append("am force-stop ").append(t.pkg).append(";");
				cleared++;
				// 记住「是我们杀的」，桌面等调用方据此把该 App 视为已停止，
				// 不再去跨进程查询（查询会把它重新冷启动）
				markCleared(t.pkg);
				KeydroidxLog.i(TAG, "已清理后台(force-stop): " + t.name + " (" + t.pkg + ")");
			}
			if (cmd.length() > 0) {
				boolean ok = Shizuku.exec(cmd.toString());
				if (!ok) {
					KeydroidxLog.w(TAG, "force-stop 批量命令发送失败");
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
					markCleared(t.pkg);
					KeydroidxLog.i(TAG, "已清理后台(kill): " + t.name + " (" + t.pkg + ")");
				} catch (Exception e) {
					KeydroidxLog.w(TAG, "清理失败: " + t.pkg + " -> " + e.getMessage());
				}
			}
		}
		return cleared;
	}

	/**
	 * 手动清理<b>单个</b>任务（<b>不受保护名单限制</b>）。
	 * <p>供「最近任务」页的「清理此任务」使用：保护名单只挡批量清理（0 键 / 清理全部），
	 * 用户对某个应用显式发起的手动清理仍然生效。
	 * <b>必须在后台线程调用</b>（含 TCP / shell 命令）。
	 *
	 * @param pkg     包名；挂机 jar 条目可传条目 key
	 * @param taskKey 任务标识（{@code midlet:<appPath>} 或包名）
	 * @param taskId  系统任务栈 id（来自 dumpsys / getRecentTasks）；未知传 &lt;= 0。
	 *                Android 5.0+ 用它把任务记录一并删除，卡片才会真正从「最近任务」消失——
	 *                实测 {@code am force-stop} <b>只杀进程</b>，任务记录会以 {@code sz=0} 的
	 *                形态继续留在系统 recents 里。
	 * @return 是否已下发清理
	 */
	public static boolean clearSingleTask(Context ctx, String pkg, String taskKey, int taskId) {
		if (ctx == null) return false;
		// 挂机 jar：显式广播 → :midlet 进程内优雅销毁（与批量清理同一链路）
		if (MidletStateStore.isMidletTaskKey(taskKey)) {
			Intent intent = new Intent(KeydroidxMidletControlReceiver.ACTION_DESTROY_MIDLET);
			intent.setClass(ctx, KeydroidxMidletControlReceiver.class);
			ctx.sendBroadcast(intent);
			KeydroidxLog.i(TAG, "手动清理挂机jar(广播销毁): " + taskKey);
			return true;
		}
		if (pkg == null || pkg.isEmpty()) return false;

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			if (!shizukuActivated) {
				KeydroidxLog.w(TAG, "mini_shizuku 未激活，无法手动清理: " + pkg);
				return false;
			}
			boolean ok = Shizuku.exec("am force-stop " + pkg);
			if (ok && taskId > 0) {
				// force-stop 不清任务记录（实测 Android 13 上任务变成 sz=0 仍留在 recents），
				// 追加 removeTask 才能真正抹掉「最近任务」卡片（实测有效）。
				// 老版本若不支持该子命令，仅报错不影响上面的进程清理。
				Shizuku.exec("am stack remove " + taskId);
			}
			// 保留「已清理」标记：拿不到 taskId（或删栈失败）时，靠它把该条目从列表隐去
			// （记录 taskId 与清理时刻，供最近任务页做「是否被重新使用」的自愈判定）
			if (ok) markCleared(pkg, taskId);
			KeydroidxLog.i(TAG, "手动清理单任务(force-stop"
					+ (taskId > 0 ? "+removeTask" : "") + "): " + pkg + " ok=" + ok);
			return ok;
		}
		try {
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return false;
			am.killBackgroundProcesses(pkg);
			markCleared(pkg);
			KeydroidxLog.i(TAG, "手动清理单任务(kill): " + pkg);
			return true;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "手动清理单任务失败: " + pkg + " -> " + e.getMessage());
			return false;
		}
	}
}
