package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconResolver;
import ru.playsoftware.j2meloader.util.MidletStateStore;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 「最近任务」数据源工具类。
 *
 * <p><b>为什么需要特权（重要）：</b>第三方应用没有系统 Recents 的读取权限——
 * {@link ActivityManager#getRecentTasks(int, int)} 自 API 21 起被收紧（普通应用只能看到自己的任务），
 * {@link ActivityManager#getAppTasks()} 同样只返回调用方自身的任务。因此只有两条通道：</p>
 * <ol>
 *   <li><b>Android 4.4（API &lt; 21）</b>：{@code getRecentTasks} 仍返回全部最近任务（需 GET_TASKS 权限，
 *       安装即授予），任务栈顺序即最近使用顺序；</li>
 *   <li><b>Android 5.0+ 且 mini_shizuku 已激活</b>：以 shell 身份执行 {@code dumpsys activity recents}
 *       解析真实任务记录（本类不依赖 ActivityManager 的权限收紧）。</li>
 * </ol>
 * <p><b>刻意不做降级：</b>5.0+ 未激活 mini_shizuku 时不提供「最近使用」(UsageStats) 之类的
 * 近似列表——那只是"历史打开记录"，不代表任务还在，展示出来会误导用户（与桌面后台计数也对不上）。
 * 此时返回空列表，UI 显示「未激活」并给出激活引导。</p>
 *
 * <p><b>缩略图不可得：</b>系统 Recents 的 {@code TaskSnapshot} 需要系统级权限
 * （CAPTURE_TASK_SNAPSHOTS），普通应用无法获取别家应用的当前画面，因此卡片只用
 * 应用图标 + 名称 + 最近时间表现。</p>
 *
 * <p><b>线程模型：</b>{@link #enumerate(Context)} 内含 shell（TCP）与 PackageManager（重 IPC）
 * 调用，<b>必须在后台线程执行</b>；结果回主线程渲染。调用前建议先
 * {@link KeydroidxBgManagerHelper#probeShizukuSync()} 刷新 shizuku 状态缓存。</p>
 */
public final class KeydroidxRecentTasksHelper {

	private static final String TAG = "RecentTasks";

	/** 列表上限：卡片 2 列，12 条 = 6 行，超出可滚动。 */
	private static final int MAX_TASKS = 12;

	/** 挂机 jar 条目 key 前缀（与 {@link MidletStateStore#taskKey(String)} 保持一致）。 */
	private static final String MIDLET_KEY_PREFIX = "midlet:";

	// ---- 模式 ----

	/** 无可用数据源（5.0+ 未激活 mini_shizuku）。 */
	public static final int MODE_UNAVAILABLE = 0;
	/** 真实任务列表（4.4 getRecentTasks / 5.0+ mini_shizuku dumpsys）。 */
	public static final int MODE_REAL_TASK = 1;

	// ---- dumpsys 解析正则（版本差异大，采用宽松匹配 + 多重兜底） ----

	/** 匹配 {@code Task{...}} / {@code TaskRecord{...}} 块首行。 */
	private static final Pattern P_TASK_BLOCK = Pattern.compile("Task(?:Record)?\\{([^}]*)\\}");
	private static final Pattern P_HASH_ID = Pattern.compile("#(\\d+)");
	/** 形如 {@code A=10234:com.example.app}。 */
	private static final Pattern P_PKG_A = Pattern.compile("A=\\d+:([A-Za-z0-9_.]+)");
	/** 形如 {@code cmp=com.example.app/.MainActivity} / {@code realActivity=com.example.app/.MainActivity}。 */
	private static final Pattern P_PKG_COMPONENT =
			Pattern.compile("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+");
	private static final Pattern P_TIME =
			Pattern.compile("(?:lastActiveTime|lastTimeMoved|lastUsedTime)=(\\d+)");

	private KeydroidxRecentTasksHelper() {}

	// ============================================================
	// 数据模型
	// ============================================================

	/** 最近任务条目。{@code pkg} 为包名；挂机 jar 条目为 {@code midlet:<appPath>}。 */
	public static class RecentTask {
		public final String pkg;
		public final String name;
		public final String taskKey;
		public final int taskId;
		/** 距上次使用的毫秒数；&lt;=0 表示未知（如 4.4 getRecentTasks 不提供时间）。 */
		public final long agoMs;
		public Drawable icon;

		RecentTask(String pkg, String name, String taskKey, int taskId, long agoMs, Drawable icon) {
			this.pkg = pkg;
			this.name = name;
			this.taskKey = taskKey;
			this.taskId = taskId;
			this.agoMs = agoMs;
			this.icon = icon;
		}

		/** 是否为挂机 jar 条目。 */
		public boolean isMidlet() {
			return MidletStateStore.isMidletTaskKey(taskKey);
		}
	}

	// ============================================================
	// 模式判定
	// ============================================================

	/**
	 * 当前可用的数据源模式。可在任意线程调用（只读 shizuku 状态缓存与 AppOps）。
	 * <p>注意：调用方应先 {@link KeydroidxBgManagerHelper#probeShizukuSync()} 刷新状态缓存，
	 * 否则冷启动瞬间可能误判为「未激活」。
	 */
	public static int getMode(Context ctx) {
		if (ctx == null) return MODE_UNAVAILABLE;
		// 4.4：getRecentTasks 可用，不需要任何特权
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return MODE_REAL_TASK;
		if (KeydroidxBgManagerHelper.isShizukuActivated()) return MODE_REAL_TASK;
		return MODE_UNAVAILABLE;
	}

	/** 模式文案（标题右侧徽标）。 */
	public static String getModeLabel(int mode) {
		return mode == MODE_REAL_TASK ? "实时" : "未激活";
	}

	// ============================================================
	// 枚举
	// ============================================================

	/** 原始候选（未过滤、未加载名称图标）。{@code agoMs} 已归一化为「距今多久」。 */
	private static final class Cand {
		final String pkg;
		final int taskId;
		final long agoMs;

		Cand(String pkg, int taskId, long agoMs) {
			this.pkg = pkg;
			this.taskId = taskId;
			this.agoMs = agoMs;
		}
	}

	/**
	 * 枚举最近任务（<b>必须在后台线程调用</b>）。
	 * 结果按系统任务栈顺序去重；挂机 jar 条目（若存在）固定置于首位。
	 *
	 * @return 可渲染的条目列表；任何异常都会降级为空列表，不会抛出
	 */
	public static List<RecentTask> enumerate(Context ctx) {
		List<RecentTask> out = new ArrayList<>();
		if (ctx == null) return out;
		collect(ctx, out, true);
		KeydroidxLog.i(TAG, "最近任务枚举完成: 条目=" + out.size());
		return out;
	}

	/**
	 * 只统计「最近任务」条目数（<b>必须在后台线程调用</b>），不加载应用名与图标。
	 * <p>供桌面「最近任务」组件行实时显示。它与页面列表走<b>同一个</b> {@link #collect}，
	 * 同源同规则，因此两处数字在结构上不可能对不上——这正是本次重构的目的
	 * （此前组件数存活进程、页面数任务栈，两套口径必然不一致）。
	 * 跳过名称与图标加载，是为了省掉每个包的 PackageManager 重 IPC。
	 */
	public static int countTasks(Context ctx) {
		if (ctx == null) return 0;
		List<RecentTask> out = new ArrayList<>();
		collect(ctx, out, false);
		return out.size();
	}

	/**
	 * 收集最近任务条目：枚举 → 过滤 → （可选）加载名称图标 → 挂机 jar 置顶。
	 *
	 * @param loadUi true 加载应用名与图标（页面渲染用）；false 仅计数（桌面组件用）
	 */
	private static void collect(Context ctx, List<RecentTask> out, boolean loadUi) {
		int mode = getMode(ctx);
		List<Cand> cands = new ArrayList<>();
		try {
			if (mode == MODE_UNAVAILABLE) {
				KeydroidxLog.w(TAG, "无可用数据源（5.0+ 未激活 mini_shizuku）");
			} else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
				cands = collectViaRecentTasks(ctx);
			} else {
				cands = collectViaDumpsys();
			}
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "枚举最近任务失败（降级为空列表）: " + e.getMessage());
		}

		buildTasks(ctx, cands, out, mode, loadUi);

		// 挂机 jar 固定置顶：它是唯一"确定还活着"的条目，且自身进程可枚举、不依赖任何特权
		RecentTask midlet = buildMidletTask(ctx, loadUi);
		if (midlet != null) {
			out.add(0, midlet);
		}
	}

	// ---- 通道 1：API < 21 的 getRecentTasks ----

	/**
	 * Android 4.4 路径：{@link ActivityManager#getRecentTasks(int, int)}。
	 * 该 API 自 API 21 起被废弃并收紧，仅在 4.4 及以下可用（需 GET_TASKS 权限）。
	 * <p>该 API 不提供任务时间，{@code agoMs} 恒为 0（UI 不显示时间）。
	 */
	@SuppressWarnings("deprecation")
	private static List<Cand> collectViaRecentTasks(Context ctx) {
		List<Cand> out = new ArrayList<>();
		ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
		if (am == null) {
			KeydroidxLog.w(TAG, "ActivityManager 不可用");
			return out;
		}
		List<ActivityManager.RecentTaskInfo> recents =
				am.getRecentTasks(MAX_TASKS * 2, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
		if (recents == null) {
			KeydroidxLog.w(TAG, "getRecentTasks 返回 null");
			return out;
		}
		for (ActivityManager.RecentTaskInfo info : recents) {
			if (info == null || info.baseIntent == null) continue;
			// 显式排除「不进入最近任务」的应用
			if ((info.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0) continue;
			ComponentName cn = info.baseIntent.getComponent();
			if (cn == null || cn.getPackageName() == null) continue;
			out.add(new Cand(cn.getPackageName(), info.id, 0));
		}
		KeydroidxLog.i(TAG, "getRecentTasks 候选: " + out.size());
		return out;
	}

	// ---- 通道 2：API 21+ mini_shizuku dumpsys ----

	/**
	 * Android 5.0+ 特权路径：shell 身份执行 {@code dumpsys activity recents} 解析真实任务栈。
	 * 输出格式随 Android 版本/ROM 差异较大，解析失败的兜底由 {@link #enumerate(Context)} 负责。
	 */
	private static List<Cand> collectViaDumpsys() {
		List<Cand> out = new ArrayList<>();
		String dump = null;
		try {
			dump = Shizuku.execWithOutput("dumpsys activity recents");
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "dumpsys activity recents 执行失败: " + e.getMessage());
		}
		if (dump == null || dump.trim().isEmpty()) {
			// 部分 ROM 的 recents 子命令无输出，退回 activities 全量 dump
			try {
				dump = Shizuku.execWithOutput("dumpsys activity activities");
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "dumpsys activity activities 执行失败: " + e.getMessage());
			}
		}
		if (dump == null || dump.isEmpty()) {
			KeydroidxLog.w(TAG, "dumpsys 无输出（mini_shizuku 服务异常？）");
			return out;
		}
		out.addAll(parseDumpsys(dump));
		KeydroidxLog.i(TAG, "dumpsys 解析候选: " + out.size());
		return out;
	}

	/**
	 * 解析 dumpsys 任务块。按 {@code Task{...}} 切块，块内优先取 {@code A=<uid>:<pkg>}，
	 * 其次取 {@code cmp=} / {@code realActivity=} 的组件包名，并尽力读取 lastActiveTime。
	 */
	private static List<Cand> parseDumpsys(String dump) {
		List<Cand> out = new ArrayList<>();
		String curPkg = null;
		int curId = -1;
		long curRaw = 0;
		boolean inBlock = false;

		for (String raw : dump.split("\n")) {
			if (raw == null) continue;
			String line = raw.trim();
			if (line.isEmpty()) continue;

			Matcher taskM = P_TASK_BLOCK.matcher(line);
			if (taskM.find()) {
				// 上一个块入库
				if (inBlock) addCand(out, curPkg, curId, curRaw);
				inBlock = true;
				curPkg = null;
				curRaw = 0;
				curId = -1;

				String inner = taskM.group(1);
				Matcher idM = P_HASH_ID.matcher(inner);
				if (idM.find()) {
					try {
						curId = Integer.parseInt(idM.group(1));
					} catch (NumberFormatException e) {
						KeydroidxLog.w(TAG, "解析 taskId 失败: " + e.getMessage());
					}
				}
				Matcher pkgM = P_PKG_A.matcher(inner);
				if (pkgM.find()) curPkg = pkgM.group(1);
				continue;
			}
			if (!inBlock) continue;

			if (curPkg == null && (line.contains("cmp=") || line.contains("realActivity="))) {
				Matcher cm = P_PKG_COMPONENT.matcher(line);
				if (cm.find()) curPkg = cm.group(1);
			}
			if (curRaw == 0) {
				Matcher tm = P_TIME.matcher(line);
				if (tm.find()) {
					try {
						curRaw = Long.parseLong(tm.group(1));
					} catch (NumberFormatException e) {
						KeydroidxLog.w(TAG, "解析 lastActiveTime 失败: " + e.getMessage());
					}
				}
			}
		}
		if (inBlock) addCand(out, curPkg, curId, curRaw);
		return out;
	}

	private static void addCand(List<Cand> out, String pkg, int taskId, long rawTime) {
		if (pkg == null || pkg.isEmpty()) return;
		out.add(new Cand(pkg, taskId, normalizeDumpsysTime(rawTime)));
	}

	/**
	 * 归一化 dumpsys 时间戳：{@code lastActiveTime} 多数版本取 {@link SystemClock#uptimeMillis()} 时间基，
	 * 少数版本给墙上时钟。按「落在哪个时间基区间内」自动判定；两者都不成立时按未知（0）处理。
	 */
	private static long normalizeDumpsysTime(long raw) {
		if (raw <= 0) return 0;
		long uptime = SystemClock.uptimeMillis();
		if (raw <= uptime) return uptime - raw;             // uptime 时间基
		long now = System.currentTimeMillis();
		if (raw <= now) return now - raw;                    // 墙上时钟时间基
		return 0;
	}

	// ============================================================
	// 候选 → 可渲染条目（去重 / 过滤 / 名称图标）
	// ============================================================

	/**
	 * 统一收口：按包名去重（保留最近一次）、排除桌面自身、过滤无可启动入口的应用，
	 * 并（可选）加载名称与图标。
	 *
	 * <p><b>「最近任务」的口径（重要）：</b>只认系统 recents 里的任务记录，即
	 * <b>用户确实打开过</b>的东西。因此：</p>
	 * <ul>
	 *   <li><b>不</b>排除系统应用——用户打开过的「设置」本来就该出现在最近任务里
	 *       （此前排除它属于"后台管理"思路，与最近任务语义冲突）；</li>
	 *   <li>进程已死但任务记录还在的条目<b>照样显示</b>：那是系统内存回收，用户确实进入过、
	 *       也没人清理它，这正是「最近任务」的固有形态；</li>
	 *   <li>唯一主动隐去的是<b>本应用刚清理掉的</b>应用（见下方 {@code wasCleared} 说明）。</li>
	 * </ul>
	 *
	 * @param mode   {@link #getMode(Context)} 的结果；真实任务模式下才做存活校验
	 * @param loadUi true 加载应用名与图标（页面渲染用）；false 仅做过滤与计数
	 */
	private static void buildTasks(Context ctx, List<Cand> cands, List<RecentTask> out,
			int mode, boolean loadUi) {
		if (cands.isEmpty()) return;
		String self = ctx.getPackageName();
		PackageManager pm = ctx.getPackageManager();
		if (pm == null) {
			KeydroidxLog.w(TAG, "PackageManager 不可用");
			return;
		}

		// LinkedHashMap 保序：首个出现的即为最近的一次
		Map<String, Cand> unique = new LinkedHashMap<>();
		for (Cand c : cands) {
			if (c == null || c.pkg == null || c.pkg.isEmpty()) continue;
			if (c.pkg.equals(self)) continue;
			if (!unique.containsKey(c.pkg)) unique.put(c.pkg, c);
		}

		// 存活判据只有一个用途：识别「本应用刚清理掉的」条目。
		// 清理只杀进程、任务记录不会消失（实测 4.4 与 Android 13 均如此；13 需要
		// am stack remove 才能真删），所以清完必须靠 wasCleared 把卡片隐去，否则
		// 用户会觉得"清了等于没清"。该标记只在内存里（进程重启即失效）：
		// 重启后这些条目会以"点进去需冷启动"的形态回来，属于无删栈能力时的已知取舍。
		Set<String> alive = mode == MODE_REAL_TASK
				? KeydroidxBgManagerHelper.getAlivePackages(ctx) : null;

		for (Map.Entry<String, Cand> e : unique.entrySet()) {
			String pkg = e.getKey();
			Cand c = e.getValue();
			if (alive != null) {
				if (!alive.contains(pkg)) {
					// 进程已死：只有「本应用清的」才隐去；系统回收的照样显示
					if (KeydroidxBgManagerHelper.wasCleared(pkg)) {
						KeydroidxLog.d(TAG, "跳过已清理任务: " + pkg);
						continue;
					}
				} else if (KeydroidxBgManagerHelper.wasCleared(pkg)) {
					// 进程还在 = 用户又从图标把它打开了，撤销「已清理」标记
					KeydroidxBgManagerHelper.unmarkCleared(pkg);
					KeydroidxLog.i(TAG, "已清理包检测到重新启动，撤销标记: " + pkg);
				}
			} else if (KeydroidxBgManagerHelper.wasCleared(pkg)) {
				// 无法确认存活时沿用旧标记过滤，避免刚清掉的应用立刻"复活"成空壳卡
				KeydroidxLog.d(TAG, "跳过已清理包(存活未知): " + pkg);
				continue;
			}
			try {
				// 无可启动入口的应用（系统服务、Provider 进程等）不是"任务"
				Intent launch = pm.getLaunchIntentForPackage(pkg);
				if (launch == null) continue;
				ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
				String name = pkg;
				Drawable icon = null;
				if (loadUi) {
					CharSequence label = pm.getApplicationLabel(ai);
					name = label != null ? label.toString() : pkg;
					try {
						// 图标包优先（单应用覆盖 → 全局图标包），未命中再用应用自身图标
						icon = KeydroidxIconResolver.resolvePackIcon(ctx, pkg, null, name);
						if (icon == null) icon = pm.getApplicationIcon(ai);
					} catch (Exception ie) {
						KeydroidxLog.w(TAG, "加载图标失败 " + pkg + ": " + ie.getMessage());
					}
				}
				out.add(new RecentTask(pkg, name, pkg, c.taskId, c.agoMs, icon));
			} catch (PackageManager.NameNotFoundException nfe) {
				KeydroidxLog.w(TAG, "包不可用，跳过: " + pkg);
			}
			if (out.size() >= MAX_TASKS) break;
		}
	}

	/**
	 * 构造挂机 jar 条目（读跨进程状态文件 + 校验 :midlet 进程存活）。
	 *
	 * @param loadUi false 时不加载图标（桌面组件仅计数，省一次图片解码）
	 */
	private static RecentTask buildMidletTask(Context ctx, boolean loadUi) {
		try {
			MidletStateStore.RunningInfo running = MidletStateStore.getRunning(ctx);
			if (running == null) return null;
			String key = MidletStateStore.taskKey(running.appPath);
			Drawable icon = loadUi ? KeydroidxBgManagerHelper.loadMidletIcon(ctx, running.appPath) : null;
			// 挂机中 = 正在前台/后台运行，视为「刚刚」
			return new RecentTask(key, running.appName, key, -1, 1000L, icon);
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "构造挂机 jar 条目失败: " + e.getMessage());
			return null;
		}
	}

	// ============================================================
	// 恢复前台（不重启）
	// ============================================================

	/**
	 * 把目标任务恢复到前台（<b>恢复而非重新启动</b>）。
	 *
	 * <p>实现方式：以该应用的启动 Intent + {@code FLAG_ACTIVITY_NEW_TASK} 下发，
	 * 系统按 taskAffinity 找到已存在的任务栈并把它拉到前台，栈顶 Activity 只走
	 * {@code onResume}（不重建）→ 用户回到该应用之前停留的界面；任务确实已被系统
	 * 回收时才会冷启动——这与 Android 系统桌面点击应用图标的行为一致。
	 * <p>刻意<strong>不</strong>使用 {@code ActivityManager.moveTaskToFront}：
	 * 它在 Android 10+ 对第三方应用被静默限制（既可能无异常也不产生位移），
	 * 无法据此判断失败并降级，反而会吞掉本可成功的恢复动作。
	 *
	 * @param ctx  需要是 Activity 上下文（挂机 jar 的启动入口要求 Activity）
	 * @return 是否成功下发
	 */
	public static boolean bringToFront(Context ctx, RecentTask task) {
		if (ctx == null || task == null) return false;

		// 挂机 jar：复用既有启动入口（MicroActivity 为 singleTask，同 jar 直接回前台续跑）
		if (task.isMidlet()) {
			String appPath = task.taskKey.substring(MIDLET_KEY_PREFIX.length());
			KeydroidxLog.i(TAG, "恢复挂机 jar: " + task.name);
			try {
				Config.startApp(ctx, task.name, appPath, false);
				return true;
			} catch (Exception e) {
				KeydroidxLog.e(TAG, "恢复挂机 jar 失败: " + task.name, e);
				return false;
			}
		}

		try {
			Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(task.pkg);
			if (launch == null) {
				KeydroidxLog.w(TAG, "无启动入口，无法恢复: " + task.pkg);
				return false;
			}
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			ctx.startActivity(launch);
			KeydroidxLog.i(TAG, "恢复应用: " + task.name + " (" + task.pkg
					+ ", taskId=" + task.taskId + ")");
			return true;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "恢复应用失败 " + task.pkg + ": " + e.getMessage());
			return false;
		}
	}
}
