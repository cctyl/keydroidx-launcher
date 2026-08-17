package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

	/** 快捷栏项数（动态） */
	private int shortcutCount = 0;
	/** 组件区项数（动态，由 widgetItems.size() 决定） */
	private int widgetCount = 0;

	/** 快捷栏第一个焦点索引 */
	private static final int SHORTCUT_FIRST = 0;

	// ---- 便捷开关栏 ----

	private HorizontalScrollView quickToggleScroll;
	private LinearLayout quickToggleBar;
	private View quickToggleDivider;
	private final List<NokiaQuickToggleItem> activeToggles = new ArrayList<>();
	private final List<View> toggleCells = new ArrayList<>();
	private boolean[] toggleStates = new boolean[0];
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

		focusTargets.clear();
		shortcutApps.clear();
		widgetItems.clear();
		focusIndex = -1;
		selectedView = null;

		loadShortcutBarAsync(view);
		rebuildWidgetArea(view);

		// 便捷开关栏
		quickToggleScroll = view.findViewById(R.id.quickToggleScroll);
		quickToggleBar = view.findViewById(R.id.quickToggleBar);
		quickToggleDivider = view.findViewById(R.id.quickToggleDivider);
		if (quickToggleBar != null) {
			buildToggleBar();
			syncToggleStatesFromSystem();
			// rebuildWidgetArea 已在 buildToggleBar 前调用（当时 toggleCells 为空），
			// 这里手动把开关单元格补进焦点列表；之后快捷栏异步加载完成会整体重建
			for (View cell : toggleCells) {
				if (cell != null) {
					focusTargets.add(cell);
				}
			}
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
		// 从桌面设置返回后刷新组件区（可能有增删改）
		View view = getView();
		if (view != null) {
			rebuildWidgetArea(view);
		}
		// 异步刷新后台管理组件计数（countBackgroundProcesses 含 shizuku TCP，不能在主线程）
		refreshBgCountAsync();
		// 更新开关栏状态
		if (quickToggleBar != null) {
			syncToggleStatesFromSystem();
		}
		NokiaLog.d("Desktop", "桌面 onResume，已刷新组件区");
	}

	@Override
	public void onPause() {
		super.onPause();
		unregisterToggleReceiver();
	}

	/** 后台线程计算后台进程数并回主线程刷新组件区（避免主线程 TCP 卡顿）。 */
	private void refreshBgCountAsync() {
		final Context appCtx = requireContext().getApplicationContext();
		new Thread(() -> {
			NokiaBgManagerHelper.probeShizukuSync();
			int count = NokiaBgManagerHelper.countBackgroundProcesses(appCtx);
			if (getActivity() == null) return;
			getActivity().runOnUiThread(() -> {
				if (!isAdded() || getView() == null) return;
				cachedBgCount = count;
				rebuildWidgetArea(getView());
			});
		}, "desktop-bg-count").start();
	}

	// ---- 构建快捷栏 ----

	private void loadShortcutBarAsync(View view) {
		long loadStart = System.currentTimeMillis();
		NokiaS60IconMap.loadFromDisk(requireContext());
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
			TextView hint = new TextView(requireContext());
			hint.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, NokiaDimens.dp(getResources(), 34)));
			hint.setGravity(Gravity.CENTER);
			hint.setText("（无快捷应用）");
			hint.setTextColor(0xFF888888);
			NokiaDimens.textSize(hint, 10);
			container.addView(hint);
		} else {
			for (int i = 0; i < apps.size(); i++) {
				LinearLayout cell = createShortcutCell(apps.get(i), i);
				if (cell != null) {
					container.addView(cell);
					focusTargets.add(cell);
				}
			}
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
			if (focusTargets.size() > 0) {
				setFocusIndex(0);
			}
		});

		NokiaS60IconMap.initAsync(requireContext(), () -> {
			if (!isAdded() || getView() == null) return;
			refreshShortcutIcons(container);
		});
	}

	// ---- 组件区动态渲染 ----

	/** 重建桌面组件区：从 NokiaWidgetStorage 读取所有组件，动态创建行 View。 */
	private void rebuildWidgetArea(View view) {
		LinearLayout notifArea = view.findViewById(R.id.notificationArea);
		if (notifArea == null) return;

		// 先清空旧 View（但保留其他非焦点子 View，如果有的话）
		notifArea.removeAllViews();
		widgetItems.clear();
		widgetItems.addAll(widgetStorage.getWidgets());
		widgetCount = widgetItems.size();

		if (widgetItems.isEmpty()) {
			// 无组件时显示提示
			TextView hint = new TextView(requireContext());
			hint.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			hint.setPadding(NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 4),
					0, 0);
			hint.setText("无更多备忘");
			hint.setTextColor(0xFF888888);
			NokiaDimens.textSize(hint, 10);
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

		// 重建焦点列表（保留快捷栏部分，重建组件区部分）
		// 先清掉旧的组件区焦点
		int shortcutFocusCount = Math.min(focusTargets.size(), shortcutCount);
		while (focusTargets.size() > shortcutFocusCount) {
			focusTargets.remove(focusTargets.size() - 1);
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
		quickToggleBar.removeAllViews();
		toggleCells.clear();

		activeToggles.clear();
		activeToggles.addAll(NokiaQuickToggleStorage.getEnabledToggles(requireContext()));
		int count = activeToggles.size();

		if (count == 0) {
			if (quickToggleScroll != null) quickToggleScroll.setVisibility(View.GONE);
			if (quickToggleDivider != null) quickToggleDivider.setVisibility(View.GONE);
			toggleStates = new boolean[0];
			return;
		}

		if (quickToggleScroll != null) quickToggleScroll.setVisibility(View.VISIBLE);
		if (quickToggleDivider != null) quickToggleDivider.setVisibility(View.VISIBLE);

		if (toggleStates.length != count) {
			toggleStates = new boolean[count];
		}

		int cellWidth = NokiaDimens.dp(getResources(), 36);
		int cellHeight = NokiaDimens.dp(getResources(), 32);

		for (int i = 0; i < count; i++) {
			NokiaQuickToggleItem item = activeToggles.get(i);
			LinearLayout cell = new LinearLayout(requireContext());
			cell.setLayoutParams(new LinearLayout.LayoutParams(cellWidth, cellHeight));
			cell.setOrientation(LinearLayout.VERTICAL);
			cell.setGravity(Gravity.CENTER);
			cell.setFocusable(true);
			cell.setClickable(true);

			// 图标
			ImageView iv = new ImageView(requireContext());
			iv.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 18), NokiaDimens.dp(getResources(), 18)));
			iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
			iv.setImageResource(item.iconRes);
			iv.setTag("icon");
			cell.addView(iv);

			// 状态指示小圆点
			View dot = new View(requireContext());
			LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4));
			dotLp.setMargins(0, NokiaDimens.dp(getResources(), 1), 0, 0);
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
	}

	/** 从系统同步所有已启用开关的真实状态并刷新视图。 */
	private void syncToggleStatesFromSystem() {
		if (activeToggles.isEmpty()) return;
		Context ctx = requireContext().getApplicationContext();
		if (toggleStates.length != activeToggles.size()) {
			toggleStates = new boolean[activeToggles.size()];
		}
		for (int i = 0; i < activeToggles.size(); i++) {
			toggleStates[i] = NokiaQuickToggleManager.isToggleOn(ctx, activeToggles.get(i).type);
		}
		renderToggleViews();
	}

	/** 纯视图渲染：根据当前内存中的 toggleStates[] 刷新单元格图标和指示灯（0 延迟）。 */
	private void renderToggleViews() {
		for (int i = 0; i < toggleCells.size(); i++) {
			View cell = toggleCells.get(i);
			if (cell == null) continue;
			boolean on = (i < toggleStates.length) && toggleStates[i];

			ImageView iv = cell.findViewWithTag("icon");
			if (iv != null) {
				iv.setAlpha(on ? 1.0f : 0.35f);
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
		final Context ctx = requireContext();
		final NokiaQuickToggleItem item = activeToggles.get(index);
		final boolean targetOn = !toggleStates[index];

		// 1. 立即乐观更新内存状态并刷新 UI，消除点击延迟感
		toggleStates[index] = targetOn;
		renderToggleViews();

		// 2. 异步执行切换链路
		NokiaQuickToggleManager.toggle(ctx, item.type, targetOn);
	}

	// ---- 广播接收器 ----

	private void registerToggleReceiver() {
		if (receiverRegistered) return;
		toggleStateReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (!isAdded() || quickToggleBar == null) return;
				syncToggleStatesFromSystem();
			}
		};
		IntentFilter filter = new IntentFilter();
		filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
		filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
		requireContext().registerReceiver(toggleStateReceiver, filter);
		receiverRegistered = true;
		NokiaLog.i("Desktop", "已注册开关栏广播接收器");
	}

	private void unregisterToggleReceiver() {
		if (toggleStateReceiver != null && receiverRegistered) {
			try {
				requireContext().unregisterReceiver(toggleStateReceiver);
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
			default:
				return createWidgetRowSimple(item);
		}
	}

	/** 创建带进度条的组件行（内存/存储）。 */
	private View createWidgetRowWithProgress(NokiaWidgetItem item, int fillColor, float usedRatio, String percentText) {
		Context ctx = requireContext();
		LinearLayout row = new LinearLayout(ctx);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3),
				NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3));
		row.setFocusable(true);
		row.setClickable(true);

		// 图标 14dp
		int iconRes = NokiaWidgetItem.getTypeIcon(item.type);
		ImageView iv = new ImageView(ctx);
		iv.setLayoutParams(new LinearLayout.LayoutParams(
				NokiaDimens.dp(getResources(), 14), NokiaDimens.dp(getResources(), 14)));
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		try { iv.setImageResource(iconRes); } catch (Exception ignored) {}
		iv.setPadding(0, 0, NokiaDimens.dp(getResources(), 5), 0);
		row.addView(iv);

		// 标签文字，弹性占满剩余空间
		TextView labelTv = new TextView(ctx);
		LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
		labelTv.setLayoutParams(labelLp);
		labelTv.setText(item.label);
		labelTv.setTextColor(0xFFFFFFFF);
		NokiaDimens.textSize(labelTv, 11);
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
		NokiaDimens.textSize(percentTv, 9);
		percentTv.setSingleLine(true);
		row.addView(percentTv);

		setupWidgetRowClick(row, item);
		return row;
	}

	/** 创建无进度条的普通组件行（日历/使用时长/可编辑类型）。 */
	private View createWidgetRowSimple(NokiaWidgetItem item) {
		Context ctx = requireContext();
		LinearLayout row = new LinearLayout(ctx);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3),
				NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 3));
		row.setFocusable(true);
		row.setClickable(true);

		// 图标
		int iconRes = NokiaWidgetItem.getTypeIcon(item.type);
		ImageView iv = new ImageView(ctx);
		iv.setLayoutParams(new LinearLayout.LayoutParams(
				NokiaDimens.dp(getResources(), 16), NokiaDimens.dp(getResources(), 16)));
		iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
		try { iv.setImageResource(iconRes); } catch (Exception ignored) {}
		iv.setPadding(0, 0, NokiaDimens.dp(getResources(), 6), 0);
		row.addView(iv);

		// 标签文字
		TextView labelTv = new TextView(ctx);
		labelTv.setLayoutParams(new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
		labelTv.setText(getWidgetLabel(item));
		labelTv.setTextColor(0xFFFFFFFF);
		NokiaDimens.textSize(labelTv, 11);
		labelTv.setSingleLine(true);
		row.addView(labelTv);

		// 右侧信息文字
		TextView infoTv = new TextView(ctx);
		infoTv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		infoTv.setTextColor(0xFFAAAAAA);
		NokiaDimens.textSize(infoTv, 10);
		infoTv.setGravity(Gravity.END);
		infoTv.setSingleLine(true);
		infoTv.setText(getWidgetInfoText(item));
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
		String keyName = NokiaLog.keyName(kc);
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
			default:
				return item.getTypeTag();
		}
	}

	/** 获取 WiFi IPv4 地址；未连接 WiFi 或无 IP 时返回 "未连接"。 */
	private String getWifiIpAddress() {
		try {
			WifiManager wifi = (WifiManager) requireContext().getApplicationContext()
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

	/** 返回已用比例 [0,1] 和百分比文字。 */
	private float getMemoryUsedRatio() {
		try {
			ActivityManager am = (ActivityManager) requireContext()
					.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return 0;
			ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
			am.getMemoryInfo(mi);
			long total = mi.totalMem;
			long avail = mi.availMem;
			if (total <= 0) return 0;
			return (float) (total - avail) / total;
		} catch (Exception e) {
			return 0;
		}
	}

	private String getMemoryPercentText() {
		try {
			ActivityManager am = (ActivityManager) requireContext()
					.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null) return "";
			ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
			am.getMemoryInfo(mi);
			long total = mi.totalMem;
			long avail = mi.availMem;
			if (total <= 0) return "";
			int pct = (int) ((total - avail) * 100 / total);
			return pct + "%";
		} catch (Exception e) {
			return "";
		}
	}

	private float getStorageUsedRatio() {
		try {
			File dataDir = Environment.getDataDirectory();
			StatFs stat = new StatFs(dataDir.getPath());
			long total, avail;
			if (Build.VERSION.SDK_INT >= 18) {
				total = stat.getBlockCountLong() * stat.getBlockSizeLong();
				avail = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
			} else {
				total = (long) stat.getBlockCount() * stat.getBlockSize();
				avail = (long) stat.getAvailableBlocks() * stat.getBlockSize();
			}
			if (total <= 0) return 0;
			return (float) (total - avail) / total;
		} catch (Exception e) {
			return 0;
		}
	}

	private String getStoragePercentText() {
		try {
			File dataDir = Environment.getDataDirectory();
			StatFs stat = new StatFs(dataDir.getPath());
			long total, avail;
			if (Build.VERSION.SDK_INT >= 18) {
				total = stat.getBlockCountLong() * stat.getBlockSizeLong();
				avail = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
			} else {
				total = (long) stat.getBlockCount() * stat.getBlockSize();
				avail = (long) stat.getAvailableBlocks() * stat.getBlockSize();
			}
			if (total <= 0) return "";
			int pct = (int) ((total - avail) * 100 / total);
			return pct + "%";
		} catch (Exception e) {
			return "";
		}
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
					NokiaLockScreen.lock(requireContext());
				});
				break;
			case NokiaWidgetItem.TYPE_BG_MANAGER:
				// 后台管理组件：点击打开后台管理窗口（运行/受保护页签 + 清除）
				row.setOnClickListener(v -> {
					NokiaLog.i("Desktop", "后台管理组件点击：打开后台窗口");
					((NokiaDesktopActivity) requireActivity())
							.openFragment(new NokiaBackgroundManagerFragment());
				});
				break;
			case NokiaWidgetItem.TYPE_IP:
				// IP地址组件：点击刷新 IP 并复制到剪贴板
				row.setOnClickListener(v -> {
					String ip = getWifiIpAddress();
					if (!"未连接".equals(ip)) {
						ClipboardManager cm = (ClipboardManager) requireContext()
								.getSystemService(Context.CLIPBOARD_SERVICE);
						if (cm != null) {
							cm.setPrimaryClip(ClipData.newPlainText("IP", ip));
						}
					}
					Toast.makeText(requireContext(),
							"未连接".equals(ip) ? "WiFi未连接" : "已复制: " + ip,
							Toast.LENGTH_SHORT).show();
					NokiaLog.i("Desktop", "IP组件点击: ip=" + ip);
					rebuildWidgetArea(getView());
				});
				break;
			default:
				// 内存、存储、使用时长等不可编辑类型无点击行为
				break;
		}
	}

	// ---- 快捷栏 ----

	private LinearLayout createShortcutCell(ShortcutApp app, int index) {
		Context ctx = requireContext();
		LinearLayout cell = new LinearLayout(ctx);
		cell.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 34)));
		cell.setOrientation(LinearLayout.VERTICAL);
		cell.setGravity(Gravity.CENTER);
		cell.setPadding(NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4));
		cell.setClickable(true);
		cell.setTag(app);

		ImageView iv = new ImageView(ctx);
		iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 22)));
		Drawable icon = loadShortcutIconMemory(app);
		if (icon != null) {
			iv.setImageDrawable(icon);
		} else {
			try {
				iv.setImageDrawable(ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher));
			} catch (Exception ignored) {}
		}
		cell.addView(iv);

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
						Drawable s60Icon = ContextCompat.getDrawable(requireContext(), s60Res);
						if (s60Icon != null) return s60Icon.mutate();
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
							Drawable s60Icon = ContextCompat.getDrawable(requireContext(), s60Res);
							if (s60Icon != null) return s60Icon.mutate();
						} catch (Exception ignored) {}
					}
					try {
						return requireActivity().getPackageManager()
								.getActivityIcon(intent.getComponent());
					} catch (Exception ignored) {}
				}
			}
		} catch (Exception e) {
			NokiaLog.w("Desktop", "加载快捷栏图标失败: " + app.label);
		}
		return null;
	}

	private void refreshShortcutIcons(final LinearLayout container) {
		if (container == null || shortcutApps.isEmpty()) return;
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		for (int i = 0; i < shortcutApps.size(); i++) {
			final ShortcutApp app = shortcutApps.get(i);
			final int index = i;
			new Thread(() -> {
				final Drawable icon = loadShortcutIconNow(app);
				mainHandler.post(() -> {
					if (!isAdded() || getView() == null) return;
					if (index >= container.getChildCount()) return;
					View child = container.getChildAt(index);
					if (!(child instanceof LinearLayout)) return;
					View iconView = ((LinearLayout) child).getChildAt(0);
					if (iconView instanceof ImageView && icon != null) {
						((ImageView) iconView).setImageDrawable(icon);
					}
				});
			}, "shortcut-icon-" + index).start();
		}
	}

	private void launchShortcutApp(ShortcutApp app) {
		NokiaLog.i("Desktop", "启动快捷栏应用: " + app.label);
		try {
			if (app.type == ShortcutApp.TYPE_ANDROID) {
				Intent intent = app.getLaunchIntent();
				if (intent != null) { startActivity(intent); return; }
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

	private int shortcutLast() { return shortcutCount; }
	private int widgetFirst() { return shortcutCount; }
	private int widgetLast() { return shortcutCount + widgetCount; }
	private int toggleCount() { return toggleCells.size(); }
	private int toggleFirst() { return shortcutCount + widgetCount; }
	private int toggleLast() { return toggleFirst() + toggleCount(); }

	private boolean isInShortcuts() { return focusIndex >= SHORTCUT_FIRST && focusIndex < shortcutLast(); }
	private boolean isInWidgets() { return focusIndex >= widgetFirst() && focusIndex < widgetLast(); }
	private boolean isInToggles() { return focusIndex >= toggleFirst() && focusIndex < toggleLast(); }

	private boolean moveUp() {
		int newIdx = focusIndex;
		if (isInShortcuts()) {
			// 在顶部快捷栏按 UP：循环跳到底部区域（开关栏或组件区最末项）
			if (toggleCount() > 0) newIdx = toggleFirst();
			else if (widgetCount > 0) newIdx = widgetLast() - 1;
		} else if (isInWidgets()) {
			// 在组件区按 UP：上一行组件，若已是第一行则跳入顶部快捷栏
			if (focusIndex > widgetFirst()) {
				newIdx = focusIndex - 1;
			} else {
				if (shortcutCount > 0) newIdx = SHORTCUT_FIRST;
				else if (toggleCount() > 0) newIdx = toggleFirst();
			}
		} else if (isInToggles()) {
			// 在开关栏按 UP：直接向上离开开关栏，跳入组件区末项或顶部快捷栏
			if (widgetCount > 0) {
				newIdx = widgetLast() - 1;
			} else if (shortcutCount > 0) {
				newIdx = SHORTCUT_FIRST;
			}
		}
		return applyFocus(newIdx);
	}

	private boolean moveDown() {
		int newIdx = focusIndex;
		if (isInShortcuts()) {
			// 在顶部快捷栏按 DOWN：直接向下离开快捷栏，跳入组件区第一项或开关栏
			if (widgetCount > 0) {
				newIdx = widgetFirst();
			} else if (toggleCount() > 0) {
				newIdx = toggleFirst();
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
			v.setBackgroundResource(R.drawable.bg_nokia_selected);
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
