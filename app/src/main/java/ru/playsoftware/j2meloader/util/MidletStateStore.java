package ru.playsoftware.j2meloader.util;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/**
 * MIDlet 挂机状态跨进程存储。
 * <p>
 * :midlet 进程启动/销毁 MIDlet 时写入/清除状态文件（properties，tmp+rename 原子写）；
 * 主进程（桌面/后台管理）通过 {@link #getRunning(Context)} 读取，并校验
 * {@code 包名:midlet} 进程真实存活（对自身 uid 的进程全版本可枚举，API 21 收紧不影响），
 * 进程已死时自动清理残留文件并返回 null。
 * <p>
 * 不用 SharedPreferences 跨进程：SP 在进程存活期间有内存缓存，读不到对方的最新写入。
 */
public final class MidletStateStore {

	private static final String TAG = "MidletStateStore";
	private static final String FILE_NAME = "midlet_state.properties";
	private static final String KEY_APP_PATH = "appPath";
	private static final String KEY_APP_NAME = "appName";
	private static final String KEY_PID = "pid";

	private MidletStateStore() {}

	/** 挂机中的 jar 信息（appPath + 显示名）。 */
	public static class RunningInfo {
		public final String appPath;
		public final String appName;

		RunningInfo(String appPath, String appName) {
			this.appPath = appPath;
			this.appName = appName;
		}
	}

	private static File stateFile(Context context) {
		return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
	}

	/** :midlet 进程写入当前运行的 MIDlet（原子写：tmp + rename）。 */
	public static void write(Context context, String appPath, String appName) {
		Properties props = new Properties();
		props.setProperty(KEY_APP_PATH, appPath == null ? "" : appPath);
		props.setProperty(KEY_APP_NAME, appName == null ? "" : appName);
		props.setProperty(KEY_PID, String.valueOf(android.os.Process.myPid()));
		File file = stateFile(context);
		File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
		try (FileOutputStream fos = new FileOutputStream(tmp)) {
			props.store(fos, "midlet running state");
			if (!tmp.renameTo(file)) {
				// rename 失败（部分文件系统）：删除旧文件后重试一次
				//noinspection ResultOfMethodCallIgnored
				file.delete();
				//noinspection ResultOfMethodCallIgnored
				tmp.renameTo(file);
			}
			Log.i(TAG, "write: " + appName + " pid=" + props.getProperty(KEY_PID));
		} catch (IOException e) {
			Log.e(TAG, "write failed", e);
		}
	}

	/** :midlet 进程清除状态（notifyDestroyed 内、killProcess 之前调用）。 */
	public static void clear(Context context) {
		File file = stateFile(context);
		if (file.exists() && !file.delete()) {
			Log.w(TAG, "clear: delete failed");
		}
		Log.i(TAG, "clear");
	}

	/**
	 * 主进程唯一读取入口：读状态文件 + 校验 {@code 包名:midlet} 进程存活且 pid 一致。
	 * 进程不存在 → 删除残留文件并返回 null。
	 */
	public static RunningInfo getRunning(Context context) {
		context = context.getApplicationContext();
		File file = stateFile(context);
		if (!file.exists()) return null;
		Properties props = new Properties();
		try (FileInputStream fis = new FileInputStream(file)) {
			props.load(fis);
		} catch (IOException e) {
			Log.e(TAG, "getRunning: read failed", e);
			return null;
		}
		String appPath = props.getProperty(KEY_APP_PATH, "");
		String appName = props.getProperty(KEY_APP_NAME, "");
		int pid;
		try {
			pid = Integer.parseInt(props.getProperty(KEY_PID, "-1"));
		} catch (NumberFormatException e) {
			pid = -1;
		}
		if (appPath.isEmpty()) return null;
		if (!isMidletProcessAlive(context, pid)) {
			// LMK 杀进程 / 崩溃残留：清掉脏文件
			Log.w(TAG, "getRunning: :midlet 进程已死，清理残留状态 (app=" + appName + ")");
			//noinspection ResultOfMethodCallIgnored
			file.delete();
			return null;
		}
		return new RunningInfo(appPath, appName);
	}

	/**
	 * 校验状态文件记录的 pid 仍存活，且属于本应用的任意自有进程（主进程或 :midlet）。
	 * pid 精确匹配防止 pid 复用误判；不限定进程名后缀，防御各 flavor 进程声明差异。
	 */
	private static boolean isMidletProcessAlive(Context context, int pid) {
		if (pid <= 0) return false;
		String pkg = context.getPackageName();
		ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
		if (am == null) return false;
		List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
		if (procs == null) return false;
		for (ActivityManager.RunningAppProcessInfo p : procs) {
			if (p.pid == pid && p.processName != null && p.processName.startsWith(pkg)) {
				return true;
			}
		}
		return false;
	}

	/** 后台管理条目 key：「midlet:&lt;appPath&gt;」，与包名空间天然隔离。 */
	public static String taskKey(String appPath) {
		return "midlet:" + appPath;
	}

	/** 判断一个后台管理条目 key 是否为挂机 jar 条目。 */
	public static boolean isMidletTaskKey(String key) {
		return key != null && key.startsWith("midlet:");
	}
}
