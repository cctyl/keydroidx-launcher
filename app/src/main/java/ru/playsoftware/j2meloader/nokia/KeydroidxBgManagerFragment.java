package ru.playsoftware.j2meloader.nokia;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;
import io.github.cctyl.nokia.common.ui.KeydroidxIcons;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.MidletStateStore;

/**
 * 「后台管理」卡片页（桌面主界面 / 功能表内按绑定键进入；桌面「后台管理」组件亦进入本页）。
 *
 * <p><b>语义（与旧「最近任务」页的根本区别）：</b>本页只列<b>当前存活的后台进程</b>，
 * 不读系统任务历史。因此：</p>
 * <ul>
 *   <li>force-stop 后进程即从枚举结果消失、卡片随之消失——不再有 sz=0 鬼壳自愈机器、
 *       不再需要 dumpsys recents 文本解析与 {@code am stack remove}；</li>
 *   <li>系统应用、输入法、桌面自身进程不出现（见
 *       {@link KeydroidxBgManagerHelper#enumerateBackgroundTasks}）；</li>
 *   <li>挂机 jar（:midlet 进程）条目固定置顶，key 形如 {@code midlet:<appPath>}。</li>
 * </ul>
 *
 * <p><b>交互：</b></p>
 * <ul>
 *   <li>2 列卡片：头部（图标 + 应用名 + 装饰 ×）+ 预览区（应用图标水印）；已保护应用右上角绿色盾牌；</li>
 *   <li>方向键循环移动焦点；确认键切换到该应用（恢复前台，不重启）；</li>
 *   <li>左软键 = 选项（切换 / 保护此应用 / 刷新 / 清理此任务 / 清理全部任务）；</li>
 *   <li>右软键 / 返回键 = 退出；</li>
 *   <li><b>数字键 5 = 清理选中的任务（无二次确认，不受保护名单限制）</b>；
 *       <b>数字键 0 = 一键清理未保护任务（无二次确认）</b>。</li>
 * </ul>
 *
 * <p><b>保护名单语义：</b>持久化于 {@link KeydroidxSettingsStorage}，重启不丢。
 * 保护只挡批量清理（0 键 / 清理全部）；「清理此任务」是手动清理，<b>不受保护名单限制</b>。</p>
 *
 * <p><b>权限：</b>Android 5.0+ 需 mini_shizuku（shell 身份）才能枚举真实后台进程并 force-stop；
 * 未激活时显示空态 + 激活引导。4.4 走 {@link android.app.ActivityManager#getRunningAppProcesses()}
 * 与 {@link android.app.ActivityManager#killBackgroundProcesses(String)}。</p>
 *
 * <p>规范要点：软键栏无高亮、空栏只置空文字不用 {@code GONE}；尺寸走 {@link KeydroidxDimens#dp}；
 * 图标走 {@link KeydroidxIcons}；卡片底色与焦点高亮来自 {@link KeydroidxTheme}；
 * 枚举含 TCP / PackageManager 调用，一律后台线程执行后回主线程渲染。</p>
 */
public class KeydroidxBgManagerFragment extends KeydroidxPageFragment {

	private static final String TAG = "BgManagerPage";

	/** 单列列表（塞班 S60 / Jbak TaskMan 风格）。 */
	private static final int COLUMNS = 1;
	/** 列表行最小高度（dp）。 */
	private static final int ROW_H_DP = 38;
	/** 应用图标尺寸（dp）。 */
	private static final int ICON_DP = 20;

	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_SUB = 0xFF90CAF9;
	private static final int COLOR_ACCENT = 0xFF64B5F6;
	private static final int COLOR_OK = 0xFF7DE2A0;
	private static final int COLOR_WARN = 0xFFFF8A80;

	private LinearLayout gridLayout;
	private ScrollView scroll;
	private TextView tvMode;
	private TextView tvHint;

	// 顶部资源仪表盘
	private View meterBox;
	private TextView tvRamTitle;
	private TextView tvRamPercent;
	private LinearLayout ramBarTrack;
	private View ramBarFill;
	private View ramBarEmpty;
	private TextView tvCpuSummary;

	private View emptyBox;
	private ImageView emptyIcon;
	private TextView emptyTitle;
	private TextView emptySub;
	private TextView emptyAction;

	private final List<KeydroidxBgManagerHelper.BgTask> tasks = new ArrayList<>();
	private Set<String> protectedSet = new HashSet<>();
	private KeydroidxSettingsStorage settingsStorage;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	/**
	 * 清理后延迟复核任务。force-stop 后 dumpsys 的 ProcessRecord 清理有 1-2s 滞后，
	 * 立即 loadAsync 会把刚杀的进程重新列回来（打回乐观移除）；延迟后再用真实存活列表复核，
	 * 期间靠 {@link #removeKilledLocally} 维持「已清卡片立即消失」。
	 */
	private final Runnable reconcileRunnable = new Runnable() {
		@Override
		public void run() {
			if (isAdded() && getView() != null) loadAsync();
		}
	};

	private View[] cellViews;
	private View selectedView;
	private int focusIndex = -1;
	private boolean available = false;
	private boolean loading = true;
	private boolean firstResume = true;
	private Toast toast;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_recent_tasks;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		settingsStorage = new KeydroidxSettingsStorage(requireContext());
		protectedSet = new HashSet<>(settingsStorage.getProtectedPackages());

		gridLayout = view.findViewById(R.id.recentGridLayout);
		scroll = view.findViewById(R.id.recentScroll);
		tvMode = view.findViewById(R.id.tvRecentMode);
		tvHint = view.findViewById(R.id.tvRecentHint);

		meterBox = view.findViewById(R.id.recentMeterBox);
		tvRamTitle = view.findViewById(R.id.tvRamTitle);
		tvRamPercent = view.findViewById(R.id.tvRamPercent);
		ramBarTrack = view.findViewById(R.id.ramBarTrack);
		ramBarFill = view.findViewById(R.id.ramBarFill);
		ramBarEmpty = view.findViewById(R.id.ramBarEmpty);
		tvCpuSummary = view.findViewById(R.id.tvCpuSummary);

		if (meterBox != null) meterBox.setBackground(createMeterBoxBackground(requireContext()));
		if (ramBarTrack != null) ramBarTrack.setBackground(createRamTrackBackground(requireContext()));

		emptyBox = view.findViewById(R.id.recentEmptyBox);
		emptyIcon = view.findViewById(R.id.recentEmptyIcon);
		emptyTitle = view.findViewById(R.id.recentEmptyTitle);
		emptySub = view.findViewById(R.id.recentEmptySub);
		emptyAction = view.findViewById(R.id.recentEmptyAction);
		if (emptyAction != null) {
			emptyAction.setOnClickListener(v -> onEmptyAction());
		}

		requestRootFocus(view);
		loadAsync();
		KeydroidxLog.i(TAG, "后台管理页初始化完成");
	}

	@Override
	public void onResume() {
		super.onResume();
		requestRootFocus(getView());
		if (firstResume) {
			firstResume = false;
			return;
		}
		// 从其它应用返回：重新读保护名单并刷新
		if (settingsStorage != null) {
			protectedSet = new HashSet<>(settingsStorage.getProtectedPackages());
		}
		loadAsync();
	}

	// ============================================================
	// 数据加载（后台线程枚举 → 主线程渲染）
	// ============================================================

	/** 清理后延迟复核（见 {@link #reconcileRunnable}）。 */
	private void scheduleReconcile() {
		mainHandler.removeCallbacks(reconcileRunnable);
		mainHandler.postDelayed(reconcileRunnable, 2000);
	}

	private void loadAsync() {
		if (!isAdded() || getContext() == null) return;
		final Context appCtx = requireContext().getApplicationContext();
		loading = true;
		renderAll();
		new Thread(new Runnable() {
			@Override
			public void run() {
				// 先同步探测一次 shizuku 状态（含 TCP，主线程会抛 NetworkOnMainThreadException）
				KeydroidxBgManagerHelper.probeShizukuSync();
				final boolean loadedAvailable = KeydroidxBgManagerHelper.isBgManagerAvailable();
				final Set<String> prot = new HashSet<>(protectedSet);
				final List<KeydroidxBgManagerHelper.BgTask> loaded =
						KeydroidxBgManagerHelper.enumerateBackgroundTasks(appCtx, prot);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						available = loadedAvailable;
						loading = false;
						tasks.clear();
						tasks.addAll(loaded);
						if (settingsStorage != null) {
							protectedSet = new HashSet<>(settingsStorage.getProtectedPackages());
						}
						renderAll();
						KeydroidxLog.i(TAG, "渲染完成: available=" + available + " 条目=" + tasks.size());
					}
				});
			}
		}, "bg-manager-load").start();
	}

	// ============================================================
	// 渲染
	// ============================================================

	private void renderAll() {
		Context ctx = getContext();
		if (ctx == null || getView() == null) return;
		renderHeader();
		renderMeterBox(ctx);
		if (tasks.isEmpty()) {
			showEmptyState(ctx);
			selectedView = null;
			focusIndex = -1;
			cellViews = null;
		} else {
			emptyBox.setVisibility(View.GONE);
			scroll.setVisibility(View.VISIBLE);
			if (focusIndex < 0 || focusIndex >= tasks.size()) {
				focusIndex = 0;
			}
			buildGrid(ctx);
			applyFocus(focusIndex);
		}
		if (getActivity() instanceof KeydroidxDesktopActivity) {
			((KeydroidxDesktopActivity) getActivity()).refreshPageBar();
		}
	}

	/** 标题右侧可用性徽标 + 摘要行。 */
	private void renderHeader() {
		if (tvMode == null) return;
		tvMode.setText(available ? "已激活" : "未激活");
		tvMode.setTextColor(available ? COLOR_OK : COLOR_WARN);

		tvHint.setText(loading || !available
				? ""
				: "确认键切换 · 5 清理 · 0 清理全部");
	}

	/** 顶部资源仪表盘（塞班 S60 / Jbak 经典风格：系统真实 RAM + 后台 CPU 汇总）。 */
	private void renderMeterBox(Context ctx) {
		if (meterBox == null || tvRamTitle == null || tvRamPercent == null) return;
		// 仅在明确检测到服务未激活时才隐藏；加载中保持常驻展示，彻底杜绝瞬间消失闪烁与布局跳动
		if (!available && !loading) {
			meterBox.setVisibility(View.GONE);
			return;
		}
		meterBox.setVisibility(View.VISIBLE);

		// 1. 读取系统真实内存指标（标准 ActivityManager API，minSdk 19 完美兼容）
		long availBytes = 0;
		long totalBytes = 0;
		int usedPercent = 0;
		try {
			ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
			if (am != null) {
				ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
				am.getMemoryInfo(mi);
				availBytes = mi.availMem;
				totalBytes = mi.totalMem;
				if (totalBytes > 0) {
					usedPercent = (int) (100 * (totalBytes - availBytes) / totalBytes);
				}
			}
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "读取系统内存信息异常: " + t.getMessage());
		}

		String ramStr = "RAM [可用 " + formatBytes(availBytes) + " / " + formatBytes(totalBytes) + "]";
		tvRamTitle.setText(ramStr);
		tvRamPercent.setText(usedPercent + "%");

		// 2. 进度条填充比率（基于权重实时自适应宽度）
		if (ramBarFill != null && ramBarEmpty != null && ramBarTrack != null) {
			int fillW = Math.max(1, Math.min(99, usedPercent));
			int emptyW = 100 - fillW;
			LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) ramBarFill.getLayoutParams();
			LinearLayout.LayoutParams ep = (LinearLayout.LayoutParams) ramBarEmpty.getLayoutParams();
			if (fp != null && ep != null) {
				fp.weight = fillW;
				ep.weight = emptyW;
				ramBarTrack.requestLayout();
			}
		}

		// 3. CPU 汇总或后台数
		if (tvCpuSummary != null) {
			int totalCpu = 0;
			boolean hasCpu = false;
			for (KeydroidxBgManagerHelper.BgTask t : tasks) {
				if (t.cpuPct >= 0) {
					totalCpu += t.cpuPct;
					hasCpu = true;
				}
			}
			if (hasCpu) {
				tvCpuSummary.setText("CPU: " + totalCpu + "%");
				tvCpuSummary.setTextColor(totalCpu > 15 ? 0xFFFFB74D : 0xFF81C784);
			} else {
				tvCpuSummary.setText(tasks.size() + " 个后台");
				tvCpuSummary.setTextColor(COLOR_SUB);
			}
		}
	}

	private static String formatBytes(long bytes) {
		if (bytes >= 1024L * 1024L * 1024L) {
			float gb = (float) bytes / (1024L * 1024L * 1024L);
			return String.format(Locale.US, "%.1fG", gb);
		} else {
			long mb = bytes / (1024L * 1024L);
			return mb + "M";
		}
	}

	/** 空态 / 未激活态 / 加载态（三者共用同一容器，文案与图标按状态切换）。 */
	private void showEmptyState(Context ctx) {
		scroll.setVisibility(View.GONE);
		emptyBox.setVisibility(View.VISIBLE);

		if (loading) {
			emptyIcon.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_HOURGLASS, COLOR_ACCENT, 26));
			emptyTitle.setText("正在读取后台应用…");
			emptySub.setText("");
			emptyAction.setVisibility(View.GONE);
			return;
		}
		if (!available) {
			emptyIcon.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_WARNING, COLOR_WARN, 26));
			emptyTitle.setText("未激活 mini_shizuku");
			emptySub.setText("Android 5.0+ 需要 mini_shizuku（shell 权限）才能读取真实后台进程并清理。");
			emptyAction.setText("去激活 mini_shizuku");
			emptyAction.setVisibility(View.VISIBLE);
			return;
		}
		emptyIcon.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_BG_MANAGER, COLOR_ACCENT, 26));
		emptyTitle.setText("暂无后台应用");
		emptySub.setText("没有应用在后台运行。\n按右软键返回桌面。");
		emptyAction.setVisibility(View.GONE);
	}

	/** 构建单列全宽列表（塞班 S60 / Jbak TaskMan 风格）。 */
	private void buildGrid(Context ctx) {
		gridLayout.removeAllViews();
		cellViews = new View[tasks.size()];
		int gap = KeydroidxDimens.dp(getResources(), 2);

		for (int i = 0; i < tasks.size(); i++) {
			View row = createRow(ctx, tasks.get(i));
			LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			if (i > 0) rlp.topMargin = gap;
			row.setLayoutParams(rlp);
			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});
			gridLayout.addView(row);
			cellViews[i] = row;
		}
	}

	/**
	 * 单个后台进程行（塞班 S60 极客任务台）：
	 * 左侧图标 + 中间（应用名加粗 + 运行时间 + 保护盾牌）+ 右侧（内存胶囊徽标 + CPU%）。
	 * 无 PID，重点突出对用户有意义的资源开销与状态。
	 */
	private View createRow(Context ctx, KeydroidxBgManagerHelper.BgTask t) {
		LinearLayout row = new LinearLayout(ctx);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setClickable(true);
		row.setFocusable(false);
		row.setMinimumHeight(KeydroidxDimens.dp(getResources(), ROW_H_DP));
		int padH = KeydroidxDimens.dp(getResources(), 6);
		int padV = KeydroidxDimens.dp(getResources(), 3);
		row.setPadding(padH, padV, padH, padV);
		row.setBackground(createRowBackground(ctx));

		// 左侧：应用图标 20dp
		ImageView icon = new ImageView(ctx);
		int iconPx = KeydroidxDimens.dp(getResources(), ICON_DP);
		LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconPx, iconPx);
		ip.setMarginEnd(KeydroidxDimens.dp(getResources(), 6));
		icon.setLayoutParams(ip);
		icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
		icon.setImageDrawable(t.icon != null
				? t.icon
				: KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_APP, 0xFFFFFFFF, ICON_DP));
		row.addView(icon);

		// 中间两行：应用名 (12sp 加粗) + 运行时间 (9sp) + 保护盾牌
		LinearLayout center = new LinearLayout(ctx);
		center.setOrientation(LinearLayout.VERTICAL);
		center.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		center.setLayoutParams(cp);

		TextView name = new TextView(ctx);
		name.setText(t.name);
		name.setTextColor(COLOR_TEXT);
		KeydroidxFontManager.textSize(name, 12);
		name.setTypeface(null, Typeface.BOLD);
		name.setSingleLine(true);
		name.setEllipsize(TextUtils.TruncateAt.END);
		name.setIncludeFontPadding(false);
		center.addView(name);

		LinearLayout subRow = new LinearLayout(ctx);
		subRow.setOrientation(LinearLayout.HORIZONTAL);
		subRow.setGravity(Gravity.CENTER_VERTICAL);
		LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		srp.topMargin = KeydroidxDimens.dp(getResources(), 2);
		subRow.setLayoutParams(srp);

		TextView uptime = new TextView(ctx);
		uptime.setTag("uptime");
		String upStr = (t.uptimeText != null && !t.uptimeText.isEmpty())
				? "⏱ " + t.uptimeText : "⏱ 运行中";
		uptime.setText(upStr);
		uptime.setTextColor(COLOR_SUB);
		KeydroidxFontManager.textSize(uptime, 9);
		uptime.setSingleLine(true);
		uptime.setIncludeFontPadding(false);
		subRow.addView(uptime);

		if (protectedSet.contains(t.pkg)) {
			TextView protBadge = new TextView(ctx);
			protBadge.setText(" 🛡️ 已保护");
			protBadge.setTextColor(COLOR_OK);
			KeydroidxFontManager.textSize(protBadge, 8);
			protBadge.setSingleLine(true);
			protBadge.setIncludeFontPadding(false);
			subRow.addView(protBadge);
		}
		center.addView(subRow);
		row.addView(center);

		// 右侧：内存胶囊徽标 + CPU%
		LinearLayout right = new LinearLayout(ctx);
		right.setOrientation(LinearLayout.VERTICAL);
		right.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
		LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		rp.setMarginStart(KeydroidxDimens.dp(getResources(), 6));
		right.setLayoutParams(rp);

		TextView memBadge = new TextView(ctx);
		memBadge.setTag("mem");
		String memStr = t.pssMB > 0 ? (t.pssMB + "MB")
				: (t.rssMB > 0 ? (t.rssMB + "MB") : "--");
		memBadge.setText(memStr);
		memBadge.setTextColor(COLOR_ACCENT);
		KeydroidxFontManager.textSize(memBadge, 9);
		memBadge.setSingleLine(true);
		memBadge.setIncludeFontPadding(false);
		memBadge.setTypeface(null, Typeface.BOLD);
		int bpPadH = KeydroidxDimens.dp(getResources(), 3);
		int bpPadV = KeydroidxDimens.dp(getResources(), 1);
		memBadge.setPadding(bpPadH, bpPadV, bpPadH, bpPadV);
		memBadge.setBackground(createMemBadgeDrawable(ctx, false));
		right.addView(memBadge);

		row.setTag(t);
		if (t.cpuPct >= 0) {
			TextView cpuText = new TextView(ctx);
			cpuText.setTag("cpu");
			cpuText.setText("CPU " + t.cpuPct + "%");
			int cpuColor;
			if (t.cpuPct > 5) {
				cpuColor = 0xFFFFB74D;
			} else if (t.cpuPct > 0) {
				cpuColor = 0xFF81C784;
			} else {
				cpuColor = 0xFF78909C; // 0% 休眠挂起进程采用柔和次级色
			}
			cpuText.setTextColor(cpuColor);
			KeydroidxFontManager.textSize(cpuText, 8);
			cpuText.setSingleLine(true);
			cpuText.setIncludeFontPadding(false);
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			clp.topMargin = KeydroidxDimens.dp(getResources(), 1);
			clp.gravity = Gravity.END;
			cpuText.setLayoutParams(clp);
			right.addView(cpuText);
		}
		row.addView(right);

		return row;
	}

	/** 列表行常态底色：半透明微暗色，避免纯透明浮动感。 */
	private Drawable createRowBackground(Context ctx) {
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(0x18000000);
		gd.setCornerRadius(KeydroidxDimens.dp(getResources(), 2));
		return gd;
	}

	/** 内存胶囊徽标底色：未选中为蓝透小框，选中时白底高对比反色。 */
	private Drawable createMemBadgeDrawable(Context ctx, boolean focused) {
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setCornerRadius(KeydroidxDimens.dp(getResources(), 2));
		if (focused) {
			gd.setColor(0xFFFFFFFF);
		} else {
			gd.setColor(0x33000000);
			gd.setStroke(KeydroidxDimens.dp(getResources(), 1), 0x3364B5F6);
		}
		return gd;
	}

	/** 顶部仪表盘外框底色。 */
	private Drawable createMeterBoxBackground(Context ctx) {
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(0x33000000);
		gd.setStroke(KeydroidxDimens.dp(getResources(), 1), 0x22FFFFFF);
		gd.setCornerRadius(KeydroidxDimens.dp(getResources(), 3));
		return gd;
	}

	/** 内存进度条底槽。 */
	private Drawable createRamTrackBackground(Context ctx) {
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(0x800A1B2D);
		gd.setStroke(KeydroidxDimens.dp(getResources(), 1), 0xFF325880);
		gd.setCornerRadius(KeydroidxDimens.dp(getResources(), 1.5f));
		return gd;
	}

	// ============================================================
	// 焦点
	// ============================================================

	private void setFocusIndex(int index) {
		if (index < 0 || index >= tasks.size()) return;
		focusIndex = index;
		applyFocus(index);
		scrollToVisible(index);
	}

	private void applyFocus(int index) {
		Context ctx = getContext();
		if (ctx == null || cellViews == null) return;
		if (selectedView != null) {
			selectedView.setBackground(createRowBackground(ctx));
			View oldUp = selectedView.findViewWithTag("uptime");
			if (oldUp instanceof TextView) ((TextView) oldUp).setTextColor(COLOR_SUB);
			View oldMem = selectedView.findViewWithTag("mem");
			if (oldMem instanceof TextView) {
				oldMem.setBackground(createMemBadgeDrawable(ctx, false));
				((TextView) oldMem).setTextColor(COLOR_ACCENT);
			}
			View oldCpu = selectedView.findViewWithTag("cpu");
			if (oldCpu instanceof TextView) {
				Object tagTask = selectedView.getTag();
				if (tagTask instanceof KeydroidxBgManagerHelper.BgTask) {
					int pct = ((KeydroidxBgManagerHelper.BgTask) tagTask).cpuPct;
					((TextView) oldCpu).setTextColor(pct > 5 ? 0xFFFFB74D : (pct > 0 ? 0xFF81C784 : 0xFF78909C));
				}
			}
			selectedView = null;
		}
		if (index < 0 || index >= cellViews.length || cellViews[index] == null) return;
		View cur = cellViews[index];
		cur.setBackground(KeydroidxTheme.createSelectionDrawable(ctx, 3));
		View newUp = cur.findViewWithTag("uptime");
		if (newUp instanceof TextView) ((TextView) newUp).setTextColor(0xFFE3F2FD);
		View newMem = cur.findViewWithTag("mem");
		if (newMem instanceof TextView) {
			newMem.setBackground(createMemBadgeDrawable(ctx, true));
			((TextView) newMem).setTextColor(0xFF0D47A1);
		}
		View newCpu = cur.findViewWithTag("cpu");
		if (newCpu instanceof TextView) {
			((TextView) newCpu).setTextColor(0xFFE0E0E0);
		}
		selectedView = cur;
	}

	private void scrollToVisible(int index) {
		if (scroll == null || cellViews == null || index < 0 || index >= cellViews.length) return;
		smoothScrollToVisible(scroll, cellViews[index]);
	}

	private KeydroidxBgManagerHelper.BgTask focusedTask() {
		if (focusIndex < 0 || focusIndex >= tasks.size()) return null;
		return tasks.get(focusIndex);
	}

	/** 防「首个方向键被吞」：根视图可聚焦 + 立即持焦 + post 兜底。 */
	private void requestRootFocus(@Nullable final View root) {
		if (root == null) return;
		root.setFocusable(true);
		root.setFocusableInTouchMode(true);
		root.requestFocus();
		root.post(new Runnable() {
			@Override
			public void run() {
				View v = getView();
				if (v != null && v.findFocus() == null) {
					v.requestFocus();
				}
			}
		});
	}

	// ============================================================
	// KeydroidxFocusHost
	// ============================================================

	@Override
	public boolean onDirection(int direction) {
		if (tasks.isEmpty()) return true;
		int size = tasks.size();
		int next;
		switch (direction) {
			case KeydroidxKeyBinding.ACTION_LEFT:
			case KeydroidxKeyBinding.ACTION_UP:
				next = focusIndex - 1;
				break;
			case KeydroidxKeyBinding.ACTION_RIGHT:
			case KeydroidxKeyBinding.ACTION_DOWN:
				next = focusIndex + 1;
				break;
			default:
				return true;
		}
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		// 首尾循环：越界后绕回，焦点永不落在无效格上
		next = ((next % size) + size) % size;
		setFocusIndex(next);
		return true;
	}

	@Override
	public boolean onSelect() {
		KeydroidxBgManagerHelper.BgTask t = focusedTask();
		if (t == null) return false;
		KeydroidxLog.i(TAG, "确认键：切换应用 " + t.name + " (" + t.pkg + ")");
		return bringToFront(t);
	}

	@Override
	public boolean onSoftLeft() {
		showOptions();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		KeydroidxLog.i(TAG, "右软键：退出后台管理");
		exitSelf();
		return true;
	}

	@Override
	public boolean onBack() {
		KeydroidxLog.i(TAG, "返回键：退出后台管理");
		exitSelf();
		return true;
	}

	private void exitSelf() {
		if (getActivity() instanceof KeydroidxDesktopActivity) {
			((KeydroidxDesktopActivity) getActivity()).exitCurrent();
		}
	}

	// ============================================================
	// 选项菜单 / 切换 / 保护 / 清理
	// ============================================================

	private void showOptions() {
		final KeydroidxBgManagerHelper.BgTask cur = focusedTask();
		List<KeydroidxOptionsDialog.OptionItem> items = new ArrayList<>();

		if (!available) {
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_SHIZUKU,
					"激活 mini_shizuku", true, false, this::openShizukuPage));
		} else if (cur != null) {
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_PLAY,
					"切换到应用", true, false, () -> bringToFront(cur)));
			final boolean prot = protectedSet.contains(cur.pkg);
			items.add(new KeydroidxOptionsDialog.OptionItem(
					prot ? KeydroidxIcons.ICON_LOCK_OPEN : KeydroidxIcons.ICON_SHIELD,
					prot ? "取消保护" : "保护此应用", true, false, () -> toggleProtect(cur)));
		}
		items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_REFRESH,
				"刷新列表", true, false, this::loadAsync));
		if (available && cur != null) {
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_DELETE,
					"清理此任务", true, false, () -> confirmClearTask(cur)));
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_CLEAR_ALL,
					"清理全部任务", true, false, this::clearAllUnprotected));
		}

		String title = (cur != null && available) ? "选项 · " + cur.name : "选项";
		KeydroidxOptionsDialog.show(getParentFragmentManager(), title, items);
		KeydroidxLog.i(TAG, "弹出选项菜单: " + title + " 项数=" + items.size());
	}

	/** 切换到该应用（恢复前台，不重启）。 */
	private boolean bringToFront(KeydroidxBgManagerHelper.BgTask t) {
		Activity act = getActivity();
		if (act == null || t == null) return false;
		// 挂机 jar：复用既有启动入口（MicroActivity 为 singleTask，同 jar 直接回前台续跑）
		if (MidletStateStore.isMidletTaskKey(t.pkg)) {
			String appPath = t.pkg.substring("midlet:".length());
			KeydroidxLog.i(TAG, "恢复挂机 jar: " + t.name);
			try {
				Config.startApp(act, t.name, appPath, false);
				return true;
			} catch (Exception e) {
				KeydroidxLog.e(TAG, "恢复挂机 jar 失败: " + t.name, e);
				return false;
			}
		}
		try {
			Intent launch = act.getPackageManager().getLaunchIntentForPackage(t.pkg);
			if (launch == null) {
				KeydroidxLog.w(TAG, "无启动入口，无法切换: " + t.pkg);
				return false;
			}
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			act.startActivity(launch);
			KeydroidxLog.i(TAG, "切换应用: " + t.name + " (" + t.pkg + ")");
			return true;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "切换应用失败 " + t.pkg + ": " + e.getMessage());
			return false;
		}
	}

	/** 切换保护状态并持久化。 */
	private void toggleProtect(KeydroidxBgManagerHelper.BgTask t) {
		boolean wasProtected = protectedSet.contains(t.pkg);
		if (wasProtected) {
			protectedSet.remove(t.pkg);
		} else {
			protectedSet.add(t.pkg);
		}
		if (settingsStorage != null) settingsStorage.setProtectedPackages(protectedSet);
		showToast(wasProtected ? "已取消保护「" + t.name + "」" : "已保护「" + t.name + "」");
		KeydroidxLog.i(TAG, "保护状态变更: " + t.pkg + " -> " + (wasProtected ? "未保护" : "已保护"));
		renderAll();
	}

	/**
	 * 「清理此任务」二次确认。手动清理单个应用不受保护名单限制，属破坏性操作，需显式确认。
	 * <p>更安全的选项（取消）置于左软键；确认键与左软键等效，故误按确认键只会取消，
	 * 破坏性动作必须显式按<b>右软键</b>。
	 */
	private void confirmClearTask(final KeydroidxBgManagerHelper.BgTask t) {
		Context ctx = getContext();
		if (ctx == null) return;
		boolean prot = protectedSet.contains(t.pkg);
		String msg = "将结束「" + t.name + "」。"
				+ (prot ? "\n该应用在保护名单中，本次为手动清理。" : "");
		new KeydroidxConfirmDialog(ctx, "清理此任务？", msg)
				.setPositiveButton("取消", null)
				.setNegativeButton("清理", () -> clearSingleTask(t))
				.show();
		KeydroidxLog.i(TAG, "弹出清理确认: " + t.name + " protected=" + prot);
	}

	/** 数字键 0：一键清理未保护任务（<b>无二次确认</b>）。 */
	public boolean onCleanKey() {
		KeydroidxLog.i(TAG, "数字键 0：一键清理未保护任务");
		clearAllUnprotected();
		return true;
	}

	/**
	 * 数字键 5：清理当前选中（焦点所在）的任务。与「清理此任务」同一手动清理语义
	 * ——不受保护名单限制、<b>无二次确认</b>（与 0 键批量清理保持一致的按键风格）。
	 */
	public boolean onClearFocusedKey() {
		KeydroidxBgManagerHelper.BgTask t = focusedTask();
		if (t == null) {
			showToast("没有选中的任务");
			return true;
		}
		KeydroidxLog.i(TAG, "数字键 5：清理选中任务 " + t.pkg);
		clearSingleTask(t);
		return true;
	}

	/**
	 * 清理全部未保护任务（<b>无二次确认</b>；保护名单自动跳过）。必须在后台线程执行 shell。
	 * <p>直接对页面当前列表逐个 force-stop（复用 {@link KeydroidxBgManagerHelper#clearSingleTask}，
	 * 内部会 markCleared 供音乐组件避让冷启动），<b>不重新枚举</b>——这样「已清理数」与用户看到的
	 * 卡片数一致，且 force-stop 对已死进程是 no-op，不会因 dumpsys 进程记录清理滞后而误报
	 * 「清理失败」。force-stop 后立即从本地列表移除已清条目（即时反馈），随后 loadAsync
	 * 用真实存活列表复核。
	 */
	private void clearAllUnprotected() {
		if (!isAdded() || getContext() == null) return;
		if (tasks.isEmpty()) {
			showToast("没有可清理的任务");
			return;
		}
		final List<KeydroidxBgManagerHelper.BgTask> targets = new ArrayList<>();
		for (KeydroidxBgManagerHelper.BgTask t : tasks) {
			if (!protectedSet.contains(t.pkg)) targets.add(t);
		}
		if (targets.isEmpty()) {
			showToast("可清理的应用都已被保护");
			return;
		}
		final Context appCtx = requireContext().getApplicationContext();
		final Set<String> killedKeys = new HashSet<>();
		new Thread(new Runnable() {
			@Override
			public void run() {
				KeydroidxBgManagerHelper.probeShizukuSync();
				if (!KeydroidxBgManagerHelper.isBgManagerAvailable()) {
					mainHandler.post(new Runnable() {
						@Override public void run() {
							if (isAdded()) showToast("请先激活 mini_shizuku");
						}
					});
					return;
				}
				int cleared = 0;
				for (KeydroidxBgManagerHelper.BgTask t : targets) {
					if (KeydroidxBgManagerHelper.clearSingleTask(appCtx, t.pkg, t.pkg, -1)) {
						cleared++;
					}
					killedKeys.add(t.pkg);
				}
				final int n = cleared;
				KeydroidxLog.i(TAG, "一键清理完成: 目标=" + targets.size() + " 成功=" + n);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						// 即时反馈：先从本地列表移除已清条目，再用真实存活列表复核
						removeKilledLocally(killedKeys);
						renderAll();
						showToast(n > 0 ? "已清理 " + n + " 个任务" : "清理失败，请检查 mini_shizuku");
						scheduleReconcile();
					}
				});
			}
		}, "bg-clear-all").start();
	}

	/** 清理单个任务（手动，不受保护名单限制）。 */
	private void clearSingleTask(final KeydroidxBgManagerHelper.BgTask t) {
		if (!isAdded() || getContext() == null) return;
		final Context appCtx = requireContext().getApplicationContext();
		final Set<String> killedKeys = new HashSet<>();
		killedKeys.add(t.pkg);
		new Thread(new Runnable() {
			@Override
			public void run() {
				KeydroidxBgManagerHelper.probeShizukuSync();
				// taskId 未知（-1）：后台管理页读存活进程而非任务历史，
				// force-stop 后进程消失、卡片随之消失，无需 am stack removeTask 删任务记录。
				final boolean ok = KeydroidxBgManagerHelper.clearSingleTask(
						appCtx, t.pkg, t.pkg, -1);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						// 即时反馈：先从本地列表移除，再用真实存活列表复核
						removeKilledLocally(killedKeys);
						renderAll();
						showToast(ok ? "已清理「" + t.name + "」" : "清理失败，请检查 mini_shizuku");
						scheduleReconcile();
					}
				});
			}
		}, "bg-clear-one").start();
	}

	/** 即时从本地列表移除已清理条目（避免 dumpsys 进程记录清理滞后导致的卡片残留）。 */
	private void removeKilledLocally(Set<String> killedKeys) {
		if (killedKeys == null || killedKeys.isEmpty()) return;
		Iterator<KeydroidxBgManagerHelper.BgTask> it = tasks.iterator();
		while (it.hasNext()) {
			if (killedKeys.contains(it.next().pkg)) it.remove();
		}
		if (focusIndex >= tasks.size()) {
			focusIndex = Math.max(0, tasks.size() - 1);
		}
	}

	private void onEmptyAction() {
		KeydroidxLog.i(TAG, "空态操作入口：跳转 mini_shizuku 激活页");
		openShizukuPage();
	}

	private void openShizukuPage() {
		if (getActivity() instanceof KeydroidxDesktopActivity) {
			((KeydroidxDesktopActivity) getActivity()).openFragment(new ShizukuFragment());
		}
	}

	// ============================================================
	// KeydroidxPage（底部菜单栏声明式装配）
	// ============================================================

	@Override
	public String getPageTitle() {
		return "后台管理";
	}

	@Override
	public String getSoftLeftText() {
		// 空态（已激活但无后台进程）时无可用动作：左软键只置空文字，View 仍占位（禁用 GONE）
		if (!loading && available && tasks.isEmpty()) {
			return null;
		}
		return "选项";
	}

	@Override
	public String getSoftRightText() {
		return "退出";
	}

	@Override
	public CharSequence getSoftCenterText() {
		if (!loading && available && !tasks.isEmpty()) {
			return "切换";
		}
		return null;
	}

	// ============================================================
	// 杂项
	// ============================================================

	private void showToast(String msg) {
		Context ctx = getContext();
		if (ctx == null || msg == null) return;
		if (toast != null) toast.cancel();
		toast = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT);
		toast.show();
		KeydroidxLog.i(TAG, "Toast: " + msg);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		mainHandler.removeCallbacksAndMessages(null);
		if (toast != null) {
			toast.cancel();
			toast = null;
		}
		selectedView = null;
		cellViews = null;
		gridLayout = null;
		scroll = null;
	}
}
