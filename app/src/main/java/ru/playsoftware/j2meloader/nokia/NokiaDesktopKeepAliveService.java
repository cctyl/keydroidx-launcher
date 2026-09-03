package ru.playsoftware.j2meloader.nokia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import io.github.cctyl.nokia.common.log.NokiaLog;
import ru.playsoftware.j2meloader.R;

/**
 * 原键桌面主进程保活前台 Service（主进程，manifest 未声明 android:process）。
 * <p>
 * <b>常驻策略</b>：桌面 onCreate（向导完成后）即拉起，之后【永不主动停止】，通知长期常驻。
 * <p>
 * <b>为何在 onCreate 而非 onStop 启动</b>：早期版本在 {@code NokiaDesktopActivity.onStop()}
 * 里启动本服务，但 onStop 往往就是息屏/锁屏发生的瞬间。那时启动前台服务会把它
 * onCreate/onStartCommand/startForeground 全部挤进桌面主线程，恰好砸进「窗口焦点
 * 从有变无」的敏感过渡窗口，曾引发 {@code Application does not have a focused window} ANR
 * → 展讯看门狗强杀进程 → 系统自动 Clearing preferred home → 按 HOME 弹出桌面选择器。
 * 在 onCreate 启动时桌面处于前台态，服务早已常驻，息屏过渡期不再有任何 FGS 启动动作去搅窗口焦点。
 * 进程被系统回收后，靠 START_STICKY 自举重启，无需 app 主动拉。
 * 代价是通知栏长期占用，用户可在通知上长按关闭该渠道。
 * <p>
 * <b>本服务绝不持有 WakeLock / WifiLock。</b>
 * 原因：前台服务提升的是进程优先级（adj），本身不会阻止 CPU 休眠；
 * 真正让系统判「频繁阻止系统休眠 / 高耗电」的是 PARTIAL_WAKE_LOCK。
 * 实测本机 15 小时内累计持锁 8.15 小时即由此前的保活实现引发。
 * 因此这里只做前台服务，不碰任何锁。
 * <p>
 * <b>已知边界（实测）：</b>本服务挡不住展锐 UnisocWatchdog 的
 * {@code killBackground_onlyLargeRam}——那是按 RSS+swap 排序杀第三方后台进程的厂商机制，
 * 实测前台服务运行期间（procState=4）进程仍会被杀。要降低那部分被杀概率，
 * 方向是降低进程内存占用，见 docs/保活与高耗电排查方案.md。
 */
public class NokiaDesktopKeepAliveService extends Service {

	private static final String TAG = "DesktopKeepAlive";
	private static final String CHANNEL_ID = "desktop_keepalive";
	private static final int NOTIFICATION_ID = 2;

	@Override
	public void onCreate() {
		super.onCreate();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
					getString(R.string.keepalive_channel_name), NotificationManager.IMPORTANCE_LOW);
			channel.setDescription(getString(R.string.keepalive_channel_desc));
			// 桌面是常驻 Launcher，常驻通知不该在图标上显示角标
			channel.setShowBadge(false);
			NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			if (nm != null) {
				nm.createNotificationChannel(channel);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		// START_STICKY：进程被回收后由系统在数秒内重启本服务，桌面进程随之恢复，
		// 下次按 Home 直接复用。本服务无外部依赖、无崩溃面，不存在重启崩溃循环风险。
		if (!startForegroundSafe(buildNotification())) {
			// 通知起不来（权限被剥离 / ROM 限制 / 后台 FGS 限制）也不影响桌面本身，
			// 只是失去保活效果；绝不让异常冒到主进程。
			stopSelf();
			return START_NOT_STICKY;
		}
		return START_STICKY;
	}

	/**
	 * 安全版 startForeground：失败返回 false，绝不抛异常。
	 * 本服务与桌面同进程，任何崩溃都会直接表现为「桌面闪退」。
	 */
	private boolean startForegroundSafe(Notification notification) {
		try {
			startForeground(NOTIFICATION_ID, notification);
			return true;
		} catch (Throwable t) {
			NokiaLog.w(TAG, "startForeground 失败，保活降级（桌面不受影响）: " + t);
			return false;
		}
	}

	private Notification buildNotification() {
		// HOME category + singleTask：与系统按 Home 键的行为一致，复用已有任务栈，
		// 不会新建一份桌面实例。
		Intent backIntent = new Intent(Intent.ACTION_MAIN);
		backIntent.addCategory(Intent.CATEGORY_HOME);
		backIntent.setClass(this, NokiaDesktopActivity.class);
		backIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

		int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			piFlags |= PendingIntent.FLAG_IMMUTABLE;
		}
		PendingIntent pi = PendingIntent.getActivity(this, 0, backIntent, piFlags);

		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.mipmap.ic_launcher)
				.setContentTitle(getString(R.string.keepalive_notification_title))
				.setContentText(getString(R.string.keepalive_notification_text))
				.setContentIntent(pi)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setOngoing(true)
				.build();
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

	/** 拉起常驻保活（NokiaDesktopActivity.onStop 调用；已运行时重复调用无副作用）。 */
	public static void start(Context context) {
		Intent intent = new Intent(context, NokiaDesktopKeepAliveService.class);
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				ContextCompat.startForegroundService(context, intent);
			} else {
				context.startService(intent);
			}
		} catch (Exception e) {
			// Android 12+ 后台启动 FGS 限制等极端场景：桌面不受影响，仅失去保活
			NokiaLog.w(TAG, "启动保活服务失败（忽略，桌面不受影响）: " + e);
		}
	}
}
