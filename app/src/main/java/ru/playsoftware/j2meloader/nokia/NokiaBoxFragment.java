package ru.playsoftware.j2meloader.nokia;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

		// 延迟到 midPanel 布局完成后再计算行数（panelH 需要实测反推）
		view.post(() -> {
			if (!isAdded()) return;
			computeRowsPerPage();
			// 订阅已安装 JAR 应用数据（数据回调会触发 buildGrid）
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
		float availDesign = panelH / density / scale;
		int rows = (int) ((availDesign - TITLE_H_DP) / ROW_H_DP);
		rows = Math.max(3, Math.min(8, rows));
		rowsPerPage = rows;
		perPage = COLS * rowsPerPage;
		NokiaLog.i("Box", "computeRowsPerPage: rowsPerPage=" + rowsPerPage
				+ " panelH=" + panelH + " scale=" + scale + " density=" + density
				+ " availDesign=" + availDesign);
	}

	// ============================
	// 数据回调
	// ============================

	private void onDbUpdated(List<AppItem> items) {
		NokiaLog.i("Box", "onDbUpdated 收到 " + (items != null ? items.size() : 0) + " 个应用");
		appItems = items != null ? items : new ArrayList<>();
		buildGrid();
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
		NokiaDimens.textSize(tv, 9);
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
		NokiaDimens.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(NokiaDimens.dp(getResources(), 72));
		cell.addView(tv);
	}

	private void populateAppCell(LinearLayout cell, AppItem app) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 36)));
		// 加载 JAR 图标
		String imgPath = app.getImagePathExt();
		if (imgPath != null) {
			try {
				Drawable icon = Drawable.createFromPath(imgPath);
				if (icon != null) iv.setImageDrawable(icon);
			} catch (Exception e) {
				NokiaLog.w("Box", "加载图标失败: " + imgPath + " " + e.getMessage());
			}
		}
		cell.addView(iv);

		TextView tv = new TextView(requireContext());
		tv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setText(app.getTitle());
		tv.setTextColor(0xFFFFFFFF);
		NokiaDimens.textSize(tv, 9);
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
			gridCellViews[focusIndex].setBackgroundResource(R.drawable.bg_nokia_selected_dark);
			selectedView = gridCellViews[focusIndex];
		}
		updateSoftKeys();
	}

	private void scrollToVisibleGrid(int index) {
		if (appScroll == null || gridCellViews == null
				|| index < 0 || index >= gridCellViews.length) return;
		View item = gridCellViews[index];
		if (item == null) return;
		appScroll.post(() -> {
			int scrollY = appScroll.getScrollY();
			int itemTop = item.getTop();
			int itemBottom = item.getBottom();
			int svHeight = appScroll.getHeight();
			if (svHeight <= 0) return;
			if (itemTop < scrollY) {
				appScroll.smoothScrollTo(0, itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				appScroll.smoothScrollTo(0, itemBottom - svHeight);
			}
		});
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
		AppUtils.deleteApp(app);
		appRepository.delete(app);
	}

	// ============================
	// 软键文字更新
	// ============================

	/**
	 * 根据当前焦点动态更新底部软键文字（由 NokiaPage getter 决定，这里只通知 Activity 重新装配）。
	 * 选中"安装"或"JAR全局设置"时，左软键文字隐藏；选中 JAR 应用时显示"选项"。
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
		// JAR 应用（focusIndex >= 2）→ 左软键"选项"；安装/JAR全局设置 → 隐藏
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
