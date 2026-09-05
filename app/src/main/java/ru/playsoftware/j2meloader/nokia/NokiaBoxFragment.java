package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.NokiaFontManager;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerFragment;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.installer.NokiaInstallerDialog;

/**
 * 应用程序中间内容碎片。
 * 网格模式展示"安装jar"入口 + JAR全局设置 + 已装 JAR 应用网格。
 * 确认键直接启动应用，左软键弹出选项菜单（启动/设置/卸载）。
 * 方向键导航，复用 J2ME-Loader 原有的安装与启动逻辑。
 */
public class NokiaBoxFragment extends NokiaPageFragment {

	// ---- 网格常量 ----
	private static final int COLS = 3;
	/** 行高由实际可用空间均分，此常量仅作为 fallback（panelH 尚未可用时）。图标 36 + 标签 9 + 间距 */
	private static final int ROW_H_DP = 64;
	private static final int TITLE_H_DP = 20;

	// ---- 视图 ----
	private ScrollView appScroll;
	private LinearLayout appContainer;

	// ---- 网格模式 ----
	private View[] gridCellViews;
	private int rowsPerPage = 4;
	private int perPage = COLS * rowsPerPage;
	private int totalGridCells = 0;
	private int focusIndex = -1;
	private View selectedView = null;

	// ---- 数据 ----
	private AppRepository appRepository;
	private List<AppItem> appItems = new ArrayList<>();
	private SharedPreferences preferences;

	/**
	 * 进程内 JAR 应用列表缓存（跨 Fragment 实例复用）。
	 * <p>
	 * 原先每次进入都是新实例 → view.post 才订阅 Room → 异步查询回调后才有内容，
	 * 期间 appContainer 为空——这就是「打开应用程序先空白一瞬」的根因。
	 * 缓存后再次进入零查询直接出图；数据库回调仍是权威数据，内容变化时才重建。
	 */
	private static final List<AppItem> cachedAppItems = new ArrayList<>();

	/**
	 * 已解码的 JAR 图标缓存（key = 图标路径 + ":" + 文件 mtime）。
	 * populateAppCell 原先用 {@code Drawable.createFromPath} 主线程解码，
	 * 每次进入都对每个 JAR 重跑一遍磁盘 IO + PNG 解码。
	 * key 带 mtime：覆盖安装同一 JAR 时图标文件被重写（路径不变），
	 * mtime 变化使旧缓存自然失效，避免显示旧图标。
	 */
	private static final Map<String, Drawable> cachedIcons = new HashMap<>();

	/** 图标缓存 key：路径 + 修改时间。覆盖安装后 mtime 变化即换新 key。 */
	private static String iconCacheKey(String imgPath) {
		if (imgPath == null) return null;
		return imgPath + ":" + new File(imgPath).lastModified();
	}

	// ---- 文件选择器 ----
	private ActivityResultLauncher<String> openFileLauncher;

	// ============================
	// 生命周期
	// ============================

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		NokiaLog.i("Box", "onCreate");

		// 注册文件选择器（必须在 onCreate 前/内注册）
		openFileLauncher = registerForActivityResult(
				FileUtils.getFilePicker(), this::onPickFileResult);

		preferences = PreferenceManager.getDefaultSharedPreferences(requireActivity());

		// 获取 AppRepository
		AppListModel appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_box;
	}

	@Override
	protected boolean isTopAlign() {
		// 百宝箱垂直居中（内容矮于面板时居中，不贴顶）
		return false;
	}

	@Override
	protected int getWallpaperRes() {
		return R.drawable.bg_nokia_box;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		appScroll = view.findViewById(R.id.appScroll);
		appContainer = view.findViewById(R.id.appContainer);

		// 有进程内缓存时同步构建一版：零查询、零磁盘 IO，首帧直接出图。
		// Room 订阅仍在 view.post 里照常进行，数据回来后内容一致则不重建。
		if (!cachedAppItems.isEmpty()) {
			appItems = new ArrayList<>(cachedAppItems);
			buildGrid();
			NokiaLog.i("Box", "复用进程内 JAR 列表缓存，首帧直接构建：" + appItems.size() + " 个应用");
		}

		// 延迟到 midPanel 布局完成后再计算行数并订阅数据（panelH 需要实测反推）
		view.post(() -> {
			if (!isAdded()) return;
			int oldRows = rowsPerPage;
			computeRowsPerPage();
			if (rowsPerPage != oldRows && !appItems.isEmpty()) {
				// 行数变化影响行高均分结果，重建一次（纯 View 操作，无 IO）
				buildGrid();
			}
			// 订阅已安装 JAR 应用数据（数据回调会触发 onDbUpdated）
			appRepository.observeApps(getViewLifecycleOwner(), this::onDbUpdated);
			NokiaLog.i("Box", "应用程序初始化完成（延迟到 panelH 可用），等待数据加载…");
		});
	}

	// ============================
	// 分辨率自适应
	// ============================

	/**
	 * 用实测 midPanel 像素高度反推行数空间预算（与菜单一致），不再使用估算公式。
	 * 百宝箱使用 ScrollView，rowsPerPage 仅用于方向键导航时的行数参考。
	 */
	private void computeRowsPerPage() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		if (panelH <= 0) {
			NokiaLog.w("Box", "computeRowsPerPage: panelH 尚未可用，保持默认 rowsPerPage=" + rowsPerPage);
			return;
		}
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float fontScale = NokiaSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1.0f;

		// 随字体缩放动态扩充行高预算，字体越大行高预留越足
		float dynamicRowHDp = ROW_H_DP + Math.max(0f, (fontScale - 1.0f) * 16f);

		float availDesign = panelH / density / scale;
		int rows = (int) ((availDesign - TITLE_H_DP) / dynamicRowHDp);
		rows = Math.max(2, Math.min(8, rows));
		rowsPerPage = rows;
		perPage = COLS * rowsPerPage;
		NokiaLog.i("Box", "computeRowsPerPage: rowsPerPage=" + rowsPerPage
				+ " panelH=" + panelH + " scale=" + scale + " density=" + density
				+ " fontScale=" + fontScale + " dynamicRowHDp=" + dynamicRowHDp
				+ " availDesign=" + availDesign);
	}

	// ============================
	// 数据回调
	// ============================

	private void onDbUpdated(List<AppItem> items) {
		List<AppItem> fresh = items != null ? items : new ArrayList<>();
		NokiaLog.i("Box", "onDbUpdated 收到 " + fresh.size() + " 个应用");
		// 内容与缓存一致时跳过重建：避免「先显示缓存版、Room 回调后又闪一次」
		// 的多余重排（安装/卸载/重命名才会走到重建分支）。
		if (isSameAppList(fresh, appItems)) {
			// 覆盖安装同一 JAR：列表三项字段全同，但图标文件被重写（mtime 变化），
			// 缓存 key 随之改变 → 检测到任一图标缓存失效即重建，让新图标上屏。
			if (hasStaleIconCache()) {
				NokiaLog.i("Box", "列表未变但图标文件已更新（覆盖安装），重建网格");
			} else {
				NokiaLog.d("Box", "数据与缓存一致，跳过重建");
				return;
			}
		}
		appItems = fresh;
		synchronized (cachedAppItems) {
			cachedAppItems.clear();
			cachedAppItems.addAll(appItems);
		}
		buildGrid();
	}

	/** 是否存在「列表指向的图标文件 mtime 已变、缓存 key 失效」的项（覆盖安装检测）。 */
	private boolean hasStaleIconCache() {
		for (AppItem app : appItems) {
			String imgPath = app.getImagePathExt();
			if (imgPath == null) continue;
			if (!cachedIcons.containsKey(iconCacheKey(imgPath))) {
				return true;
			}
		}
		return false;
	}

	/** 按标题+路径逐项比对两个列表是否内容一致（Room 回调去重用）。 */
	private static boolean isSameAppList(List<AppItem> a, List<AppItem> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			AppItem x = a.get(i);
			AppItem y = b.get(i);
			if (!TextUtils.equals(x.getTitle(), y.getTitle())
					|| !TextUtils.equals(x.getPathExt(), y.getPathExt())
					|| !TextUtils.equals(x.getImagePathExt(), y.getImagePathExt())) {
				return false;
			}
		}
		return true;
	}

	// ============================
	// 构建网格
	// ============================

	private void buildGrid() {
		if (appContainer == null) return;
		appContainer.removeAllViews();
		totalGridCells = 2 + appItems.size(); // 安装 + JAR 全局设置 + 已装应用
		int totalRows = (int) Math.ceil((double) totalGridCells / COLS);
		gridCellViews = new View[totalGridCells];

		// 行高均分拉伸：按实测可用空间计算每行实际 dp 高度
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float rowActualDp = rowsPerPage > 0 ? (availDesign - TITLE_H_DP) / rowsPerPage : ROW_H_DP;
		int rowH = NokiaDimens.dp(getResources(), Math.round(rowActualDp));

		NokiaLog.i("Box", "buildGrid: totalCells=" + totalGridCells
				+ " rows=" + totalRows + " apps=" + appItems.size()
				+ " rowH=" + rowH + "px rowActualDp=" + rowActualDp + " availDesign=" + availDesign);

		for (int r = 0; r < totalRows; r++) {
			LinearLayout row = createGridRow(rowH);

			for (int c = 0; c < COLS; c++) {
				int pos = r * COLS + c;
				LinearLayout cell = createGridCell();

				if (pos < totalGridCells) {
					cell.setClickable(true);
					final int fpos = pos;
					cell.setOnClickListener(v -> {
						setFocusIndex(fpos);
						onSelect();
					});

					if (pos == 0) {
						// "安装" 入口
						populateInstallCell(cell);
					} else if (pos == 1) {
						// "JAR 全局设置" 入口
						populateGlobalProfileCell(cell);
					} else {
						// JAR 应用
						AppItem app = appItems.get(pos - 2);
						populateAppCell(cell, app);
					}
					gridCellViews[pos] = cell;
				}
				row.addView(cell);
			}
			appContainer.addView(row);
		}

		// 恢复焦点
		if (focusIndex >= totalGridCells) focusIndex = totalGridCells - 1;
		if (focusIndex < 0 && totalGridCells > 0) focusIndex = 0;
		applyFocusGrid();
	}

	private LinearLayout createGridRow(int rowH) {
		LinearLayout row = new LinearLayout(requireContext());
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, rowH));
		return row;
	}

	private LinearLayout createGridCell() {
		LinearLayout cell = new LinearLayout(requireContext());
		cell.setOrientation(LinearLayout.VERTICAL);
		cell.setGravity(Gravity.CENTER);
		cell.setLayoutParams(new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
		cell.setPadding(NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4));
		return cell;
	}

	private void populateInstallCell(LinearLayout cell) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 36)));
		try {
			Drawable icon = ContextCompat.getDrawable(requireContext(), R.drawable.s60_app);
			if (icon != null) iv.setImageDrawable(icon);
		} catch (Exception ignored) {}
		cell.addView(iv);

		TextView tv = new TextView(requireContext());
		tv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setText("安装");
		tv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(NokiaDimens.dp(getResources(), 72));
		cell.addView(tv);
	}

	private void populateGlobalProfileCell(LinearLayout cell) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 36)));
		try {
			Drawable icon = ContextCompat.getDrawable(requireContext(), R.drawable.s60_settings);
			if (icon != null) iv.setImageDrawable(icon);
		} catch (Exception ignored) {}
		cell.addView(iv);

		TextView tv = new TextView(requireContext());
		tv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setText("JAR 全局设置");
		tv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(NokiaDimens.dp(getResources(), 72));
		cell.addView(tv);
	}

	private void populateAppCell(LinearLayout cell, AppItem app) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 36)));
		// 加载 JAR 图标：优先取进程内缓存，未命中才做磁盘解码并回填。
		// createFromPath 是主线程磁盘 IO + PNG 解码，应用多时每次进入都全量重跑
		// 是明显的卡顿来源；卸载后旧图标会因路径不再被引用而自然失效。
		String imgPath = app.getImagePathExt();
		if (imgPath != null) {
			String cacheKey = iconCacheKey(imgPath);
			Drawable icon = cachedIcons.get(cacheKey);
			if (icon == null) {
				try {
					icon = Drawable.createFromPath(imgPath);
					if (icon != null) {
						cachedIcons.put(cacheKey, icon);
					}
				} catch (Exception e) {
					NokiaLog.w("Box", "加载图标失败: " + imgPath + " " + e.getMessage());
				}
			}
			if (icon != null) {
				iv.setImageDrawable(icon);
			}
		}
		cell.addView(iv);

		TextView tv = new TextView(requireContext());
		tv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setText(app.getTitle());
		tv.setTextColor(0xFFFFFFFF);
		NokiaFontManager.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(NokiaDimens.dp(getResources(), 72));
		cell.addView(tv);
	}

	// ============================
	// 焦点管理
	// ============================

	private void setFocusIndex(int index) {
		if (gridCellViews == null || index < 0 || index >= gridCellViews.length) return;
		clearFocusGrid();
		focusIndex = index;
		applyFocusGrid();
		scrollToVisibleGrid(index);
	}

	private void clearFocusGrid() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyFocusGrid() {
		if (focusIndex >= 0 && focusIndex < gridCellViews.length
				&& gridCellViews[focusIndex] != null) {
			gridCellViews[focusIndex].setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			selectedView = gridCellViews[focusIndex];
		}
		updateSoftKeys();
	}

	private void scrollToVisibleGrid(int index) {
		if (appScroll == null || gridCellViews == null
				|| index < 0 || index >= gridCellViews.length) return;
		smoothScrollToVisible(appScroll, gridCellViews[index]);
	}

	// ============================
	// 文件选择与安装
	// ============================

	private void launchFilePicker() {
		NokiaLog.i("Box", "启动文件选择器");
		String path = preferences.getString(Constants.PREF_LAST_PATH, null);
		if (path == null) {
			File dir = Environment.getExternalStorageDirectory();
			if (dir.canRead()) {
				path = dir.getAbsolutePath();
			}
		}
		try {
			openFileLauncher.launch(path);
		} catch (Exception e) {
			NokiaLog.e("Box", "启动文件选择器失败", e);
		}
	}

	private void onPickFileResult(android.net.Uri uri) {
		if (uri == null) {
			NokiaLog.i("Box", "文件选择器返回 null（用户取消）");
			return;
		}
		NokiaLog.i("Box", "文件选择器返回: " + uri);
		preferences.edit()
				.putString(Constants.PREF_LAST_PATH, FilteredFilePickerFragment.getLastPath())
				.apply();
		NokiaInstallerDialog.newInstance(uri).show(getChildFragmentManager(), "installer");
	}

	// ============================
	// NokiaFocusHost —— 方向键
	// ============================

	@Override
	public boolean onDirection(int direction) {
		return onDirectionGrid(direction);
	}

	private boolean onDirectionGrid(int direction) {
		if (gridCellViews == null || totalGridCells == 0) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		int row = focusIndex / COLS;
		int col = focusIndex % COLS;
		int totalRows = (int) Math.ceil((double) totalGridCells / COLS);
		int newIdx = focusIndex;

		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (row > 0) {
					newIdx = focusIndex - COLS;
				}
				break;
			case NokiaKeyBinding.ACTION_DOWN:
				if (row < totalRows - 1) {
					int below = focusIndex + COLS;
					if (below < totalGridCells) newIdx = below;
				}
				break;
			case NokiaKeyBinding.ACTION_LEFT:
				if (col > 0) {
					newIdx = focusIndex - 1;
				} else {
					// 回绕到本行最右
					int rightOfRow = Math.min(row * COLS + COLS - 1, totalGridCells - 1);
					newIdx = rightOfRow;
				}
				break;
			case NokiaKeyBinding.ACTION_RIGHT:
				int rightOfRow = Math.min(row * COLS + COLS - 1, totalGridCells - 1);
				if (col < (rightOfRow % COLS) || focusIndex < rightOfRow) {
					newIdx = focusIndex + 1;
					if (newIdx >= totalGridCells) newIdx = row * COLS; // 回绕到本行最左
				} else {
					newIdx = row * COLS; // 回绕到本行最左
				}
				break;
			default:
				return false;
		}

		if (newIdx != focusIndex) {
			setFocusIndex(newIdx);
		}
		return true;
	}

	// ============================
	// NokiaFocusHost —— 确认键
	// ============================

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || totalGridCells == 0) return false;

		if (focusIndex == 0) {
			// 安装入口
			NokiaLog.i("Box", "onSelect: 安装");
			launchFilePicker();
			return true;
		}
		if (focusIndex == 1) {
			// JAR 全局设置
			NokiaLog.i("Box", "onSelect: JAR 全局设置");
			NokiaGlobalProfile.openGlobalSettings(requireContext());
			return true;
		}

		// JAR 应用 → 直接启动
		int appIdx = focusIndex - 2;
		if (appIdx >= 0 && appIdx < appItems.size()) {
			AppItem app = appItems.get(appIdx);
			NokiaLog.i("Box", "onSelect: 直接启动 " + app.getTitle());
			NokiaJarLauncher.launch(requireActivity(), app.getTitle(), app.getPathExt());
			return true;
		}
		return false;
	}

	// ============================
	// 选项菜单（左软键弹出）
	// ============================

	/**
	 * 弹出诺基亚风格确认弹窗：把全局 JAR 设置覆盖到所有已装 JAR。
	 */
	private void showSyncAllDialog() {
		NokiaLog.i("Box", "弹出同步全局设置确认弹窗");
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_SETTINGS,
				"确定，覆盖全部", true, false, () -> {
			NokiaLog.i("Box", "确认同步全局设置到所有 JAR");
			doSyncAll();
		}));
		items.add(new NokiaOptionsDialog.OptionItem(0,
				"取消", true, false, () -> {
			NokiaLog.i("Box", "取消同步全局设置");
		}));
		NokiaOptionsDialog.show(getParentFragmentManager(),
				"同步全局设置\n将覆盖所有已装 JAR 的设置，确定？", items);
	}

	/** 后台执行同步，完成后 Toast 提示数量（按百宝箱已装 JAR 列表逐个同步，避免扫磁盘漏掉未启动过的 JAR）。 */
	private void doSyncAll() {
		final android.content.Context appCtx = requireContext().getApplicationContext();
		final List<AppItem> apps = new ArrayList<>(appItems);
		new Thread(() -> {
			int n = 0;
			for (AppItem a : apps) {
				if (NokiaGlobalProfile.syncAppConfig(appCtx, a.getTitle(), a.getPathExt())) {
					n++;
				}
			}
			final int total = n;
			new Handler(Looper.getMainLooper()).post(() -> {
				if (!isAdded()) return;
				Toast.makeText(requireContext(),
						total > 0 ? "已同步 " + total + " 个 JAR" : "无可同步的 JAR",
						Toast.LENGTH_SHORT).show();
			});
		}, "sync-global").start();
	}

	/**
	 * 弹出诺基亚风格选项菜单弹窗（启动/设置/卸载）。
	 */
	private void showAppOptionsMenu(AppItem app) {
		NokiaLog.i("Box", "弹出选项菜单: " + app.getTitle());
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_PLAY,
				"启动", true, false, () -> {
			NokiaLog.i("Box", "选项菜单-启动: " + app.getTitle());
			NokiaJarLauncher.launch(requireActivity(), app.getTitle(), app.getPathExt());
		}));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_SETTINGS,
				"设置", true, false, () -> {
			NokiaLog.i("Box", "选项菜单-设置: " + app.getTitle());
			Config.startApp(requireContext(), app.getTitle(), app.getPathExt(), true);
		}));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_DELETE,
				"卸载", true, false, () -> {
			NokiaLog.i("Box", "选项菜单-卸载: " + app.getTitle());
			showUninstallDialog(app);
		}));
		NokiaOptionsDialog.show(getParentFragmentManager(), app.getTitle(), items);
	}

	// ============================
	// 卸载
	// ============================

	/**
	 * 弹出诺基亚风格卸载确认弹窗。弹窗只接收应用名用于展示，
	 * 实际删除逻辑通过 {@link NokiaUninstallDialog.ConfirmListener} 回调执行。
	 */
	private void showUninstallDialog(AppItem app) {
		if (app == null) {
			NokiaLog.w("Box", "showUninstallDialog: app 为 null，忽略");
			return;
		}
		NokiaLog.i("Box", "弹出卸载确认弹窗: " + app.getTitle());
		NokiaUninstallDialog dialog = NokiaUninstallDialog.newInstance(app.getTitle());
		dialog.setConfirmListener(() -> doUninstall(app));
		dialog.show(getParentFragmentManager(), "uninstall");
	}

	/** 执行卸载：删除应用目录/存档/图标 + 数据库记录，数据库变更会触发 onDbUpdated 自动重建网格 */
	private void doUninstall(AppItem app) {
		if (app == null) return;
		NokiaLog.i("Box", "执行卸载: " + app.getTitle());
		// 图标文件即将被删除，同步清掉进程内缓存（重装同路径应用时不至于显示旧图）
		String imgPath = app.getImagePathExt();
		if (imgPath != null) {
			cachedIcons.remove(iconCacheKey(imgPath));
		}
		AppUtils.deleteApp(app);
		appRepository.delete(app);
	}

	// ============================
	// 软键文字更新
	// ============================

	/**
	 * 根据当前焦点动态更新底部软键文字（由 NokiaPage getter 决定，这里只通知 Activity 重新装配）。
	 * 选中"安装"时左软键隐藏；选中"JAR全局设置"时显示"同步全部"；选中 JAR 应用时显示"选项"。
	 */
	private void updateSoftKeys() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.refreshPageBar();
	}

	// ============================
	// NokiaFocusHost —— 软键
	// ============================

	@Override
	public boolean onSoftLeft() {
		// JAR 全局设置 → 左软键"同步全部"（把全局配置覆盖到所有已装 JAR）
		if (focusIndex == 1) {
			showSyncAllDialog();
			return true;
		}
		// JAR 应用（focusIndex >= 2）→ 选项菜单
		if (focusIndex >= 2) {
			int appIdx = focusIndex - 2;
			if (appIdx >= 0 && appIdx < appItems.size()) {
				AppItem app = appItems.get(appIdx);
				showAppOptionsMenu(app);
				return true;
			}
		}
		// 安装 / JAR全局设置 → 左软键无反应
		return false;
	}

	@Override
	public boolean onSoftRight() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ============================
	// NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配）
	// ============================

	@Override
	public String getPageTitle() {
		return "应用程序";
	}

	@Override
	public String getSoftLeftText() {
		// JAR 全局设置 → 左软键"同步全部"；JAR 应用→"选项"；安装 → 隐藏
		if (focusIndex == 1) return "同步全部";
		return focusIndex >= 2 ? "选项" : null;
	}

	@Override
	public String getSoftRightText() {
		return "退出";
	}

	// ============================
	// 工具方法
	// ============================


	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
