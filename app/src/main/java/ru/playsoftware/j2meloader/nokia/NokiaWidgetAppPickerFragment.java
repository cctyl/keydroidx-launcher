package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.sqlite.db.SupportSQLiteProgram;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.appsdb.AppDatabase;
import ru.playsoftware.j2meloader.appsdb.AppItemDao;
import ru.playsoftware.j2meloader.config.Config;

/**
 * 桌面组件设置 → 添加组件 → 应用选择页（应用类组件添加/编辑 + Activity快捷选应用）。
 * 同一界面通过 Bundle 区分四种模式：
 * - ADD：从 S6 类型选择选「应用」进入，标题「选择应用」，确认键 addWidget → 回 S1；
 * - EDIT：从 S1 应用类组件确认键进入，标题「更换应用」，确认键 updateWidget(editIndex) → 回 S1；
 * - ACTIVITY_ADD：从 S6 类型选择选「Activity快捷」进入，标题「选择应用」，确认键 → 进入步骤2选Activity；
 * - ACTIVITY_EDIT：从 S1 Activity快捷组件确认键进入，标题「选择应用」，确认键 → 进入步骤2选Activity。
 * <p>
 * 混合展示安卓应用 + J2ME 应用（不分组，按名称排序），宫格列数 3~5 自适应、
 * 行数按实测 panelH 反推；顶部搜索框可方向键聚焦、两步激活 EditText 过滤；
 * 已添加应用灰色不可选；EDIT 模式当前编辑项浅蓝底 + 📌 角标、光标初始定位其上。
 * ACTIVITY_ADD / ACTIVITY_EDIT 模式不做已添加标记（Activity快捷可与应用组件共存）。
 */
public class NokiaWidgetAppPickerFragment extends NokiaPageFragment {

	private static final String TAG = "WidgetAppPicker";
	private static final String EXTRA_MODE = "mode";
	private static final String EXTRA_EDIT_INDEX = "editIndex";
	private static final int MODE_ADD = 0;
	private static final int MODE_EDIT = 1;
	private static final int MODE_ACTIVITY_ADD = 2;
	private static final int MODE_ACTIVITY_EDIT = 3;

	private static final int COLS_MIN = 3;
	private static final int COLS_MAX = 5;
	private static final int CELL_MIN_W_DP = 72;
	private static final int ROW_H_DP = 64;        // fallback，行高实际均分拉伸
	private static final int TITLE_H_DP = 22;      // 标题/搜索/匹配计数 预留高度（dp）
	private static final int SEARCH_H_DP = 32;
	private static final int MATCH_H_DP = 14;
	private static final int RESERVED_H_DP = TITLE_H_DP + SEARCH_H_DP + MATCH_H_DP;

	private int mode = MODE_ADD;
	private int editIndex = -1;

	private LinearLayout grid;
	private TextView tvPage;
	private EditText etSearch;
	private TextView tvMatchCount;
	private TextView tvEmpty;
	private Toast toast;

	private final List<AppEntry> allApps = new ArrayList<>();
	private final List<AppEntry> filtered = new ArrayList<>();
	private final Set<String> addedKeys = new HashSet<>();
	private String currentEditValue; // EDIT 模式当前编辑项的 widget.value

	private NokiaWidgetStorage storage;

	// 宫格参数
	private int columns = COLS_MIN;
	private int rowsPerPage = 3;
	private int perPage = 9;
	private int pageIndex = 0;
	private int totalPages = 1;
	private int focusPos = -1;        // -1 = 搜索框聚焦，>=0 = 宫格页内位置
	private boolean editing = false;  // EditText 编辑态（软键盘弹出）
	private boolean editLocateDone = false; // EDIT 模式当前编辑项是否已定位到宫格

	private FrameLayout[] cellViews;
	private ImageView[] cellIcons;
	private TextView[] cellLabels;
	private View[] cellBadges;
	private boolean[] cellAdded;
	private boolean[] cellCurrent;

	private float swipeThreshold;
	private float swipeMinVel;
	private View.OnTouchListener swipeTouchListener;

	/** 宫格项：label 显示名，icon 图标，key 身份标识（= widget.value），isJ2me 是否 JAR。 */
	private static class AppEntry {
		final String label;
		final Drawable icon;
		final String key;
		AppEntry(String label, Drawable icon, String key) {
			this.label = label;
			this.icon = icon;
			this.key = key;
		}
	}

	// ---- 创建入口 ----

	public static NokiaWidgetAppPickerFragment newAddMode() {
		NokiaWidgetAppPickerFragment f = new NokiaWidgetAppPickerFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_ADD);
		f.setArguments(b);
		return f;
	}

	public static NokiaWidgetAppPickerFragment newEditMode(int editIndex) {
		NokiaWidgetAppPickerFragment f = new NokiaWidgetAppPickerFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_EDIT);
		b.putInt(EXTRA_EDIT_INDEX, editIndex);
		f.setArguments(b);
		return f;
	}

	public static NokiaWidgetAppPickerFragment newActivityAddMode() {
		NokiaWidgetAppPickerFragment f = new NokiaWidgetAppPickerFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_ACTIVITY_ADD);
		f.setArguments(b);
		return f;
	}

	public static NokiaWidgetAppPickerFragment newActivityEditMode(int editIndex) {
		NokiaWidgetAppPickerFragment f = new NokiaWidgetAppPickerFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_ACTIVITY_EDIT);
		b.putInt(EXTRA_EDIT_INDEX, editIndex);
		f.setArguments(b);
		return f;
	}

	/** 是否处于 Activity 快捷组件的添加/编辑流程（步骤1 选应用）。 */
	private boolean isActivityMode() {
		return mode == MODE_ACTIVITY_ADD || mode == MODE_ACTIVITY_EDIT;
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_widget_app_picker;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		if (args != null) {
			mode = args.getInt(EXTRA_MODE, MODE_ADD);
			editIndex = args.getInt(EXTRA_EDIT_INDEX, -1);
		}
		NokiaLog.i(TAG, "初始化 mode=" + modeName() + " editIndex=" + editIndex);

		storage = new NokiaWidgetStorage(requireContext());
		grid = view.findViewById(R.id.appGrid);
		tvPage = view.findViewById(R.id.tvAppPage);
		etSearch = view.findViewById(R.id.etAppSearch);
		tvMatchCount = view.findViewById(R.id.tvAppMatchCount);
		tvEmpty = view.findViewById(R.id.tvAppEmpty);
		updateTitle();

		// 重置状态，防止从步骤2返回后重复加载
		pageIndex = 0;
		focusPos = -1;
		editLocateDone = false;
		editing = false;
		loadAddedKeys();
		initSearch();
		initSwipeListener(view);

		// 延迟到 midPanel 布局完成后再计算宫格参数并加载应用
		view.post(() -> {
			if (!isAdded()) return;
			computeGridParams();
			loadAndroidApps();
			sortApps();
			refreshFilteredList();
			buildPage();
			setInitialFocus();
			loadJ2meAppsAsync();
			NokiaLog.i(TAG, "应用选择页初始化完成：共 " + allApps.size() + " 项，"
					+ totalPages + " 页，每页 " + perPage + " 格（" + columns + "×" + rowsPerPage + "）");
		});
	}

	private String modeName() {
		switch (mode) {
			case MODE_EDIT: return "EDIT";
			case MODE_ACTIVITY_ADD: return "ACTIVITY_ADD";
			case MODE_ACTIVITY_EDIT: return "ACTIVITY_EDIT";
			default: return "ADD";
		}
	}

	private void updateTitle() {
		TextView tv = getView() == null ? null : getView().findViewById(R.id.tvAppPickerTitle);
		if (tv != null) {
			tv.setText(mode == MODE_EDIT ? "更换应用" : "选择应用");
		}
	}

	// ---- 已添加组件标记 ----

	private void loadAddedKeys() {
		addedKeys.clear();
		List<NokiaWidgetItem> widgets = storage.getWidgets();
		// Activity 模式下不做已添加标记（Activity快捷可与应用组件共存于不同应用）
		if (!isActivityMode()) {
			for (int i = 0; i < widgets.size(); i++) {
				NokiaWidgetItem w = widgets.get(i);
				if (w.type != NokiaWidgetItem.TYPE_APP) continue;
				if (w.value != null && !w.value.isEmpty()) {
					addedKeys.add(w.value);
				}
			}
		}
		if (mode == MODE_EDIT || mode == MODE_ACTIVITY_EDIT) {
			if (editIndex >= 0 && editIndex < widgets.size()) {
				currentEditValue = widgets.get(editIndex).value;
				NokiaLog.i(TAG, "EDIT 模式当前编辑项 value=" + currentEditValue);
			} else {
				// editIndex 越界 → 降级为添加模式
				NokiaLog.w(TAG, "editIndex 越界 " + editIndex + "/" + widgets.size() + "，降级为添加模式");
				mode = isActivityMode() ? MODE_ACTIVITY_ADD : MODE_ADD;
				updateTitle();
			}
		}
	}

	// ---- 搜索框 ----

	private void initSearch() {
		etSearch.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				NokiaLog.d(TAG, "IME 完成键：关闭软键盘，焦点回宫格第一行");
				exitEditing();
				focusPos = 0;
				applyFocus();
				return true;
			}
			return false;
		});
		etSearch.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
			@Override public void afterTextChanged(Editable s) {}
			@Override public void onTextChanged(CharSequence s, int a, int b, int c) {
				NokiaLog.d(TAG, "搜索文本变化: \"" + s + "\"");
				refreshFilteredList();
				pageIndex = 0;
				buildPage();
				if (!editing && focusPos != -1) {
					focusPos = 0;
				}
				applyFocus();
			}
		});
		// 触屏点击搜索框 = 确认键激活编辑态
		etSearch.setOnClickListener(v -> {
			NokiaLog.d(TAG, "触屏点击搜索框，激活编辑态");
			activateEditing();
		});
	}

	private void activateEditing() {
		editing = true;
		focusPos = -1;
		etSearch.setFocusable(true);
		etSearch.setFocusableInTouchMode(true);
		etSearch.requestFocus();
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
			NokiaLog.d(TAG, "已弹出软键盘（编辑态）");
		}
		applyFocus();
	}

	private void exitEditing() {
		if (!editing) return;
		editing = false;
		etSearch.clearFocus();
		etSearch.setFocusable(false);
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
		}
		NokiaLog.d(TAG, "退出编辑态，关闭软键盘");
	}

	// ---- 数据加载 ----

	/** 计算列数（按设计宽反推）与行数（按实测 panelH 反推），并分配页内缓存数组。 */
	private void computeGridParams() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;

		// 列数：clamp(3, floor(可用宽度dp/72), 5)，设计基准宽 240dp
		float availWdp = 240f;
		columns = Math.max(COLS_MIN, Math.min(COLS_MAX, (int) (availWdp / CELL_MIN_W_DP)));
		NokiaLog.i(TAG, "columns=" + columns + " availWdp=" + availWdp);

		// 行数：clamp(3, floor((availDesign - 预留) / 64), 8)，行高在 buildPage 均分拉伸
		int rows = (int) ((availDesign - RESERVED_H_DP) / ROW_H_DP);
		rows = Math.max(3, Math.min(8, rows));
		rowsPerPage = rows;
		perPage = columns * rowsPerPage;
		NokiaLog.i(TAG, "computeGridParams: rowsPerPage=" + rowsPerPage + " perPage=" + perPage
				+ " panelH=" + panelH + " scale=" + scale + " density=" + density
				+ " availDesign=" + availDesign);
	}

	/** 同步加载安卓应用（ACTION_MAIN + CATEGORY_LAUNCHER）。每次调用前清空已有数据，防止重复添加。 */
	private void loadAndroidApps() {
		allApps.clear();
		PackageManager pm = requireActivity().getPackageManager();
		Intent main = new Intent(Intent.ACTION_MAIN, null);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		// flags=0：只枚举已安装且已启用的可启动组件，不混入停用组件/卸载残留包
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
		String selfPkg = requireActivity().getPackageName();
		// 同包多 launcher 入口（如系统相机/短信、MT 的主题别名）按包名去重，
		// 避免同一应用出现多个图标
		Set<String> seenPkgs = new HashSet<>();

		for (ResolveInfo ri : list) {
			ActivityInfo ai = ri.activityInfo;
			if (ai == null) continue;
			if (ai.packageName.equals(selfPkg)) continue;
			if (!seenPkgs.add(ai.packageName)) continue;
			CharSequence labelCs = ri.loadLabel(pm);
			String label = (labelCs != null && labelCs.length() > 0) ? labelCs.toString() : ai.name;
			Drawable icon = ri.loadIcon(pm);
			String key = ai.packageName + "/" + ai.name;
			allApps.add(new AppEntry(label, icon, key));
		}
		NokiaLog.i(TAG, "加载安卓应用: " + allApps.size() + " 个");
	}

	/** 异步加载 J2ME 应用（J2ME-apps.db），完成后合入并刷新宫格。 */
	private void loadJ2meAppsAsync() {
		Single.fromCallable(() -> {
			List<AppEntry> j2me = new ArrayList<>();
			loadJ2meApps(j2me);
			return j2me;
		})
		.subscribeOn(Schedulers.io())
		.observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
		.subscribe(
			j2me -> {
				if (!isAdded()) return;
				// 去重合入：已存在的 key 跳过，防止 J2ME 异步加载与安卓列表重复
				Set<String> existingKeys = new HashSet<>();
				for (AppEntry e : allApps) existingKeys.add(e.key);
				int added = 0;
				for (AppEntry e : j2me) {
					if (!existingKeys.contains(e.key)) {
						allApps.add(e);
						added++;
					}
				}
				NokiaLog.i(TAG, "J2ME 应用加载完成：" + j2me.size() + " 个，合入 " + added + " 个，去重跳过 " + (j2me.size() - added) + " 个");
				if (added > 0) {
					sortApps();
					refreshFilteredList();
					buildPage();
					setInitialFocus();
				}
			},
			error -> NokiaLog.e(TAG, "加载 J2ME 应用失败", error)
		);
	}

	private void loadJ2meApps(List<AppEntry> out) {
		try {
			String emulatorDir = Config.getEmulatorDir();
			File dbFile = new File(emulatorDir, "J2ME-apps.db");
			if (!dbFile.exists()) {
				NokiaLog.i(TAG, "J2ME 数据库不存在，跳过 JAR 应用加载");
				return;
			}
			AppDatabase db = AppDatabase.open(requireActivity().getApplicationContext(), emulatorDir);
			AppItemDao dao = db.appItemDao();
			List<AppItem> j2meApps = dao.getAllSingle(new SimpleSortQuery()).blockingGet();
			db.close();
			for (AppItem app : j2meApps) {
				String label = app.getTitle();
				Drawable icon = null;
				String iconPath = app.getImagePathExt();
				if (iconPath != null) {
					try {
						icon = Drawable.createFromPath(iconPath);
					} catch (Exception e) {
						NokiaLog.w(TAG, "加载 J2ME 图标失败: " + iconPath);
					}
				}
				String key = "j2me:" + label + ":" + app.getPathExt();
				out.add(new AppEntry(label, icon, key));
			}
			NokiaLog.i(TAG, "加载 J2ME 应用: " + j2meApps.size() + " 个");
		} catch (Exception e) {
			NokiaLog.e(TAG, "加载 J2ME 应用失败", e);
		}
	}

	private void sortApps() {
		Collections.sort(allApps, new Comparator<AppEntry>() {
			@Override
			public int compare(AppEntry a, AppEntry b) {
				return a.label.compareToIgnoreCase(b.label);
			}
		});
	}

	private void refreshFilteredList() {
		filtered.clear();
		String q = etSearch.getText() == null ? "" : etSearch.getText().toString().trim().toLowerCase();
		for (AppEntry app : allApps) {
			if (q.isEmpty() || app.label.toLowerCase().contains(q)) {
				filtered.add(app);
			}
		}
		if (q.isEmpty()) {
			tvMatchCount.setVisibility(View.INVISIBLE);
			tvMatchCount.setText("");
		} else {
			tvMatchCount.setText("匹配 " + filtered.size() + " 个应用");
			tvMatchCount.setVisibility(View.VISIBLE);
		}
	}

	// ---- 宫格构建 ----

	private void buildPage() {
		if (grid == null) return;
		grid.removeAllViews();
		totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / perPage));
		if (pageIndex >= totalPages) pageIndex = totalPages - 1;
		if (pageIndex < 0) pageIndex = 0;

		// 行高均分拉伸：按实测可用空间计算每行实际 dp 高度
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float rowActualDp = rowsPerPage > 0 ? (availDesign - RESERVED_H_DP) / rowsPerPage : ROW_H_DP;
		int rowH = NokiaDimens.dp(getResources(), Math.round(rowActualDp));

		if (filtered.isEmpty()) {
			tvEmpty.setVisibility(View.VISIBLE);
			tvEmpty.setText(TextUtils.isEmpty(etSearch.getText()) ? "暂无应用" : "未找到匹配应用");
			tvPage.setText("1/1");
			cellViews = new FrameLayout[0];
			NokiaLog.i(TAG, "无匹配应用，显示空状态");
			return;
		}
		tvEmpty.setVisibility(View.GONE);

		int start = pageIndex * perPage;
		int count = Math.min(perPage, filtered.size() - start);
		NokiaLog.d(TAG, "buildPage 页=" + (pageIndex + 1) + "/" + totalPages
				+ " start=" + start + " count=" + count + " rows=" + rowsPerPage
				+ " rowH=" + rowH + "px rowActualDp=" + rowActualDp);

		cellViews = new FrameLayout[perPage];
		cellIcons = new ImageView[perPage];
		cellLabels = new TextView[perPage];
		cellBadges = new View[perPage];
		cellAdded = new boolean[perPage];
		cellCurrent = new boolean[perPage];

		for (int r = 0; r < rowsPerPage; r++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, rowH));

			for (int c = 0; c < columns; c++) {
				int pos = r * columns + c;
				FrameLayout cell = new FrameLayout(requireContext());
				cell.setLayoutParams(new LinearLayout.LayoutParams(
						0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

				if (pos < count) {
					AppEntry item = filtered.get(start + pos);
					boolean added = addedKeys.contains(item.key);
					boolean current = mode == MODE_EDIT && item.key.equals(currentEditValue);
					cellAdded[pos] = added;
					cellCurrent[pos] = current;

					// 内层：图标 + 名称（居中）
					LinearLayout inner = new LinearLayout(requireContext());
					inner.setOrientation(LinearLayout.VERTICAL);
					inner.setGravity(Gravity.CENTER);
					inner.setLayoutParams(new FrameLayout.LayoutParams(
							FrameLayout.LayoutParams.MATCH_PARENT,
							FrameLayout.LayoutParams.MATCH_PARENT));

					ImageView iv = new ImageView(requireContext());
					iv.setLayoutParams(new LinearLayout.LayoutParams(
							NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 36)));
					if (item.icon != null) {
						item.icon.setFilterBitmap(false);
						iv.setImageDrawable(item.icon);
					} else {
						try {
							iv.setImageDrawable(ContextCompat.getDrawable(
									requireContext(), R.mipmap.ic_launcher));
						} catch (Exception ignored) {}
					}
					inner.addView(iv);

					TextView tv = new TextView(requireContext());
					tv.setLayoutParams(new LinearLayout.LayoutParams(
							LinearLayout.LayoutParams.WRAP_CONTENT,
							LinearLayout.LayoutParams.WRAP_CONTENT));
					tv.setText(item.label);
					NokiaDimens.textSize(tv, 9);
					tv.setSingleLine(true);
					tv.setEllipsize(TextUtils.TruncateAt.END);
					tv.setMaxWidth(NokiaDimens.dp(getResources(), 72));
					inner.addView(tv);

					cell.addView(inner);

					// 右上角 📌 角标（仅当前编辑项）
					TextView badge = new TextView(requireContext());
					badge.setText("📌");
					NokiaDimens.textSize(badge, 10);
					badge.setTextColor(0xFFFFFFFF);
					FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
							FrameLayout.LayoutParams.WRAP_CONTENT,
							FrameLayout.LayoutParams.WRAP_CONTENT);
					blp.gravity = Gravity.TOP | Gravity.END;
					badge.setLayoutParams(blp);
					badge.setVisibility(current ? View.VISIBLE : View.GONE);
					cell.addView(badge);
					cellBadges[pos] = badge;

					final int fpos = pos;
					cell.setClickable(true);
					cell.setOnClickListener(v -> {
						setFocusPos(fpos);
						onSelect();
					});
					cell.setOnTouchListener(swipeTouchListener);

					cellIcons[pos] = iv;
					cellLabels[pos] = tv;
				}
				row.addView(cell);
				cellViews[pos] = cell;
			}
			grid.addView(row);
		}

		tvPage.setText((pageIndex + 1) + "/" + totalPages);
		applyFocus();
	}

	/** 按已添加/当前编辑/焦点状态刷新所有格子与搜索框外观。 */
	private void applyFocus() {
		if (etSearch != null) {
			etSearch.setBackgroundResource(
					(focusPos == -1 || editing) ? 0 : R.drawable.bg_nokia_searchbox);
		}
		if (cellViews == null) return;
		for (int i = 0; i < cellViews.length; i++) {
			FrameLayout cell = cellViews[i];
			if (cell == null) continue;
			boolean focused = (focusPos == i) && !editing && focusPos >= 0;
			styleCell(cell, i, focused);
		}
	}

	private void styleCell(FrameLayout cell, int pos, boolean focused) {
		ImageView iv = pos < cellIcons.length ? cellIcons[pos] : null;
		TextView tv = pos < cellLabels.length ? cellLabels[pos] : null;
		View badge = pos < cellBadges.length ? cellBadges[pos] : null;
		boolean added = pos < cellAdded.length && cellAdded[pos];
		boolean current = pos < cellCurrent.length && cellCurrent[pos];

		if (focused) {
			cell.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
		} else if (current) {
			cell.setBackgroundResource(R.drawable.bg_nokia_current);
		} else if (added) {
			cell.setBackgroundResource(R.drawable.bg_nokia_disabled);
		} else {
			cell.setBackgroundResource(0);
		}

		if (iv != null) {
			iv.setAlpha(added ? 0.3f : 1f);
		}
		if (tv != null) {
			tv.setTextColor(added ? 0xFF666666 : 0xFFFFFFFF);
		}
		if (badge != null) {
			badge.setVisibility(current ? View.VISIBLE : View.GONE);
		}
	}

	// ---- 焦点 / 导航 ----

	private void setFocusPos(int pos) {
		if (pos < 0 || pos >= perPage) return;
		NokiaLog.d(TAG, "setFocusPos " + focusPos + " -> " + pos + " (page=" + (pageIndex + 1) + "/" + totalPages + ")");
		focusPos = pos;
		applyFocus();
	}

	/** EDIT 模式初始定位到当前编辑项所在格；ADD 模式定位到第一格。 */
	private void setInitialFocus() {
		if ((mode == MODE_EDIT || mode == MODE_ACTIVITY_EDIT) && !editLocateDone && currentEditValue != null) {
			for (int i = 0; i < filtered.size(); i++) {
				if (currentEditValue.equals(filtered.get(i).key)) {
					pageIndex = i / perPage;
					focusPos = i % perPage;
					buildPage();
					editLocateDone = true;
					NokiaLog.i(TAG, "EDIT 初始光标定位到当前编辑项 i=" + i
							+ " page=" + (pageIndex + 1) + "/" + totalPages + " focusPos=" + focusPos);
					return;
				}
			}
			// 未找到：可能编辑项是 J2ME 应用，尚未异步加载完成，稍后重试定位。
			NokiaLog.d(TAG, "EDIT 编辑项暂未定位（可能是 J2ME 未加载完），等待异步加载后重试");
			if (focusPos < 0) {
				focusPos = 0;
				applyFocus();
			}
			return;
		}
		// 已定位过（含 ADD 模式），保持当前焦点
		if (focusPos >= 0 && focusPos < perPage) {
			NokiaLog.d(TAG, "已定位过焦点，跳过初始定位 focusPos=" + focusPos);
			return;
		}
		focusPos = 0;
		applyFocus();
	}

	private void pageNext() {
		if (!isAdded() || getView() == null) return;
		int col = focusPos % columns;
		if (pageIndex < totalPages - 1) {
			pageIndex++;
			buildPage();
			focusPos = Math.min(col, Math.max(0,
					Math.min(perPage, filtered.size() - pageIndex * perPage) - 1));
			applyFocus();
			NokiaLog.d(TAG, "翻页(下/左滑) -> " + (pageIndex + 1) + "/" + totalPages + " col=" + col);
		}
	}

	private void pagePrev() {
		if (!isAdded() || getView() == null) return;
		int col = focusPos % columns;
		if (pageIndex > 0) {
			pageIndex--;
			buildPage();
			focusPos = (rowsPerPage - 1) * columns + col;
			applyFocus();
			NokiaLog.d(TAG, "翻页(上/右滑) -> " + (pageIndex + 1) + "/" + totalPages + " col=" + col);
		}
	}

	@Override
	public boolean onDirection(int direction) {
		// 编辑态：任意方向键 → 退出编辑态、焦点回宫格第一行，再按方向移动
		if (editing) {
			NokiaLog.d(TAG, "编辑态收到方向键，退出编辑、焦点回宫格");
			exitEditing();
			focusPos = 0;
			applyFocus();
			if (direction == NokiaKeyBinding.ACTION_UP) {
				return true; // 已回到宫格第一行
			}
			return true;
		}
		// 搜索框聚焦态
		if (focusPos == -1) {
			NokiaLog.d(TAG, "搜索框聚焦态收到方向键 action="
					+ NokiaKeyBinding.getActionName(direction));
			if (direction == NokiaKeyBinding.ACTION_UP || direction == NokiaKeyBinding.ACTION_DOWN) {
				setFocusPos(0); // 焦点回宫格第一行
			}
			// 左右无效果
			return true;
		}

		int row = focusPos / columns;
		int col = focusPos % columns;
		int count = Math.min(perPage, filtered.size() - pageIndex * perPage);

		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (row == 0) {
					if (pageIndex > 0) {
						pagePrev(); // 当前页第一行 → 翻到上一页最后一排
					} else {
						focusPos = -1; // 第一页第一行 → 焦点移到搜索框
						applyFocus();
						NokiaLog.d(TAG, "onDirection 上：第一页第一行 → 搜索框");
					}
				} else if ((focusPos - columns) < count) {
					setFocusPos(focusPos - columns);
				}
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (row < rowsPerPage - 1 && (focusPos + columns) < count) {
					setFocusPos(focusPos + columns);
				} else if (pageIndex < totalPages - 1) {
					pageNext();
				}
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
				if (col > 0) {
					setFocusPos(focusPos - 1); // 到最左再按左 → 不动（不回绕）
				} else {
					NokiaLog.d(TAG, "onDirection 左：已到最左，不动");
				}
				return true;
			case NokiaKeyBinding.ACTION_RIGHT:
				if (col < columns - 1) {
					setFocusPos(focusPos + 1); // 到最右再按右 → 不动（不回绕）
				} else {
					NokiaLog.d(TAG, "onDirection 右：已到最右，不动");
				}
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean onSelect() {
		if (editing) {
			// 编辑态确认键 = IME 完成：关闭软键盘、焦点回宫格第一行
			exitEditing();
			focusPos = 0;
			applyFocus();
			return true;
		}
		if (focusPos == -1) {
			activateEditing();
			return true;
		}
		int global = pageIndex * perPage + focusPos;
		if (global < 0 || global >= filtered.size()) {
			NokiaLog.w(TAG, "onSelect 越界 global=" + global);
			return false;
		}
		AppEntry app = filtered.get(global);
		// Activity 模式不做已添加标记，所有应用可选
		if (!isActivityMode() && (cellAdded[focusPos] || cellCurrent[focusPos])) {
			NokiaLog.i(TAG, "确认键：该应用已添加/当前编辑项，不可选择 label=" + app.label);
			return true; // 消费，不响应
		}
		confirmSelection(app);
		return true;
	}

	/** 添加/更换应用组件并返回上一层（普通模式），或进入步骤2选Activity（Activity模式）。 */
	private void confirmSelection(AppEntry app) {
		NokiaLog.i(TAG, "选中应用 label=" + app.label + " key=" + app.key);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();

		if (isActivityMode()) {
			// Activity 模式：选中应用 → 进入步骤2（选择 Activity）
			String pkg = extractPackageName(app.key);
			NokiaLog.i(TAG, "Activity模式：进入步骤2 选Activity pkg=" + pkg + " appLabel=" + app.label);
			NokiaWidgetActivityPickerFragment picker;
			if (mode == MODE_ACTIVITY_EDIT) {
				picker = NokiaWidgetActivityPickerFragment.newEditMode(pkg, app.label, editIndex);
			} else {
				picker = NokiaWidgetActivityPickerFragment.newAddMode(pkg, app.label);
			}
			host.openFragment(picker);
			return;
		}

		if (mode == MODE_EDIT) {
			// EDIT 模式：栈为 S1 → AppPicker，pop 一层回到 S1
			NokiaWidgetItem item = new NokiaWidgetItem(NokiaWidgetItem.TYPE_APP, app.label, app.key);
			storage.updateWidget(editIndex, item);
			showToast("已更换为 " + app.label);
			host.exitCurrent();
		} else {
			if (storage.isFull()) {
				NokiaLog.w(TAG, "组件已达上限，拒绝添加");
				showToast("组件已达上限");
				return;
			}
			NokiaWidgetItem item = new NokiaWidgetItem(NokiaWidgetItem.TYPE_APP, app.label, app.key);
			storage.addWidget(item);
			showToast("已添加 " + app.label);
			// ADD 模式：栈为 S1 → 类型选择 → AppPicker，需 pop 两层回到 S1（跳过类型选择页）。
			// exitCurrent 走异步 popBackStack，连续两次不可靠，这里用同步 popBackStackImmediate。
			FragmentManager fm = host.getSupportFragmentManager();
			int entries = fm.getBackStackEntryCount();
			if (entries > 1) {
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "ADD 确认后同步出栈 2 层，回到 S1");
			} else {
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "ADD 确认后出栈 1 层");
			}
		}
	}

	/** 从 app.key（格式：pkg/cls）中提取包名。 */
	private String extractPackageName(String key) {
		if (key == null) return "";
		int slash = key.indexOf('/');
		return slash >= 0 ? key.substring(0, slash) : key;
	}

	@Override
	public boolean onSoftLeft() {
		return false; // 左软键（空）
	}

	@Override
	public boolean onSoftRight() {
		if (editing) {
			exitEditing(); // 关闭软键盘 + 返回
		}
		NokiaLog.i(TAG, "右软键：返回上一层");
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		if (editing) {
			exitEditing(); // 关闭软键盘，焦点留在搜索框（回退到焦点态）
			applyFocus();
			return true;
		}
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return mode == MODE_EDIT ? "更换应用" : "选择应用";
	}

	@Override
	public String getSoftLeftText() {
		return null;
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	/** 右软键：Activity编辑模式回到 S1，其他模式回到上一层。 */
	private void doExit() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		if (mode == MODE_ACTIVITY_EDIT) {
			// 编辑模式：回到 S1（出栈一层）
			host.exitCurrent();
		} else {
			// 添加模式：回到 S6 类型选择页（出栈一层）
			host.exitCurrent();
		}
	}

	// ---- 触摸滑动翻页 ----

	private void initSwipeListener(View root) {
		swipeThreshold = NokiaDimens.dp(getResources(), 24);
		swipeMinVel = 0.35f;
		swipeTouchListener = new View.OnTouchListener() {
			private float downX, downY;
			private long downTime;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				switch (event.getAction()) {
					case MotionEvent.ACTION_DOWN:
						downX = event.getX();
						downY = event.getY();
						downTime = event.getEventTime();
						return !v.isClickable();
					case MotionEvent.ACTION_UP: {
						float dx = event.getX() - downX;
						float dy = event.getY() - downY;
						long dt = event.getEventTime() - downTime;
						float dist = Math.max(Math.abs(dx), Math.abs(dy));
						float vel = dt > 0 ? dist / (float) dt : 0f;
						if (dist >= swipeThreshold
								|| (dist >= swipeThreshold * 0.5f && vel >= swipeMinVel)) {
							if (Math.abs(dx) >= Math.abs(dy)) {
								if (dx < 0) pageNext(); else pagePrev();
							} else {
								if (dy < 0) pageNext(); else pagePrev();
							}
							return true;
						}
						return false;
					}
					default:
						return false;
				}
			}
		};
		root.setOnTouchListener(swipeTouchListener);
		View mid = requireActivity().findViewById(R.id.midPanel);
		if (mid != null) {
			mid.setOnTouchListener(swipeTouchListener);
		}
		NokiaLog.d(TAG, "滑动翻页监听已挂载（根视图 + midPanel + 每个 cell 复用）");
	}

	// ---- 工具 ----

	private void showToast(String msg) {
		if (toast != null) toast.cancel();
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		NokiaLog.i(TAG, "Toast: " + msg);
	}

	/** Simple SupportSQLiteQuery for "SELECT * FROM apps ORDER BY title" */
	private static class SimpleSortQuery implements SupportSQLiteQuery {
		@Override
		public String getSql() {
			return "SELECT * FROM apps ORDER BY title ASC";
		}

		@Override
		public void bindTo(SupportSQLiteProgram statement) {}

		@Override
		public int getArgCount() { return 0; }
	}
}
