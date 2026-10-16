package ru.playsoftware.j2meloader.nokia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

	/** 卡片网格列数。 */
	private static final int COLUMNS = 2;
	/** 卡片总高（dp）：头部行 + 预览区。 */
	private static final int CARD_H_DP = 70;
	/** 头部行高（dp）。 */
	private static final int HEAD_H_DP = 20;
	/** 头部图标 / 预览区水印图标尺寸（dp）。 */
	private static final int ICON_DP = 13;
	private static final int PREVIEW_ICON_DP = 22;
	/** 保护盾牌角标尺寸（dp）。 */
	private static final int PROT_FLAG_DP = 11;

	private static final int COLOR_TEXT = 0xFFE8EEF5;
	private static final int COLOR_SUB = 0xFF90CAF9;
	private static final int COLOR_ACCENT = 0xFF64B5F6;
	private static final int COLOR_OK = 0xFF7DE2A0;
	private static final int COLOR_WARN = 0xFFFF8A80;

	private LinearLayout gridLayout;
	private ScrollView scroll;
	private TextView tvMode;
	private TextView tvHint;
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
				: "确认键切换 · 5 清理选中 · 0 清理全部\n拨号键呼出本页（桌面按下即进入）");
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

	/** 构建 2 列卡片网格（行 + 均分宽度的 cell）。 */
	private void buildGrid(Context ctx) {
		gridLayout.removeAllViews();
		cellViews = new View[tasks.size()];
		int cardH = KeydroidxDimens.dp(getResources(), CARD_H_DP);
		int gap = KeydroidxDimens.dp(getResources(), 6);

		LinearLayout row = null;
		for (int i = 0; i < tasks.size(); i++) {
			if (i % COLUMNS == 0) {
				row = new LinearLayout(ctx);
				row.setOrientation(LinearLayout.HORIZONTAL);
				LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, cardH);
				if (i > 0) rlp.topMargin = gap;
				row.setLayoutParams(rlp);
				gridLayout.addView(row);
			}
			View cell = createCard(ctx, tasks.get(i));
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
			if (i % COLUMNS != 0) clp.setMarginStart(gap);
			cell.setLayoutParams(clp);
			final int index = i;
			cell.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});
			if (row != null) row.addView(cell);
			cellViews[i] = cell;
		}
		// 末行不满 2 个时补透明占位，避免末卡独占全宽
		if (tasks.size() % COLUMNS != 0 && row != null) {
			View filler = new Space(ctx);
			LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
					0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
			flp.setMarginStart(gap);
			filler.setLayoutParams(flp);
			row.addView(filler);
		}
	}

	/**
	 * 单张后台管理卡：头部行（图标 + 应用名 + 装饰 ×）+ 预览区
	 * （居中半透明应用图标作占位）。已保护盾牌角标位于预览区右上角。
	 */
	private View createCard(Context ctx, KeydroidxBgManagerHelper.BgTask t) {
		FrameLayout cell = new FrameLayout(ctx);
		cell.setClickable(true);
		cell.setBackground(createCardBackground(ctx));
		int padH = KeydroidxDimens.dp(getResources(), 5);
		int padV = KeydroidxDimens.dp(getResources(), 3);

		LinearLayout inner = new LinearLayout(ctx);
		inner.setOrientation(LinearLayout.VERTICAL);
		inner.setPadding(padH, padV, padH, padV);
		inner.setLayoutParams(new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		// 头部行：小图标 + 应用名（撑满剩余宽）+ 装饰 ×
		LinearLayout head = new LinearLayout(ctx);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				KeydroidxDimens.dp(getResources(), HEAD_H_DP)));

		ImageView icon = new ImageView(ctx);
		int iconPx = KeydroidxDimens.dp(getResources(), ICON_DP);
		icon.setLayoutParams(new LinearLayout.LayoutParams(iconPx, iconPx));
		icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
		icon.setImageDrawable(t.icon != null
				? t.icon
				: KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_APP, 0xFFFFFFFF, ICON_DP));
		head.addView(icon);

		TextView name = new TextView(ctx);
		LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		np.setMarginStart(KeydroidxDimens.dp(getResources(), 3));
		np.setMarginEnd(KeydroidxDimens.dp(getResources(), 2));
		name.setLayoutParams(np);
		name.setText(t.name);
		name.setTextColor(COLOR_TEXT);
		KeydroidxFontManager.textSize(name, 11);
		name.setSingleLine(true);
		name.setEllipsize(TextUtils.TruncateAt.END);
		head.addView(name);

		// 右上角 ×：纯装饰（对齐系统最近任务样式），不可聚焦不可点
		TextView close = new TextView(ctx);
		close.setText("×");
		close.setTextColor(COLOR_SUB);
		KeydroidxFontManager.textSize(close, 13);
		close.setIncludeFontPadding(false);
		close.setClickable(false);
		close.setFocusable(false);
		close.setContentDescription(null);
		LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		xlp.gravity = Gravity.CENTER_VERTICAL;
		xlp.setMarginStart(KeydroidxDimens.dp(getResources(), 2));
		close.setLayoutParams(xlp);
		head.addView(close);

		inner.addView(head);

		// 预览区：占满卡片剩余高度
		FrameLayout preview = new FrameLayout(ctx);
		LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
		plp.topMargin = KeydroidxDimens.dp(getResources(), 3);
		preview.setLayoutParams(plp);
		preview.setBackground(createPreviewBackground(ctx));

		ImageView watermark = new ImageView(ctx);
		int wPx = KeydroidxDimens.dp(getResources(), PREVIEW_ICON_DP);
		FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(wPx, wPx);
		wlp.gravity = Gravity.CENTER;
		watermark.setLayoutParams(wlp);
		watermark.setScaleType(ImageView.ScaleType.FIT_CENTER);
		watermark.setImageDrawable(t.icon != null
				? t.icon
				: KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_APP, 0xFFFFFFFF, PREVIEW_ICON_DP));
		watermark.setAlpha(0.3f);
		preview.addView(watermark);

		inner.addView(preview);

		cell.addView(inner);

		if (protectedSet.contains(t.pkg)) {
			ImageView flag = new ImageView(ctx);
			FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
					KeydroidxDimens.dp(getResources(), PROT_FLAG_DP),
					KeydroidxDimens.dp(getResources(), PROT_FLAG_DP));
			fp.gravity = Gravity.TOP | Gravity.END;
			fp.topMargin = padV + KeydroidxDimens.dp(getResources(), HEAD_H_DP)
					+ KeydroidxDimens.dp(getResources(), 3);
			fp.rightMargin = KeydroidxDimens.dp(getResources(), 3);
			flag.setLayoutParams(fp);
			flag.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_SHIELD, COLOR_OK, PROT_FLAG_DP));
			cell.addView(flag);
		}
		return cell;
	}

	/** 卡片常态底色：主题 cardBgColor + 圆角（焦点态改用 {@link KeydroidxTheme#createSelectionDrawable}）。 */
	private Drawable createCardBackground(Context ctx) {
		KeydroidxTheme.ThemeDef theme = KeydroidxTheme.getCurrentTheme(ctx);
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(theme.cardBgColor);
		gd.setCornerRadius(KeydroidxDimens.dp(getResources(), 6));
		return gd;
	}

	/** 预览区底色：卡片底色压暗，与头部形成层次。 */
	private Drawable createPreviewBackground(Context ctx) {
		KeydroidxTheme.ThemeDef theme = KeydroidxTheme.getCurrentTheme(ctx);
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(darken(theme.cardBgColor, 0.65f));
		gd.setCornerRadius(KeydroidxDimens.dp(getResources(), 4));
		return gd;
	}

	/** 颜色按比例压暗（保留 alpha）。 */
	private static int darken(int color, float factor) {
		int r = (int) (((color >> 16) & 0xFF) * factor);
		int g = (int) (((color >> 8) & 0xFF) * factor);
		int b = (int) ((color & 0xFF) * factor);
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
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
			selectedView.setBackground(createCardBackground(ctx));
			selectedView = null;
		}
		if (index < 0 || index >= cellViews.length || cellViews[index] == null) return;
		cellViews[index].setBackground(KeydroidxTheme.createSelectionDrawable(ctx, 6));
		selectedView = cellViews[index];
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
				next = focusIndex - 1;
				break;
			case KeydroidxKeyBinding.ACTION_RIGHT:
				next = focusIndex + 1;
				break;
			case KeydroidxKeyBinding.ACTION_UP:
				next = focusIndex - COLUMNS;
				break;
			case KeydroidxKeyBinding.ACTION_DOWN:
				next = focusIndex + COLUMNS;
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
