package ru.playsoftware.j2meloader.nokia;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaTheme;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.playsoftware.j2meloader.J2meLoaderActivity;
import ru.playsoftware.j2meloader.R;

/**
 * 功能表（应用网格）中间内容碎片。
 * 通过 PackageManager 枚举所有可启动的安卓应用，分页以 3 列网格展示真实 APP 图标。
 * 方向键在页内移动焦点：左/右到边界时翻到上/下一页；确认键启动对应 APP。
 * 末尾追加「百宝箱」「按键绑定」两个特殊入口，保留原功能可达性。
 */
public class NokiaMenuFragment extends NokiaPageFragment {

	/**
	 * 第一页固定槽位（参照诺基亚 S60 功能表布局）。
	 * 每个槽位是一组候选包名（优先级从高到低），命中第一个即固定到前排；
	 * 全部候选都不存在则跳过该槽位（不占位，后面应用自动补上）。
	 * 显示名与图标沿用真实应用，保证可识别。
	 */
	private static final String[][] PINNED_SLOTS = {
			// 1 日历
			{"com.android.calendar", "com.google.android.calendar", "com.miui.calendar",
					"com.samsung.android.calendar", "com.huawei.calendar"},
			// 2 名片夹（联系人）
			{"com.android.contacts", "com.google.android.contacts",
					"com.samsung.android.app.contacts"},
			// 3 通讯记录（拨号/电话）
			{"com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer"},
			// 4 网络（浏览器）
			{"com.android.browser", "com.android.chrome", "com.mi.globalbrowser",
					"com.huawei.browser", "com.UCMobile", "com.tencent.mtt"},
			// 5 信息
			{"com.android.mms", "com.google.android.apps.messaging", "com.android.messaging",
					"com.samsung.android.messaging"},
			// 6 多媒体（图库/相册）
			{"com.android.gallery3d", "com.miui.gallery", "com.google.android.apps.photos",
					"com.huawei.photos", "com.samsung.android.gallery"},
			// 7 文件（参考图"共享"位 → 安卓文件管理器）
			{"com.android.fileexplorer", "com.mi.android.globalFileexplorer",
					"com.android.documentsui", "com.google.android.documentsui",
					"com.huawei.hidisk"},
			// 8 商店
			{"com.android.vending", "com.xiaomi.market", "com.huawei.appmarket",
					"com.heytap.market", "com.oppo.market", "com.bbk.appstore"},
			// 9 相机
			{"com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera",
					"com.huawei.camera", "com.samsung.android.camera"},
			// 10 设置
			{"com.android.settings"},
	};

	/** 与 PINNED_SLOTS 一一对应的 S60 风格图标资源 ID */
	private static final int[] PINNED_SLOT_ICONS = {
			R.drawable.s60_calendar,   // 1 日历
			R.drawable.s60_contacts,   // 2 名片夹
			R.drawable.s60_call_log,   // 3 通讯记录
			R.drawable.s60_browser,    // 4 网络
			R.drawable.s60_mms,        // 5 信息
			R.drawable.s60_gallery,    // 6 多媒体
			R.drawable.s60_files,      // 7 文件
			R.drawable.s60_app,        // 8 商店
			R.drawable.s60_camera,     // 9 相机
			R.drawable.s60_settings,   // 10 设置
	};

	/** 列数固定 3 列（诺基亚经典风格） */
	private static final int COLS = 3;
	/** 行高由实际可用空间均分，此常量仅作为 fallback（panelH 尚未可用时）。图标 36 + 标签 9 + 间距 */
	private static final int ROW_H_DP = 58;
	/** 标题预留高度（dp，含功能表标题与网格上下内边距，留少量余量防裁切） */
	private static final int TITLE_H_DP = 22;

	private final ArrayList<NokiaAppItem> items = new ArrayList<>();
	private LinearLayout appGrid;
	private TextView tvPage;

	/** 每页行数（按分辨率/可用高度自适应，区间 [3,8]） */
	private int rowsPerPage = 4;
	/** 每页格子数 = COLS * rowsPerPage */
	private int perPage = COLS * rowsPerPage;
	private int totalPages = 1;
	private int pageIndex = 0;
	/** 当前页内焦点位置（0..perPage-1） */
	private int focusPos = 0;

	private View[] cellViews;
	private NokiaAppItem[] pageItems;
	private View selectedView = null;

	/** 防止 S60 图标异步扫描完成后重复刷新当前页图标 */
	private boolean iconRefreshDone = false;

	/**
	 * 包安装/卸载/替换广播接收器：应用列表实时跟随系统变化。
	 * 卸载（ACTION_DELETE）会切到系统卸载页，Fragment 只是 onPause 不销毁，
	 * 因此注册放在 onViewCreated / onDestroyView 生命周期内，能覆盖卸载完成返回的场景。
	 */
	private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action == null) return;
			// ACTION_PACKAGE_REPLACED 只关心包名，这里统一在刷新时重新 queryIntentActivities
			NokiaLog.i("Menu", "收到包变化广播: " + action + " data=" + intent.getDataString());
			// 稍作延迟等系统包表稳定，避免偶发仍能查到底层已卸载的残留
			View v = getView();
			if (v != null) {
				v.postDelayed(() -> {
					if (isAdded()) refreshAppList();
				}, 300);
			} else {
				refreshAppList();
			}
		}
	};

	/** 应用显示名内存缓存（进程内复用，避免每次进入功能表反复 loadLabel IPC） */
	private static final Map<String, String> labelCache = new HashMap<>();

	/** 系统图标未加载完成前的占位图标（懒加载） */
	private Drawable placeholderIcon;

	/** 滑动翻页阈值（px，由 dp 换算）与最小速度（px/ms） */
	private float swipeThreshold;
	private float swipeMinVel;
	/** 复用于根视图与每个 cell 的滑动手势监听 */
	private View.OnTouchListener swipeTouchListener;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_menu;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		// 监听包安装/卸载/替换与应用冻结状态变化，实时刷新应用列表
		IntentFilter pkgFilter = new IntentFilter();
		pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
		pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
		pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
		pkgFilter.addDataScheme("package");
		try {
			requireContext().registerReceiver(packageReceiver, pkgFilter);
			NokiaLog.i("Menu", "已注册包变化广播接收器（ADDED/REMOVED/REPLACED）");
		} catch (Exception e) {
			NokiaLog.e("Menu", "注册包变化广播失败", e);
		}

		IntentFilter freezeFilter = new IntentFilter();
		freezeFilter.addAction(NokiaFreezeManager.ACTION_FREEZE_STATE_CHANGED);
		try {
			requireContext().registerReceiver(packageReceiver, freezeFilter);
		} catch (Exception e) {
			NokiaLog.e("Menu", "注册冻结状态广播失败", e);
		}

		appGrid = view.findViewById(R.id.appGrid);
		tvPage = view.findViewById(R.id.menuPage);

		// 先初始化滑动监听（在 buildCurrentPage 之前，使每个 cell 都能挂载）
		initSwipeListener(view);

		// 延迟到 midPanel 布局完成后再计算行数并构建网格（panelH 需要实测反推）
		view.post(() -> {
			if (!isAdded()) return;
			computeRowsPerPage();
			// 按 perPage 分配页内缓存数组
			cellViews = new View[perPage];
			pageItems = new NokiaAppItem[perPage];
			loadApps();
			buildCurrentPage();
			setFocusPos(0);

			NokiaLog.i("Menu", "功能表初始化完成（延迟到 panelH 可用）：共 " + items.size() + " 项，"
					+ totalPages + " 页，每页 " + perPage + " 格（" + COLS + "×" + rowsPerPage + "）");
		});
	}

	@Override
	public void onDestroyView() {
		try {
			requireContext().unregisterReceiver(packageReceiver);
			NokiaLog.i("Menu", "已注销包变化广播接收器");
		} catch (Exception ignore) {
			// 未注册或已注销，忽略
		}
		super.onDestroyView();
	}

	// ---- 分辨率自适应：计算每页行数 ----

	/**
	 * 用实测 midPanel 像素高度反推行数空间预算，保证文字绝不被裁切。
	 * 公式：availDesign = panelH(px) / density / scale；
	 * 行数：先扣除标题与内边距预留，再按单元格真实物理最小需求（图标36 + 上下padding 8 + 文字高度 + 边框缓冲）
	 * 计算能容纳的最大行数。若均分后单行高度不足以容纳文字，则自动减 1 行，确保剩余行均匀美观拉伸。
	 */
	private void computeRowsPerPage() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		if (panelH <= 0) {
			// panelH 尚未布局完成，保持默认值，稍后由 post 回调重新计算
			NokiaLog.w("Menu", "computeRowsPerPage: panelH 尚未可用，保持默认 rowsPerPage=" + rowsPerPage);
			return;
		}
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float fontScale = NokiaSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1.0f;

		// 实测反推：可用设计高度 = panelH(px) / density / scale
		float availDesign = panelH / density / scale;
		// 标题栏(22dp) + appGrid上下padding(4dp)
		float availForGrid = Math.max(0f, availDesign - TITLE_H_DP - 4f);

		// 单行单元格所需的绝对最小安全设计高度：
		// 36dp (图标) + 8dp (上下Cell padding 4+4) + 文字行高预算 (9sp * fontScale * 1.6f + 2dp) + 2dp (选中高亮边框余量)
		float textHeightBudget = Math.max(16f, (9f * fontScale * 1.6f) + 2f);
		float minSafeRowHDp = 36f + 8f + textHeightBudget + 2f;

		int rows = (int) (availForGrid / minSafeRowHDp);
		// 安全兜底校验：如果均分后的高度小于最小安全高度，减去一行
		while (rows > 2 && (availForGrid / rows) < minSafeRowHDp) {
			rows--;
		}
		rows = Math.max(2, Math.min(8, rows));
		rowsPerPage = rows;
		perPage = COLS * rowsPerPage;
		NokiaLog.i("Menu", "computeRowsPerPage: rowsPerPage=" + rowsPerPage
				+ " panelH=" + panelH + " scale=" + scale + " density=" + density
				+ " fontScale=" + fontScale + " minSafeRowHDp=" + minSafeRowHDp
				+ " availDesign=" + availDesign + " availForGrid=" + availForGrid);
	}

	// ---- 加载真实安卓应用 ----

	private void loadApps() {
		items.clear();
		long loadStart = System.currentTimeMillis();
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		PackageManager pm = host.getPackageManager();

		// 图标缓存：内存 + 磁盘 + 后台线程加载（避免主线程逐个 loadIcon IPC 卡顿）
		NokiaAppIconCache.init(requireContext());
		// S60 图标缓存：冷启动时桌面已通过 initAsync 异步扫描并持久化；
		// 此处仅读磁盘缓存（毫秒级，无 PackageManager 批量查询），不阻塞功能表打开
		NokiaS60IconMap.loadFromDisk(requireContext());

		Intent main = new Intent(Intent.ACTION_MAIN, null);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		// flags=0：只返回「已安装 + 已启用 + 可启动」的组件（与系统桌面一致）。
		// 绝不混入 MATCH_DISABLED_COMPONENTS / MATCH_UNINSTALLED_PACKAGES——
		// 那会把停用组件（主题别名、CarPlay 入口）与卸载残留包也枚举进来，
		// 导致启动报 ActivityNotFoundException 或同包出现多个图标。
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
		NokiaLog.i("Menu", "queryIntentActivities (仅启用应用) 返回 " + list.size() + " 个可启动应用");

		// 先全部放入临时池 pool，后续再按固定槽位提取
		List<NokiaAppItem> pool = new ArrayList<>();
		Set<String> seenPackages = new HashSet<>();
		String selfPkg = host.getPackageName();
		for (ResolveInfo ri : list) {
			ActivityInfo ai = ri.activityInfo;
			if (ai == null) {
				NokiaLog.w("Menu", "跳过空 activityInfo");
				continue;
			}
			boolean appEnabled = ai.applicationInfo == null || ai.applicationInfo.enabled;
			boolean compEnabled = ai.enabled && appEnabled;
			NokiaLog.d("Menu", "枚举入口: " + ai.packageName + "/" + ai.name
					+ " enabled=" + compEnabled
					+ " (componentEnabled=" + ai.enabled
					+ ", appEnabled=" + appEnabled + ")");
			if (ai.packageName.equals(selfPkg)) {
				NokiaLog.d("Menu", "排除桌面自身: " + ai.packageName);
				continue;
			}
			// 过滤同应用的多图标入口（如部分应用提供的多种样式启动别名）
			if (!seenPackages.add(ai.packageName)) {
				NokiaLog.d("Menu", "跳过重复包名入口: " + ai.packageName + "/" + ai.name
						+ " (首个入口已选中)");
				continue;
			}
			NokiaLog.d("Menu", "选中入口: " + ai.packageName + "/" + ai.name
					+ " enabled=" + compEnabled);
			// 应用名走进程内缓存，避免每次进入功能表重复 loadLabel IPC
			String labelKey = ai.packageName + "/" + ai.name;
			String label = labelCache.get(labelKey);
			if (label == null) {
				CharSequence labelCs = ri.loadLabel(pm);
				label = (labelCs != null && labelCs.length() > 0) ? labelCs.toString() : ai.name;
				labelCache.put(labelKey, label);
			}
			// 不再在主线程 loadIcon（重 IPC，低端设备是功能表卡顿根因）；
			// 系统图标交给 buildCurrentPage 里的 NokiaAppIconCache 后台加载。
			Drawable icon = null;
			Intent launch = new Intent(Intent.ACTION_MAIN);
			launch.addCategory(Intent.CATEGORY_LAUNCHER);
			launch.setClassName(ai.packageName, ai.name);
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			NokiaAppItem item = new NokiaAppItem(NokiaAppItem.TYPE_APP, label, icon, launch);

			// 尝试替换为 S60 风格图标，同时记录匹配结果用于后续分组排序（传入 label 以启用应用名匹配）
			int s60IconRes = NokiaS60IconMap.getIcon(ai.packageName, label);
			item.s60IconResId = s60IconRes;
			if (s60IconRes != 0) {
				Drawable s60Icon = safeDrawable(host, s60IconRes);
				if (s60Icon != null) {
					item.icon = s60Icon;
				}
			}

			pool.add(item);
		}

		// 按名称排序，保证 pool 中的顺序稳定
		Collections.sort(pool, new Comparator<NokiaAppItem>() {
			@Override
			public int compare(NokiaAppItem a, NokiaAppItem b) {
				return a.label.compareToIgnoreCase(b.label);
			}
		});

		// —— 第一页固定槽位：按参考图顺序从应用池点名 ——
		List<NokiaAppItem> pinned = new ArrayList<>();
		for (int s = 0; s < PINNED_SLOTS.length; s++) {
			NokiaAppItem hit = null;
			for (String pkg : PINNED_SLOTS[s]) {
				hit = pollByPackage(pool, pkg);
			if (hit != null) {
				NokiaLog.d("Menu", "固定槽位 " + (s + 1) + " 命中: " + pkg + " -> " + hit.label);
				// 替换为 S60 风格图标
				Drawable s60icon = safeDrawable(host, PINNED_SLOT_ICONS[s]);
				if (s60icon != null) {
					hit.icon = s60icon;
					NokiaLog.d("Menu", "  -> 已替换为 S60 图标");
				}
				break;
			}
		}
		if (hit != null) {
			pinned.add(hit);
			} else {
				NokiaLog.d("Menu", "固定槽位 " + (s + 1) + " 未命中，跳过（候选包均不存在）");
			}
		}

		// 被冻结（包级停用）的应用单独枚举后追加，不混入正常应用枚举，保证列表确定性
		addFrozenApps(pool, host, pm, selfPkg);

		// 最终顺序：固定槽位 → 应用程序 → 按键绑定 → 桌面设置 → S60匹配应用（按名） → 未匹配应用（按名）
		items.addAll(pinned);

		// 应用程序图标：优先用 S60 应用程序图标
		Drawable boxIcon = safeDrawable(host, R.drawable.s60_app);
		if (boxIcon == null) boxIcon = safeDrawable(host, R.drawable.ic_nokia_box);
		Drawable settingsIcon = safeDrawable(host, R.drawable.s60_settings);
		if (settingsIcon == null) settingsIcon = safeDrawable(host, R.drawable.ic_nokia_settings);
		items.add(new NokiaAppItem(NokiaAppItem.TYPE_BOX, "应用程序", boxIcon, null));
		// 原始 J2ME-Loader 主界面（启动器/文件选择器/应用列表）入口
		Drawable mainIcon = safeDrawable(host, R.mipmap.ic_launcher);
		if (mainIcon == null) mainIcon = boxIcon;
		items.add(new NokiaAppItem(NokiaAppItem.TYPE_MAIN, "J2ME Loader", mainIcon, null));
		NokiaLog.d("Menu", "已追加特殊入口：J2ME 加载器（TYPE_MAIN，进入 J2meLoaderActivity）");
		items.add(new NokiaAppItem(NokiaAppItem.TYPE_SETTINGS, "桌面设置", settingsIcon, null));

		// 将 pool 拆分为已匹配 S60 图标 和 未匹配，匹配的排在前面。
		// 使用构建 pool 时记录的 s60IconResId，避免二次调用 getIcon() 因缓存状态变化导致分组不一致。
		List<NokiaAppItem> matchedPool = new ArrayList<>();
		List<NokiaAppItem> unmatchedPool = new ArrayList<>();
		for (NokiaAppItem app : pool) {
			if (app.s60IconResId != 0) {
				matchedPool.add(app);
			} else {
				unmatchedPool.add(app);
			}
		}
		// 两组内部均按名称排序
		Comparator<NokiaAppItem> labelCmp = (a, b) -> a.label.compareToIgnoreCase(b.label);
		Collections.sort(matchedPool, labelCmp);
		Collections.sort(unmatchedPool, labelCmp);

		items.addAll(matchedPool);
		items.addAll(unmatchedPool);

		NokiaLog.i("Menu", "最终列表（固定槽位 " + pinned.size() + " + 特殊入口 + 匹配 " + matchedPool.size() + " + 未匹配 " + unmatchedPool.size() + "）共 " + items.size() + " 项");
		totalPages = Math.max(1, (int) Math.ceil((double) items.size() / perPage));
		NokiaLog.i("Menu", "loadApps 主线程耗时 " + (System.currentTimeMillis() - loadStart)
				+ "ms（枚举 + label，不含系统图标 IPC）");

		// S60 图标缓存：冷启动时桌面已后台扫描（scanStarted 防重复）；此处仅确保扫描已发起，
		// 完成后在主线程刷新当前页应用图标（不重建网格，不影响焦点/分页）
		NokiaS60IconMap.initAsync(requireContext(), new Runnable() {
			@Override
			public void run() {
				refreshAfterIconInit();
			}
		});
	}

	/**
	 * 包安装/卸载/替换后刷新应用列表：重新枚举并重建当前页，尽量保持当前页与焦点位置。
	 * 卸载导致当前页变空（越界）时，焦点收敛到新列表末尾。
	 */
	private void refreshAppList() {
		if (!isAdded() || getView() == null) return;
		NokiaLog.i("Menu", "刷新应用列表（包变化触发）");
		int oldPage = pageIndex;
		int oldFocus = focusPos;
		// 允许本次刷新后再次应用 S60 图标缓存（新装应用也走一次）
		iconRefreshDone = false;
		loadApps();
		// 若卸载后总页数减少，收敛到最后一页
		if (pageIndex > totalPages - 1) {
			pageIndex = Math.max(0, totalPages - 1);
		}
		buildCurrentPage();
		// 焦点保持原位置，越界则收敛到当前页末尾
		int count = Math.min(perPage, items.size() - pageIndex * perPage);
		int newFocus = Math.min(oldFocus, Math.max(0, count - 1));
		setFocusPos(newFocus);
		NokiaLog.i("Menu", "刷新完成: page=" + (pageIndex + 1) + "/" + totalPages
				+ " focus=" + newFocus + " 共 " + items.size() + " 项");
	}

	/**
	 * S60 图标异步扫描完成后：用最新缓存刷新当前页各应用的图标（仅替换 ImageView，不重建网格）。
	 */
	private void refreshAfterIconInit() {
		if (iconRefreshDone || !isAdded() || getView() == null) return;
		iconRefreshDone = true;
		long start = System.currentTimeMillis();
		for (int i = 0; i < perPage; i++) {
			NokiaAppItem item = pageItems[i];
			View cell = cellViews[i];
			if (item == null || cell == null) continue;
			if (item.type != NokiaAppItem.TYPE_APP) continue;
			if (item.launchIntent == null || item.launchIntent.getComponent() == null) continue;
			String pkg = item.launchIntent.getComponent().getPackageName();
			int resId = NokiaS60IconMap.getIcon(pkg, item.label);
			if (resId == 0) continue;
			Drawable s60Icon = safeDrawable((NokiaDesktopActivity) requireActivity(), resId);
			if (s60Icon == null) continue;
			s60Icon.setFilterBitmap(false);
			item.icon = s60Icon;
			item.s60IconResId = resId; // 同步更新匹配结果
			if (cell instanceof LinearLayout) {
				View iv = ((LinearLayout) cell).getChildAt(0);
				if (iv instanceof ImageView) {
					((ImageView) iv).setImageDrawable(s60Icon);
				}
			}
		}
		long elapsed = System.currentTimeMillis() - start;
		NokiaLog.i("Menu", "S60 图标缓存更新后刷新当前页图标，耗时 " + elapsed + "ms");
	}

	/** 在应用池中按包名查找第一个命中的项并移除，返回之；未命中返回 null。 */
	@Nullable
	private static NokiaAppItem pollByPackage(List<NokiaAppItem> pool, String pkg) {
		for (int i = 0; i < pool.size(); i++) {
			NokiaAppItem app = pool.get(i);
			if (app.launchIntent != null && app.launchIntent.getComponent() != null
					&& pkg.equals(app.launchIntent.getComponent().getPackageName())) {
				pool.remove(i);
				return app;
			}
		}
		return null;
	}

	/**
	 * 单独枚举「被冻结」（包级停用）的应用并追加到 pool。
	 * <p>
	 * 正常应用枚举已用 flags=0（只含启用组件），冻结应用因包被停用而不会出现；
	 * 此处用 MATCH_DISABLED_COMPONENTS 单独查，且只保留「包停用、但组件本身启用」的项——
	 * 包被冻结但组件可用，解冻后即可正常启动。组件本身也停用的（如 STK 的 StkMain、
	 * MT 的 NoBg/Dark 主题别名、抖音 CarPlay 入口）无法启动，一律跳过，绝不混入列表。
	 */
	private void addFrozenApps(List<NokiaAppItem> pool, NokiaDesktopActivity host,
			PackageManager pm, String selfPkg) {
		Intent main = new Intent(Intent.ACTION_MAIN, null);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		int flags = 0;
		if (Build.VERSION.SDK_INT >= 24) {
			flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
		} else {
			flags |= PackageManager.GET_DISABLED_COMPONENTS;
		}
		List<ResolveInfo> list;
		try {
			list = pm.queryIntentActivities(main, flags);
		} catch (Exception e) {
			NokiaLog.e("Menu", "冻结应用枚举失败", e);
			return;
		}

		int added = 0;
		for (ResolveInfo ri : list) {
			ActivityInfo ai = ri.activityInfo;
			if (ai == null || ai.applicationInfo == null) continue;
			// 只保留「包停用 + 组件启用」的项（解冻后可正常启动）；跳过自身
			if (ai.applicationInfo.enabled) continue;
			if (!ai.enabled) continue;
			if (ai.packageName.equals(selfPkg)) continue;
			if (poolContainsPackage(pool, ai.packageName)) continue;

			String labelKey = ai.packageName + "/" + ai.name;
			String label = labelCache.get(labelKey);
			if (label == null) {
				CharSequence labelCs = ri.loadLabel(pm);
				label = (labelCs != null && labelCs.length() > 0) ? labelCs.toString() : ai.name;
				labelCache.put(labelKey, label);
			}
			Intent launch = new Intent(Intent.ACTION_MAIN);
			launch.addCategory(Intent.CATEGORY_LAUNCHER);
			launch.setClassName(ai.packageName, ai.name);
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			NokiaAppItem item = new NokiaAppItem(NokiaAppItem.TYPE_APP, label, null, launch);
			item.s60IconResId = NokiaS60IconMap.getIcon(ai.packageName, label);
			if (item.s60IconResId != 0) {
				Drawable s60Icon = safeDrawable(host, item.s60IconResId);
				if (s60Icon != null) {
					item.icon = s60Icon;
				}
			}
			pool.add(item);
			added++;
			NokiaLog.d("Menu", "追加冻结应用: " + ai.packageName + "/" + ai.name);
		}
		if (added > 0) {
			NokiaLog.i("Menu", "追加冻结应用 " + added + " 个");
		}
	}

	/** pool 中是否已存在指定包名的应用项 */
	private static boolean poolContainsPackage(List<NokiaAppItem> pool, String pkg) {
		for (NokiaAppItem app : pool) {
			if (app.launchIntent != null && app.launchIntent.getComponent() != null
					&& pkg.equals(app.launchIntent.getComponent().getPackageName())) {
				return true;
			}
		}
		return false;
	}

	private Drawable safeDrawable(NokiaDesktopActivity host, int resId) {
		try {
			Drawable d = ContextCompat.getDrawable(host, resId);
			// API 19 上多个 ImageView 共享同一 Bitmap 时，硬件加速渲染可能触发
			// Adreno GL_INVALID_OPERATION 导致图标变黑；mutate 隔离 Drawable 状态
			if (d != null) {
				d = d.mutate();
			}
			return d;
		} catch (Exception e) {
			NokiaLog.w("Menu", "加载图标失败 res=" + resId);
			return null;
		}
	}

	/** 系统图标未加载完成前的占位图标（懒加载 + mutate 隔离，避免共享 Bitmap 变黑） */
	private Drawable getPlaceholderIcon() {
		if (placeholderIcon == null) {
			try {
				Drawable d = ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher);
				placeholderIcon = d != null ? d.mutate() : null;
			} catch (Exception e) {
				NokiaLog.w("Menu", "加载占位图标失败");
			}
		}
		return placeholderIcon;
	}

	// ---- 构建当前页网格 ----

	private void buildCurrentPage() {
		if (appGrid == null) return;
		appGrid.removeAllViews();
		// 重置页内缓存
		for (int i = 0; i < perPage; i++) {
			cellViews[i] = null;
			pageItems[i] = null;
		}

		// 行高均分拉伸：按实测可用空间计算每行实际 dp 高度
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float availForGrid = Math.max(0f, availDesign - TITLE_H_DP - 4f);
		float rowActualDp = rowsPerPage > 0 ? (availForGrid / rowsPerPage) : ROW_H_DP;
		int rowH = NokiaDimens.dp(getResources(), Math.round(rowActualDp));

		int start = pageIndex * perPage;
		int count = Math.min(perPage, items.size() - start);
		NokiaLog.d("Menu", "buildCurrentPage 页=" + (pageIndex + 1) + "/" + totalPages
				+ " start=" + start + " count=" + count + " rows=" + rowsPerPage
				+ " rowH=" + rowH + "px rowActualDp=" + rowActualDp + " availDesign=" + availDesign);

		for (int r = 0; r < rowsPerPage; r++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, rowH));

			for (int c = 0; c < COLS; c++) {
				int pos = r * COLS + c;
				LinearLayout cell = new LinearLayout(requireContext());
				cell.setOrientation(LinearLayout.VERTICAL);
				cell.setGravity(Gravity.CENTER);
				cell.setLayoutParams(new LinearLayout.LayoutParams(
						0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
				cell.setPadding(NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 4));

				if (pos < count) {
					NokiaAppItem item = items.get(start + pos);
					pageItems[pos] = item;

					String pkg = null;
					if (item.launchIntent != null) {
						if (item.launchIntent.getComponent() != null) {
							pkg = item.launchIntent.getComponent().getPackageName();
						} else if (!TextUtils.isEmpty(item.launchIntent.getPackage())) {
							pkg = item.launchIntent.getPackage();
						}
					}
					boolean isFrozen = (pkg != null && NokiaFreezeManager.getInstance(requireContext()).isAppFrozen(pkg));

					FrameLayout iconContainer = new FrameLayout(requireContext());
					iconContainer.setLayoutParams(new LinearLayout.LayoutParams(
							NokiaDimens.dp(getResources(), 36), NokiaDimens.dp(getResources(), 36)));

					ImageView iv = new ImageView(requireContext());
					iv.setLayoutParams(new FrameLayout.LayoutParams(
							FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
					if (item.icon != null) {
						// 关闭缩放过滤：S60 图标为 nodpi 位图，最近邻缩放更锐利、契合复古风格，
						// 避免 36dp 内降采样发虚（API 19 尤其明显）；真实应用图标密度感知，影响甚微。
						item.icon.setFilterBitmap(false);
						iv.setImageDrawable(item.icon);
					} else if (item.type == NokiaAppItem.TYPE_APP
							&& item.launchIntent != null
							&& item.launchIntent.getComponent() != null) {
						// S60 未命中 → 先显示占位，后台线程加载真实系统图标（内存/磁盘缓存复用）
						iv.setImageDrawable(getPlaceholderIcon());
						final String asyncPkg = item.launchIntent.getComponent().getPackageName();
						final ComponentName cn = item.launchIntent.getComponent();
						final NokiaAppItem fItem = item;
						iv.setTag(asyncPkg);
						NokiaAppIconCache.loadAsync(requireContext(), asyncPkg, cn, (loadedPkg, d) -> {
							// 校验：cell 仍属于该应用（翻页/重建后 tag 变化则跳过），
							// 且该应用未被 S60 图标替换（S60 优先级高于系统图标）
							if (d == null || iv.getTag() == null
									|| !iv.getTag().equals(loadedPkg)) return;
							if (fItem.s60IconResId != 0) return;
							d.setFilterBitmap(false);
							iv.setImageDrawable(d);
							fItem.icon = d;
						});
					}
					iconContainer.addView(iv);

					// 若已被系统级真实冻结，用冰块效果全包围覆盖图标；若仅在名单中未冻结，显示雪花角标
					boolean isRealFrozen = NokiaFreezeManager.getInstance(requireContext()).isAppFrozen(pkg);
					boolean isInList = NokiaFreezeManager.getInstance(requireContext()).isInFreezeList(pkg);
					if (isRealFrozen) {
						ImageView iceCover = new ImageView(requireContext());
						FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
						coverLp.gravity = Gravity.CENTER;
						iceCover.setLayoutParams(coverLp);
						iceCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
						iceCover.setImageResource(R.drawable.ic_nokia_ice_block_cover);
						iconContainer.addView(iceCover);
					} else if (isInList) {
						ImageView badgeIv = new ImageView(requireContext());
						int badgeSize = NokiaDimens.dp(getResources(), 14);
						FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSize, badgeSize);
						badgeLp.gravity = Gravity.BOTTOM | Gravity.END;
						badgeIv.setLayoutParams(badgeLp);
						badgeIv.setImageResource(R.drawable.ic_nokia_ice_badge);
						iconContainer.addView(badgeIv);
					}

					TextView tv = new TextView(requireContext());
					tv.setLayoutParams(new LinearLayout.LayoutParams(
							LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
					tv.setText(item.label);
					tv.setTextColor(0xFFFFFFFF);
					NokiaDimens.textSize(tv, 9);
					tv.setSingleLine(true);
					tv.setEllipsize(TextUtils.TruncateAt.END);
					tv.setMaxWidth(NokiaDimens.dp(getResources(), 72));
					cell.addView(iconContainer);
					cell.addView(tv);

					final int fpos = pos;
					cell.setClickable(true);
					cell.setOnClickListener(v -> {
						setFocusPos(fpos);
						onSelect();
					});
					cell.setOnTouchListener(swipeTouchListener);
					cellViews[pos] = cell;
				}
				row.addView(cell);
			}
			appGrid.addView(row);
		}

		if (tvPage != null) {
			tvPage.setText((pageIndex + 1) + "/" + totalPages);
		}
	}


	// ---- NokiaFocusHost 接口 ----

	@Override
	public boolean onDirection(int direction) {
		int pos = focusPos;
		int row = pos / COLS;
		int col = pos % COLS;
		int count = Math.min(perPage, items.size() - pageIndex * perPage);

		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (row > 0 && (pos - COLS) < count) {
					// 页内上移
					setFocusPos(pos - COLS);
				} else if (pageIndex > 0) {
					// 已到本页顶部 → 翻上一页
					pagePrev();
				}
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (row < rowsPerPage - 1 && (pos + COLS) < count) {
					// 页内下移
					setFocusPos(pos + COLS);
				} else if (pageIndex < totalPages - 1) {
					// 已到本页底部 → 翻下一页
					pageNext();
				}
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
				// 仅在本行内移动：到最左端回绕到本行最右端
				if (col > 0) {
					setFocusPos(pos - 1);
				} else {
					setFocusPos(pos + COLS - 1);
				}
				return true;
			case NokiaKeyBinding.ACTION_RIGHT:
				// 仅在本行内移动：到最右端回绕到本行最左端
				if (col < COLS - 1) {
					setFocusPos(pos + 1);
				} else {
					setFocusPos(pos - (COLS - 1));
				}
				return true;
			default:
				return false;
		}
	}

	/**
	 * 翻页后重建当前页并把焦点定位到目标位置（按列保持连续性）。
	 * @param col     要保持的列
	 * @param desired 期望的页内位置（行×COLS+col），会收敛到本页实际项数范围内
	 */
	private void rebuildAndFocusCol(int col, int desired) {
		buildCurrentPage();
		int newCount = Math.min(perPage, items.size() - pageIndex * perPage);
		int newPos = desired;
		if (newPos >= newCount) {
			newPos = Math.max(0, newCount - 1);
		}
		focusPos = newPos;
		applyFocusBackground();
	}

	// ---- 翻页（方向键 / 滑动共用） ----

	/** 翻到下一页，保持当前焦点列置于下一页首行（"一直往下"的延续）。 */
	private void pageNext() {
		if (!isAdded() || getView() == null) return;
		int col = focusPos % COLS;
		if (pageIndex < totalPages - 1) {
			pageIndex++;
			rebuildAndFocusCol(col, col);
			NokiaLog.d("Menu", "翻页(下/左滑) -> " + (pageIndex + 1) + "/" + totalPages
					+ " col=" + col);
		}
	}

	/** 翻到上一页，保持当前焦点列置于上一页末行（"一直往上"的延续）。 */
	private void pagePrev() {
		if (!isAdded() || getView() == null) return;
		int col = focusPos % COLS;
		if (pageIndex > 0) {
			pageIndex--;
			rebuildAndFocusCol(col, (rowsPerPage - 1) * COLS + col);
			NokiaLog.d("Menu", "翻页(上/右滑) -> " + (pageIndex + 1) + "/" + totalPages
					+ " col=" + col);
		}
	}

	/**
	 * 初始化滑动翻页手势监听并挂载到根视图。
	 * 同一监听实例也会在 buildCurrentPage() 中挂载到每个 cell，
	 * 因为 cell 是 clickable 的、会消费触摸事件，若不挂载到 cell 则滑过图标时无法翻页。
	 * 判定规则：上滑/左滑 → 下一页；下滑/右滑 → 上一页。
	 * 位移或速度任一达到阈值即判定为滑动并消费事件（避免误触 item 点击）；
	 * 否则不消费，事件继续下发，cell 的 onClick 正常启动应用。
	 */
	private void initSwipeListener(View root) {
		swipeThreshold = NokiaDimens.dp(getResources(), 24);   // 位移阈值（dp）
		swipeMinVel = 0.35f;       // 速度阈值（px/ms，快速轻扫也翻页）
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
						// clickable 的 app cell 不消费 down，留给 onClick；
						// 非 clickable 的视图（根布局/midPanel/空白区）必须消费 down，
						// 否则系统不再下发后续 MOVE/UP，空白处滑动失效。
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
							return true; // 消费：阻止本次抬起触发 item 点击
						}
						return false; // 非滑动：交给 cell 的 onClick 启动应用
					}
					default:
						return false;
				}
			}
		};
		root.setOnTouchListener(swipeTouchListener);
		// 同时挂载到 midPanel，覆盖碎片根视图没铺满的空白壁纸区域
		View mid = requireActivity().findViewById(R.id.midPanel);
		if (mid != null) {
			mid.setOnTouchListener(swipeTouchListener);
			NokiaLog.d("Menu", "initSwipeListener 已挂载到 midPanel（覆盖空白区）");
		}
		NokiaLog.d("Menu", "initSwipeListener 已挂载滑动翻页监听（根视图 + midPanel + 每个 cell 复用）");
	}

	@Override
	public boolean onSelect() {
		int global = pageIndex * perPage + focusPos;
		if (global < 0 || global >= items.size()) {
			NokiaLog.w("Menu", "onSelect 越界 global=" + global);
			return false;
		}
		NokiaAppItem item = items.get(global);
		if (item == null) return false;
		NokiaLog.i("Menu", "onSelect type=" + item.type + " label=" + item.label);

		if (item.type == NokiaAppItem.TYPE_BOX) {
			((NokiaDesktopActivity) requireActivity()).openBox();
			return true;
		}
		if (item.type == NokiaAppItem.TYPE_MAIN) {
			try {
				Intent main = new Intent(requireActivity(), J2meLoaderActivity.class);
				main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
						| Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
				startActivity(main);
				NokiaLog.i("Menu", "进入原始 J2ME-Loader 主界面 J2meLoaderActivity");
			} catch (Exception e) {
				NokiaLog.e("Menu", "启动 J2meLoaderActivity 失败", e);
			}
			return true;
		}
		if (item.type == NokiaAppItem.TYPE_SETTINGS) {
			((NokiaDesktopActivity) requireActivity()).openDesktopSettings();
			return true;
		}
		// 原生应用（带 launchIntent）
		if (item.launchIntent != null) {
			String pkg = null;
			if (item.launchIntent.getComponent() != null) {
				pkg = item.launchIntent.getComponent().getPackageName();
			} else if (!TextUtils.isEmpty(item.launchIntent.getPackage())) {
				pkg = item.launchIntent.getPackage();
			}

			if (pkg != null && NokiaFreezeManager.getInstance(requireContext()).isAppFrozen(pkg)) {
				NokiaFreezeManager.getInstance(requireContext()).unfreezeAndLaunch(item.launchIntent, pkg, item.label);
				return true;
			}

			try {
				startActivity(item.launchIntent);
				NokiaLog.i("Menu", "启动应用 " + item.label);
			} catch (Exception e) {
				// 兜底：组件可能因应用更新/状态变化而失效，重新解析当前启用入口再试一次
				if (pkg != null && retryWithLaunchIntent(pkg, item.label)) {
					return true;
				}
				NokiaLog.e("Menu", "启动失败 " + item.label, e);
			}
			return true;
		}
		return false;
	}

	/** 启动失败兜底：用 getLaunchIntentForPackage 重新解析启用入口并启动。 */
	private boolean retryWithLaunchIntent(String pkg, String label) {
		try {
			Intent retry = requireActivity().getPackageManager().getLaunchIntentForPackage(pkg);
			if (retry == null) return false;
			retry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			startActivity(retry);
			NokiaLog.i("Menu", "兜底启动成功 " + label + " -> " + retry.getComponent());
			return true;
		} catch (Exception e2) {
			NokiaLog.e("Menu", "兜底启动也失败 " + label, e2);
			return false;
		}
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键 = "选项"：仅对安卓原生应用弹出选项菜单；特殊入口保持原确认动作
		int global = pageIndex * perPage + focusPos;
		if (global >= 0 && global < items.size()) {
			NokiaAppItem item = items.get(global);
			if (item != null && item.type == NokiaAppItem.TYPE_APP) {
				showAppOptionsMenu(item);
				return true;
			}
		}
		return onSelect();
	}

	/**
	 * 弹出诺基亚风格选项菜单（卸载 / 应用设置），仅针对安卓原生应用。
	 */
	private void showAppOptionsMenu(NokiaAppItem item) {
		if (item == null || item.launchIntent == null) {
			NokiaLog.w("Menu", "showAppOptionsMenu: item 或 launchIntent 为 null，忽略");
			return;
		}
		String resolvedPkg = item.launchIntent.getPackage();
		if (TextUtils.isEmpty(resolvedPkg) && item.launchIntent.getComponent() != null) {
			resolvedPkg = item.launchIntent.getComponent().getPackageName();
		}
		if (TextUtils.isEmpty(resolvedPkg)) {
			NokiaLog.w("Menu", "showAppOptionsMenu: 无法解析包名，忽略 " + item.label);
			return;
		}
		final String pkg = resolvedPkg;
		NokiaLog.i("Menu", "弹出选项菜单: " + item.label + " pkg=" + pkg);
		List<NokiaOptionsDialog.OptionItem> options = new ArrayList<>();

		// 冻结 / 移出冻结列表选项
		boolean inFreezeList = NokiaFreezeManager.getInstance(requireContext()).isInFreezeList(pkg);
		boolean isFrozen = NokiaFreezeManager.getInstance(requireContext()).isAppFrozen(pkg);
		if (inFreezeList) {
			if (isFrozen) {
				options.add(new NokiaOptionsDialog.OptionItem(R.drawable.ic_nokia_freeze,
						"解冻应用", true, false, () -> {
					NokiaFreezeManager.getInstance(requireContext()).unfreezeApp(pkg, (success, msg) -> {
						if (isAdded()) {
							Toast.makeText(requireContext(), success ? ("已解冻: " + item.label) : ("解冻失败: " + msg), Toast.LENGTH_SHORT).show();
							buildCurrentPage();
						}
					});
				}));
			} else {
				options.add(new NokiaOptionsDialog.OptionItem(R.drawable.ic_nokia_freeze,
						"立即冻结", true, false, () -> {
					NokiaFreezeManager.getInstance(requireContext()).freezeApp(pkg, (success, msg) -> {
						if (isAdded()) {
							Toast.makeText(requireContext(), success ? ("已冻结: " + item.label) : ("冻结失败: " + msg), Toast.LENGTH_SHORT).show();
							buildCurrentPage();
						}
					});
				}));
			}
			options.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_close_clear_cancel,
					"移出冻结列表", true, false, () -> {
				NokiaFreezeManager.getInstance(requireContext()).removeFromFreezeList(pkg);
				Toast.makeText(requireContext(), "已移出冻结列表", Toast.LENGTH_SHORT).show();
				buildCurrentPage();
			}));
		} else {
			options.add(new NokiaOptionsDialog.OptionItem(R.drawable.ic_nokia_freeze,
					"加入冻结列表", true, false, () -> {
				NokiaFreezeManager.getInstance(requireContext()).addToFreezeList(pkg);
				Toast.makeText(requireContext(), "已加入冻结列表", Toast.LENGTH_SHORT).show();
				buildCurrentPage();
			}));
		}
		options.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_delete,
				"卸载", true, false, () -> {
			NokiaLog.i("Menu", "选项菜单-卸载: " + item.label + " pkg=" + pkg);
			try {
				Intent uninstall = new Intent(Intent.ACTION_DELETE,
						Uri.fromParts("package", pkg, null));
				uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity(uninstall);
			} catch (Exception e) {
				NokiaLog.e("Menu", "卸载跳转失败 " + item.label, e);
			}
		}));
		options.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_manage,
				"应用设置", true, false, () -> {
			NokiaLog.i("Menu", "选项菜单-应用设置: " + item.label + " pkg=" + pkg);
			try {
				Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.fromParts("package", pkg, null));
				settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity(settings);
			} catch (Exception e) {
				NokiaLog.e("Menu", "应用设置跳转失败 " + item.label, e);
			}
		}));
		NokiaOptionsDialog.show(getParentFragmentManager(), item.label, options);
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

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return "功能表";
	}

	@Override
	public String getSoftLeftText() {
		return "选项";
	}

	@Override
	public String getSoftRightText() {
		return "退出";
	}

	// ---- 内部逻辑 ----

	private void setFocusPos(int pos) {
		if (pos < 0 || pos >= perPage) return;
		clearFocusBackground();
		focusPos = pos;
		applyFocusBackground();
	}

	private void clearFocusBackground() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyFocusBackground() {
		if (focusPos >= 0 && focusPos < cellViews.length) {
			View v = cellViews[focusPos];
			if (v != null) {
				v.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
				selectedView = v;
				return;
			}
		}
		selectedView = null;
	}
}
