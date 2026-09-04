package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import io.github.cctyl.nokia.common.log.NokiaLog;

/**
 * 通知监听服务。承担两个职责：
 * <ol>
 * <li>取得「通知使用权」身份：{@link android.media.session.MediaSessionManager#getActiveSessions}
 * 要求传入一个<b>已启用的</b> NotificationListenerService 组件名做校验，桌面读取生态音乐
 * App 的播放状态依赖它（历史原因，见下方原始说明）。</li>
 * <li>通知中心数据源：把系统通知回调转交 {@link NokiaNotificationRepository}，
 * 由仓储统一做过滤、排序与订阅分发。</li>
 * </ol>
 * <p>
 * 清除通知也必须经本服务：{@link #cancelNotification} 需要服务处于已连接状态，
 * 未连接（未授权或系统重绑中）时请求直接失败，由 UI 层提示用户，不静默吞掉。
 * <p>
 * 本类运行在默认（主）进程，与桌面、通知中心页面同进程，仓储单例可直接共享。
 */
public class NokiaNotificationListenerService extends NotificationListenerService {

	private static final String TAG = "NotifListener";

	private static volatile NokiaNotificationListenerService instance;

	/** 当前连接实例；null = 未连接（未授权或绑定中）。 */
	public static NokiaNotificationListenerService getInstance() {
		return instance;
	}

	/** 应用上下文，供仓储在后台线程做 PackageManager 查询。 */
	public static Context appContext() {
		NokiaNotificationListenerService s = instance;
		return s != null ? s.getApplicationContext() : null;
	}

	/**
	 * 服务被系统绑定。
	 * <p>
	 * 连接态以「服务实例就绪」为准，而不是 {@link #onListenerConnected()}：Android 4.4（API19）
	 * 的 NotificationManagerService 不会派发该回调（5.0+ 才稳定回调），导致 4.4 上
	 * connected 永远为 false —— 通知中心一直停在「正在读取」且拿不到全量快照。
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		instance = this;
		NokiaLog.i(TAG, "onCreate：服务实例就绪");
		NokiaNotificationRepository.get().onListenerConnected();
	}

	@Override
	public void onDestroy() {
		if (instance == this) {
			instance = null;
		}
		NokiaLog.w(TAG, "onDestroy：服务实例销毁");
		NokiaNotificationRepository.get().onListenerDisconnected();
		super.onDestroy();
	}

	@Override
	public void onListenerConnected() {
		instance = this;
		NokiaLog.i(TAG, "onListenerConnected");
		NokiaNotificationRepository.get().onListenerConnected();
	}

	@Override
	public void onListenerDisconnected() {
		if (instance == this) {
			instance = null;
		}
		NokiaLog.w(TAG, "onListenerDisconnected");
		NokiaNotificationRepository.get().onListenerDisconnected();
	}

	@Override
	public void onNotificationPosted(StatusBarNotification sbn) {
		NokiaLog.d(TAG, "onNotificationPosted: "
				+ (sbn != null ? sbn.getPackageName() : "null"));
		NokiaNotificationRepository.get().onPosted(sbn);
	}

	@Override
	public void onNotificationRemoved(StatusBarNotification sbn) {
		NokiaLog.d(TAG, "onNotificationRemoved: "
				+ (sbn != null ? sbn.getPackageName() : "null"));
		NokiaNotificationRepository.get().onRemoved(sbn);
	}

	/**
	 * 清除单条通知。API21+ 走 {@link #cancelNotification(String)}；
	 * API19/20（minSdk=19）只有废弃的三元组签名，效果一致。必须服务已连接，否则返回 false。
	 */
	public boolean cancelByKey(String key, String pkg, String tag, int id) {
		if (getInstance() == null) {
			NokiaLog.w(TAG, "cancelByKey 失败：服务未连接");
			return false;
		}
		try {
			if (android.os.Build.VERSION.SDK_INT >= 21 && key != null) {
				cancelNotification(key);
			} else {
				cancelNotification(pkg, tag, id);
			}
			return true;
		} catch (Exception e) {
			NokiaLog.e(TAG, "cancelNotification 失败 key=" + key, e);
			return false;
		}
	}

	/** 清除全部通知。必须服务已连接，否则返回 false。 */
	public boolean cancelAll() {
		if (getInstance() == null) {
			NokiaLog.w(TAG, "cancelAll 失败：服务未连接");
			return false;
		}
		try {
			cancelAllNotifications();
			return true;
		} catch (Exception e) {
			NokiaLog.e(TAG, "cancelAllNotifications 失败", e);
			return false;
		}
	}
}
