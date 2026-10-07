package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxIcons;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
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

import io.github.cctyl.nokia.common.permission.KeydroidxPermissionManager;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.dialog.KeydroidxConfirmDialog;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hjq.permissions.OnPermissionCallback;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerFragment;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.installer.KeydroidxInstallerDialog;

/**
 * 应用程序中间内容碎片。
 * 网格模式展示"安装jar"入口 + JAR全局设置 + 已装 JAR 应用网格。
 * 确认键直接启动应用，左软键弹出选项菜单（启动/设置/卸载）。
 * 方向键导航，复用 J2ME-Loader 原有的安装与启动逻辑。
 */
public class KeydroidxBoxFragment extends KeydroidxPageFragment {
	private static final String TAG = "KeydroidxBoxFragment";

	/**
	 * 本进程是否已在本页「进入时」自动申请过存储权限。
	 * <p>只拦自动申请这一条路径：用户若拒绝，不再每次进页都弹窗打扰，
	 * 改由「安装」入口按需再申请（那时是用户明确的动作，弹窗不突兀）。
	 */
	private static boolean storagePermissionRequestedOnEnter;


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
		KeydroidxLog.i("Box", "onCreate");

		// 注册文件选择器（必须在 onCreate 前/内注册）
		openFileLauncher = registerForActivityResult(
				FileUtils.getFilePicker(), this::onPickFileResult);

		preferences = PreferenceManager.getDefaultSharedPreferences(requireActivity());

		// 获取 AppRepository
		AppListModel appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();

		// 自愈一次：存储权限可能在别处被授予（系统懒提示、设置页），此时工作目录已可写，
		// 但仓库仍停在「未初始化」状态。若不重试，安装流程会直接空指针。
		appRepository.ensureReady();
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_box;
	}

	@Override
	protected boolean isTopAlign() {
		// 百宝箱垂直居中（内容矮于面板时居中，不贴顶）
		return false;
	}

	@Override
	protected int getWallpaperRes() {
		return R.drawable.bg_keydroidx_box;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		appScroll = view.findViewById(R.id.appScroll);
		appContainer = view.findViewById(R.id.appContainer);

		// 首帧无条件构建一版：有缓存带缓存，无缓存也必须展示"安装"与"JAR全局设置"基础入口。
		// Room 订阅仍在 view.post 里照常进行，数据回来后内容一致则不重建。
		if (!cachedAppItems.isEmpty()) {
			appItems = new ArrayList<>(cachedAppItems);
		}
		buildGrid();
		KeydroidxLog.i("Box", "首帧同步构建网格完成，应用数：" + appItems.size());

		// 延迟到 midPanel 布局完成后再计算行数并订阅数据（panelH 需要实测反推）
		view.post(() -> {
			if (!isAdded()) return;
			int oldRows = rowsPerPage;
			computeRowsPerPage();
			if (rowsPerPage != oldRows) {
				// 行数变化影响行高均分结果，重建一次（纯 View 操作，无 IO）
				buildGrid();
			}
			// 订阅已安装 JAR 应用数据（数据回调会触发 onDbUpdated）
			appRepository.observeApps(getViewLifecycleOwner(), this::onDbUpdated);
			// 本页一切功能都依赖外部工作目录：首次进入就补齐存储权限，
			// 而不是等用户点了「安装」才把人拦在操作中途。
			requestStoragePermissionOnEnter();
			KeydroidxLog.i("Box", "应用程序初始化完成（延迟到 panelH 可用），等待数据加载…");
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
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		if (panelH <= 0) {
			KeydroidxLog.w("Box", "computeRowsPerPage: panelH 尚未可用，保持默认 rowsPerPage=" + rowsPerPage);
			return;
		}
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float fontScale = KeydroidxSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1.0f;

		// 随字体缩放动态扩充行高预算，字体越大行高预留越足
		float dynamicRowHDp = ROW_H_DP + Math.max(0f, (fontScale - 1.0f) * 16f);

		float availDesign = panelH / density / scale;
		int rows = (int) ((availDesign - TITLE_H_DP) / dynamicRowHDp);
		rows = Math.max(2, Math.min(8, rows));
		rowsPerPage = rows;
		perPage = COLS * rowsPerPage;
		KeydroidxLog.i("Box", "computeRowsPerPage: rowsPerPage=" + rowsPerPage
				+ " panelH=" + panelH + " scale=" + scale + " density=" + density
				+ " fontScale=" + fontScale + " dynamicRowHDp=" + dynamicRowHDp
				+ " availDesign=" + availDesign);
	}

	// ============================
	// 数据回调
	// ============================

	private void onDbUpdated(List<AppItem> items) {
		List<AppItem> fresh = items != null ? items : new ArrayList<>();
		KeydroidxLog.i("Box", "onDbUpdated 收到 " + fresh.size() + " 个应用");
		// 内容与缓存一致且网格已正常渲染时跳过重建：避免「先显示缓存版、Room 回调后又闪一次」
		// 的多余重排（安装/卸载/重命名才会走到重建分支）。
		if (gridCellViews != null && isSameAppList(fresh, appItems)) {
			// 覆盖安装同一 JAR：列表三项字段同，但图标文件被重写（mtime 变化），
			// 缓存 key 随之改变 → 检测到任一图标缓存失效即重建，让新图标上屏。
			if (hasStaleIconCache()) {
				KeydroidxLog.i("Box", "列表未变但图标文件已更新（覆盖安装），重建网格");
			} else {
				KeydroidxLog.d("Box", "数据与缓存一致，跳过重建");
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
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float rowActualDp = rowsPerPage > 0 ? (availDesign - TITLE_H_DP) / rowsPerPage : ROW_H_DP;
		int rowH = KeydroidxDimens.dp(getResources(), Math.round(rowActualDp));

		KeydroidxLog.i("Box", "buildGrid: totalCells=" + totalGridCells
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
		cell.setPadding(KeydroidxDimens.dp(getResources(), 4), KeydroidxDimens.dp(getResources(), 4), KeydroidxDimens.dp(getResources(), 4), KeydroidxDimens.dp(getResources(), 4));
		return cell;
	}

	private void populateInstallCell(LinearLayout cell) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(KeydroidxDimens.dp(getResources(), 36), KeydroidxDimens.dp(getResources(), 36)));
		try {
			Drawable icon = ContextCompat.getDrawable(requireContext(), R.drawable.s60_app);
			if (icon != null) iv.setImageDrawable(icon);
		} catch (Exception ignored) {
			KeydroidxLog.w(TAG, "setLayoutParams failed: " + ignored.getMessage());
		}
		cell.addView(iv);

		TextView tv = new TextView(requireContext());
		tv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setText("安装");
		tv.setTextColor(0xFFFFFFFF);
		KeydroidxFontManager.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(KeydroidxDimens.dp(getResources(), 72));
		cell.addView(tv);
	}

	private void populateGlobalProfileCell(LinearLayout cell) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(KeydroidxDimens.dp(getResources(), 36), KeydroidxDimens.dp(getResources(), 36)));
		try {
			Drawable icon = ContextCompat.getDrawable(requireContext(), R.drawable.s60_settings);
			if (icon != null) iv.setImageDrawable(icon);
		} catch (Exception ignored) {
			KeydroidxLog.w(TAG, "setLayoutParams failed: " + ignored.getMessage());
		}
		cell.addView(iv);

		TextView tv = new TextView(requireContext());
		tv.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tv.setText("JAR 全局设置");
		tv.setTextColor(0xFFFFFFFF);
		KeydroidxFontManager.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(KeydroidxDimens.dp(getResources(), 72));
		cell.addView(tv);
	}

	private void populateAppCell(LinearLayout cell, AppItem app) {
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(KeydroidxDimens.dp(getResources(), 36), KeydroidxDimens.dp(getResources(), 36)));
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
					KeydroidxLog.w("Box", "加载图标失败: " + imgPath + " " + e.getMessage());
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
		KeydroidxFontManager.textSize(tv, 9);
		tv.setSingleLine(true);
		tv.setEllipsize(TextUtils.TruncateAt.END);
		tv.setMaxWidth(KeydroidxDimens.dp(getResources(), 72));
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
			gridCellViews[focusIndex].setBackground(KeydroidxTheme.createSelectionDrawable(requireContext(), 4));
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

	/**
	 * 安装前确保「存储权限 + 工作目录数据库」真正可用。
	 *
	 * <p><b>背景（实测）</b>：模拟器工作目录位于外部存储，而存储权限按设计是首次使用相关功能时
	 * 才申请的（见 {@code KeydroidxPermissionManager} 核心权限全集说明）。权限授予发生在进程
	 * 启动之后，而 {@link AppRepository} 只在构造时判定过一次目录可写性，于是出现
	 * 「授权后不重启应用就不生效」：目录其实已经可写，但仓库仍是未初始化状态，
	 * 一进安装流程就 {@code AppItemDao.get(...) on a null object reference}。
	 *
	 * <p>因此这里做三件事：
	 * <ol>
	 *   <li>权限缺失 → 诺基亚风格说明框 + 系统权限框（不依赖 ROM 的懒提示时机）；
	 *   <li>授权成功 → 重建工作目录 + 重新初始化数据库，然后自动继续安装流程（无需重启）；</li>
	 *   <li>授权后目录仍不可写（当前进程未拿到新的存储视图）→ 明确提示需重启应用，而不是放任崩溃。</li>
	 * </ol>
	 *
	 * <p>注：正常情况下权限在进入本页时就已被 {@link #requestStoragePermissionOnEnter()} 申请掉，
	 * 这里只是兜底——用户此前拒绝过、或权限被系统/用户收回时，安装入口仍要拦一道。
	 *
	 * @return true 表示已就绪，可继续安装流程
	 */
	private boolean ensureStorageReady() {
		Context context = getContext();
		if (context == null) {
			return false;
		}
		// 先自愈一次：权限可能已在别处授予，此时目录已可写，直接补齐数据库初始化
		appRepository.ensureReady();
		if (appRepository.isReady()) {
			return true;
		}
		if (needsStoragePermission(context)) {
			requestStoragePermission(true);
			return false;
		}
		// 无权限诉求却仍未就绪：目录确实不可写（权限已授予但本进程未生效 / 路径不可用）
		showStorageUnavailableDialog();
		return false;
	}

	/**
	 * 进入「应用程序」页即主动补齐存储权限（每进程只自动申请一次）。
	 *
	 * <p>为什么不等用户点「安装」：本页的一切——已装 JAR 列表、安装、启动——都依赖外部工作目录。
	 * 权限缺失时进来只看到一个空列表，用户点「安装」才弹权限，等于把人拦在操作中途。
	 * 因此首次进入本页就申请，授予后立刻重建目录并初始化数据库，列表直接出数据。
	 */
	private void requestStoragePermissionOnEnter() {
		if (storagePermissionRequestedOnEnter) {
			return;
		}
		Context context = getContext();
		if (context == null || !needsStoragePermission(context)) {
			return;
		}
		storagePermissionRequestedOnEnter = true;
		KeydroidxLog.i("Box", "进入应用程序页，主动申请存储权限");
		requestStoragePermission(false);
	}

	/**
	 * 申请「存储读写」权限；授予后重建工作目录并重新初始化数据库。
	 *
	 * @param continueInstall true 表示申请来自「安装」入口，授予后自动继续打开文件选择器；
	 *                        false 表示申请来自页面进入时的主动申请，授予后只需把列表数据刷出来
	 */
	private void requestStoragePermission(boolean continueInstall) {
		KeydroidxLog.i("Box", "存储权限缺失，发起申请（授予后继续安装=" + continueInstall + "）");
		KeydroidxPermissionManager.requestWithNokiaDialog(requireActivity(),
				"存储权限申请",
				"运行 J2ME 应用需要读写手机存储权限：用于读取安装包，并把应用数据写入模拟器工作目录。",
				Collections.singletonList(Manifest.permission.WRITE_EXTERNAL_STORAGE),
				new OnPermissionCallback() {
					@Override
					public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
						KeydroidxLog.i("Box", "存储权限已授予，重建工作目录并重新初始化数据库");
						FileUtils.initWorkDir(new File(Config.getEmulatorDir()));
						appRepository.ensureReady();
						if (!appRepository.isReady()) {
							// 授权已生效但当前进程仍未拿到可写视图：只能靠重启进程
							showStorageUnavailableDialog();
						} else if (continueInstall) {
							launchFilePicker();
						}
					}

					@Override
					public void onDenied(@NonNull List<String> permissions, boolean quick) {
						KeydroidxLog.w("Box", "存储权限被拒绝，无法安装 JAR（quick=" + quick + "）");
						if (isAdded()) {
							Toast.makeText(requireContext(),
									"没有存储权限，无法运行 JAR 应用", Toast.LENGTH_SHORT).show();
						}
					}
				});
	}

	/**
	 * 当前系统是否需要「存储读写」运行时权限才能访问外部工作目录。
	 *
	 * <p>API 23 以下无运行时权限机制（安装即授予）；API 30（Android 11）起分区存储强制执行，
	 * 该权限已失效、安装改走 SAF（见 {@code FileUtils.isExternalStorageLegacy()}），
	 * 因此这两段区间都无需申请。
	 */
	private static boolean needsStoragePermission(Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
				|| Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return false;
		}
		return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				!= PackageManager.PERMISSION_GRANTED;
	}

	/**
	 * 工作目录最终仍不可写时的显式提示。
	 * <p>兜底路径：部分 ROM 在授予存储权限后不刷新已启动进程的存储视图，
	 * 此时只能靠重启进程生效——提供一键重启，避免让用户自己去猜「为什么要重启」。
	 */
	private void showStorageUnavailableDialog() {
		if (!isAdded()) {
			return;
		}
		String path = Config.getEmulatorDir();
		KeydroidxLog.w("Box", "工作目录不可写，JAR 列表/安装均不可用: " + path);
		new KeydroidxConfirmDialog(requireContext(), "无法访问存储",
				"无法写入模拟器工作目录：\n" + path
						+ "\n\n若刚刚授予了存储权限，需要重新启动应用才会生效。\n确定立即重启应用吗？")
				.setPositiveButton("立即重启", this::restartApp)
				.setNegativeButton("稍后", null)
				.show();
	}

	/** 重新拉起自身并结束当前进程，使新的存储视图/权限在进程启动时生效。 */
	private void restartApp() {
		Context context = getContext();
		if (context == null) {
			return;
		}
		KeydroidxLog.i("Box", "重启应用以使存储权限生效");
		try {
			Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
				context.startActivity(intent);
			}
		} catch (Exception e) {
			KeydroidxLog.w("Box", "重新拉起应用失败: " + e.getMessage());
		}
		android.os.Process.killProcess(android.os.Process.myPid());
	}

	private void launchFilePicker() {
		KeydroidxLog.i("Box", "启动文件选择器");
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
			KeydroidxLog.e("Box", "启动文件选择器失败", e);
		}
	}

	private void onPickFileResult(android.net.Uri uri) {
		if (uri == null) {
			KeydroidxLog.i("Box", "文件选择器返回 null（用户取消）");
			return;
		}
		KeydroidxLog.i("Box", "文件选择器返回: " + uri);
		preferences.edit()
				.putString(Constants.PREF_LAST_PATH, FilteredFilePickerFragment.getLastPath())
				.apply();
		KeydroidxInstallerDialog.newInstance(uri).show(getChildFragmentManager(), "installer");
	}

	// ============================
	// KeydroidxFocusHost —— 方向键
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
			case KeydroidxKeyBinding.ACTION_UP:
				if (row > 0) {
					newIdx = focusIndex - COLS;
				}
				break;
			case KeydroidxKeyBinding.ACTION_DOWN:
				if (row < totalRows - 1) {
					int below = focusIndex + COLS;
					if (below < totalGridCells) newIdx = below;
				}
				break;
			case KeydroidxKeyBinding.ACTION_LEFT:
				if (col > 0) {
					newIdx = focusIndex - 1;
				} else {
					// 回绕到本行最右
					int rightOfRow = Math.min(row * COLS + COLS - 1, totalGridCells - 1);
					newIdx = rightOfRow;
				}
				break;
			case KeydroidxKeyBinding.ACTION_RIGHT:
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
	// KeydroidxFocusHost —— 确认键
	// ============================

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || totalGridCells == 0) return false;

		if (focusIndex == 0) {
			// 安装入口
			KeydroidxLog.i("Box", "onSelect: 安装");
			if (!ensureStorageReady()) {
				return true;
			}
			launchFilePicker();
			return true;
		}
		if (focusIndex == 1) {
			// JAR 全局设置
			KeydroidxLog.i("Box", "onSelect: JAR 全局设置");
			KeydroidxGlobalProfile.openGlobalSettings(requireContext());
			return true;
		}

		// JAR 应用 → 直接启动
		int appIdx = focusIndex - 2;
		if (appIdx >= 0 && appIdx < appItems.size()) {
			AppItem app = appItems.get(appIdx);
			KeydroidxLog.i("Box", "onSelect: 直接启动 " + app.getTitle());
			KeydroidxJarLauncher.launch(requireActivity(), app.getTitle(), app.getPathExt());
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
		KeydroidxLog.i("Box", "弹出同步全局设置确认弹窗");
		List<KeydroidxOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_SETTINGS,
				"确定，覆盖全部", true, false, () -> {
			KeydroidxLog.i("Box", "确认同步全局设置到所有 JAR");
			doSyncAll();
		}));
		items.add(new KeydroidxOptionsDialog.OptionItem(0,
				"取消", true, false, () -> {
			KeydroidxLog.i("Box", "取消同步全局设置");
		}));
		KeydroidxOptionsDialog.show(getParentFragmentManager(),
				"同步全局设置\n将覆盖所有已装 JAR 的设置，确定？", items);
	}

	/** 后台执行同步，完成后 Toast 提示数量（按百宝箱已装 JAR 列表逐个同步，避免扫磁盘漏掉未启动过的 JAR）。 */
	private void doSyncAll() {
		final android.content.Context appCtx = requireContext().getApplicationContext();
		final List<AppItem> apps = new ArrayList<>(appItems);
		new Thread(() -> {
			int n = 0;
			for (AppItem a : apps) {
				if (KeydroidxGlobalProfile.syncAppConfig(appCtx, a.getTitle(), a.getPathExt())) {
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
		KeydroidxLog.i("Box", "弹出选项菜单: " + app.getTitle());
		List<KeydroidxOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_PLAY,
				"启动", true, false, () -> {
			KeydroidxLog.i("Box", "选项菜单-启动: " + app.getTitle());
			KeydroidxJarLauncher.launch(requireActivity(), app.getTitle(), app.getPathExt());
		}));
		items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_SETTINGS,
				"设置", true, false, () -> {
			KeydroidxLog.i("Box", "选项菜单-设置: " + app.getTitle());
			Config.startApp(requireContext(), app.getTitle(), app.getPathExt(), true);
		}));
		items.add(new KeydroidxOptionsDialog.OptionItem(KeydroidxIcons.ICON_DELETE,
				"卸载", true, false, () -> {
			KeydroidxLog.i("Box", "选项菜单-卸载: " + app.getTitle());
			showUninstallDialog(app);
		}));
		KeydroidxOptionsDialog.show(getParentFragmentManager(), app.getTitle(), items);
	}

	// ============================
	// 卸载
	// ============================

	/**
	 * 弹出诺基亚风格卸载确认弹窗。弹窗只接收应用名用于展示，
	 * 实际删除逻辑通过 {@link KeydroidxUninstallDialog.ConfirmListener} 回调执行。
	 */
	private void showUninstallDialog(AppItem app) {
		if (app == null) {
			KeydroidxLog.w("Box", "showUninstallDialog: app 为 null，忽略");
			return;
		}
		KeydroidxLog.i("Box", "弹出卸载确认弹窗: " + app.getTitle());
		KeydroidxUninstallDialog dialog = KeydroidxUninstallDialog.newInstance(app.getTitle());
		dialog.setConfirmListener(() -> doUninstall(app));
		dialog.show(getParentFragmentManager(), "uninstall");
	}

	/** 执行卸载：删除应用目录/存档/图标 + 数据库记录，数据库变更会触发 onDbUpdated 自动重建网格 */
	private void doUninstall(AppItem app) {
		if (app == null) return;
		KeydroidxLog.i("Box", "执行卸载: " + app.getTitle());
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
	 * 根据当前焦点动态更新底部软键文字（由 KeydroidxPage getter 决定，这里只通知 Activity 重新装配）。
	 * 选中"安装"时左软键隐藏；选中"JAR全局设置"时显示"同步全部"；选中 JAR 应用时显示"选项"。
	 */
	private void updateSoftKeys() {
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		host.refreshPageBar();
	}

	// ============================
	// KeydroidxFocusHost —— 软键
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
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ============================
	// KeydroidxPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配）
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
