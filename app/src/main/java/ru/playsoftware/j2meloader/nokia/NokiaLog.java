package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诺基亚桌面统一日志工具。
 * <p>
 * 所有 nokia 包内的调试输出都走这里，统一 TAG 与格式（[子类] 消息），
 * 并可通过 {@link #setEnabled(boolean)} 全局开关控制 logcat 输出。
 * <p>
 * 文件日志：{@link #init(Context)} 后，所有日志按天写入
 * {@code /sdcard/Android/data/<package>/log/yyyyMMdd.log}（异步写，不阻塞 UI 线程），
 * 便于排查「已停止运行」等崩溃问题；崩溃堆栈通过 {@link #fileCrash(String, Throwable)}
 * 同步落盘。旧日志默认保留 {@link #KEEP_DAYS} 天，初始化时自动清理。
 */
public final class NokiaLog {

	private static final String TAG = "NokiaDesktop";
	private static volatile boolean enabled = true;

	// ---- 文件日志 ----
	private static final int KEEP_DAYS = 7;
	private static final Object FILE_LOCK = new Object();
	private static volatile boolean fileLogEnabled = false;
	/** 文件日志最低级别（Android Log 级别）。低于该级别的日志不落盘；默认 DEBUG 全记录。 */
	private static volatile int fileMinLevel = Log.DEBUG;
	private static File logDir;
	private static HandlerThread fileThread;
	private static Handler fileHandler;
	private static String curDateKey = "";
	private static FileWriter curWriter;
	private static final SimpleDateFormat DATE_KEY = new SimpleDateFormat("yyyyMMdd", Locale.US);
	private static final SimpleDateFormat LINE_TS = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

	private NokiaLog() {
	}

	/** 全局开关。默认开启（调试）。 */
	public static void setEnabled(boolean e) {
		enabled = e;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/**
	 * 初始化文件日志（应在 Application 早期、主进程调用一次，幂等）。
	 * 日志目录：/sdcard/Android/data/&lt;package&gt;/log，按天 yyyyMMdd.log。
	 */
	public static synchronized void init(Context context) {
		try {
			if (fileLogEnabled || context == null) return;
			File external = context.getExternalFilesDir(null);
			if (external == null) {
				Log.w(TAG, "外部存储不可用，跳过文件日志初始化");
				return;
			}
			File dir = new File(external.getParentFile(), "log");
			if (!dir.exists() && !dir.mkdirs()) {
				Log.w(TAG, "日志目录创建失败: " + dir.getAbsolutePath());
				return;
			}
			logDir = dir;
			cleanOldLogs();
			fileThread = new HandlerThread("NokiaLogFile");
			fileThread.start();
			fileHandler = new Handler(fileThread.getLooper());
			fileLogEnabled = true;
			// 日志记录开关：开启=详细（全级别），关闭=仅 ERROR 及以上
			fileMinLevel = NokiaSettingsStorage.isFileLogEnabled(context)
					? Log.DEBUG : Log.ERROR;
			Log.i(TAG, "文件日志已启用: " + logDir.getAbsolutePath()
					+ " minLevel=" + fileMinLevel);
			appendAsync(Log.INFO, "SYS", "===== 日志记录启动 ===== 保留最近 " + KEEP_DAYS + " 天");
		} catch (Exception e) {
			Log.w(TAG, "NokiaLog.init 失败", e);
		}
	}

	/** 当日日志目录（/sdcard/Android/data/&lt;package&gt;/log），未初始化时返回 null。 */
	public static File getLogDir() {
		return logDir;
	}

	/**
	 * 设置文件日志最低级别（Android {@link Log} 级别常量）。
	 * 桌面设置「日志记录」切换时调用，实时生效：
	 * 开启=DEBUG（全级别），关闭=ERROR（及以上）。
	 */
	public static void setFileMinLevel(int level) {
		fileMinLevel = level;
		Log.i(TAG, "setFileMinLevel: " + level);
	}

	public static void d(String sub, String msg) {
		if (enabled) Log.d(TAG, "[" + sub + "] " + msg);
		appendAsync(Log.DEBUG, sub, msg);
	}

	public static void i(String sub, String msg) {
		if (enabled) Log.i(TAG, "[" + sub + "] " + msg);
		appendAsync(Log.INFO, sub, msg);
	}

	public static void w(String sub, String msg) {
		if (enabled) Log.w(TAG, "[" + sub + "] " + msg);
		appendAsync(Log.WARN, sub, msg);
	}

	public static void e(String sub, String msg) {
		if (enabled) Log.e(TAG, "[" + sub + "] " + msg);
		appendAsync(Log.ERROR, sub, msg);
	}

	public static void e(String sub, String msg, Throwable t) {
		if (enabled) Log.e(TAG, "[" + sub + "] " + msg, t);
		if (!fileLogEnabled || fileHandler == null || Log.ERROR < fileMinLevel) return;
		final StringBuilder sb = new StringBuilder();
		sb.append('[').append(LINE_TS.format(new Date())).append("][").append(sub).append("] ")
				.append(msg).append('\n');
		if (t != null) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			sb.append(sw);
		}
		final String line = sb.toString();
		fileHandler.post(() -> writeLine(line));
	}

	/**
	 * 崩溃堆栈同步写入当日日志（进程即将终止，必须同步落盘后再交给系统处理）。
	 * 不受日志记录开关影响：崩溃（FATAL）始终记录。
	 */
	public static void fileCrash(String msg, Throwable t) {
		if (!fileLogEnabled || logDir == null) return;
		synchronized (FILE_LOCK) {
			try {
				FileWriter w = openDailyWriter();
				if (w == null) return;
				w.write("[" + LINE_TS.format(new Date()) + "][FATAL] " + msg + "\n");
				if (t != null) {
					StringWriter sw = new StringWriter();
					t.printStackTrace(new PrintWriter(sw));
					w.write(sw.toString() + "\n");
				}
				w.flush();
				closeWriter();
			} catch (Exception ignored) {
				// 日志写入失败静默，避免日志自身引发崩溃
			}
		}
	}

	// ---- 文件写入 ----

	private static void appendAsync(int level, String sub, String msg) {
		if (!fileLogEnabled || fileHandler == null) return;
		if (level < fileMinLevel) return;
		final String line = "[" + LINE_TS.format(new Date()) + "][" + sub + "] " + msg + "\n";
		fileHandler.post(() -> writeLine(line));
	}

	private static void writeLine(String line) {
		synchronized (FILE_LOCK) {
			try {
				FileWriter w = openDailyWriter();
				if (w == null) return;
				w.write(line);
				w.flush();
			} catch (Exception ignored) {
			}
		}
	}

	/** 按日期切换当日日志文件（调用方需持有 FILE_LOCK）。 */
	private static FileWriter openDailyWriter() throws IOException {
		String key = DATE_KEY.format(new Date());
		if (!key.equals(curDateKey) || curWriter == null) {
			closeWriter();
			curDateKey = key;
			File f = new File(logDir, key + ".log");
			curWriter = new FileWriter(f, true);
		}
		return curWriter;
	}

	private static void closeWriter() {
		if (curWriter != null) {
			try {
				curWriter.close();
			} catch (IOException ignored) {
			}
			curWriter = null;
		}
	}

	/** 删除超过保留天数的历史日志文件。 */
	private static void cleanOldLogs() {
		try {
			File[] files = logDir.listFiles((dir, name) -> name.endsWith(".log"));
			if (files == null) return;
			long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000;
			for (File f : files) {
				if (f.lastModified() < cutoff) {
					f.delete();
				}
			}
		} catch (Exception ignored) {
		}
	}

	/** 把 keyCode 转成面向用户的中文键名（日志与 UI 通用）。 */
	public static String keyName(int keyCode) {
		switch (keyCode) {
			case KeyEvent.KEYCODE_DPAD_UP:      return "上";
			case KeyEvent.KEYCODE_DPAD_DOWN:    return "下";
			case KeyEvent.KEYCODE_DPAD_LEFT:    return "左";
			case KeyEvent.KEYCODE_DPAD_RIGHT:   return "右";
			case KeyEvent.KEYCODE_DPAD_CENTER:  return "确认";
			case KeyEvent.KEYCODE_ENTER:        return "确定";
			case KeyEvent.KEYCODE_SPACE:        return "空格";
			case KeyEvent.KEYCODE_BUTTON_A:     return "A";
			case KeyEvent.KEYCODE_SOFT_LEFT:    return "左软键";
			case KeyEvent.KEYCODE_SOFT_RIGHT:   return "右软键";
			case KeyEvent.KEYCODE_MENU:         return "菜单";
			case KeyEvent.KEYCODE_BACK:         return "返回";
			case KeyEvent.KEYCODE_ENDCALL:      return "挂机";
			case KeyEvent.KEYCODE_CALL:         return "通话";
			case KeyEvent.KEYCODE_CAMERA:       return "相机";
			case KeyEvent.KEYCODE_VOLUME_UP:    return "音量加";
			case KeyEvent.KEYCODE_VOLUME_DOWN:  return "音量减";
			case KeyEvent.KEYCODE_POWER:        return "电源";
			case KeyEvent.KEYCODE_HOME:         return "Home";
			case KeyEvent.KEYCODE_STAR:         return "*号";
			case KeyEvent.KEYCODE_POUND:        return "井号";
			case KeyEvent.KEYCODE_DEL:          return "删除";
			case KeyEvent.KEYCODE_CLEAR:        return "清除";
			case KeyEvent.KEYCODE_0:            return "0";
			case KeyEvent.KEYCODE_1:            return "1";
			case KeyEvent.KEYCODE_2:            return "2";
			case KeyEvent.KEYCODE_3:            return "3";
			case KeyEvent.KEYCODE_4:            return "4";
			case KeyEvent.KEYCODE_5:            return "5";
			case KeyEvent.KEYCODE_6:            return "6";
			case KeyEvent.KEYCODE_7:            return "7";
			case KeyEvent.KEYCODE_8:            return "8";
			case KeyEvent.KEYCODE_9:            return "9";
			case KeyEvent.KEYCODE_UNKNOWN:      return "未绑定";
			default:
				if (Build.VERSION.SDK_INT >= 29) {
					return KeyEvent.keyCodeToString(keyCode);
				}
				return "KEYCODE_" + keyCode;
		}
	}
}
