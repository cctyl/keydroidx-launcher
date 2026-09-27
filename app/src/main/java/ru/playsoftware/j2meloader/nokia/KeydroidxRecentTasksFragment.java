package ru.playsoftware.j2meloader.nokia;

import android.app.Activity;
import android.content.Context;
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
import java.util.List;
import java.util.Set;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;
import io.github.cctyl.nokia.common.ui.KeydroidxIcons;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;

import ru.playsoftware.j2meloader.R;

/**
 * 「最近任务」卡片页（桌面主界面 / 功能表内按绑定键进入；桌面「最近任务」组件亦进入本页）。
 *
 * <p><b>交互（与设计稿 docs/recent-apps/prototype.html 一致）：</b></p>
 * <ul>
 *   <li>2 列最近任务卡：头部（图标 + 应用名 + 最近时间）+ 预览区（应用图标水印作「截图」占位），
 *       视觉对齐系统最近任务而非应用列表；已保护应用预览区右上角绿色盾牌；</li>
 *   <li>方向键循环移动焦点；确认键把该应用恢复到前台（不重启，回到它之前的界面）；</li>
 *   <li>左软键 = 选项（打开 / 保护此应用 / 刷新 / 清理此任务 / 清理全部任务）；</li>
 *   <li>右软键 / 返回键 = 退出；</li>
 *   <li><b>数字键 5 = 清理选中的任务（无二次确认，不受保护名单限制）</b>；
 *       <b>数字键 0 = 一键清理未保护任务（无二次确认）</b>。</li>
 * </ul>
 *
 * <p><b>保护名单语义：</b>持久化于 {@link KeydroidxSettingsStorage}，重启不丢。
 * 保护只挡批量清理（0 键 / 清理全部）；「清理此任务」是手动清理，<b>不受保护名单限制</b>，
 * 因为它是用户对单个应用的显式操作。</p>
 *
 * <p>规范要点：软键栏无高亮、空栏只置空文字不用 {@code GONE}；尺寸走 {@link KeydroidxDimens#dp}；
 * 图标走 {@link KeydroidxIcons}；卡片底色与焦点高亮来自 {@link KeydroidxTheme}；
 * 枚举含 TCP / PackageManager 调用，一律后台线程执行后回主线程渲染。</p>
 */
public class KeydroidxRecentTasksFragment extends KeydroidxPageFragment {

	private static final String TAG = "RecentTasksPage";

	/** 卡片网格列数。 */
	private static final int COLUMNS = 2;
	/** 卡片总高（dp）：头部行（图标+应用名+时间）+ 预览区，模拟系统最近任务卡。 */
	private static final int CARD_H_DP = 70;
	/** 头部行高（dp）。 */
	private static final int HEAD_H_DP = 20;
	/** 头部图标 / 预览区水印图标尺寸（dp）。 */
	private static final int ICON_DP = 13;
	private static final int PREVIEW_ICON_DP = 22;
	/** 保护盾牌角标尺寸（dp）。 */
	private static final int PROT_FLAG_DP = 11;

	// 主题未提供「成功 / 警示」语义色，这里沿用同一组字面量，
	// 避免同类状态在不同页面出现两种颜色。卡片底色与焦点高亮仍取自 KeydroidxTheme。
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

	private final List<KeydroidxRecentTasksHelper.RecentTask> tasks = new ArrayList<>();
	private Set<String> protectedSet = new HashSet<>();
	private KeydroidxSettingsStorage settingsStorage;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private View[] cellViews;
	private View selectedView;
	private int focusIndex = -1;
	private int mode = KeydroidxRecentTasksHelper.MODE_UNAVAILABLE;
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

		// 防「进入界面后首个方向键被吞」：根视图必须可聚焦并持焦
		requestRootFocus(view);
		loadAsync();
		KeydroidxLog.i(TAG, "最近任务页初始化完成");
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
				final int loadedMode = KeydroidxRecentTasksHelper.getMode(appCtx);
				final List<KeydroidxRecentTasksHelper.RecentTask> loaded =
						KeydroidxRecentTasksHelper.enumerate(appCtx);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						mode = loadedMode;
						loading = false;
						tasks.clear();
						tasks.addAll(loaded);
						if (settingsStorage != null) {
							protectedSet = new HashSet<>(settingsStorage.getProtectedPackages());
						}
						renderAll();
						KeydroidxLog.i(TAG, "渲染完成: mode=" + mode + " 条目=" + tasks.size());
					}
				});
			}
		}, "recent-tasks-load").start();
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

	/** 标题右侧数据模式徽标 + 摘要行。 */
	private void renderHeader() {
		if (tvMode == null) return;
		tvMode.setText(KeydroidxRecentTasksHelper.getModeLabel(mode));
		tvMode.setTextColor(mode == KeydroidxRecentTasksHelper.MODE_REAL_TASK ? COLOR_OK : COLOR_WARN);

		// 操作提示统一放在顶部信息区；第二行说明拨号键入口（拨号键即「最近任务」的桌面入口）
		tvHint.setText(loading || mode == KeydroidxRecentTasksHelper.MODE_UNAVAILABLE
				? ""
				: "确认键回到应用 · 5 清理选中 · 0 清理全部\n拨号键呼出本页（桌面按下即进入）");
	}

	/** 空态 / 未激活态 / 加载态（三者共用同一容器，文案与图标按状态切换）。 */
	private void showEmptyState(Context ctx) {
		scroll.setVisibility(View.GONE);
		emptyBox.setVisibility(View.VISIBLE);

		if (loading) {
			emptyIcon.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_HOURGLASS, COLOR_ACCENT, 26));
			emptyTitle.setText("正在读取最近任务…");
			emptySub.setText("");
			emptyAction.setVisibility(View.GONE);
			return;
		}
		if (mode == KeydroidxRecentTasksHelper.MODE_UNAVAILABLE) {
			emptyIcon.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_WARNING, COLOR_WARN, 26));
			emptyTitle.setText("未激活 mini_shizuku");
			emptySub.setText("Android 5.0+ 需要 mini_shizuku（shell 权限）才能读取系统真实任务列表。");
			emptyAction.setText("去激活 mini_shizuku");
			emptyAction.setVisibility(View.VISIBLE);
			return;
		}
		emptyIcon.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_BG_MANAGER, COLOR_ACCENT, 26));
		emptyTitle.setText("暂无最近任务");
		emptySub.setText("打开应用后，它们会出现在这里。\n按右软键返回桌面。");
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
		// 末行不满 2 个时补透明占位：卡片宽度由 weight 均分决定，
		// 若最后一个 cell 独占一行会吞掉整行剩余宽度、被撑成全宽卡片。
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
	 * 单张最近任务卡：头部行（图标 + 应用名 + 最近时间）+ 预览区
	 * （居中大号半透明应用图标充当「截图」占位），视觉对齐系统最近任务而非应用列表。
	 * 已保护盾牌角标位于预览区右上角。
	 */
	private View createCard(Context ctx, KeydroidxRecentTasksHelper.RecentTask t) {
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

		// 头部行：小图标 + 应用名（撑满剩余宽）+ 距今时间
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

		// 右上角 ×：纯装饰（对齐系统最近任务样式），不可聚焦不可点，
		// 点击落在整卡上仍是「回到应用」；清理走数字键 5 或选项菜单
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

		// 预览区：占满卡片剩余高度，模拟系统最近任务的「截图」区域
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

		// 「距今多久」放在预览区左下角（头部行右侧让位给装饰 ×）
		String ago = formatAgo(t.agoMs);
		if (!ago.isEmpty()) {
			TextView time = new TextView(ctx);
			FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			tlp.gravity = Gravity.BOTTOM | Gravity.START;
			tlp.setMargins(KeydroidxDimens.dp(getResources(), 3), 0,
					0, KeydroidxDimens.dp(getResources(), 2));
			time.setLayoutParams(tlp);
			time.setText(ago);
			time.setTextColor(COLOR_SUB);
			KeydroidxFontManager.textSize(time, 8);
			preview.addView(time);
		}
		inner.addView(preview);

		cell.addView(inner);

		if (protectedSet.contains(t.taskKey)) {
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

	/** 预览区底色：卡片底色压暗，模拟系统最近任务的「截图」区域，与头部形成层次。 */
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

	/** 「距今多久」文案；未知（&lt;=0）返回空串，卡片不显示时间行。 */
	private static String formatAgo(long agoMs) {
		if (agoMs <= 0) return "";
		long sec = agoMs / 1000;
		if (sec < 60) return "刚刚";
		long min = sec / 60;
		if (min < 60) return min + " 分钟前";
		long hour = min / 60;
		if (hour < 24) return hour + " 小时前";
		long day = hour / 24;
		if (day < 30) return day + " 天前";
		return "";
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

	private KeydroidxRecentTasksHelper.RecentTask focusedTask() {
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
		KeydroidxRecentTasksHelper.RecentTask t = focusedTask();
		if (t == null) return false;
		KeydroidxLog.i(TAG, "确认键：恢复应用 " + t.name + " (" + t.pkg + ")");
		return bringToFront(t);
	}

	@Override
	public boolean onSoftLeft() {
		showOptions();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		KeydroidxLog.i(TAG, "右软键：退出最近任务");
		exitSelf();
		return true;
	}

	@Override
	public boolean onBack() {
		KeydroidxLog.i(TAG, "返回键：退出最近任务");
		exitSelf();
		return true;
	}

	private void exitSelf() {
		if (getActivity() instanceof KeydroidxDesktopActivity) {
			((KeydroidxDesktopActivity) getActivity()).exitCurrent();
		}
	}

	// ============================================================
	// 选项菜单 / 恢复 / 保护 / 清理
	// ============================================================

	private void showOptions() {
		final KeydroidxRecentTasksHelper.RecentTask cur = focusedTask();
		boolean unavailable = mode == KeydroidxRecentTasksHelper.MODE_UNAVAILABLE;
		List<KeydroidxOptionsDialog.OptionItem> items = new ArrayList<>();

		if (unavailable) {
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_SHIZUKU,
					"激活 mini_shizuku", true, false, this::openShizukuPage));
		} else if (cur != null) {
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_PLAY,
					"打开应用", true, false, () -> bringToFront(cur)));
			final boolean prot = protectedSet.contains(cur.taskKey);
			items.add(new KeydroidxOptionsDialog.OptionItem(
					prot ? KeydroidxIcons.ICON_LOCK_OPEN : KeydroidxIcons.ICON_SHIELD,
					prot ? "取消保护" : "保护此应用", true, false, () -> toggleProtect(cur)));
		}
		items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_REFRESH,
				"刷新列表", true, false, this::loadAsync));
		if (!unavailable && cur != null) {
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_DELETE,
					"清理此任务", true, false, () -> confirmClearTask(cur)));
			items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_CLEAR_ALL,
					"清理全部任务", true, false, this::clearAllUnprotected));
		}

		String title = (cur != null && !unavailable) ? "选项 · " + cur.name : "选项";
		KeydroidxOptionsDialog.show(getParentFragmentManager(), title, items);
		KeydroidxLog.i(TAG, "弹出选项菜单: " + title + " 项数=" + items.size());
	}

	private boolean bringToFront(KeydroidxRecentTasksHelper.RecentTask t) {
		Activity act = getActivity();
		if (act == null) return false;
		// 恢复语义：重新下发启动 Intent，系统把已存在的任务栈拉回前台（不重启）
		return KeydroidxRecentTasksHelper.bringToFront(act, t);
	}

	/** 切换保护状态并持久化。 */
	private void toggleProtect(KeydroidxRecentTasksHelper.RecentTask t) {
		boolean wasProtected = protectedSet.contains(t.taskKey);
		if (wasProtected) {
			protectedSet.remove(t.taskKey);
		} else {
			protectedSet.add(t.taskKey);
		}
		if (settingsStorage != null) settingsStorage.setProtectedPackages(protectedSet);
		showToast(wasProtected ? "已取消保护「" + t.name + "」" : "已保护「" + t.name + "」");
		KeydroidxLog.i(TAG, "保护状态变更: " + t.taskKey + " -> " + (wasProtected ? "未保护" : "已保护"));
		renderAll();
	}

	/**
	 * 「清理此任务」二次确认。手动清理单个应用不受保护名单限制，属破坏性操作，需显式确认。
	 * <p>按 FEATURE_PHONE_UI_SPEC §16：更安全的选项（取消）置于左软键；确认键与左软键等效，
	 * 因此误按确认键只会取消，破坏性动作必须显式按<b>右软键</b>。
	 */
	private void confirmClearTask(final KeydroidxRecentTasksHelper.RecentTask t) {
		Context ctx = getContext();
		if (ctx == null) return;
		boolean prot = protectedSet.contains(t.taskKey);
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
		KeydroidxRecentTasksHelper.RecentTask t = focusedTask();
		if (t == null) {
			showToast("没有选中的任务");
			return true;
		}
		KeydroidxLog.i(TAG, "数字键 5：清理选中任务 " + t.taskKey);
		clearSingleTask(t);
		return true;
	}

	/**
	 * 清理全部未保护任务（<b>无二次确认</b>；保护名单自动跳过）。必须在后台线程执行 shell。
	 *
	 * <p>清理对象是<b>本页列出的任务</b>而不是「正在运行的进程」：
	 * {@link KeydroidxBgManagerHelper#clearBackgroundTasks} 只枚举存活进程，而最近任务列表是
	 * 任务历史——被列出但进程已死的应用清不掉任何东西，用户会以为按键没反应。
	 * 因此这里逐个调 {@link KeydroidxBgManagerHelper#clearSingleTask}：它除了
	 * {@code am force-stop} 杀进程，还会把对应的系统任务记录一并删除
	 * （Android 5.0+ 走 {@code am stack remove}）。只 force-stop 是不够的——
	 * 实测任务记录（{@code sz=0} 空壳）仍留在 recents 里，卡片不会消失。</p>
	 */
	private void clearAllUnprotected() {
		if (!isAdded() || getContext() == null) return;
		final List<KeydroidxRecentTasksHelper.RecentTask> targets = new ArrayList<>();
		for (KeydroidxRecentTasksHelper.RecentTask t : tasks) {
			if (!protectedSet.contains(t.taskKey)) targets.add(t);
		}
		if (targets.isEmpty()) {
			showToast(tasks.isEmpty() ? "没有可清理的任务" : "可清理的应用都已被保护");
			return;
		}
		final Context appCtx = requireContext().getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				KeydroidxBgManagerHelper.probeShizukuSync();
				if (!KeydroidxBgManagerHelper.isBgManagerAvailable()) {
					mainHandler.post(new Runnable() {
						@Override
						public void run() {
							if (isAdded()) showToast("请先激活 mini_shizuku");
						}
					});
					return;
				}
				int cleared = 0;
				for (KeydroidxRecentTasksHelper.RecentTask t : targets) {
					if (KeydroidxBgManagerHelper.clearSingleTask(appCtx, t.pkg, t.taskKey, t.taskId)) cleared++;
				}
				final int n = cleared;
				KeydroidxLog.i(TAG, "一键清理完成: 目标=" + targets.size() + " 成功=" + n);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						showToast(n > 0 ? "已清理 " + n + " 个任务" : "清理失败，请检查 mini_shizuku");
						loadAsync();
					}
				});
			}
		}, "recent-clear-all").start();
	}

	/** 清理单个任务（手动，不受保护名单限制）。 */
	private void clearSingleTask(final KeydroidxRecentTasksHelper.RecentTask t) {
		if (!isAdded() || getContext() == null) return;
		final Context appCtx = requireContext().getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				KeydroidxBgManagerHelper.probeShizukuSync();
				final boolean ok = KeydroidxBgManagerHelper.clearSingleTask(appCtx, t.pkg, t.taskKey, t.taskId);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						showToast(ok ? "已清理「" + t.name + "」" : "清理失败，请检查 mini_shizuku");
						loadAsync();
					}
				});
			}
		}, "recent-clear-one").start();
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
		return "最近任务";
	}

	@Override
	public String getSoftLeftText() {
		// 空态（数据源可用但无任务）时无可用动作：左软键只置空文字，View 仍占位（禁用 GONE）
		if (!loading && mode != KeydroidxRecentTasksHelper.MODE_UNAVAILABLE && tasks.isEmpty()) {
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
