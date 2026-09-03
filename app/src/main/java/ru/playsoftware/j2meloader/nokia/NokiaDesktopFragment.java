package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.drawable.NokiaDashedLineDrawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;


/**
 * 桌面待机屏中间内容碎片。
 * 支持方向键在快捷应用栏（动态数量）和桌面组件区之间导航。
 * 桌面组件由用户在桌面设置中配置，动态加载自 NokiaWidgetStorage。
 */
public class NokiaDesktopFragment extends NokiaPageFragment {

	private final List<View> focusTargets = new ArrayList<>();
	private final List<ShortcutApp> shortcutApps = new ArrayList<>();
	private final List<NokiaWidgetItem> widgetItems = new ArrayList<>();
	private int focusIndex = -1;
	private View selectedView = null;
	private NokiaSettingsStorage settingsStorage;
	private NokiaWidgetStorage widgetStorage;
	/** 选中快捷应用图标上方浮出的名称气泡 */
	private TextView shortcutNameBubble;
	private HorizontalScrollView shortcutBar;
	private Handler bubbleHandler;
	private static final long BUBBLE_DURATION = 2000;

	/** 后台管理组件计数缓存（由后台线程刷新，主线程只读，避免 TCP 卡顿）。 */
	private volatile int cachedBgCount = -1;

	/**
	 * 离开过桌面后才需要在 onResume 重建内容区。
	 * 冷启动时 {@link #onPageCreated} 刚完成全量构建，紧接着的 onResume 会再构建一遍
	 * （快捷栏 + 组件区 + 开关栏全量重建，含若干主线程 Binder 调用），
	 * 等于把首屏耗时翻倍。
	 */
	private boolean contentDirty = false;

	/**
	 * 快捷栏图标 / 冻结角标的后台线程池。
	 * 原先每个快捷项各起一个线程，8 项就是 8 个线程并发做 PackageManager IPC + 位图分配，
	 * 冷启动时容易抢占 CPU 并引发 GC 抖动，拖慢主线程。
	 */
	private static final ExecutorService ICON_EXECUTOR = Executors.newFixedThreadPool(2);

	/**
	 * 音乐播放状态相关的跨进程调用专用单线程线程池。
	 * 这些调用（registerContentObserver / ContentProvider query / MediaSession）在对方
	 * 进程不在时都会同步等待其冷启动，必须挪出主线程；串行执行还能避免
	 * register 与 refresh 并发导致的竞态。
	 */
	private static final ExecutorService MUSIC_EXECUTOR = Executors.newSingleThreadExecutor();

	/** 音乐状态异步刷新的序号：只应用最后一次刷新的结果，避免乱序覆盖。 */
	private int musicRefreshSeq = 0;

	/** 冻结角标 View 的 tag（用于查找/去重）。 */
	private static final String TAG_FREEZE_BADGE = "freeze_badge";
	/** 组件行右侧信息文字的 tag（用于局部刷新，避免整区重建）。 */
	private static final String TAG_WIDGET_INFO = "widget_info";

	// ---- 单次组件区渲染内共享的实时数据快照 ----
	// 组件行原本各自独立查询（音乐行甚至查 3 次 ContentProvider），
	// 这些都是主线程同步 Binder 调用，组件越多越慢，故在同一次渲染内合并为一次。

	/** 音乐播放状态快照。 */
	private MusicSnapshot musicSnapshot;
	/** 内存信息快照（total &lt;= 0 表示尚未查询）。 */
	private long memTotal = -1;
	private long memAvail;
	/** 存储信息快照（total &lt;= 0 表示尚未查询）。 */
	private long storageTotal = -1;
	private long storageAvail;
	/** WiFi IP 文本快照。 */
	private String ipText;

	/** 快捷栏项数（动态） */
	private int shortcutCount = 0;
	/** 组件区项数（动态，由 widgetItems.size() 决定） */
	private int widgetCount = 0;

	// ---- 通知条（快捷栏下方，见 docs/通知中心功能设计.md 阶段二） ----

	/** 通知条是否参与焦点：1=显示并占用一个焦点位，0=隐藏（focusTargets 中无此条目）。 */
	private int notifBarCount = 0;
	private LinearLayout notifBar;
	private TextView notifBarText;
	private TextView notifBarBadge;

	/** 通知仓储订阅：通知到达/移除/清除后刷新通知条。回调在主线程（仓储已节流）。 */
	private final NokiaNotificationRepository.Listener notifRepoListener =
			new NokiaNotificationRepository.Listener() {
				@Override
				public void onNotificationsChanged() {
					if (!isAdded() || getView() == null) return;
					refreshNotifBar();
				}
			};

	/** 快捷栏第一个焦点索引 */
	private static final int SHORTCUT_FIRST = 0;

	/** 音乐播放器播放状态 ContentProvider URI（跨进程读取当前播放态） */
	private static final Uri MUSIC_PLAYBACK_URI =
			Uri.parse("content://io.github.cctyl.keydroidx.music.playback/state");
	/** 音乐播放器包名 / 播放详情 Activity（组件确认键打开） */
	private static final String MUSIC_PKG = "io.github.cctyl.keydroidx.music";
	private static final String MUSIC_PLAYER_ACTIVITY = "io.github.cctyl.keydroidx.music.ui.MusicPlayerActivity";

	/** 音乐组件刷新：ContentObserver 监听播放状态变化，仅重建音乐组件行 */
	private final ContentObserver musicPlaybackObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
		@Override
		public void onChange(boolean selfChange, Uri uri) {
			if (!isAdded() || getView() == null) return;
			// 播放状态变化：后台重新取一次最新状态，回来后只重建音乐组件行
			refreshMusicAsync();
		}
	};
	private boolean musicObserverRegistered = false;

	/**
	 * 「通知使用权」提示弹窗是否已在本次进程弹过：只提示一次，避免每次回到桌面都打扰用户。
	 * 用户选了「不再提示」后写入设置（{@link NokiaSettingsStorage#isNotifyAccessPromptDisabled}）永久关闭。
	 */
	private boolean notifyAccessPromptShown = false;

	// ---- 便捷开关栏 ----

	private HorizontalScrollView quickToggleScroll;
	private LinearLayout quickToggleBar;
	private View quickToggleDivider;
	private final List<NokiaQuickToggleItem> activeToggles = new ArrayList<>();
	private final List<View> toggleCells = new ArrayList<>();
	private boolean[] toggleStates = new boolean[0];
	/** 快捷开关图标尺寸（dp，随字号缩放），重绘亮度图标时复用。 */
	private int toggleIconSizeDp = 18;
	private BroadcastReceiver toggleStateReceiver;
	private boolean receiverRegistered = false;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_desktop;
	}

	@Override
	protected int getWallpaperRes() {
		return R.drawable.bg_nokia_desktop;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		shortcutBar = view.findViewById(R.id.shortcutBar);
		shortcutNameBubble = view.findViewById(R.id.shortcutNameBubble);
		bubbleHandler = new Handler(Looper.getMainLooper());

		// 点线分割线
		View dividerTop = view.findViewById(R.id.shortcutDividerTop);
		View dividerBottom = view.findViewById(R.id.shortcutDivider);
		View toggleDivider = view.findViewById(R.id.quickToggleDivider);
		if (dividerTop != null) {
			dividerTop.setBackground(new NokiaDashedLineDrawable(getResources(), 0x60FFFFFF, 3, 3));
		}
		if (dividerBottom != null) {
			dividerBottom.setBackground(new NokiaDashedLineDrawable(getResources(), 0x60FFFFFF, 3, 3));
		}
		if (toggleDivider != null) {
			toggleDivider.setBackground(new NokiaDashedLineDrawable(getResources(), 0x60FFFFFF, 3, 3));
		}

		settingsStorage = new NokiaSettingsStorage(requireContext());
		widgetStorage = new NokiaWidgetStorage(requireContext());

		// 通知条（快捷栏下方）：确认键进入通知中心。初始状态按当前通知快照装配
		notifBar = view.findViewById(R.id.notifBarContainer);
		notifBarText = view.findViewById(R.id.notifBarText);
		notifBarBadge = view.findViewById(R.id.notifBarBadge);
		if (notifBar != null) {
			android.widget.ImageView ivBell = view.findViewById(R.id.notifBarIcon);
			if (ivBell != null) {
				ivBell.setImageDrawable(io.github.cctyl.nokia.common.ui.NokiaIcons.get(
						requireContext(),
						io.github.cctyl.nokia.common.ui.NokiaIcons.ICON_NOTIFICATIONS,
						0xFFB8C8EA, 14));
			}
			notifBar.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					((NokiaDesktopActivity) requireActivity()).openNotificationCenter();
				}
			});
		}

		focusTargets.clear();
		shortcutApps.clear();
		widgetItems.clear();
		focusIndex = -1;
		selectedView = null;
		// 本次已完整构建过内容区：无论冷启动还是从返回栈恢复，
		// 紧接着的 onResume 都不必再重建一遍。
		contentDirty = false;

		// 通知条初始装配：焦点表已清空，notifBarCount 归零后按当前快照重新挂入
		notifBarCount = 0;
		refreshNotifBar();

		loadShortcutBarAsync(view);
		rebuildWidgetArea(view);
		// 音乐播放状态异步补齐：组件区已用默认/上次快照先渲染出来，主线程不等待
		refreshMusicAsync();

		// 便捷开关栏
		quickToggleScroll = view.findViewById(R.id.quickToggleScroll);
		quickToggleBar = view.findViewById(R.id.quickToggleBar);
		quickToggleDivider = view.findViewById(R.id.quickToggleDivider);
		if (quickToggleBar != null) {
			buildToggleBar();
			// 开关状态要读 Wifi/Settings/Bluetooth/Audio/Location 等多处系统服务，
			// 每开关 1~2 次 Binder 调用，累加起来会拖慢首帧。
			// 推迟到首帧绘制完成之后再同步：桌面先出来，指示灯随后补齐（延迟不可感知）。
			view.post(new Runnable() {
				@Override
				public void run() {
					if (!isAdded()) return;
					syncToggleStatesFromSystem();
				}
			});
			// mini_shizuku 在线状态需在后台探测（socket 操作，不能在主线程）。
			// 探测结果回来后若状态有变化，重绘开关图标——亮度图标依赖该状态
			// （未激活时固定显示 brightness_low）。
			NokiaQuickToggleManager.refreshShizukuStateAsync(new Runnable() {
				@Override
				public void run() {
					if (isAdded() && getView() != null) {
						renderToggleViews();
					}
				}
			});
		}

		// 注册广播接收器监听系统开关状态变化
		registerToggleReceiver();

		NokiaLog.i("Desktop", "桌面待机屏初始化完成：快捷栏 " + shortcutCount
				+ " 项，组件区 " + widgetCount + " 项");
	}

	@Override
	public void onResume() {
		super.onResume();
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.refreshPageBar();
		// 注册音乐播放状态 ContentObserver（监听播放态变化刷新组件行）
		registerMusicPlaybackObserver();
		// 通知条：订阅通知变化并按最新快照刷新（从通知中心清除后回桌面立即同步）
		NokiaNotificationRepository.get().addListener(notifRepoListener);
		refreshNotifBar();
		// 异步刷新后台管理组件计数（countBackgroundProcesses 含 shizuku TCP，不能在主线程）
		refreshBgCountAsync();
		// 音乐组件读取播放状态首选 MediaSession，它依赖通知使用权；
		// 未授予时主动弹窗说明并给一键入口，而不是默默退化到会冷启动音乐的 Provider 查询
		maybePromptNotificationAccess();

		if (!contentDirty) {
			// 冷启动：onPageCreated 刚完成全量构建，这里重复重建等于把首屏耗时翻倍
			return;
		}
		contentDirty = false;
		View view = getView();
		if (view == null) return;
		// 从桌面设置返回后刷新快捷栏/组件区/开关栏（可能有增删改）
		loadShortcutBarAsync(view);
		rebuildWidgetArea(view);
		// 音乐播放状态异步补齐：组件区已用默认/上次快照先渲染出来，主线程不等待
		refreshMusicAsync();
		if (quickToggleBar == null) return;
		buildToggleBar();
		syncToggleStatesFromSystem();
		// 重新探测 mini_shizuku 在线状态：用户在 Shizuku 激活页启动服务后回到桌面，
		// 缓存可能仍是旧值，会导致亮度图标停在不正确的档位。
		// 探测本身是 TCP，必须在后台线程（与上面的 refreshBgCountAsync 同理）。
		NokiaQuickToggleManager.refreshShizukuStateAsync(new Runnable() {
			@Override
			public void run() {
				if (isAdded() && getView() != null) {
					renderToggleViews();
				}
			}
		});
		NokiaLog.d("Desktop", "桌面 onResume，已刷新组件区");
	}

	@Override
	public void onPause() {
		super.onPause();
		// 离开桌面后再次回来需要重建内容区（桌面设置可能增删改了组件/快捷栏/开关）
		contentDirty = true;
		unregisterToggleReceiver();
		unregisterMusicPlaybackObserver();
		// 通知条订阅随生命周期解除，避免持有已销毁的视图引用
		NokiaNotificationRepository.get().removeListener(notifRepoListener);
	}

	/** 后台线程计算后台进程数并回主线程刷新「后台管理」组件行（避免主线程 TCP 卡顿）。 */
	private void refreshBgCountAsync() {
		Context c = getContext();
		if (c == null) return;
		final Context appCtx = c.getApplicationContext();
		// 单独起线程，不占用 ICON_EXECUTOR：探测含 TCP，可能耗时较久，
		// 混进图标线程池会拖慢快捷栏图标的加载。
		new Thread(new Runnable() {
			@Override
			public void run() {
				NokiaBgManagerHelper.probeShizukuSync();
				final int count = NokiaBgManagerHelper.countBackgroundProcesses(appCtx);
				Activity activity = getActivity();
				if (activity == null) return;
				activity.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						cachedBgCount = count;
						// 只改这一行的文字：整体重建会连带重查内存/存储/音乐 Provider
						updateBgManagerRowText(getView());
					}
				});
			}
		}, "desktop-bg-count").start();
	}

	/**
	 * 只刷新「后台管理」组件行右侧的计数文字，不整体重建组件区。
	 * 计数是异步回来的，此时重建整区属于纯浪费（还会造成组件行闪烁/焦点跳动）。
	 */
	private void updateBgManagerRowText(View view) {
		LinearLayout notifArea = view.findViewById(R.id.notificationArea);
		if (notifArea == null) return;
		for (int i = 0; i < widgetItems.size() && i < notifArea.getChildCount(); i++) {
			NokiaWidgetItem item = widgetItems.get(i);
			if (item.type != NokiaWidgetItem.TYPE_BG_MANAGER) continue;
			View row = notifArea.getChildAt(i);
			TextView infoTv = row.findViewWithTag(TAG_WIDGET_INFO);
			if (infoTv != null) {
				infoTv.setText(getWidgetInfoText(item));
			}
		}
	}

	// ---- 构建快捷栏 ----

	private void loadShortcutBarAsync(View view) {
		Context c = getContext();
		if (c == null) return;
		long loadStart = System.currentTimeMillis();
		NokiaS60IconMap.loadFromDisk(c);
		long loadElapsed = System.currentTimeMillis() - loadStart;
		NokiaLog.i("Desktop", "S60 图标磁盘缓存加载耗时 " + loadElapsed + "ms");

		settingsStorage.getShortcutAppsAsync(new NokiaSettingsStorage.OnShortcutAppsLoaded() {
			@Override
			public void onLoaded(List<ShortcutApp> apps) {
				if (!isAdded() || getView() == null) return;
				NokiaLog.i("Desktop", "快捷栏配置就绪：" + apps.size() + " 项");
				rebuildShortcutBar(apps);
			}
		});
	}

	// ---- 通知条 ----

	/**
	 * 按通知仓储当前快照装配通知条：未读数 + 最新一条摘要。
	 * 显示条件：设置开关开、已授予通知使用权、当前有通知。三者任一不满足即隐藏。
	 */
	private void refreshNotifBar() {
		if (notifBar == null || !isAdded()) return;
		Context ctx = getContext();
		if (ctx == null) return;
		NokiaNotificationRepository repo = NokiaNotificationRepository.get();
		List<NokiaNotificationItem> items = repo.getItems();
		boolean granted = NokiaMusicSessionReader.isNotificationListenerEnabled(ctx);
		boolean show = NokiaSettingsStorage.isNotificationBarEnabled(ctx)
				&& granted && !items.isEmpty();
		if (show) {
			NokiaNotificationItem latest = repo.getLatest();
			int unread = repo.getUnreadCount();
			String countText = unread > 0
					? unread + " 条未读通知" : items.size() + " 条通知";
			String summary = latest != null ? latest.displayTitle() : "";
			if (latest != null && latest.text != null && latest.text.trim().length() > 0) {
				summary += "：" + latest.text;
			}
			notifBarText.setText(countText + " · " + summary);
			if (unread > 0) {
				notifBarBadge.setText(String.valueOf(unread));
				notifBarBadge.setVisibility(View.VISIBLE);
			} else {
				notifBarBadge.setVisibility(View.GONE);
			}
		}
		setNotifBarVisible(show);
	}

	/**
	 * 切换通知条可见性，并把通知条作为焦点条目插入/移出 focusTargets
	 * （位置：快捷项之后、组件区之前）。
	 * notifBarCount 是分区索引计算的单一数据源，必须与 focusTargets 实际内容同步变更。
	 */
	private void setNotifBarVisible(boolean visible) {
		if (notifBar == null) return;
		boolean was = notifBarCount == 1;
		if (was == visible) {
			notifBar.setVisibility(visible ? View.VISIBLE : View.GONE);
			return;
		}
		notifBarCount = visible ? 1 : 0;
		notifBar.setVisibility(visible ? View.VISIBLE : View.GONE);
		if (visible) {
			int at = Math.min(shortcutCount, focusTargets.size());
			if (!focusTargets.contains(notifBar)) {
				focusTargets.add(at, notifBar);
			}
		} else {
			focusTargets.remove(notifBar);
		}
		if (focusIndex >= focusTargets.size()) {
			setFocusIndex(Math.max(0, focusTargets.size() - 1));
		}
	}

	private void rebuildShortcutBar(List<ShortcutApp> apps) {
		View view = getView();
		if (view == null) return;
		LinearLayout container = view.findViewById(R.id.shortcutContainer);
		if (container == null) return;

		long buildStart = System.currentTimeMillis();
		container.removeAllViews();

		shortcutApps.clear();
		shortcutApps.addAll(apps);
		shortcutCount = apps.size();
		focusIndex = -1;
		selectedView = null;

		focusTargets.clear();

		if (apps.isEmpty()) {
			Context ctx = getContext();
			if (ctx != null) {
				TextView hint = new TextView(ctx);
				hint.setLayoutParams(new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.WRAP_CONTENT, NokiaDimens.dp(getResources(), 34)));
				hint.setGravity(Gravity.CENTER);
				hint.setText("（无快捷应用）");
				hint.setTextColor(0xFF888888);
				NokiaFontManager.textSize(hint, 10);
				container.addView(hint);
			}
		} else {
			for (int i = 0; i < apps.size(); i++) {
				LinearLayout cell = createShortcutCell(apps.get(i), i);
				if (cell != null) {
					container.addView(cell);
					focusTargets.add(cell);
				}
			}
		}

		// 通知条焦点（排在快捷项之后、组件区之前；notifBarCount=0 时无此条目）
		if (notifBarCount == 1 && notifBar != null && !focusTargets.contains(notifBar)) {
			focusTargets.add(notifBar);
		}

		// 收集组件区焦点（排在快捷项之后）
		collectWidgetTargets(view);

		// 追加便捷开关栏的所有单元格到焦点列表
		for (View cell : toggleCells) {
			if (cell != null) {
				focusTargets.add(cell);
			}
		}

		long buildElapsed = System.currentTimeMillis() - buildStart;
		NokiaLog.i("Desktop", "快捷栏已构建：" + apps.size() + " 项，共 " + focusTargets.size()
				+ " 个焦点，耗时 " + buildElapsed + "ms");

		view.post(() -> {
			if (!isAdded() || getContext() == null) return;
			if (focusTargets.size() > 0) {
				setFocusIndex(0);
			}
		});

		// 冻结角标不依赖 S60 图标扫描，先单独刷一次（后台查 PackageManager）
		refreshShortcutCellsAsync(container, false);

		Context ctx = getContext();
		if (ctx != null) {
			NokiaS60IconMap.initAsync(ctx, () -> {
				if (!isAdded() || getView() == null) return;
				refreshShortcutCellsAsync(container, true);
			});
		}
	}

	// ---- 组件区动态渲染 ----

	/**
	 * 注册音乐播放状态 ContentObserver（重复注册防抖）。
	 * <p>
	 * {@code registerContentObserver} 内部要先拿到 Provider 连接，对方进程不在时会触发
	 * 冷启动并同步等待（实测与 query 一样可阻塞 1s 以上），因此挪到后台线程执行。
	 * <p>
	 * 音乐刚被本应用的后台管理清理时<b>不注册</b>：注册一样会把它冷启动起来，等于用户
	 * 刚清完后台、桌面又把它拉起来。等 {@link #refreshMusicAsync()} 检测到它重新启动后再补注册。
	 */
	private void registerMusicPlaybackObserver() {
		Context ctx = getContext();
		if (ctx == null || musicObserverRegistered) return;
		if (NokiaBgManagerHelper.wasCleared(MUSIC_PKG)) {
			NokiaLog.i("Desktop", "音乐已被后台管理清理，暂不注册播放状态监听（避免冷启动）");
			return;
		}
		final Context appCtx = ctx.getApplicationContext();
		MUSIC_EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				try {
					appCtx.getContentResolver().registerContentObserver(
							MUSIC_PLAYBACK_URI, false, musicPlaybackObserver);
					musicObserverRegistered = true;
					NokiaLog.i("Desktop", "已注册音乐播放状态 ContentObserver");
				} catch (Exception e) {
					NokiaLog.w("Desktop", "注册音乐播放 ContentObserver 失败: " + e.getMessage());
				}
			}
		});
	}

	private void unregisterMusicPlaybackObserver() {
		Context ctx = getContext();
		if (ctx == null || !musicObserverRegistered) return;
		musicObserverRegistered = false;
		final Context appCtx = ctx.getApplicationContext();
		MUSIC_EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				try {
					appCtx.getContentResolver().unregisterContentObserver(musicPlaybackObserver);
				} catch (Exception ignored) {}
			}
		});
	}

	/** 仅重建音乐播放组件行（保持焦点不丢失、其余组件不动），用当前快照重绘。 */
	private void rebuildMusicWidgetRowOnly(View view) {
		LinearLayout notifArea = view.findViewById(R.id.notificationArea);
		if (notifArea == null) return;
		for (int i = 0; i < widgetItems.size(); i++) {
			if (widgetItems.get(i).type == NokiaWidgetItem.TYPE_MUSIC_PLAYER) {
				// 仅替换该行 View，并更新焦点列表对应项
				View newRow = createWidgetRow(widgetItems.get(i));
				if (newRow == null) continue;
				View oldRow = notifArea.getChildAt(i);
				if (oldRow != null) {
					notifArea.removeViewAt(i);
				}
				notifArea.addView(newRow, i);
				int focusIdx = shortcutCount + notifBarCount + i;
				if (focusIdx >= 0 && focusIdx < focusTargets.size()) {
					focusTargets.set(focusIdx, newRow);
				}
				NokiaLog.i("Desktop", "音乐播放组件行已刷新");
				break;
			}
		}
	}

	/** 重建桌面组件区：从 NokiaWidgetStorage 读取所有组件，动态创建行 View。 */
	private void rebuildWidgetArea(View view) {
		LinearLayout notifArea = view.findViewById(R.id.notificationArea);
		if (notifArea == null) return;

		// 同一次渲染共享一份实时数据快照：内存/存储/音乐原本每个组件行都独立查询一次
		//（音乐行甚至查 3 次 ContentProvider），都是主线程同步 Binder 调用，组件越多越慢。
		resetWidgetDataSnapshot();

		// 先清空旧 View（但保留其他非焦点子 View，如果有的话）
		notifArea.removeAllViews();
		widgetItems.clear();
		widgetItems.addAll(widgetStorage.getWidgets());
		widgetCount = widgetItems.size();

		if (widgetItems.isEmpty()) {
			Context c = getContext();
			if (c == null) return;
			// 无组件时显示提示
			TextView hint = new TextView(c);
			hint.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			hint.setPadding(NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 4),
					0, 0);
			hint.setText("无更多备忘");
			hint.setTextColor(0xFF888888);
			NokiaFontManager.textSize(hint, 10);
			notifArea.addView(hint);
			NokiaLog.i("Desktop", "组件区为空");
		} else {
			for (int i = 0; i < widgetItems.size(); i++) {
				View row = createWidgetRow(widgetItems.get(i));
				if (row != null) {
					notifArea.addView(row);
				}
			}
			NokiaLog.i("Desktop", "组件区已渲染 " + widgetItems.size() + " 个组件");
		}

		// 重建焦点列表（保留「快捷栏 + 通知条」头部，重建组件区部分）
		// 先清掉旧的组件区焦点（头部长度 = 快捷项 + 通知条）
		int headFocusCount = Math.min(focusTargets.size(), shortcutCount + notifBarCount);
		while (focusTargets.size() > headFocusCount) {
			focusTargets.remove(focusTargets.size() - 1);
		}
		// 头部重建后确保通知条焦点在位（notifBarCount=1 时），防御异步构建窗口内的缺席
		if (notifBarCount == 1 && notifBar != null && !focusTargets.contains(notifBar)) {
			focusTargets.add(Math.min(shortcutCount, focusTargets.size()), notifBar);
		}
		// 重新收集
		collectWidgetTargets(view);

		// 追加便捷开关栏的所有单元格到焦点列表
		for (View cell : toggleCells) {
			if (cell != null) {
				focusTargets.add(cell);
			}
		}

		// 如果当前焦点索引超出范围，复位
		if (focusIndex >= focusTargets.size()) {
			setFocusIndex(Math.max(0, focusTargets.size() - 1));
		}
	}

	// ---- 便捷开关栏 ----

	/** 构建开关单元格（根据 NokiaQuickToggleStorage 中启用的开关动态创建）。 */
	private void buildToggleBar() {
		if (quickToggleBar == null) return;
		List<View> oldToggleCells = new ArrayList<>(toggleCells);
		quickToggleBar.removeAllViews();
		toggleCells.clear();

		Context ctx = getContext();
		if (ctx == null) return;

		activeToggles.clear();
		activeToggles.addAll(NokiaQuickToggleStorage.getEnabledToggles(ctx));
		int count = activeToggles.size();

		if (count == 0) {
			if (quickToggleScroll != null) quickToggleScroll.setVisibility(View.GONE);
			if (quickToggleDivider != null) quickToggleDivider.setVisibility(View.GONE);
			toggleStates = new boolean[0];
			syncToggleFocusTargets(oldToggleCells);
			return;
		}

		if (quickToggleScroll != null) quickToggleScroll.setVisibility(View.VISIBLE);
		if (quickToggleDivider != null) quickToggleDivider.setVisibility(View.VISIBLE);

		if (toggleStates.length != count) {
			toggleStates = new boolean[count];
		}

		float fontScale = NokiaSettingsStorage.getFontScale(ctx);
		float toggleScale = 1.0f + (fontScale - 1.0f) * 0.6f;
		if (toggleScale < 0.8f) toggleScale = 0.8f;

		int cellHeight = NokiaDimens.dp(getResources(), Math.round(32 * toggleScale));
		boolean useWeight = count <= 4;
		int fixedCellWidth = NokiaDimens.dp(getResources(), Math.round(48 * toggleScale));
		int iconSize = Math.round(18 * toggleScale);
		toggleIconSizeDp = iconSize; // 存下来，renderToggleViews 重绘亮度图标时复用同一尺寸
		int dotSize = Math.max(3, Math.round(4 * toggleScale));
		int dotMarginTop = Math.max(1, Math.round(1 * toggleScale));

		for (int i = 0; i < count; i++) {
			NokiaQuickToggleItem item = activeToggles.get(i);
			LinearLayout cell = new LinearLayout(ctx);
			if (useWeight) {
				cell.setLayoutParams(new LinearLayout.LayoutParams(0, cellHeight, 1f));
			} else {
				cell.setLayoutParams(new LinearLayout.LayoutParams(fixedCellWidth, cellHeight));
			}
			cell.setOrientation(LinearLayout.VERTICAL);
			cell.setGravity(Gravity.CENTER);
			cell.setFocusable(true);
			cell.setClickable(true);

			// 图标（Material Icons 矢量字体）
			ImageView iv = new ImageView(ctx);
			iv.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), iconSize), NokiaDimens.dp(getResources(), iconSize)));
			iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
			iv.setImageDrawable(NokiaIcons.get(ctx, item.getIconUnicode(ctx), 0xFFFFFFFF, iconSize));
			iv.setTag("icon");
			cell.addView(iv);

			// 状态指示小圆点
			View dot = new View(ctx);
			LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), dotSize), NokiaDimens.dp(getResources(), dotSize));
			dotLp.setMargins(0, NokiaDimens.dp(getResources(), dotMarginTop), 0, 0);
			dot.setLayoutParams(dotLp);
			dot.setTag("dot");
			cell.addView(dot);

			final int index = i;
			cell.setOnClickListener(v -> {
				toggleItem(index);
			});

			quickToggleBar.addView(cell);
			toggleCells.add(cell);
		}
		syncToggleFocusTargets(oldToggleCells);
	}

	/**
	 * 把当前开关单元格同步回焦点列表。
	 * <p>
	 * 开关栏位于焦点列表末尾。buildToggleBar() 重建开关栏后，
	 * 旧的单元格 View 已被从 quickToggleBar 移除，但 focusTargets
	 * 里仍可能残留旧引用。此处精确移除旧引用并追加新单元格，
	 * 避免方向键高亮/点击落到不可见的旧 View 上。
	 */
	private void syncToggleFocusTargets(List<View> oldCells) {
		boolean wasFocusedOnToggle = focusIndex >= 0 && focusIndex < focusTargets.size()
				&& oldCells.contains(focusTargets.get(focusIndex));
		for (View old : oldCells) {
			focusTargets.remove(old);
		}
		for (View cell : toggleCells) {
			if (cell != null) {
				focusTargets.add(cell);
			}
		}
		if (focusIndex >= focusTargets.size()) {
			setFocusIndex(Math.max(0, focusTargets.size() - 1));
		} else if (wasFocusedOnToggle && focusIndex >= 0) {
			setFocusIndex(focusIndex);
		}
	}

	/** 从系统同步所有已启用开关的真实状态并刷新视图。 */
	private void syncToggleStatesFromSystem() {
		if (activeToggles.isEmpty()) return;
		Context ctx = getContext();
		if (ctx == null) return;
		Context appCtx = ctx.getApplicationContext();
		if (toggleStates.length != activeToggles.size()) {
			toggleStates = new boolean[activeToggles.size()];
		}
		for (int i = 0; i < activeToggles.size(); i++) {
			toggleStates[i] = NokiaQuickToggleManager.isToggleOn(appCtx, activeToggles.get(i).type);
		}
		renderToggleViews();
	}

	/** 纯视图渲染：根据当前内存中的 toggleStates[] 刷新单元格图标和指示灯（0 延迟）。 */
	private void renderToggleViews() {
		Context ctx = getContext();
		for (int i = 0; i < toggleCells.size(); i++) {
			View cell = toggleCells.get(i);
			if (cell == null) continue;
			boolean on = (i < toggleStates.length) && toggleStates[i];
			NokiaQuickToggleItem item = (i < activeToggles.size()) ? activeToggles.get(i) : null;

			ImageView iv = cell.findViewWithTag("icon");
			if (iv != null) {
				// 亮度图标随档位变化（低/中/高/自动），需按当前档位重新取字符。
				// 亮度不是二值开关，档位由图标本身表达，故图标恒为全不透明
				// ——否则最暗档会被压到 0.35 透明度，几乎看不清。
				if (item != null && item.type == NokiaQuickToggleItem.TYPE_BRIGHTNESS) {
					if (ctx != null) {
						iv.setImageDrawable(NokiaIcons.get(ctx, item.getIconUnicode(ctx),
								0xFFFFFFFF, toggleIconSizeDp));
					}
					iv.setAlpha(1.0f);
				} else {
					iv.setAlpha(on ? 1.0f : 0.35f);
				}
			}
			View dot = cell.findViewWithTag("dot");
			if (dot != null) {
				android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
				gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
				gd.setColor(on ? 0xFF4FC3F7 : 0xFF445566);
				dot.setBackground(gd);
			}
		}
	}

	/** 切换指定索引的开关状态（0 毫秒乐观更新图标 + 执行）。 */
	private void toggleItem(int index) {
		if (index < 0 || index >= activeToggles.size() || index >= toggleStates.length) return;
		final Context ctx = getContext();
		if (ctx == null) return;
		final NokiaQuickToggleItem item = activeToggles.get(index);

		// 电源类操作（关机/重启/Recovery/Fastboot）不可逆且会丢失未保存数据：
		// 先二次确认，且不做状态乐观更新（它们本就没有持续的开关态）。
		if (NokiaQuickToggleItem.isPowerAction(item.type)) {
			confirmPowerAction(item);
			return;
		}

		// 亮度是四档循环（低→中→高→自动），不是二值开关：无法用 !state 预估下一态，
		// 因此不做乐观更新，由 toggleBrightness 在主线程同步推进档位后再统一渲染。
		if (item.type == NokiaQuickToggleItem.TYPE_BRIGHTNESS) {
			// toggleBrightness 忽略传入的 targetOn，自行切到下一档并同步写入档位缓存
			NokiaQuickToggleManager.toggle(ctx, item.type, false);
			// 此刻缓存已是新档位，渲染出来的图标与 Toast 提示才一致
			toggleStates[index] = NokiaQuickToggleManager.isBrightnessHigh(ctx);
			renderToggleViews();
			return;
		}

		final boolean targetOn = !toggleStates[index];

		// 1. 立即乐观更新内存状态并刷新 UI，消除点击延迟感
		toggleStates[index] = targetOn;
		renderToggleViews();

		// 2. 异步执行切换链路
		NokiaQuickToggleManager.toggle(ctx, item.type, targetOn);
	}

	/**
	 * 电源类操作的二次确认弹窗。
	 * 复用 {@link NokiaOptionsDialog}：它已接入用户按键映射（Dialog 是独立 Window，
	 * Activity 的按键分发对其无效）与当前主题配色，无需另造一个确认框。
	 */
	private void confirmPowerAction(final NokiaQuickToggleItem item) {
		NokiaLog.i("Desktop", "电源操作二次确认: " + item.name + " type=" + item.type);
		List<NokiaOptionsDialog.OptionItem> options = new ArrayList<>();
		options.add(new NokiaOptionsDialog.OptionItem(
				item.getIconUnicode(), "确认" + item.name, true, false,
				new Runnable() {
					@Override
					public void run() {
						Context ctx = getContext();
						if (ctx == null) return;
						NokiaQuickToggleManager.toggle(ctx, item.type, true);
					}
				}));
		options.add(new NokiaOptionsDialog.OptionItem(
				NokiaIcons.ICON_CLOSE, "取消", true, false, null));
		NokiaOptionsDialog.show(getParentFragmentManager(), "确认" + item.name, options);
	}

	// ---- 广播接收器 ----

	private void registerToggleReceiver() {
		if (receiverRegistered) return;
		Context ctx = getContext();
		if (ctx == null) return;
		toggleStateReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (!isAdded() || quickToggleBar == null) return;
				syncToggleStatesFromSystem();
				// 冻结状态变化后快捷栏角标也要跟着变（后台查 PackageManager）
				if (NokiaFreezeManager.ACTION_FREEZE_STATE_CHANGED.equals(intent.getAction())) {
					View v = getView();
					if (v != null) {
						refreshShortcutCellsAsync(v.findViewById(R.id.shortcutContainer), false);
					}
				}
			}
		};
		IntentFilter filter = new IntentFilter();
		filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
		filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
		filter.addAction("android.location.MODE_CHANGED");
		filter.addAction(NokiaFreezeManager.ACTION_FREEZE_STATE_CHANGED);
		ctx.registerReceiver(toggleStateReceiver, filter);
		receiverRegistered = true;
		NokiaLog.i("Desktop", "已注册开关栏广播接收器");
	}

	private void unregisterToggleReceiver() {
		if (toggleStateReceiver != null && receiverRegistered) {
			try {
				Context ctx = getContext();
				if (ctx != null) {
					ctx.unregisterReceiver(toggleStateReceiver);
				}
			} catch (Exception ignored) {}
			receiverRegistered = false;
			NokiaLog.i("Desktop", "已注销开关栏广播接收器");
		}
	}

	/** 创建单个组件行 View。内存/存储带进度条，其余类型仅文字。 */
	private View createWidgetRow(NokiaWidgetItem item) {
		switch (item.type) {
			case NokiaWidgetItem.TYPE_MEMORY:
				return createWidgetRowWithProgress(item, 0xFF4FC3F7, getMemoryUsedRatio(), getMemoryPercentText());
			case NokiaWidgetItem.TYPE_STORAGE:
				return createWidgetRowWithProgress(item, 0xFF81C784, getStorageUsedRatio(), getStoragePercentText());
			case NokiaWidgetItem.TYPE_MUSIC_PLAYER:
				return createMusicPlayerWidgetRow(item);
			default:
				return createWidgetRowSimple(item);
		}
	}

	/** 创建带进度条的组件行（内存/存储）。 */
	private View createWidgetRowWithProgress(NokiaWidgetItem item, int fillColor, float usedRatio, String percentText) {
		Context ctx = getContext();
		if (ctx == null) return null;
		LinearLayout row = new LinearLayout(ctx);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3),
				NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3));
		row.setFocusable(true);
		row.setClickable(true);

		// 图标（统一使用 Material Icons 矢量字体）
		ImageView iv = new ImageView(ctx);
		int iconSize = NokiaDimens.dp(getResources(), 20);
		iv.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		String unicode = item.getTypeIconUnicode();
		if (!TextUtils.isEmpty(unicode)) {
			iv.setImageDrawable(NokiaIcons.get(ctx, unicode, 0xFFFFFFFF, 20));
		}
		iv.setPadding(0, 0, NokiaDimens.dp(getResources(), 4), 0);
		row.addView(iv);

		// 标签文字，弹性占满剩余空间
		TextView labelTv = new TextView(ctx);
		LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
		labelTv.setLayoutParams(labelLp);
		labelTv.setText(item.label);
		labelTv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(labelTv, 11);
		labelTv.setSingleLine(true);
		row.addView(labelTv);

		// 进度条背景轨 50dp × 4dp，圆角 2dp
		int barW = NokiaDimens.dp(getResources(), 50);
		int barH = NokiaDimens.dp(getResources(), 4);
		LinearLayout barTrack = new LinearLayout(ctx);
		LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(barW, barH);
		trackLp.gravity = Gravity.CENTER_VERTICAL;
		barTrack.setLayoutParams(trackLp);
		barTrack.setOrientation(LinearLayout.HORIZONTAL);
		// 背景轨：半透明白色 #26FFFFFF，圆角 2dp
		android.graphics.drawable.GradientDrawable trackBg = new android.graphics.drawable.GradientDrawable();
		trackBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		trackBg.setCornerRadius(NokiaDimens.dp(getResources(), 2));
		trackBg.setColor(0x26FFFFFF);
		barTrack.setBackground(trackBg);
		barTrack.setPadding(0, 0, 0, 0);

		// 进度条填充（子 View，动态宽度 = usedRatio * 50dp）
		View barFill = new View(ctx);
		int fillW = Math.max(0, (int) (usedRatio * barW));
		barFill.setLayoutParams(new LinearLayout.LayoutParams(fillW, barH));
		android.graphics.drawable.GradientDrawable fillBg = new android.graphics.drawable.GradientDrawable();
		fillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		fillBg.setCornerRadius(NokiaDimens.dp(getResources(), 2));
		fillBg.setColor(fillColor);
		barFill.setBackground(fillBg);
		barTrack.addView(barFill);
		row.addView(barTrack);

		// 百分比文字 9sp，进度条右侧 3dp
		TextView percentTv = new TextView(ctx);
		LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
		pctLp.setMargins(NokiaDimens.dp(getResources(), 3), 0, 0, 0);
		percentTv.setLayoutParams(pctLp);
		percentTv.setText(percentText);
		percentTv.setTextColor(0x80FFFFFF);
		NokiaFontManager.textSize(percentTv, 9);
		percentTv.setSingleLine(true);
		row.addView(percentTv);

		setupWidgetRowClick(row, item);
		return row;
	}

	/** 创建「正在播放」音乐组件行（图标 + 歌名/歌手 + 歌词 + 进度条）。 */
	private View createMusicPlayerWidgetRow(NokiaWidgetItem item) {
		Context ctx = getContext();
		if (ctx == null) return null;
		MusicSnapshot music = getMusicSnapshot();
		LinearLayout row = new LinearLayout(ctx);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3),
				NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3));
		row.setFocusable(true);
		row.setClickable(true);

		// 图标（Material Icons 音符）
		ImageView iv = new ImageView(ctx);
		int iconSize = NokiaDimens.dp(getResources(), 20);
		iv.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		iv.setImageDrawable(NokiaIcons.get(ctx, NokiaIcons.ICON_MUSIC_NOTE, 0xFFFFFFFF, 20));
		iv.setPadding(0, 0, NokiaDimens.dp(getResources(), 5), 0);
		row.addView(iv);

		// 右侧：两行文本（歌名/歌手 + 歌词）
		LinearLayout textCol = new LinearLayout(ctx);
		textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		textCol.setOrientation(LinearLayout.VERTICAL);

		// 第一行：歌名 - 歌手 / 播放状态
		TextView titleTv = new TextView(ctx);
		titleTv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		titleTv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(titleTv, 11);
		titleTv.setSingleLine(true);
		titleTv.setEllipsize(TextUtils.TruncateAt.END);
		titleTv.setText(music.title);
		textCol.addView(titleTv);

		// 第二行：当前歌词 / 占位
		TextView lyricTv = new TextView(ctx);
		lyricTv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		lyricTv.setTextColor(0xFFAAAAAA);
		NokiaFontManager.textSize(lyricTv, 9);
		lyricTv.setSingleLine(true);
		lyricTv.setEllipsize(TextUtils.TruncateAt.END);
		lyricTv.setText(music.lyric);
		textCol.addView(lyricTv);

		// 进度条（横条）
		int barW = NokiaDimens.dp(getResources(), 40);
		int barH = NokiaDimens.dp(getResources(), 3);
		LinearLayout barTrack = new LinearLayout(ctx);
		barTrack.setLayoutParams(new LinearLayout.LayoutParams(barW, barH));
		barTrack.setOrientation(LinearLayout.HORIZONTAL);
		android.graphics.drawable.GradientDrawable trackBg = new android.graphics.drawable.GradientDrawable();
		trackBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		trackBg.setCornerRadius(NokiaDimens.dp(getResources(), 1));
		trackBg.setColor(0x26FFFFFF);
		barTrack.setBackground(trackBg);

		View barFill = new View(ctx);
		int fillW = Math.max(0, (int) (music.progress * barW));
		barFill.setLayoutParams(new LinearLayout.LayoutParams(fillW, barH));
		android.graphics.drawable.GradientDrawable fillBg = new android.graphics.drawable.GradientDrawable();
		fillBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		fillBg.setCornerRadius(NokiaDimens.dp(getResources(), 1));
		fillBg.setColor(0xFF4FC3F7);
		barFill.setBackground(fillBg);
		barTrack.addView(barFill);

		LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(barW, barH);
		barLp.leftMargin = NokiaDimens.dp(getResources(), 5);
		barTrack.setLayoutParams(barLp);
		row.addView(textCol);
		row.addView(barTrack);

		setupWidgetRowClick(row, item);
		return row;
	}

	/** 音乐播放状态快照：一次播放状态读取的结果，供同一行的标题/歌词/进度复用。 */
	private static final class MusicSnapshot {
		String title = "音乐播放器（未播放）";
		String lyric = "暂无歌词";
		float progress;
		/** 是否正在播放（MediaSession 通道有效时才有意义）。 */
		boolean playing;
	}

	/**
	 * 清空实时数据快照，下次取用时重新查询（每次组件区渲染/音乐状态变化时调用）。
	 * 注意：不清除 musicSnapshot——音乐状态由 {@link #refreshMusicAsync()} 单独异步维护，
	 * 这里清掉会让每次重建组件区都先闪回「未播放」再跳回歌名。
	 */
	private void resetWidgetDataSnapshot() {
		memTotal = -1;
		storageTotal = -1;
		ipText = null;
	}

	/**
	 * 取音乐播放状态快照：<b>只返回已有缓存或默认值，绝不发起任何跨进程查询</b>。
	 * <p>
	 * 真实状态由 {@link #refreshMusicAsync()} 在后台线程补齐后局部刷新。
	 * 原先这里同步 query ContentProvider，而 Provider 所在进程不在时 AMS 会冷启动它，
	 * 主线程同步等待——实测「清理后台 → 返回桌面」时这一下要 1.2s（Skipped 75 frames /
	 * 单帧 1303ms），正是返回桌面卡顿的根因。
	 */
	private MusicSnapshot getMusicSnapshot() {
		if (musicSnapshot != null) {
			return musicSnapshot;
		}
		MusicSnapshot s = new MusicSnapshot();
		musicSnapshot = s;
		return s;
	}

	/** 组件区是否包含「正在播放」组件（没有就完全不必读播放状态）。 */
	private boolean hasMusicWidget() {
		for (int i = 0; i < widgetItems.size(); i++) {
			if (widgetItems.get(i).type == NokiaWidgetItem.TYPE_MUSIC_PLAYER) return true;
		}
		return false;
	}

	/**
	 * 后台线程刷新音乐播放状态，回来后只重建音乐组件行。
	 * <p>
	 * 读取顺序：
	 * <ol>
	 *   <li>MediaSession（{@link NokiaMusicSessionReader}）——只返回已注册的 session，
	 *       拿不到就是没在播放，<b>不会把对方进程冷启动起来</b>；</li>
	 *   <li>MediaSession 报告「正在播放」时，再查一次 Provider 补歌词——此时对方进程
	 *       必然存活（有前台服务），查询是毫秒级，不会冷启动；</li>
	 *   <li>MediaSession 不可用（未授予通知使用权 / API &lt; 21）时，回退到 Provider 查询。
	 *       仍在后台线程，主线程不卡；且只有确认对方进程存活时才查——已死就不查，
	 *       否则会把刚清理掉的音乐重新冷启动起来。</li>
	 * </ol>
	 * <p>
	 * 若音乐是本应用的后台管理刚清理掉的，结论已知（已停止），主线程立刻按「未播放」渲染，
	 * 后台只用 {@code ps -A} 确认它是否真的还没起来，全程不发任何跨进程查询。
	 */
	private void refreshMusicAsync() {
		if (!hasMusicWidget()) return;
		Context c = getContext();
		if (c == null) return;
		final Context appCtx = c.getApplicationContext();
		final boolean killedByUs = NokiaBgManagerHelper.wasCleared(MUSIC_PKG);
		if (killedByUs) {
			// 我们刚杀掉它 → 它一定没在播放。先把行刷成「未播放」，不必等后台线程
			musicSnapshot = new MusicSnapshot();
			View v = getView();
			if (v != null) rebuildMusicWidgetRowOnly(v);
		}
		final int seq = ++musicRefreshSeq;
		MUSIC_EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				MusicSnapshot s;
				boolean revived = false;
				if (killedByUs) {
					if (NokiaBgManagerHelper.isPackageAlive(appCtx, MUSIC_PKG)) {
						// 它又活过来了：清掉标记，走正常读取
						NokiaBgManagerHelper.unmarkCleared(MUSIC_PKG);
						revived = true;
						s = buildMusicSnapshot(appCtx);
					} else {
						s = new MusicSnapshot();
					}
				} else {
					s = buildMusicSnapshot(appCtx);
				}
				final boolean needRegisterObserver = revived;
				// 只应用最后一次刷新的结果，避免多次刷新乱序覆盖
				if (seq != musicRefreshSeq) return;
				Activity activity = getActivity();
				if (activity == null) return;
				activity.runOnUiThread(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						musicSnapshot = s;
						rebuildMusicWidgetRowOnly(getView());
						// 音乐重启后原先的观察者注册已随对方进程失效，补注册一次
						if (needRegisterObserver) registerMusicPlaybackObserver();
					}
				});
			}
		});
	}

	/**
	 * 提示授予「通知使用权」（桌面首次出现音乐组件时弹一次）。
	 * <p>
	 * 音乐组件读取播放状态首选 MediaSession 通道（不冷启动对方进程），它依赖通知使用权；
	 * 未授予就只能退化到 ContentProvider 查询，而查询会把音乐进程冷启动起来。
	 * 这属于需要用户授权的能力，必须主动弹窗说明并给一键入口。
	 */
	private void maybePromptNotificationAccess() {
		if (notifyAccessPromptShown || !hasMusicWidget()) return;
		Context ctx = getContext();
		if (ctx == null) return;
		if (NokiaSettingsStorage.isNotifyAccessPromptDisabled(ctx)) return;
		if (NokiaMusicSessionReader.isNotificationListenerEnabled(ctx)) return;
		notifyAccessPromptShown = true;
		NokiaLog.i("Desktop", "弹出通知使用权授予提示");
		NokiaMusicSessionReader.showGrantPrompt(getParentFragmentManager(), ctx, true);
	}

	/** 后台线程构建音乐快照：MediaSession 优先，不可用时回退 Provider 查询。 */
	private MusicSnapshot buildMusicSnapshot(Context appCtx) {
		NokiaMusicSessionReader.MusicState st = NokiaMusicSessionReader.read(appCtx, MUSIC_PKG);
		if (st != null && st.hasData()) {
			MusicSnapshot s = new MusicSnapshot();
			s.playing = st.playing;
			s.title = st.title
					+ (TextUtils.isEmpty(st.artist) ? "" : " - " + st.artist)
					+ (st.playing ? "" : "  [暂停]");
			s.progress = st.durationMs > 0 ? (float) st.positionMs / st.durationMs : 0f;
			// MediaSession 的 metadata 不含歌词，只在播放中补查（此时进程必在，不会冷启动）
			s.lyric = st.playing ? queryMusicProviderSnapshot(appCtx).lyric : "暂无歌词";
			return s;
		}
		// 回退 Provider：query 在对方进程不在时会把它冷启动起来。
		// 能判断存活（ps -A / getRunningAppProcesses）就先判一次，已死直接按未播放返回。
		if (!NokiaBgManagerHelper.isPackageAlive(appCtx, MUSIC_PKG)) {
			NokiaLog.i("Desktop", "音乐进程未运行，跳过播放状态查询（避免冷启动）");
			return new MusicSnapshot();
		}
		return queryMusicProviderSnapshot(appCtx);
	}

	/**
	 * 查询音乐 Provider 拿播放状态（<b>只能在后台线程调用</b>）。
	 * 标题/歌词/进度一次查完，原先三者各查一次等于发 3 次跨进程查询。
	 */
	private MusicSnapshot queryMusicProviderSnapshot(Context ctx) {
		MusicSnapshot s = new MusicSnapshot();
		Cursor c = null;
		try {
			c = ctx.getContentResolver().query(MUSIC_PLAYBACK_URI, null, null, null, null);
			if (c == null || !c.moveToFirst()) return s;
			int iTitle = c.getColumnIndex("title");
			int iArtist = c.getColumnIndex("artist");
			int iPlaying = c.getColumnIndex("is_playing");
			int iLyric = c.getColumnIndex("lyric_text");
			int iPos = c.getColumnIndex("position_ms");
			int iDur = c.getColumnIndex("duration_ms");
			if (iTitle >= 0) {
				String title = c.getString(iTitle);
				String artist = iArtist >= 0 ? c.getString(iArtist) : null;
				if (title != null && !title.isEmpty()) {
					s.playing = iPlaying >= 0 && c.getInt(iPlaying) == 1;
					s.title = title
							+ (artist != null && !artist.isEmpty() ? " - " + artist : "")
							+ (s.playing ? "" : "  [暂停]");
				}
			}
			if (iLyric >= 0) {
				String lyric = c.getString(iLyric);
				if (lyric != null && !lyric.isEmpty()) s.lyric = lyric;
			}
			if (iPos >= 0 && iDur >= 0) {
				long dur = c.getLong(iDur);
				if (dur > 0) s.progress = (float) c.getLong(iPos) / dur;
			}
		} catch (Exception e) {
			NokiaLog.w("Desktop", "读取音乐播放状态失败: " + e.getMessage());
		} finally {
			if (c != null) c.close();
		}
		return s;
	}

	/** 创建无进度条的普通组件行（日历/使用时长/可编辑类型）。 */
	private View createWidgetRowSimple(NokiaWidgetItem item) {
		Context ctx = getContext();
		if (ctx == null) return null;
		LinearLayout row = new LinearLayout(ctx);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3),
				NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3));
		row.setFocusable(true);
		row.setClickable(true);

		// 图标（内置类型统一使用 Material Icons 矢量字体）
		ImageView iv = new ImageView(ctx);
		int iconSize = NokiaDimens.dp(getResources(), 20);
		iv.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		String unicode = item.getTypeIconUnicode();
		if (!TextUtils.isEmpty(unicode)) {
			iv.setImageDrawable(NokiaIcons.get(ctx, unicode, 0xFFFFFFFF, 20));
		}
		if (item.type == NokiaWidgetItem.TYPE_QS_TILE && !TextUtils.isEmpty(item.value)) {
			try {
				String[] parts = item.value.split("/");
				if (parts.length == 2) {
					ComponentName cn = new ComponentName(parts[0], parts[1]);
					PackageManager pm = ctx.getPackageManager();
					ServiceInfo si = pm.getServiceInfo(cn, 0);
					Drawable d = si.loadIcon(pm);
					if (d == null && si.applicationInfo != null) d = si.applicationInfo.loadIcon(pm);
					if (d != null) iv.setImageDrawable(d);
				}
			} catch (Exception ignored) {}
		}
		iv.setPadding(0, 0, NokiaDimens.dp(getResources(), 5), 0);
		row.addView(iv);

		// 标签文字
		TextView labelTv = new TextView(ctx);
		labelTv.setLayoutParams(new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		labelTv.setText(getWidgetLabel(item));
		labelTv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(labelTv, 11);
		labelTv.setSingleLine(true);
		row.addView(labelTv);

		// 右侧信息文字
		TextView infoTv = new TextView(ctx);
		infoTv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		infoTv.setTextColor(0xFFAAAAAA);
		NokiaFontManager.textSize(infoTv, 10);
		infoTv.setGravity(Gravity.END);
		infoTv.setSingleLine(true);
		infoTv.setText(getWidgetInfoText(item));
		// 打 tag：后台管理计数等异步数据回来后可只改这一处文字，无需整区重建
		infoTv.setTag(TAG_WIDGET_INFO);
		row.addView(infoTv);

		setupWidgetRowClick(row, item);
		return row;
	}

	/** 获取组件主标签文字。锁屏组件动态显示「按下XX键锁屏」（XX 为当前绑定的锁屏键名，非 keycode）。 */
	private String getWidgetLabel(NokiaWidgetItem item) {
		if (item.type != NokiaWidgetItem.TYPE_LOCK_SCREEN) return item.label;
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		NokiaKeyBinding kb = host != null ? host.getKeyBinding() : null;
		int kc = kb != null ? kb.getKeyCode(NokiaKeyBinding.ACTION_LOCK_SCREEN)
				: KeyEvent.KEYCODE_UNKNOWN;
		String keyName = NokiaKeyBinding.keyName(kc);
		NokiaLog.i("Desktop", "锁屏组件提示文字: 按下" + keyName + "键锁屏");
		return "按下" + keyName + "键锁屏";
	}

	/** 获取组件右侧展示信息（无进度条类型）。 */
	private String getWidgetInfoText(NokiaWidgetItem item) {
		switch (item.type) {
			case NokiaWidgetItem.TYPE_CALENDAR:
				return getCalendarText();
			case NokiaWidgetItem.TYPE_USAGE:
				return getUsageText();
			case NokiaWidgetItem.TYPE_LOCK_SCREEN:
				return ""; // 提示文字已在主标签中，右侧留空
			case NokiaWidgetItem.TYPE_BG_MANAGER:
				if (!NokiaBgManagerHelper.isBgManagerAvailable()) {
					return "未激活";
				}
				if (cachedBgCount < 0) {
					return "…";
				}
				return cachedBgCount + " 个后台";
			case NokiaWidgetItem.TYPE_IP:
				return getWifiIpAddress();
			case NokiaWidgetItem.TYPE_QS_TILE:
				return "[快捷]";
			default:
				return item.getTypeTag();
		}
	}

	/**
	 * 获取 WiFi IPv4 地址（同一次渲染内缓存）。
	 * 组件区可能有多个 IP 组件，逐个查 WifiManager 会重复发起 Binder 调用。
	 */
	private String getWifiIpAddress() {
		if (ipText == null) {
			ipText = queryWifiIpAddress();
		}
		return ipText;
	}

	/** 实际查询 WiFi IPv4 地址；未连接 WiFi 或无 IP 时返回 "未连接"。 */
	private String queryWifiIpAddress() {
		try {
			Context ctx = getContext();
			if (ctx == null) return "未连接";
			WifiManager wifi = (WifiManager) ctx.getApplicationContext()
					.getSystemService(Context.WIFI_SERVICE);
			if (wifi == null) return "未连接";
			WifiInfo info = wifi.getConnectionInfo();
			if (info == null) return "未连接";
			int ip = info.getIpAddress();
			if (ip == 0) return "未连接";
			return String.format(Locale.US, "%d.%d.%d.%d",
					ip & 0xff, (ip >> 8) & 0xff,
					(ip >> 16) & 0xff, (ip >> 24) & 0xff);
		} catch (Exception e) {
			return "未连接";
		}
	}

	// ---- 进度条数据 ----

	/**
	 * 取内存信息快照（同一次渲染只查一次）。
	 * 比例与百分比文字原本各自调一次 {@code ActivityManager.getMemoryInfo()}，
	 * 即一个内存组件行发两次 Binder 调用。
	 */
	private void queryMemoryInfo() {
		if (memTotal >= 0) return;
		memTotal = 0;
		memAvail = 0;
		try {
			Context ctx = getContext();
			if (ctx == null) return;
			ActivityManager am = (ActivityManager) ctx
					.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return;
			ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
			am.getMemoryInfo(mi);
			memTotal = mi.totalMem;
			memAvail = mi.availMem;
		} catch (Exception e) {
			memTotal = 0;
		}
	}

	/** 返回已用比例 [0,1]。 */
	private float getMemoryUsedRatio() {
		queryMemoryInfo();
		if (memTotal <= 0) return 0;
		return (float) (memTotal - memAvail) / memTotal;
	}

	/** 返回已用百分比文字。 */
	private String getMemoryPercentText() {
		queryMemoryInfo();
		if (memTotal <= 0) return "";
		return (int) ((memTotal - memAvail) * 100 / memTotal) + "%";
	}

	/**
	 * 取存储信息快照（同一次渲染只构造一次 {@link StatFs}）。
	 * 比例与百分比文字原本各自构造一次 StatFs，即一次 statvfs 系统调用 ×2。
	 */
	private void queryStorageInfo() {
		if (storageTotal >= 0) return;
		storageTotal = 0;
		storageAvail = 0;
		try {
			File dataDir = Environment.getDataDirectory();
			StatFs stat = new StatFs(dataDir.getPath());
			if (Build.VERSION.SDK_INT >= 18) {
				storageTotal = stat.getBlockCountLong() * stat.getBlockSizeLong();
				storageAvail = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
			} else {
				storageTotal = (long) stat.getBlockCount() * stat.getBlockSize();
				storageAvail = (long) stat.getAvailableBlocks() * stat.getBlockSize();
			}
		} catch (Exception e) {
			storageTotal = 0;
		}
	}

	private float getStorageUsedRatio() {
		queryStorageInfo();
		if (storageTotal <= 0) return 0;
		return (float) (storageTotal - storageAvail) / storageTotal;
	}

	private String getStoragePercentText() {
		queryStorageInfo();
		if (storageTotal <= 0) return "";
		return (int) ((storageTotal - storageAvail) * 100 / storageTotal) + "%";
	}

	// ---- 不可编辑类型实时数据 ----

	private String getCalendarText() {
		try {
			Calendar cal = Calendar.getInstance();
			String[] weekDays = {"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"};
			int dow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0=Sunday
			String weekDay = weekDays[dow];
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
			return weekDay + " " + sdf.format(cal.getTime());
		} catch (Exception e) {
			return "日历";
		}
	}

	private String getUsageText() {
		try {
			long uptime = android.os.SystemClock.elapsedRealtime() / 1000;
			long hours = uptime / 3600;
			long minutes = (uptime % 3600) / 60;
			if (hours > 0) {
				return hours + "小时" + minutes + "分";
			}
			return minutes + "分钟";
		} catch (Exception e) {
			return "使用时长";
		}
	}

	/** 设置组件行的点击行为。 */
	private void setupWidgetRowClick(LinearLayout row, NokiaWidgetItem item) {
		switch (item.type) {
			case NokiaWidgetItem.TYPE_URL:
				row.setOnClickListener(v -> {
					String url = item.value;
					if (url != null && !url.isEmpty()) {
						NokiaLog.i("Desktop", "打开网址组件: " + url);
						try {
							Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							startActivity(intent);
						} catch (Exception e) {
							NokiaLog.e("Desktop", "打开网址失败: " + url, e);
						}
					}
				});
				break;
			case NokiaWidgetItem.TYPE_APP:
			case NokiaWidgetItem.TYPE_ACTIVITY:
				row.setOnClickListener(v -> {
					String pkgAndCls = item.value;
					if (pkgAndCls != null && pkgAndCls.contains("/")) {
						String[] parts = pkgAndCls.split("/", 2);
						NokiaLog.i("Desktop", "打开应用组件: " + pkgAndCls);
						try {
							Intent intent = new Intent(Intent.ACTION_MAIN);
							intent.setClassName(parts[0], parts[1]);
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							startActivity(intent);
						} catch (Exception e) {
							NokiaLog.e("Desktop", "打开应用失败: " + pkgAndCls, e);
						}
					}
				});
				break;
			case NokiaWidgetItem.TYPE_CALENDAR:
				row.setOnClickListener(v -> {
					NokiaLog.i("Desktop", "打开日历");
					try {
						Intent intent = new Intent(Intent.ACTION_MAIN);
						intent.addCategory(Intent.CATEGORY_APP_CALENDAR);
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						startActivity(intent);
					} catch (Exception e) {
						// 没有日历应用时降级为通用 VIEW
						NokiaLog.w("Desktop", "无日历应用，尝试通用打开");
						try {
							Intent fallback = new Intent(Intent.ACTION_VIEW);
							fallback.setData(android.provider.CalendarContract.CONTENT_URI);
							fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							startActivity(fallback);
						} catch (Exception e2) {
							NokiaLog.e("Desktop", "打开日历失败", e2);
						}
					}
				});
				break;
			case NokiaWidgetItem.TYPE_LOCK_SCREEN:
				// 锁屏组件：点击执行一键锁屏（需设备管理员权限，未授权跳转系统激活页）
				row.setOnClickListener(v -> {
					NokiaLog.i("Desktop", "锁屏组件点击：执行锁屏");
					Context c = getContext();
					if (c != null) {
						NokiaLockScreen.lock(c);
					}
				});
				break;
			case NokiaWidgetItem.TYPE_BG_MANAGER:
				// 后台管理组件：点击打开后台管理窗口（运行/受保护页签 + 清除）
				row.setOnClickListener(v -> {
					NokiaLog.i("Desktop", "后台管理组件点击：打开后台窗口");
					if (getActivity() instanceof NokiaDesktopActivity) {
						((NokiaDesktopActivity) getActivity())
								.openFragment(new NokiaBackgroundManagerFragment());
					}
				});
				break;
			case NokiaWidgetItem.TYPE_IP:
				// IP地址组件：点击刷新 IP 并复制到剪贴板
				row.setOnClickListener(v -> {
					Context c = getContext();
					if (c == null) return;
					String ip = queryWifiIpAddress();
					if (!"未连接".equals(ip)) {
						ClipboardManager cm = (ClipboardManager) c
								.getSystemService(Context.CLIPBOARD_SERVICE);
						if (cm != null) {
							cm.setPrimaryClip(ClipData.newPlainText("IP", ip));
						}
					}
					Toast.makeText(c,
							"未连接".equals(ip) ? "WiFi未连接" : "已复制: " + ip,
							Toast.LENGTH_SHORT).show();
					NokiaLog.i("Desktop", "IP组件点击: ip=" + ip);
					rebuildWidgetArea(getView());
				});
				break;
			case NokiaWidgetItem.TYPE_QS_TILE:
				// 快捷开关组件：触发已绑定的第三方 QS Tile
				row.setOnClickListener(v -> {
					triggerQsTile(item);
				});
				break;
			case NokiaWidgetItem.TYPE_MUSIC_PLAYER:
				// 正在播放组件：确认/点击进入音乐播放详情页
				row.setOnClickListener(v -> {
					launchMusicPlayer();
				});
				break;
			default:
				// 内存、存储、使用时长等不可编辑类型无点击行为
				break;
		}
	}

	/** 启动音乐播放器详情页（音乐播放器未安装时提示）。 */
	private void launchMusicPlayer() {
		Context ctx = getContext();
		if (ctx == null) return;
		NokiaLog.i("Desktop", "正在播放组件点击：打开音乐播放器");
		try {
			Intent intent = new Intent(Intent.ACTION_MAIN);
			intent.setClassName(MUSIC_PKG, MUSIC_PLAYER_ACTIVITY);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
		} catch (Exception e) {
			NokiaLog.e("Desktop", "打开音乐播放器失败", e);
			Toast.makeText(ctx, "未安装音乐播放器", Toast.LENGTH_SHORT).show();
		}
	}

	/** 触发第三方 QS Tile 快捷开关（支持特化通道、Shizuku、Root 多级执行）。 */
	private void triggerQsTile(NokiaWidgetItem item) {
		if (item == null || TextUtils.isEmpty(item.value)) return;
		final Context ctx = getContext();
		if (ctx == null) return;
		final String target = item.value.trim();
		final String label = item.label;
		NokiaLog.i("Desktop", "触发快捷开关: " + label + " target=" + target);

		// 1. 小黑屋特化官方一键冻结通道（免 root、免 Shizuku、直接调用官方公开的 StopappActivity）
		if (target.contains("web1n.stopapp")) {
			try {
				Intent freezeIntent = new Intent();
				freezeIntent.setComponent(new ComponentName("web1n.stopapp", "web1n.stopapp.activity.StopappActivity"));
				freezeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				ctx.startActivity(freezeIntent);
				Toast.makeText(ctx, "已触发: " + label, Toast.LENGTH_SHORT).show();
				NokiaLog.i("Desktop", "已直接拉起小黑屋一键冻结 StopappActivity");
				return;
			} catch (Exception e) {
				NokiaLog.e("Desktop", "拉起小黑屋 StopappActivity 失败，尝试通用 Shell 通道", e);
			}
		}

		// 2. 通用 Shizuku / Root / Shell 通道
		new Thread(() -> {
			boolean executed = false;
			// 复合命令：先展开设置面板激活 Tile 交互通道，再触发 click-tile，支持带弹窗的第三方 App
			String cmd = "cmd statusbar expand-settings && sleep 0.1 && cmd statusbar click-tile " + target;

			// 优先使用 mini_shizuku 服务以 Shell 身份执行
			if (Shizuku.isRunning()) {
				executed = Shizuku.exec(cmd);
				NokiaLog.i("Desktop", "Shizuku 执行 click-tile 结果: " + executed);
			}

			// 若 Shizuku 未运行，尝试 root su 执行
			if (!executed) {
				try {
					Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
					int exitCode = p.waitFor();
					if (exitCode == 0) {
						executed = true;
						NokiaLog.i("Desktop", "Root 执行 click-tile 成功");
					}
				} catch (Exception ignored) {
				}
			}

			final boolean success = executed;
			if (getActivity() != null) {
				getActivity().runOnUiThread(() -> {
					if (success) {
						Toast.makeText(ctx, "已触发: " + label, Toast.LENGTH_SHORT).show();
					} else {
						// 提示需要激活 mini_shizuku
						Toast.makeText(ctx, "快捷开关需在高级设置中激活 mini_shizuku 服务", Toast.LENGTH_LONG).show();
					}
				});
			}
		}).start();
	}

	// ---- 快捷栏 ----

	private LinearLayout createShortcutCell(ShortcutApp app, int index) {
		Context ctx = getContext();
		if (ctx == null) return null;
		float fontScale = NokiaSettingsStorage.getFontScale(ctx);
		// 图标缩放比字体小一号：进行适当弱化，避免视觉过大（如 1.3x 字体对应约 1.15x 图标）
		float iconScale = 1.0f + (fontScale - 1.0f) * 0.6f;
		if (iconScale < 0.8f) iconScale = 0.8f;

		int iconBase = Math.round(22 * iconScale);
		int cellW = Math.round(36 * iconScale);
		int cellH = Math.round(34 * iconScale);
		int padding = Math.max(1, Math.round(4 * iconScale));

		LinearLayout cell = new LinearLayout(ctx);
		cell.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), cellW), NokiaDimens.dp(getResources(), cellH)));
		cell.setOrientation(LinearLayout.VERTICAL);
		cell.setGravity(Gravity.CENTER);
		cell.setPadding(
				NokiaDimens.dp(getResources(), padding),
				NokiaDimens.dp(getResources(), padding),
				NokiaDimens.dp(getResources(), padding),
				NokiaDimens.dp(getResources(), padding));
		cell.setClickable(true);
		cell.setTag(app);

		FrameLayout iconContainer = new FrameLayout(ctx);
		iconContainer.setLayoutParams(new LinearLayout.LayoutParams(
				NokiaDimens.dp(getResources(), iconBase), NokiaDimens.dp(getResources(), iconBase)));

		ImageView iv = new ImageView(ctx);
		iv.setLayoutParams(new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		Drawable icon = loadShortcutIconMemory(app);
		if (icon != null) {
			iv.setImageDrawable(icon);
		} else {
			try {
				iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher));
			} catch (Exception ignored) {}
		}
		iconContainer.addView(iv);

		// 冻结角标不在这里同步查询：isAppFrozen() 是一次 PackageManager Binder 调用，
		// 快捷栏 8 项就是 8 次主线程 IPC（应用不存在时还要构造异常）。
		// 改为随图标一起在后台线程解析（见 refreshShortcutCellsAsync / applyFreezeBadge）。

		cell.addView(iconContainer);

		cell.setOnClickListener(v -> launchShortcutApp(app));
		return cell;
	}

	private Drawable loadShortcutIconMemory(ShortcutApp app) {
		try {
			if (app.type == ShortcutApp.TYPE_ANDROID) {
				Intent intent = app.getLaunchIntent();
				if (intent != null && intent.getComponent() != null) {
					String pkg = intent.getComponent().getPackageName();
					int s60Res = NokiaS60IconMap.getIcon(pkg, app.label);
					if (s60Res != 0) {
						try {
							Context ctx = getContext();
							if (ctx != null) {
								Drawable s60Icon = ContextCompat.getDrawable(ctx, s60Res);
								if (s60Icon != null) return s60Icon.mutate();
							}
						} catch (Exception ignored) {}
					}
				}
			}
		} catch (Exception e) {
			NokiaLog.w("Desktop", "加载快捷栏图标(内存)失败: " + app.label);
		}
		return null;
	}

	private Drawable loadShortcutIconNow(ShortcutApp app) {
		try {
			if (app.type == ShortcutApp.TYPE_J2ME && app.iconPath != null) {
				Drawable d = Drawable.createFromPath(app.iconPath);
				if (d != null) return d;
			}
			if (app.type == ShortcutApp.TYPE_ANDROID) {
				Intent intent = app.getLaunchIntent();
				if (intent != null && intent.getComponent() != null) {
					String pkg = intent.getComponent().getPackageName();
					int s60Res = NokiaS60IconMap.getIcon(pkg, app.label);
					if (s60Res != 0) {
						try {
							Context ctx = getContext();
							if (ctx != null) {
								Drawable s60Icon = ContextCompat.getDrawable(ctx, s60Res);
								if (s60Icon != null) return s60Icon.mutate();
							}
						} catch (Exception ignored) {}
					}
					try {
						if (getActivity() != null) {
							return getActivity().getPackageManager()
									.getActivityIcon(intent.getComponent());
						}
					} catch (Exception ignored) {}
				}
			}
		} catch (Exception e) {
			NokiaLog.w("Desktop", "加载快捷栏图标失败: " + app.label);
		}
		return null;
	}

	/**
	 * 后台解析快捷栏单元格的「图标 + 冻结角标」，完成后回主线程更新。
	 * <p>
	 * 走共享线程池（ICON_EXECUTOR）：原先每个快捷项各起一个线程，8 项就是 8 个线程
	 * 并发做 PackageManager IPC + 位图分配，冷启动时容易抢占 CPU 并引发 GC 抖动，
	 * 反而拖慢主线程的首帧渲染。
	 *
	 * @param withIcons 是否同时重新加载图标；false 表示只刷新冻结角标
	 *                  （冻结状态广播后调用，不必重解码图标）
	 */
	private void refreshShortcutCellsAsync(final LinearLayout container, final boolean withIcons) {
		if (container == null || shortcutApps.isEmpty()) return;
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		final Resources res = container.getResources();
		final int targetPx = computeShortcutIconTargetPx();
		final Context appCtx = requireContext().getApplicationContext();
		for (int i = 0; i < shortcutApps.size(); i++) {
			final ShortcutApp app = shortcutApps.get(i);
			final int index = i;
			ICON_EXECUTOR.execute(new Runnable() {
				@Override
				public void run() {
					// 预缩放到实际显示尺寸，避免大图每帧缩小绘制（低端机快速滚动叠影的诱因）
					final Drawable icon = withIcons
							? scaleShortcutIcon(loadShortcutIconNow(app), targetPx, res)
							: null;
					final boolean frozen = isShortcutAppFrozen(app, appCtx);
					mainHandler.post(new Runnable() {
						@Override
						public void run() {
							if (!isAdded() || getView() == null) return;
							if (index >= container.getChildCount()) return;
							View child = container.getChildAt(index);
							if (!(child instanceof LinearLayout)) return;
							View firstChild = ((LinearLayout) child).getChildAt(0);
							if (!(firstChild instanceof FrameLayout)) return;
							FrameLayout iconBox = (FrameLayout) firstChild;
							if (withIcons && icon != null) {
								View ivChild = iconBox.getChildAt(0);
								if (ivChild instanceof ImageView) {
									((ImageView) ivChild).setImageDrawable(icon);
								}
							}
							applyFreezeBadge(iconBox, frozen, res);
						}
					});
				}
			});
		}
	}

	/** 快捷应用当前是否处于冻结状态（PackageManager IPC，仅在后台线程调用）。 */
	private static boolean isShortcutAppFrozen(ShortcutApp app, Context ctx) {
		if (app.type != ShortcutApp.TYPE_ANDROID) return false;
		Intent intent = app.getLaunchIntent();
		if (intent == null) return false;
		String pkg = intent.getComponent() != null
				? intent.getComponent().getPackageName()
				: intent.getPackage();
		if (TextUtils.isEmpty(pkg)) return false;
		return NokiaFreezeManager.getInstance(ctx).isAppFrozen(pkg);
	}

	/** 显示/移除快捷图标右下角的冻结角标（幂等）。 */
	private void applyFreezeBadge(FrameLayout iconBox, boolean frozen, Resources res) {
		View existing = iconBox.findViewWithTag(TAG_FREEZE_BADGE);
		if (!frozen) {
			if (existing != null) iconBox.removeView(existing);
			return;
		}
		if (existing != null) return;
		Context ctx = getContext();
		if (ctx == null) return;
		ImageView badgeIv = new ImageView(ctx);
		float fontScale = NokiaSettingsStorage.getFontScale(ctx);
		float iconScale = 1.0f + (fontScale - 1.0f) * 0.6f;
		if (iconScale < 0.8f) iconScale = 0.8f;
		int badgeSize = Math.max(NokiaDimens.dp(res, 8), Math.round(NokiaDimens.dp(res, 10) * iconScale));
		FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSize, badgeSize);
		badgeLp.gravity = Gravity.BOTTOM | Gravity.END;
		badgeIv.setLayoutParams(badgeLp);
		badgeIv.setImageResource(R.drawable.ic_nokia_ice_badge);
		badgeIv.setTag(TAG_FREEZE_BADGE);
		iconBox.addView(badgeIv);
	}

	/** 快捷图标目标显示尺寸（与 createShortcutCell 的 iconBase 保持一致），单位 px。 */
	private int computeShortcutIconTargetPx() {
		Context ctx = getContext();
		if (ctx == null) return 0;
		float fontScale = NokiaSettingsStorage.getFontScale(ctx);
		float iconScale = 1.0f + (fontScale - 1.0f) * 0.6f;
		if (iconScale < 0.8f) iconScale = 0.8f;
		int iconBase = Math.round(22 * iconScale);
		return NokiaDimens.dp(getResources(), iconBase);
	}

	/** 把任意 Drawable 预缩放为 targetPx 见方的小位图（等比适配，居中），失败时原样返回。 */
	private Drawable scaleShortcutIcon(Drawable d, int targetPx, Resources res) {
		if (d == null || res == null || targetPx <= 0) return d;
		try {
			int w = d.getIntrinsicWidth();
			int h = d.getIntrinsicHeight();
			if (w <= 0 || h <= 0) return d;
			float scale = Math.min((float) targetPx / w, (float) targetPx / h);
			int nw = Math.max(1, Math.round(w * scale));
			int nh = Math.max(1, Math.round(h * scale));
			Bitmap bmp = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bmp);
			d.setBounds((targetPx - nw) / 2, (targetPx - nh) / 2,
					(targetPx - nw) / 2 + nw, (targetPx - nh) / 2 + nh);
			d.draw(canvas);
			canvas.setBitmap(null);
			return new BitmapDrawable(res, bmp);
		} catch (Exception e) {
			NokiaLog.w("Desktop", "快捷图标预缩放失败: " + e.getMessage());
			return d;
		}
	}

	private void launchShortcutApp(ShortcutApp app) {
		Context ctx = getContext();
		if (ctx == null) return;
		NokiaLog.i("Desktop", "启动快捷栏应用: " + app.label);
		try {
			if (app.type == ShortcutApp.TYPE_ANDROID) {
				String pkg = null;
				Intent intent = app.getLaunchIntent();
				if (intent != null) {
					if (intent.getComponent() != null) pkg = intent.getComponent().getPackageName();
					else if (!TextUtils.isEmpty(intent.getPackage())) pkg = intent.getPackage();
				}

				if (pkg != null && NokiaFreezeManager.getInstance(ctx).isAppFrozen(pkg)) {
					NokiaFreezeManager.getInstance(ctx).unfreezeAndLaunch(app.getLaunchIntent(), pkg, app.label);
					return;
				}

				if (intent != null) {
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					try {
						startActivity(intent);
					} catch (Exception e) {
						// 兜底：缓存的组件可能因应用更新/停用而失效，
						// 重新解析当前「启用」入口再试，成功则修正存储的 intentUri
						if (pkg != null) {
							Intent retry = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
							if (retry != null) {
								retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								startActivity(retry);
								app.intentUri = retry.toUri(Intent.URI_INTENT_SCHEME);
								settingsStorage.setShortcutApps(new ArrayList<>(shortcutApps));
								NokiaLog.i("Desktop", "快捷栏应用兜底启动成功并修正缓存: "
										+ app.label + " -> " + retry.getComponent());
								return;
							}
						}
						throw e;
					}
					return;
				}
			}
			if (app.type == ShortcutApp.TYPE_J2ME) {
				NokiaJarLauncher.launch(requireActivity(), app.label, app.appKey);
			}
		} catch (Exception e) {
			NokiaLog.e("Desktop", "启动快捷栏应用失败: " + app.label, e);
		}
	}

	// ---- 焦点收集 ----

	private void collectWidgetTargets(View view) {
		LinearLayout notifArea = view.findViewById(R.id.notificationArea);
		if (notifArea == null) return;
		for (int i = 0; i < notifArea.getChildCount(); i++) {
			View child = notifArea.getChildAt(i);
			if (child.isFocusable()) {
				focusTargets.add(child);
			}
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		if (focusTargets.isEmpty()) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP: return moveUp();
			case NokiaKeyBinding.ACTION_DOWN: return moveDown();
			case NokiaKeyBinding.ACTION_LEFT: return moveLeft();
			case NokiaKeyBinding.ACTION_RIGHT: return moveRight();
			default: return false;
		}
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= focusTargets.size()) return false;
		View v = focusTargets.get(focusIndex);
		if (v != null) v.performClick();
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		((NokiaDesktopActivity) requireActivity()).openMenu();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		((NokiaDesktopActivity) requireActivity()).openDesktopSettings();
		return true;
	}

	@Override
	public boolean onBack() {
		return false;
	}

	// ---- NokiaPage ----

	@Override
	public String getPageTitle() { return null; }

	@Override
	public String getSoftLeftText() { return "功能表"; }

	@Override
	public String getSoftRightText() { return "桌面设置"; }

	// ---- 导航 ----
	// 分区顺序（自上而下）：顶部快捷栏 → 通知条 → 组件区 → 便捷开关栏。
	// 索引一律从上往下累加，notifBarCount（0/1）是通知条是否占用一个焦点位的单一数据源。

	private int headCount() { return shortcutCount + notifBarCount; }
	private int shortcutLast() { return shortcutCount; }
	private int notifFirst() { return shortcutCount; }
	private int notifLast() { return headCount(); }
	private int widgetFirst() { return notifLast(); }
	private int widgetLast() { return widgetFirst() + widgetCount; }
	private int toggleCount() { return toggleCells.size(); }
	private int toggleFirst() { return widgetLast(); }
	private int toggleLast() { return toggleFirst() + toggleCount(); }

	private boolean isInShortcuts() { return focusIndex >= SHORTCUT_FIRST && focusIndex < shortcutLast(); }
	private boolean isInNotifBar() { return notifBarCount > 0 && focusIndex >= notifFirst() && focusIndex < notifLast(); }
	private boolean isInWidgets() { return focusIndex >= widgetFirst() && focusIndex < widgetLast(); }
	private boolean isInToggles() { return focusIndex >= toggleFirst() && focusIndex < toggleLast(); }

	private boolean moveUp() {
		int newIdx = focusIndex;
		if (isInShortcuts()) {
			// 在顶部快捷栏按 UP：循环跳到底部区域（开关栏或组件区最末项）
			if (toggleCount() > 0) newIdx = toggleFirst();
			else if (widgetCount > 0) newIdx = widgetLast() - 1;
		} else if (isInNotifBar()) {
			// 在通知条按 UP：回到快捷栏末项；无快捷项则循环到底部
			if (shortcutCount > 0) newIdx = shortcutLast() - 1;
			else if (toggleCount() > 0) newIdx = toggleLast() - 1;
			else if (widgetCount > 0) newIdx = widgetLast() - 1;
		} else if (isInWidgets()) {
			// 在组件区按 UP：上一行组件，若已是第一行则跳入通知条或顶部快捷栏
			if (focusIndex > widgetFirst()) {
				newIdx = focusIndex - 1;
			} else {
				if (notifBarCount > 0) newIdx = notifFirst();
				else if (shortcutCount > 0) newIdx = SHORTCUT_FIRST;
				else if (toggleCount() > 0) newIdx = toggleFirst();
			}
		} else if (isInToggles()) {
			// 在开关栏按 UP：直接向上离开开关栏，跳入组件区末项、通知条或顶部快捷栏
			if (widgetCount > 0) {
				newIdx = widgetLast() - 1;
			} else if (notifBarCount > 0) {
				newIdx = notifFirst();
			} else if (shortcutCount > 0) {
				newIdx = SHORTCUT_FIRST;
			}
		}
		return applyFocus(newIdx);
	}

	private boolean moveDown() {
		int newIdx = focusIndex;
		if (isInShortcuts()) {
			// 在顶部快捷栏按 DOWN：先落通知条，再组件区，再开关栏
			if (notifBarCount > 0) {
				newIdx = notifFirst();
			} else if (widgetCount > 0) {
				newIdx = widgetFirst();
			} else if (toggleCount() > 0) {
				newIdx = toggleFirst();
			}
		} else if (isInNotifBar()) {
			// 在通知条按 DOWN：进入组件区第一项或开关栏；都无则循环回快捷栏
			if (widgetCount > 0) {
				newIdx = widgetFirst();
			} else if (toggleCount() > 0) {
				newIdx = toggleFirst();
			} else if (shortcutCount > 0) {
				newIdx = SHORTCUT_FIRST;
			}
		} else if (isInWidgets()) {
			// 在组件区按 DOWN：下一行组件，若已是最后一行则跳入开关栏（或循环回顶部）
			if (focusIndex < widgetLast() - 1) {
				newIdx = focusIndex + 1;
			} else if (toggleCount() > 0) {
				newIdx = toggleFirst();
			} else if (shortcutCount > 0) {
				newIdx = SHORTCUT_FIRST;
			}
		} else if (isInToggles()) {
			// 在开关栏按 DOWN：直接向下离开开关栏，循环回到顶部快捷栏或组件区第一项
			if (shortcutCount > 0) {
				newIdx = SHORTCUT_FIRST;
			} else if (widgetCount > 0) {
				newIdx = widgetFirst();
			}
		}
		return applyFocus(newIdx);
	}

	private boolean moveLeft() {
		int newIdx = focusIndex;
		if (isInShortcuts()) {
			// 快捷栏横向循环向左
			if (focusIndex > SHORTCUT_FIRST) {
				newIdx = focusIndex - 1;
			} else if (shortcutCount > 1) {
				newIdx = shortcutLast() - 1;
			}
		} else if (isInNotifBar()) {
			// 通知条为单行，横向保持聚焦
			return false;
		} else if (isInWidgets()) {
			// 组件区为纵向单列，按 LEFT 保持聚焦，不横向乱跳
			return false;
		} else if (isInToggles()) {
			// 开关栏横向循环向左
			if (focusIndex > toggleFirst()) {
				newIdx = focusIndex - 1;
			} else if (toggleCount() > 1) {
				newIdx = toggleLast() - 1;
			}
		}
		return applyFocus(newIdx);
	}

	private boolean moveRight() {
		int newIdx = focusIndex;
		if (isInShortcuts()) {
			// 快捷栏横向循环向右
			if (focusIndex < shortcutLast() - 1) {
				newIdx = focusIndex + 1;
			} else if (shortcutCount > 1) {
				newIdx = SHORTCUT_FIRST;
			}
		} else if (isInNotifBar()) {
			// 通知条为单行，按 RIGHT 保持聚焦，不横向乱跳
			return false;
		} else if (isInWidgets()) {
			// 组件区为纵向单列，按 RIGHT 保持聚焦，不横向乱跳
			return false;
		} else if (isInToggles()) {
			// 开关栏横向循环向右
			if (focusIndex < toggleLast() - 1) {
				newIdx = focusIndex + 1;
			} else if (toggleCount() > 1) {
				newIdx = toggleFirst();
			}
		}
		return applyFocus(newIdx);
	}

	private boolean applyFocus(int newIdx) {
		if (newIdx < 0 || newIdx >= focusTargets.size()) return false;
		if (newIdx != focusIndex) {
			scrollToVisible(newIdx);
			setFocusIndex(newIdx);
			return true;
		}
		return false;
	}

	private void scrollToVisible(int index) {
		if (index < 0 || index >= focusTargets.size()) return;
		View target = focusTargets.get(index);
		if (target == null) return;

		if (isInShortcuts()) {
			ViewParent parent = target.getParent();
			while (parent instanceof View) {
				View pv = (View) parent;
				if (pv.getId() == R.id.shortcutBar) {
					int scrollX = target.getLeft() - pv.getPaddingLeft();
					pv.scrollTo(Math.max(0, scrollX - NokiaDimens.dp(getResources(), 12)), 0);
					return;
				}
				parent = pv.getParent();
			}
		} else if (isInToggles()) {
			if (quickToggleScroll != null) {
				int scrollX = target.getLeft() - quickToggleScroll.getPaddingLeft();
				quickToggleScroll.smoothScrollTo(Math.max(0, scrollX - NokiaDimens.dp(getResources(), 16)), 0);
			}
		}
	}

	private void setFocusIndex(int index) {
		if (index < 0 || index >= focusTargets.size()) return;
		if (focusIndex >= 0 && focusIndex < focusTargets.size()) {
			View old = focusTargets.get(focusIndex);
			if (old != null) old.setBackgroundResource(0);
		}
		focusIndex = index;
		View v = focusTargets.get(index);
		if (v != null) {
			Context ctx = getContext();
			if (ctx != null) {
				v.setBackground(NokiaTheme.createSelectionDrawable(ctx, 4));
			}
			selectedView = v;
		}
		if (isInShortcuts() && v != null) {
			showShortcutBubble(index, v);
		} else {
			hideShortcutBubble();
		}
	}

	private void showShortcutBubble(int index, View cell) {
		if (shortcutNameBubble == null || shortcutBar == null) return;
		if (index < 0 || index >= shortcutApps.size()) return;
		ShortcutApp app = shortcutApps.get(index);
		shortcutNameBubble.setText(app.label);

		int contentW = (int) (240 * getResources().getDisplayMetrics().density);
		View parent = (View) shortcutNameBubble.getParent();
		if (parent != null && parent.getWidth() > 0) contentW = parent.getWidth();
		shortcutNameBubble.measure(
				View.MeasureSpec.makeMeasureSpec(Math.max(0, contentW - 4), View.MeasureSpec.AT_MOST),
				View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
		int bw = shortcutNameBubble.getMeasuredWidth();

		int cx = shortcutBar.getLeft() + (cell.getLeft() - shortcutBar.getScrollX()) + cell.getWidth() / 2;
		int left = cx - bw / 2;
		int maxLeft = contentW - bw - 2;
		if (left < 2) left = 2;
		if (left > maxLeft) left = Math.max(2, maxLeft);
		shortcutNameBubble.setX(left);
		shortcutNameBubble.setY(shortcutBar.getTop() + shortcutBar.getHeight() + NokiaDimens.dp(getResources(), 1));
		shortcutNameBubble.setVisibility(View.VISIBLE);

		bubbleHandler.removeCallbacks(bubbleHideRunnable);
		bubbleHandler.postDelayed(bubbleHideRunnable, BUBBLE_DURATION);
	}

	private final Runnable bubbleHideRunnable = () -> hideShortcutBubble();

	private void hideShortcutBubble() {
		if (bubbleHandler != null) bubbleHandler.removeCallbacks(bubbleHideRunnable);
		if (shortcutNameBubble != null) shortcutNameBubble.setVisibility(View.GONE);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		unregisterToggleReceiver();
		if (bubbleHandler != null) bubbleHandler.removeCallbacks(bubbleHideRunnable);
		bubbleHandler = null;
		shortcutNameBubble = null;
		shortcutBar = null;
		quickToggleScroll = null;
		quickToggleBar = null;
		quickToggleDivider = null;
		toggleCells.clear();
		activeToggles.clear();
	}
}
