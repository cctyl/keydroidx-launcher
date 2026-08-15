package ru.playsoftware.j2meloader.nokia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

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
 */
public class NokiaMidletKeepAliveService extends Service {

	private static final String TAG = "MidletKeepAlive";
	private static final String CHANNEL_ID = "midlet_keepalive";
	private static final int NOTIFICATION_ID = 1;

	@Override
	public void onCreate() {
		super.onCreate();
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
		if (!MidletThread.hasInstance()) {
			// 僵尸防御（系统重启拉起/竞态残留）：进程内已无 MIDlet。
			// 注意：API 26+ 规定 startForegroundService 调用后必须调用 startForeground，
			// 即使立即 stopSelf 也要先补一次，否则 ForegroundServiceDidNotStartInTimeException
			// 会杀死进程并触发崩溃循环（实测 bug）。
			if (!startForegroundSafe(buildNotification(null, null, null))) {
				stopSelf();
				return START_NOT_STICKY;
			}
			stopForeground(true);
			stopSelf();
			return START_NOT_STICKY;
		}
		String appName = intent != null ? intent.getStringExtra(Constants.KEY_MIDLET_NAME) : null;
		String appPath = intent != null && intent.getData() != null ? intent.getData().toString() : null;
		int[] keyCodes = intent != null ? intent.getIntArrayExtra(Constants.KEY_KEYCODES) : null;
		if (appName == null || appPath == null) {
			// START_STICKY 重启 intent 为 null：从状态文件兜底
			MidletStateStore.RunningInfo r = MidletStateStore.getRunning(this);
			if (r != null) {
				if (appName == null) appName = r.appName;
				if (appPath == null) appPath = r.appPath;
			}
		}
		if (keyCodes == null) {
			keyCodes = NokiaKeyBinding.loadKeyCodes(this);
		}
		if (!startForegroundSafe(buildNotification(appName, appPath, keyCodes))) {
			// 保活降级：通知起不来（权限被剥离/ROM 限制等极端场景）也不能崩溃——
			// 挂机本身仍成立（进程存活），只是无常驻通知、更易被系统回收
			stopSelf();
			return START_NOT_STICKY;
		}
		// NOT_STICKY：进程死=挂机已失效，禁止系统重启空进程制造崩溃循环
		return START_NOT_STICKY;
	}

	/**
	 * 安全版 startForeground：失败返回 false（调用方降级 stopSelf），绝不抛异常——
	 * 本服务运行在 :midlet 进程，任何崩溃都会连带杀死挂机中的 MIDlet（实测 bug）。
	 */
	private boolean startForegroundSafe(Notification notification) {
		try {
			startForeground(NOTIFICATION_ID, notification);
			return true;
		} catch (Throwable t) {
			NokiaLog.e(TAG, "startForeground 失败，保活降级（挂机不受影响）: " + t);
			return false;
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
		stopForeground(true);
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/** 挂机时启动保活（MicroActivity.onStop 调用；后台启动场景走 startForegroundService）。 */
	public static void start(Context context, String appName, String appPath, int[] keyCodes) {
		Intent intent = new Intent(context, NokiaMidletKeepAliveService.class);
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
			NokiaLog.w(TAG, "启动保活服务失败（忽略，挂机不受影响）: " + e);
		}
	}

	/** 回前台撤除保活通知（MicroActivity.onResume 调用）。 */
	public static void stop(Context context) {
		try {
			context.stopService(new Intent(context, NokiaMidletKeepAliveService.class));
		} catch (Exception ignored) {
		}
	}
}
