package ru.playsoftware.j2meloader.nokia;

import android.service.notification.NotificationListenerService;

/**
 * 通知监听服务：本类自身不处理任何通知，存在的唯一目的是取得「通知使用权」。
 * <p>
 * 必要性：{@link android.media.session.MediaSessionManager#getActiveSessions} 要求传入一个
 * <b>已启用的</b> {@link NotificationListenerService} 组件名做身份校验，否则抛
 * {@link SecurityException}。桌面要读取生态音乐 App 的播放状态（歌名/歌手/进度），
 * 又不想通过 ContentProvider 把对方进程冷启动起来（实测冷启动会阻塞桌面主线程 1.2s，
 * 造成返回桌面时跳帧卡顿），只能走 MediaSession 通道，因此必须有这个壳服务。
 * <p>
 * 未授予通知使用权时，读取器会自动回退到异步查询 ContentProvider（不卡主线程，
 * 但会有冷启动代价），功能不依赖本服务。
 */
public class NokiaNotificationListenerService extends NotificationListenerService {
}
