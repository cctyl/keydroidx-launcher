package ru.playsoftware.j2meloader.nokia;

import android.app.PendingIntent;
import android.graphics.drawable.Drawable;

/**
 * 通知中心里的一个通知条目。
 * <p>
 * 由 {@link NokiaNotificationRepository} 从系统 {@link android.service.notification.StatusBarNotification}
 * 转换而来：{@link android.service.notification.StatusBarNotification} 是系统对象且随通知更新不断重建，
 * 不能长期持有，因此转成不可变的业务模型后再交给 UI 层。
 */
public class NokiaNotificationItem {

	/** 通知唯一标识（API21+ 为 key，低版本为 pkg|tag|id 拼装），用于清除与去重 */
	public final String key;
	/** 发通知的应用包名 */
	public final String pkg;
	/** 应用名（未取到时回退包名） */
	public final String appName;
	/** 通知标题（EXTRA_TITLE / EXTRA_TITLE_BIG） */
	public final String title;
	/** 通知正文（EXTRA_TEXT / EXTRA_BIG_TEXT） */
	public final String text;
	/** 通知发布时间（{@link android.service.notification.StatusBarNotification#getPostTime()}） */
	public final long postTime;
	/** 应用图标（S60 图标优先，回退系统应用图标） */
	public final Drawable icon;
	/** 点击通知时要发送的意图；null 表示无法打开 */
	public final PendingIntent contentIntent;
	/** 是否可清除（系统标记为 ongoing 的通知用户不能清除） */
	public final boolean clearable;
	/** 常驻通知（FLAG_ONGOING_EVENT），默认不在列表中展示 */
	public final boolean ongoing;
	/** 低版本清除通知所需的三元组，API21+ 为 null */
	public final String tag;
	public final int id;

	public NokiaNotificationItem(String key, String pkg, String appName, String title, String text,
			long postTime, Drawable icon, PendingIntent contentIntent,
			boolean clearable, boolean ongoing, String tag, int id) {
		this.key = key;
		this.pkg = pkg;
		this.appName = appName;
		this.title = title;
		this.text = text;
		this.postTime = postTime;
		this.icon = icon;
		this.contentIntent = contentIntent;
		this.clearable = clearable;
		this.ongoing = ongoing;
		this.tag = tag;
		this.id = id;
	}

	/** 列表里显示的标题：标题为空时退化到应用名，保证每行都有主文本。 */
	public String displayTitle() {
		if (title != null && title.trim().length() > 0) return title;
		return appName;
	}
}
