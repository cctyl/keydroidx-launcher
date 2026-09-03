package ru.playsoftware.j2meloader.nokia;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import io.github.cctyl.nokia.common.log.NokiaLog;

/**
 * 通知中心数据仓储（进程内单例）。
 * <p>
 * 数据流向：{@link NokiaNotificationListenerService} 收到系统回调后把原始
 * {@link StatusBarNotification} 交给本类，本类在后台线程完成「转模型 → 过滤 → 排序」，
 * 再回主线程通知订阅者（通知中心列表页、桌面通知条）。
 * <p>
 * 为什么不直接把 StatusBarNotification 交给 UI：它是系统对象，随通知更新不断重建，
 * 且取图标/应用名需要 PackageManager IPC，不能在主线程做。
 * <p>
 * 未读水位线（{@link #lastReadTime}）是进程内存态：通知中心页面每次可见时调
 * {@link #markAllRead()}，未读数 = 发布时间晚于水位线的条数。进程重启后归零可接受——
 * 系统通知本身在重启后也不会留存。
 * <p>
 * 过滤规则：排除本应用（避免自家保活常驻通知混入）；常驻通知（FLAG_ONGOING_EVENT）
 * 默认隐藏，由「通知中心」设置项控制。
 */
public final class NokiaNotificationRepository {

	private static final String TAG = "NotifRepo";

	/** 通知变化回调。回调发生在主线程，实现方应尽快返回、只做轻量刷新。 */
	public interface Listener {
		void onNotificationsChanged();
	}

	private static final NokiaNotificationRepository INSTANCE = new NokiaNotificationRepository();

	public static NokiaNotificationRepository get() {
		return INSTANCE;
	}

	private NokiaNotificationRepository() {
	}

	private final Object lock = new Object();
	/** 已过滤、按 postTime 倒序的快照。 */
	private final List<NokiaNotificationItem> items = new ArrayList<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	/** 包名 → 图标 / 应用名 缓存，避免重复 IPC。 */
	private final Map<String, Drawable> iconCache = new HashMap<>();
	private final Map<String, String> labelCache = new HashMap<>();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	/** 单线程串行转换，保证写入顺序与系统回调顺序一致。 */
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	/** 提交序号：后台完成后比对，防止乱序覆盖。 */
	private final AtomicLong seq = new AtomicLong();

	/** 通知使用权是否已连接（服务 onListenerConnected 后为 true）。 */
	private volatile boolean connected;
	/** 未读水位线：postTime 大于它的通知视为未读。 */
	private volatile long lastReadTime;

	// ---- 生命周期（由 NokiaNotificationListenerService 回调） ----

	/** 服务已连接：置位并主动拉一次全量快照。 */
	public void onListenerConnected() {
		connected = true;
		NokiaLog.i(TAG, "通知监听已连接，拉取全量快照");
		refreshFromService();
	}

	public void onListenerDisconnected() {
		connected = false;
		NokiaLog.w(TAG, "通知监听断开连接");
		notifyChanged();
	}

	public boolean isConnected() {
		return connected;
	}

	/**
	 * 全量刷新：从系统服务拉当前活跃通知列表。
	 * 由 Service 在 onListenerConnected / 重连后调用。
	 */
	public void refreshFromService() {
		NokiaNotificationListenerService service = NokiaNotificationListenerService.getInstance();
		if (service == null) return;
		StatusBarNotification[] active;
		try {
			active = service.getActiveNotifications();
		} catch (Exception e) {
			NokiaLog.w(TAG, "getActiveNotifications 失败: " + e.getMessage());
			return;
		}
		applyRaw(active != null ? active : new StatusBarNotification[0]);
	}

	/** 单条新增/更新（onNotificationPosted）。 */
	public void onPosted(StatusBarNotification sbn) {
		if (sbn == null) return;
		applyUpsert(sbn);
	}

	/** 单条移除（onNotificationRemoved）。 */
	public void onRemoved(StatusBarNotification sbn) {
		if (sbn == null) return;
		String key = keyOf(sbn);
		synchronized (lock) {
			for (int i = items.size() - 1; i >= 0; i--) {
				if (items.get(i).key.equals(key)) {
					items.remove(i);
					break;
				}
			}
		}
		notifyChanged();
	}

	// ---- 转换与排序（后台线程） ----

	private void applyRaw(final StatusBarNotification[] raws) {
		final long mySeq = seq.incrementAndGet();
		executor.execute(new Runnable() {
			@Override
			public void run() {
				Context appCtx = NokiaNotificationListenerService.appContext();
				List<NokiaNotificationItem> converted = new ArrayList<>();
				for (StatusBarNotification sbn : raws) {
					NokiaNotificationItem item = convert(appCtx, sbn);
					if (item != null) converted.add(item);
				}
				finishApply(mySeq, converted);
			}
		});
	}

	private void applyUpsert(final StatusBarNotification sbn) {
		final long mySeq = seq.incrementAndGet();
		executor.execute(new Runnable() {
			@Override
			public void run() {
				Context appCtx = NokiaNotificationListenerService.appContext();
				NokiaNotificationItem item = convert(appCtx, sbn);
				List<NokiaNotificationItem> snapshot;
				synchronized (lock) {
					String key = keyOf(sbn);
					for (int i = items.size() - 1; i >= 0; i--) {
						if (items.get(i).key.equals(key)) {
							items.remove(i);
							break;
						}
					}
					if (item != null) items.add(item);
					sortLocked();
					snapshot = new ArrayList<>(items);
				}
				finishApply(mySeq, snapshot);
			}
		});
	}

	private void finishApply(long mySeq, List<NokiaNotificationItem> converted) {
		// 过期的后台任务直接丢弃，避免旧快照覆盖新状态
		if (mySeq != seq.get()) {
			NokiaLog.d(TAG, "丢弃过期刷新 seq=" + mySeq);
			return;
		}
		synchronized (lock) {
			items.clear();
			items.addAll(converted);
			sortLocked();
		}
		notifyChanged();
	}

	private void sortLocked() {
		Collections.sort(items, new Comparator<NokiaNotificationItem>() {
			@Override
			public int compare(NokiaNotificationItem a, NokiaNotificationItem b) {
				// 倒序：最新在前
				return Long.compare(b.postTime, a.postTime);
			}
		});
	}

	/**
	 * StatusBarNotification → 业务模型。返回 null 表示该通知被过滤。
	 * 在后台线程调用（含 PackageManager IPC）。
	 */
	private NokiaNotificationItem convert(Context appCtx, StatusBarNotification sbn) {
		if (appCtx == null) return null;
		String pkg = sbn.getPackageName();
		if (pkg == null || pkg.equals(appCtx.getPackageName())) {
			return null; // 排除自家通知（保活等）
		}
		boolean ongoing = sbn.isOngoing();
		if (ongoing && !NokiaSettingsStorage.isNotificationShowOngoing(appCtx)) {
			return null; // 常驻通知默认不展示
		}

		String title = null;
		String text = null;
		try {
			Notification n = sbn.getNotification();
			if (n != null && n.extras != null) {
				CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
				CharSequence tBig = n.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
				CharSequence body = n.extras.getCharSequence(Notification.EXTRA_TEXT);
				CharSequence bodyBig = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
				title = tBig != null ? tBig.toString() : (t != null ? t.toString() : null);
				text = bodyBig != null ? bodyBig.toString() : (body != null ? body.toString() : null);
			}
		} catch (Exception e) {
			NokiaLog.w(TAG, "解析通知内容失败: " + e.getMessage());
		}

		String appName = resolveAppName(appCtx, pkg);
		Drawable icon = resolveIcon(appCtx, pkg, appName);

		return new NokiaNotificationItem(
				keyOf(sbn), pkg, appName,
				title, text,
				sbn.getPostTime(), icon,
				sbn.getNotification() != null ? sbn.getNotification().contentIntent : null,
				sbn.isClearable(), ongoing,
				sbn.getTag(), sbn.getId());
	}

	private String resolveAppName(Context appCtx, String pkg) {
		synchronized (labelCache) {
			String cached = labelCache.get(pkg);
			if (cached != null) return cached;
		}
		String label = pkg;
		try {
			PackageManager pm = appCtx.getPackageManager();
			CharSequence cs = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
			if (cs != null && cs.length() > 0) label = cs.toString();
		} catch (Exception e) {
			NokiaLog.d(TAG, "取应用名失败 " + pkg + ": " + e.getMessage());
		}
		synchronized (labelCache) {
			labelCache.put(pkg, label);
		}
		return label;
	}

	private Drawable resolveIcon(Context appCtx, String pkg, String appName) {
		synchronized (iconCache) {
			Drawable cached = iconCache.get(pkg);
			if (cached != null) return cached;
		}
		Drawable icon = null;
		try {
			// 优先 S60 风格图标，与功能表观感一致
			int resId = NokiaS60IconMap.getIcon(pkg, appName);
			if (resId != 0) {
				icon = appCtx.getResources().getDrawable(resId);
			}
			if (icon == null) {
				icon = appCtx.getPackageManager().getApplicationIcon(pkg);
			}
		} catch (Exception e) {
			NokiaLog.d(TAG, "取应用图标失败 " + pkg + ": " + e.getMessage());
		}
		if (icon != null) {
			synchronized (iconCache) {
				iconCache.put(pkg, icon);
			}
		}
		return icon;
	}

	/** 通知唯一 key：API21+ 用系统 key；低版本用 pkg|tag|id 拼装。 */
	private static String keyOf(StatusBarNotification sbn) {
		if (android.os.Build.VERSION.SDK_INT >= 21) {
			String k = sbn.getKey();
			if (k != null) return k;
		}
		return sbn.getPackageName() + "|" + sbn.getTag() + "|" + sbn.getId();
	}

	// ---- 读取接口（UI 层） ----

	/** 当前快照（已过滤、最新在前）。返回副本，调用方可安全持有。 */
	public List<NokiaNotificationItem> getItems() {
		synchronized (lock) {
			return new ArrayList<>(items);
		}
	}

	/** 未读条数（postTime 晚于上次进入页面时间的条数）。 */
	public int getUnreadCount() {
		synchronized (lock) {
			int count = 0;
			for (NokiaNotificationItem item : items) {
				if (item.postTime > lastReadTime) count++;
			}
			return count;
		}
	}

	/** 最新一条通知（可能为 null）。桌面通知条摘要用。 */
	public NokiaNotificationItem getLatest() {
		synchronized (lock) {
			return items.isEmpty() ? null : items.get(0);
		}
	}

	/** 页面可见时调用：把未读水位线推到当前最新通知的时间。 */
	public void markAllRead() {
		synchronized (lock) {
			long max = lastReadTime;
			for (NokiaNotificationItem item : items) {
				if (item.postTime > max) max = item.postTime;
			}
			if (max != lastReadTime) {
				lastReadTime = max;
				notifyChanged();
			}
		}
	}

	/** 未读水位线（postTime 晚于它的通知视为未读）。UI 行视图判断未读点用。 */
	public long getLastReadTime() {
		return lastReadTime;
	}

	// ---- 订阅 ----

	public void addListener(Listener l) {
		listeners.add(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	/**
	 * 主线程 200ms 合并通知订阅者：通知风暴（下载进度等高频更新）时避免 UI 连续重建。
	 */
	private void notifyChanged() {
		mainHandler.removeCallbacksAndMessages(null);
		mainHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (listeners.isEmpty()) return;
				for (Listener l : listeners) {
					try {
						l.onNotificationsChanged();
					} catch (Exception e) {
						NokiaLog.w(TAG, "通知订阅者回调异常: " + e.getMessage());
					}
				}
			}
		}, 200);
	}
}
