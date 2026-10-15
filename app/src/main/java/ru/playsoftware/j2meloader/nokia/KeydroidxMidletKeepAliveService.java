package ru.playsoftware.j2meloader.nokia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.MidletStateStore;

/**
 * jar 挂机保活前台 Service（运行在 :midlet 进程）。
 * <p>
 * MicroActivity.onStop 时启动（挂机），onResume 时停止。常驻通知：
 * 标题=jar 名称，点击通知经显式 Intent 携带 appPath/appName/键码表回到
 * MicroActivity 续跑（singleTask 复用/重建重挂）。
 * <p>
 * 僵尸防御：系统 START_STICKY 重启拉起时进程内已无 MIDlet 实例，
 * 直接 stopSelf，避免无意义常驻通知。
 * <p>
 * <b>FGS 义务前置（线上实测崩溃）</b>：startForegroundService 被调用后，系统看门狗
 * 要求在超时窗口内（实测约 10s）调到 startForeground，否则抛
 * {@code ForegroundServiceDidNotStartInTimeException} 杀死本进程、连带杀死挂机中的
 * MIDlet（Android 16 / OPPO 线上复现）。因此 onStartCommand 的<b>第一行</b>就先
 * startForeground 一个构建成本近零的极简通知履行义务，磁盘 I/O（状态文件、
 * SharedPreferences）与正式通知（含 PendingIntent）构建全部后移，完成后用
 * {@link NotificationManager#notify} 原地替换，用户无感知。
 */
public class KeydroidxMidletKeepAliveService extends Service {

	private static final String TAG = "MidletKeepAlive";
	private static final String CHANNEL_ID = "midlet_keepalive";
	private static final int NOTIFICATION_ID = 1;

	private PowerManager.WakeLock wakeLock;
	private WifiManager.WifiLock wifiLock;

	@Override
	public void onCreate() {
		super.onCreate();
		acquireLocks();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
					"JAR 后台运行", NotificationManager.IMPORTANCE_LOW);
			channel.setDescription("jar 应用挂机保活通知");
			NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			if (nm != null) {
				nm.createNotificationChannel(channel);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// FGS 义务前置：必须先于一切业务工作（hasInstance 判断、磁盘 I/O、
		// SharedPreferences 读取、PendingIntent/正式通知构建）挂出极简通知，
		// 否则主线程繁忙/进程被冻结时超出看门狗窗口，进程被杀（线上实测崩溃）。
		if (!startForegroundSafe(buildBareNotification())) {
			// 极端 ROM 限制下极简通知也起不来：无法履行 FGS 义务，只能停服降级。
			// 此时看门狗仍可能抛超时异常，但该场景概率极低，与修复前持平。
			stopSelf();
			return START_NOT_STICKY;
		}
		if (!MidletThread.hasInstance()) {
			// 僵尸防御（系统重启拉起/竞态残留）：进程内已无 MIDlet。
			// FGS 义务已在方法开头履行，这里直接撤除并停服即可。
			stopForeground(true);
			stopSelf();
			return START_NOT_STICKY;
		}
		String appName = intent != null ? intent.getStringExtra(Constants.KEY_MIDLET_NAME) : null;
		String appPath = intent != null && intent.getData() != null ? intent.getData().toString() : null;
		int[] keyCodes = intent != null ? intent.getIntArrayExtra(Constants.KEY_KEYCODES) : null;
		if (appName == null || appPath == null) {
			// START_STICKY 重启 intent 为 null：从状态文件兜底（磁盘 I/O，
			// 已在 startForeground 之后，不再占用 FGS 超时窗口）
			MidletStateStore.RunningInfo r = MidletStateStore.getRunning(this);
			if (r != null) {
				if (appName == null) appName = r.appName;
				if (appPath == null) appPath = r.appPath;
			}
		}
		if (keyCodes == null) {
			keyCodes = KeydroidxKeyBinding.loadKeyCodes(this);
		}
		// 正式通知原地替换（带点击回跳 PendingIntent）。FGS 义务已履行，
		// 即使替换失败也仅通知停留在极简版，绝不影响挂机。
		updateNotification(buildNotification(appName, appPath, keyCodes));
		// NOT_STICKY：进程死=挂机已失效，禁止系统重启空进程制造崩溃循环
		return START_NOT_STICKY;
	}

	/**
	 * 安全版 startForeground：失败返回 false（调用方降级 stopSelf），绝不抛异常——
	 * 本服务运行在 :midlet 进程，任何崩溃都会连带杀死挂机中的 MIDlet（实测 bug）。
	 * <p>
	 * 失败属预期内降级（权限被剥离/ROM 限制等环境因素），按规范记 {@code w} 而非
	 * {@code e}：不落盘、不占用崩溃上报每日配额。
	 */
	private boolean startForegroundSafe(Notification notification) {
		try {
			startForeground(NOTIFICATION_ID, notification);
			return true;
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "startForeground 失败，保活降级（挂机不受影响）: " + t);
			return false;
		}
	}

	/**
	 * 极简通知：仅小图标 + 通用文案，不含 PendingIntent 与任何 extras，构建成本近零。
	 * 专用于 onStartCommand 第一时间履行 startForeground 义务；
	 * 业务信息随后由 {@link #updateNotification} 用正式通知原地替换。
	 */
	private Notification buildBareNotification() {
		Notification.Builder builder;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			builder = new Notification.Builder(this, CHANNEL_ID);
		} else {
			builder = new Notification.Builder(this);
		}
		return builder
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle("J2ME")
				.setContentText("正在后台运行")
				.setOngoing(true)
				.build();
	}

	/**
	 * 原地更新为正式通知（带点击回跳）。仅 {@code notify}，不再 startForeground：
	 * FGS 义务已在 onStartCommand 开头履行，这里失败仅意味着通知停留在极简版，
	 * 绝不抛异常、绝不影响挂机。
	 */
	private void updateNotification(Notification notification) {
		try {
			NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			if (nm != null) {
				nm.notify(NOTIFICATION_ID, notification);
			} else {
				KeydroidxLog.w(TAG, "NotificationManager 不可用，通知停留在极简版（挂机不受影响）");
			}
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "更新正式通知失败（挂机不受影响）: " + t);
		}
	}

	private Notification buildNotification(String appName, String appPath, int[] keyCodes) {
		PendingIntent pi = null;
		if (appPath != null && appName != null) {
			Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(appPath),
					this, MicroActivity.class);
			intent.putExtra(Constants.KEY_MIDLET_NAME, appName);
			intent.putExtra(Constants.KEY_KEYCODES, keyCodes);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
					| Intent.FLAG_ACTIVITY_SINGLE_TOP);
			int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				piFlags |= PendingIntent.FLAG_IMMUTABLE;
			}
			pi = PendingIntent.getActivity(this, 0, intent, piFlags);
		}

		Notification.Builder builder;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			builder = new Notification.Builder(this, CHANNEL_ID);
		} else {
			builder = new Notification.Builder(this);
		}
		builder
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle(appName == null ? "J2ME" : appName)
				.setContentText("正在后台运行");
		if (pi != null) {
			builder.setContentIntent(pi);
		}
		return builder.setOngoing(true).build();
	}

	@Override
	public void onDestroy() {
		releaseLocks();
		stopForeground(true);
		super.onDestroy();
	}

	private void acquireLocks() {
		try {
			PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
			if (pm != null && wakeLock == null) {
				wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nokia:midlet_keepalive");
				wakeLock.setReferenceCounted(false);
				wakeLock.acquire();
				KeydroidxLog.i(TAG, "已获取 PartialWakeLock，防止 CPU 在挂机时休眠断网");
			}
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "获取 WakeLock 失败: " + t);
		}

		try {
			WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
			if (wm != null && wifiLock == null) {
				int lockType = WifiManager.WIFI_MODE_FULL;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
					lockType = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
				}
				wifiLock = wm.createWifiLock(lockType, "nokia:midlet_wifi_keepalive");
				wifiLock.setReferenceCounted(false);
				wifiLock.acquire();
				KeydroidxLog.i(TAG, "已获取 WifiLock，防止系统灭屏休眠 WiFi 芯片导致 QQ 掉线");
			}
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "获取 WifiLock 失败: " + t);
		}
	}

	private void releaseLocks() {
		try {
			if (wakeLock != null && wakeLock.isHeld()) {
				wakeLock.release();
				wakeLock = null;
				KeydroidxLog.i(TAG, "已释放 PartialWakeLock");
			}
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "释放 WakeLock 失败: " + t);
		}

		try {
			if (wifiLock != null && wifiLock.isHeld()) {
				wifiLock.release();
				wifiLock = null;
				KeydroidxLog.i(TAG, "已释放 WifiLock");
			}
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "释放 WifiLock 失败: " + t);
		}
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/** 挂机时启动保活（MicroActivity.onStop 调用；后台启动场景走 startForegroundService）。 */
	public static void start(Context context, String appName, String appPath, int[] keyCodes) {
		Intent intent = new Intent(context, KeydroidxMidletKeepAliveService.class);
		intent.putExtra(Constants.KEY_MIDLET_NAME, appName);
		intent.setData(Uri.parse(appPath));
		intent.putExtra(Constants.KEY_KEYCODES, keyCodes);
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				ContextCompat.startForegroundService(context, intent);
			} else {
				context.startService(intent);
			}
		} catch (Exception e) {
			// Android 12+ 后台 FGS 限制等极端场景：挂机本身不受影响（进程仍存活），仅无通知
			KeydroidxLog.w(TAG, "启动保活服务失败（忽略，挂机不受影响）: " + e);
		}
	}

	/** 回前台撤除保活通知（MicroActivity.onResume 调用）。 */
	public static void stop(Context context) {
		try {
			context.stopService(new Intent(context, KeydroidxMidletKeepAliveService.class));
		} catch (Exception ignored) {
			KeydroidxLog.w(TAG, "stopService failed: " + ignored.getMessage());
		}
	}
}
