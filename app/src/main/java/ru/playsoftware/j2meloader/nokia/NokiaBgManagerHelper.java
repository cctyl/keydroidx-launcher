package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台管理工具类。
 * 负责：枚举后台进程（非前台/非可见）、统计后台数量、清理后台进程（跳过保护名单）。
 * <p>
 * 关键 API：{@link ActivityManager#getRunningAppProcesses()}（枚举）与
 * {@link ActivityManager#killBackgroundProcesses(String)}（清理）。
 * killBackgroundProcesses 无需任何权限，普通应用即可调用——这就是手机管家
 * 「一键加速」的底层原理，本 Launcher 无需 root 也无需系统签名。
 */
public final class NokiaBgManagerHelper {

	private NokiaBgManagerHelper() {}

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

	/**
	 * 该进程是否算「后台进程」。
	 * IMPORTANCE_VISIBLE=200（对用户可见，如画中画/分屏可见），
	 * 大于它的（SERVICE=300 / BACKGROUND=400 / CACHED=400）均为后台，可清理。
	 */
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

	/** 统计当前后台进程数（按包名去重，排除桌面自身）。供桌面组件行实时显示。 */
	public static int countBackgroundProcesses(Context ctx) {
		Set<String> seen = new HashSet<>();
		try {
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return 0;
			List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
			if (procs == null) return 0;
			for (ActivityManager.RunningAppProcessInfo p : procs) {
				if (!isBackgroundProcess(p)) continue;
				if (p.pkgList == null || p.pkgList.length == 0) continue;
				if (isSelfProcess(ctx, p.processName, p.pkgList[0])) continue;
				seen.add(p.pkgList[0]);
			}
		} catch (Exception e) {
			NokiaLog.e("BgManager", "countBackgroundProcesses 失败", e);
		}
		return seen.size();
	}

	/**
	 * 枚举后台任务（含图标与保护状态），按名称排序。
	 * 可在后台线程调用（内部有 PackageManager 查询）。已卸载的残留进程自动跳过。
	 *
	 * @param protectedSet 保护名单（包名集合）；可为 null
	 */
	public static List<BgTask> enumerateBackgroundTasks(Context ctx, Set<String> protectedSet) {
		List<BgTask> out = new ArrayList<>();
		try {
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			PackageManager pm = ctx.getPackageManager();
			if (am == null || pm == null) return out;
			List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
			if (procs == null) return out;

			Set<String> seen = new HashSet<>();
			for (ActivityManager.RunningAppProcessInfo p : procs) {
				if (!isBackgroundProcess(p)) continue;
				if (p.pkgList == null || p.pkgList.length == 0) continue;
				String pkg = p.pkgList[0];
				if (isSelfProcess(ctx, p.processName, pkg)) continue;
				if (seen.contains(pkg)) continue;
				seen.add(pkg);
				try {
					ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
					String name = pm.getApplicationLabel(ai) != null
							? pm.getApplicationLabel(ai).toString() : pkg;
					Drawable icon = null;
					try {
						icon = pm.getApplicationIcon(ai);
					} catch (Exception ignored) {}
					boolean prot = protectedSet != null && protectedSet.contains(pkg);
					out.add(new BgTask(pkg, name, icon, prot));
				} catch (PackageManager.NameNotFoundException e) {
					// 已卸载的残留进程，跳过
				}
			}

			Collections.sort(out, new Comparator<BgTask>() {
				@Override
				public int compare(BgTask a, BgTask b) {
					return a.name.compareToIgnoreCase(b.name);
				}
			});
		} catch (Exception e) {
			NokiaLog.e("BgManager", "enumerateBackgroundTasks 失败", e);
		}
		return out;
	}

	/**
	 * 清理所有未保护的后台进程（跳过保护名单），返回实际清理数量。
	 * killBackgroundProcesses 对系统应用通常无效（系统自动忽略或立即重启），属正常现象。
	 */
	public static int clearBackgroundTasks(Context ctx, Set<String> protectedSet) {
		int cleared = 0;
		try {
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return 0;
			List<BgTask> tasks = enumerateBackgroundTasks(ctx, protectedSet);
			for (BgTask t : tasks) {
				if (t.prot) continue;
				try {
					am.killBackgroundProcesses(t.pkg);
					cleared++;
					NokiaLog.i("BgManager", "已清理后台: " + t.name + " (" + t.pkg + ")");
				} catch (Exception e) {
					NokiaLog.w("BgManager", "清理失败: " + t.pkg + " -> " + e.getMessage());
				}
			}
		} catch (Exception e) {
			NokiaLog.e("BgManager", "clearBackgroundTasks 失败", e);
		}
		return cleared;
	}
}
