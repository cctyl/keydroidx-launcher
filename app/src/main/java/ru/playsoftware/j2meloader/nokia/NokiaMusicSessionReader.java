package ru.playsoftware.j2meloader.nokia;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.List;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

/**
 * 通过 MediaSession 读取指定 App 的播放状态（歌名 / 歌手 / 播放中 / 进度 / 时长）。
 * <p>
 * <b>为什么不用 ContentProvider：</b>{@code ContentResolver.query()} 到某个 Provider 时，
 * 若提供它的进程尚未运行，AMS 会直接把该进程冷启动起来，调用方主线程同步等待。
 * 实测桌面在「清理后台 → 返回桌面」时，音乐 Provider 冷启动耗时约 1.2s，
 * 直接导致 Skipped 75 frames / 单帧 1303ms 的卡顿。
 * <p>
 * MediaSession 通道没有这个问题：session 由 system_server 的 MediaSessionService 维护，
 * {@link MediaSessionManager#getActiveSessions} 只返回<b>当前已注册</b>的 session，
 * 拿不到就是没在播放，绝不会去拉起对方进程——这也正是官方「正在播放」控件的做法。
 * <p>
 * <b>前提：</b>需要用户授予「通知使用权」（见 {@link NokiaNotificationListenerService}）。
 * 未授予或 API &lt; 21 时 {@link #read} 返回 null，调用方回退到 Provider 查询。
 * <p>
 * <b>已知限制：</b>MediaSession 的 metadata 里没有歌词（音乐 App 只 set 了
 * title/artist/album），歌词仍需 Provider 补充，但只在「正在播放」时补——
 * 此时对方进程必然存活，查询是毫秒级，不会冷启动。
 */
public final class NokiaMusicSessionReader {

	private static final String TAG = "MusicSession";

	private NokiaMusicSessionReader() {}

	/** 读到的播放状态。 */
	public static final class MusicState {
		public String title;
		public String artist;
		public boolean playing;
		public long positionMs;
		public long durationMs;

		/** 是否真的读到了数据（false 表示目标 App 当前没有任何 MediaSession）。 */
		public boolean hasData() {
			return !TextUtils.isEmpty(title);
		}
	}

	/** MediaSession 通道是否可用（API 21+ 且已授予通知使用权）。 */
	public static boolean isAvailable(Context ctx) {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
				&& isNotificationListenerEnabled(ctx);
	}

	/**
	 * 读取目标 App 的播放状态。
	 * <b>必须在后台线程调用</b>（含 Binder 调用）。失败/不可用/目标未播放时返回 null。
	 */
	public static MusicState read(Context ctx, String targetPackage) {
		if (ctx == null || TextUtils.isEmpty(targetPackage)) return null;
		if (!isAvailable(ctx)) {
			return null;
		}
		return readInternal(ctx.getApplicationContext(), targetPackage);
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private static MusicState readInternal(Context ctx, String targetPackage) {
		MediaSessionManager msm;
		try {
			msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
		} catch (Exception e) {
			NokiaLog.w(TAG, "获取 MediaSessionManager 失败: " + e.getMessage());
			return null;
		}
		if (msm == null) return null;

		List<MediaController> controllers;
		try {
			controllers = msm.getActiveSessions(
					new ComponentName(ctx, NokiaNotificationListenerService.class));
		} catch (SecurityException e) {
			// 通知使用权已被撤销（缓存过期），本次按不可用处理
			NokiaLog.w(TAG, "通知使用权未授予，MediaSession 读取被拒");
			return null;
		} catch (Exception e) {
			NokiaLog.w(TAG, "getActiveSessions 失败: " + e.getMessage());
			return null;
		}
		if (controllers == null || controllers.isEmpty()) {
			return null;
		}

		// 目标包中优先取「正在播放」的那个 session；都没有则取第一个（暂停态也要显示歌名）
		MediaController target = null;
		for (MediaController c : controllers) {
			if (c == null || !targetPackage.equals(c.getPackageName())) continue;
			if (isPlaying(c)) {
				target = c;
				break;
			}
			if (target == null) target = c;
		}
		if (target == null) {
			NokiaLog.d(TAG, "目标包无活跃 MediaSession: " + targetPackage);
			return null;
		}

		MusicState st = new MusicState();
		MediaMetadata md = target.getMetadata();
		if (md != null) {
			st.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
			st.artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
			st.durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
		}
		android.media.session.PlaybackState ps = target.getPlaybackState();
		if (ps != null) {
			st.playing = ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
			st.positionMs = ps.getPosition();
		}
		if (!st.hasData()) {
			NokiaLog.d(TAG, "MediaSession 无标题数据: " + targetPackage);
			return null;
		}
		NokiaLog.i(TAG, "MediaSession 读到播放状态: " + st.title
				+ (st.artist != null ? " - " + st.artist : "")
				+ " playing=" + st.playing);
		return st;
	}

	@TargetApi(Build.VERSION_CODES.LOLLIPOP)
	private static boolean isPlaying(MediaController c) {
		android.media.session.PlaybackState ps = c.getPlaybackState();
		return ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
	}

	/**
	 * 本 App 是否已被授予「通知使用权」。
	 * 用 {@code enabled_notification_listeners} 判断——没有官方 API 可以查询自身状态。
	 */
	public static boolean isNotificationListenerEnabled(Context ctx) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
		String pkg = ctx.getPackageName();
		String flat;
		try {
			flat = Settings.Secure.getString(ctx.getContentResolver(),
					"enabled_notification_listeners");
		} catch (Exception e) {
			NokiaLog.w(TAG, "读取 enabled_notification_listeners 失败: " + e.getMessage());
			return false;
		}
		if (flat == null || flat.isEmpty()) return false;
		String[] parts = flat.split(":");
		for (String part : parts) {
			ComponentName cn = ComponentName.unflattenFromString(part);
			if (cn != null && pkg.equals(cn.getPackageName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 弹出「授予通知使用权」提示弹窗（诺基亚风格），桌面与高级设置共用。
	 * <p>
	 * 未授予时 MediaSession 通道不可用，只能退化到 ContentProvider 查询，
	 * 而查询会把音乐进程冷启动起来（用户清完后台，桌面又把它拉起来）。
	 * 这是需要用户授权的系统权限，必须主动说明用途并给出一键入口，不能默默退化。
	 *
	 * @param fm       弹窗宿主 FragmentManager
	 * @param ctx      用于跳转设置页 / 写设置
	 * @param neverAsk 是否提供「不再提示」项（桌面首次提示需要；设置页手动进入不需要）
	 */
	public static void showGrantPrompt(FragmentManager fm, final Context ctx, boolean neverAsk) {
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_SETTINGS, "去开启", true, false,
				new Runnable() {
					@Override
					public void run() {
						if (!openNotificationListenerSettings(ctx)) {
							Toast.makeText(ctx, "当前系统版本不支持通知使用权", Toast.LENGTH_SHORT).show();
						}
					}
				}));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_CLOSE, "以后再说", true, false, null));
		if (neverAsk) {
			items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_CLOSE, "不再提示", true, false,
					new Runnable() {
						@Override
						public void run() {
							NokiaSettingsStorage.setNotifyAccessPromptDisabled(ctx, true);
						}
					}));
		}
		NokiaOptionsDialog.show(fm, "音乐组件需要通知使用权", items);
		NokiaLog.i(TAG, "已弹出通知使用权授予提示");
	}

	/** 跳转系统「通知使用权」设置页。 */
	public static boolean openNotificationListenerSettings(Context ctx) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false;
		try {
			android.content.Intent intent = new android.content.Intent(
					Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
			intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
			ctx.startActivity(intent);
			return true;
		} catch (Exception e) {
			NokiaLog.e(TAG, "打开通知使用权设置失败", e);
			return false;
		}
	}
}
