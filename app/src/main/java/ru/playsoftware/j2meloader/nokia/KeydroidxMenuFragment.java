package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;

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
import android.os.Handler;
import android.os.Looper;
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

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxAdwIconPack;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPack;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPackManager;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconResolver;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxS60Icons;
import ru.playsoftware.j2meloader.J2meLoaderActivity;
import ru.playsoftware.j2meloader.R;

/**
 * 功能表（应用网格）中间内容碎片。
 * 通过 PackageManager 枚举所有可启动的安卓应用，分页以 3 列网格展示真实 APP 图标。
 * 方向键在页内移动焦点：左/右到边界时翻到上/下一页；确认键启动对应 APP。
 * 末尾追加「百宝箱」「按键绑定」两个特殊入口，保留原功能可达性。
 */
public class KeydroidxMenuFragment extends KeydroidxPageFragment {
	private static final String TAG = "KeydroidxMenuFragment";


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

	/** 图标框基准边长（dp，fontScale = 1 时）。实际边长随字号缩放，见 {@link #iconBoxDp(float)} */
	private static final int ICON_BOX_DP = 36;

	/**
	 * 图标随字号的放大系数：比字号弱一档（0.6），与桌面快捷栏/宫格同一约定，
	 * 避免大字号下图标喧宾夺主；fontScale &lt; 1 时兜底 0.8 倍。
	 * 例：字号 1.3x → 图标约 1.18x；1.5x → 1.3x；2.0x → 1.6x。
	 */
	private static float iconScale(float fontScale) {
		return Math.max(0.8f, 1.0f + (fontScale - 1.0f) * 0.6f);
	}

	/** 图标框边长（dp）：随字号同步放大，避免「字形变大、图标不变」的失衡 */
	private static int iconBoxDp(float fontScale) {
		return Math.round(ICON_BOX_DP * iconScale(fontScale));
	}

	/** 应用名文字行高预算（dp）：9sp × 点阵字体行距 1.6 + 2dp，下限 16dp */
	private static float textBudgetDp(float fontScale) {
		return Math.max(16f, (9f * fontScale * 1.6f) + 2f);
	}

	/**
	 * 单行单元格所需的绝对最小安全设计高度（dp）：
	 * 图标框 + 8dp(上下 Cell padding 4+4) + 文字行高预算 + 2dp(选中高亮边框余量)。
	 * 图标随字号变大后此值随之变大，行数会被自动顶下去——「能放几行就放几行」靠它实现。
	 */
	private static float minRowHDp(float fontScale) {
		return iconBoxDp(fontScale) + 8f + textBudgetDp(fontScale) + 2f;
	}

	/**
	 * 标题区实际高度预算（dp）：13sp 标题行高（点阵字体行距系数 1.5，随 fontScale 缩放）
	 * + appGrid 底部 padding 2dp。
	 * 必须与 fragment_keydroidx_menu.xml 的实际占位严格一致（标题 marginTop=0、
	 * appGrid paddingTop=0 / paddingBottom=2），否则网格总高会超出 midPanel，
	 * 最后一行应用名被底部裁切。预算宁可略大（多出的是底部留白）也不可小于实际占位。
	 */
	private static float titleBudgetDp(float fontScale) {
		return 13f * fontScale * 1.5f + 2f;
	}

	private final ArrayList<KeydroidxAppItem> items = new ArrayList<>();
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
	private KeydroidxAppItem[] pageItems;
	private View selectedView = null;

	/**
	 * 包安装/卸载/替换广播接收器：应用列表实时跟随系统变化。
	 * 卸载（ACTION_DELETE）会切到系统卸载页，Fragment 只是 onPause 不销毁，
	 * 因此注册放在 onViewCreated / onDestroyView 生命周期内，能覆盖卸载完成返回的场景。
	 */
	private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			handleAppListBroadcast(intent);
		}
	};

	/**
	 * 冻结状态变更广播接收器（与 packageReceiver 必须是<b>两个独立实例</b>）。
	 * <p>
	 * 历史 bug：曾用同一个 {@code packageReceiver} 先注册 pkgFilter 再注册 freezeFilter，
	 * 而 Android 语义下「同一实例重复 registerReceiver 会用新 filter <b>替换</b>旧 filter」，
	 * 导致包安装/卸载/替换广播实际从未生效。二者又不能合并成一个 filter —— pkgFilter 带
	 * {@code dataScheme("package")}，而冻结广播 intent 无 data，合并后会匹配不上。
	 */
	private final BroadcastReceiver freezeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			handleAppListBroadcast(intent);
		}
	};

	/** 两个 receiver 共用的处理逻辑（冻结状态变更 / 包安装卸载替换）。 */
	private void handleAppListBroadcast(Intent intent) {
		String action = intent.getAction();
		if (action == null) return;
		KeydroidxLog.i("Menu", "收到包变化广播: " + action + " data=" + intent.getDataString());

		// 冻结状态变化：列表内容不变，只需重绘当前页的冰块/角标（轻量，主线程直接做）
		if (KeydroidxFreezeManager.ACTION_FREEZE_STATE_CHANGED.equals(action)) {
			invalidateFrozenCache();
			// pm disable-user 通过 Shizuku 返回成功后，PMS 对部分包（targetSdk 较高者）
			// 的状态更新存在数秒级延迟，buildCurrentPage 即时查询 getApplicationInfo
			// 会返回旧状态、毒化缓存导致冰块不显示。优先用广播携带的预期值预写缓存。
			String changedPkg = intent.getStringExtra(KeydroidxFreezeManager.EXTRA_PACKAGE);
			if (changedPkg != null) {
				boolean expectedFrozen = intent.getBooleanExtra(
						KeydroidxFreezeManager.EXTRA_FROZEN, false);
				frozenStateCache.put(changedPkg, expectedFrozen);
				KeydroidxLog.d("Menu", "预写冻结缓存: " + changedPkg + " -> " + expectedFrozen);
			} else {
				// 一键冻结/解冻走批量路径：携带整包名列表 + 预期状态。
				ArrayList<String> batch = intent.getStringArrayListExtra(
						KeydroidxFreezeManager.EXTRA_PACKAGES);
				if (batch != null && !batch.isEmpty()) {
					boolean expectedFrozen = intent.getBooleanExtra(
							KeydroidxFreezeManager.EXTRA_FROZEN, false);
					for (String p : batch) {
						frozenStateCache.put(p, expectedFrozen);
					}
					KeydroidxLog.d("Menu", "批量预写冻结缓存: " + batch.size()
							+ " 个 -> " + expectedFrozen);
				}
			}
			if (isAdded() && getView() != null) {
				buildCurrentPage();
				applyFocusBackground();
			}
			return;
		}

		// 包安装/卸载/替换：列表内容会变，需重新枚举。
		// 稍作延迟等系统包表稳定，避免偶发仍能查到底层已卸载的残留
		View v = getView();
		if (v != null) {
			v.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (isAdded()) refreshAppList();
				}
			}, 300);
		} else {
			refreshAppList();
		}
	}

	/** 应用显示名内存缓存（进程内复用，避免每次进入功能表反复 loadLabel IPC） */
	private static final Map<String, String> labelCache = new HashMap<>();

	/**
	 * 进程内应用列表缓存（含已解析的图标）。
	 * <p>
	 * 原先每次进入功能表都是 {@code new KeydroidxMenuFragment()}，完整重跑一遍
	 * 2 次 {@code queryIntentActivities} + 逐个 {@code loadLabel}，期间网格为空
	 * —— 这就是「打开功能表先空白一瞬」的根因。缓存后第二次进入零 IPC 直接出图。
	 * <p>
	 * 失效时机：包安装/卸载/替换（见 packageReceiver）、冻结状态变化（见 freezeReceiver）。
	 */
	private static final List<KeydroidxAppItem> cachedItems = new ArrayList<>();

	/**
	 * 缓存构建时的图标外观指纹（图标包 ID + 单应用覆盖摘要）。
	 * 与当前指纹不一致说明用户切换过图标包或改过覆盖 → 缓存整体失效重建，
	 * 避免从图标包设置页返回功能表时仍显示旧图标。
	 */
	private static String cachedIconState;

	/** 应用列表构建线程池（仅在缓存缺失时用于后台枚举，避免阻塞主线程）。 */
	private static final ExecutorService LIST_EXECUTOR = Executors.newSingleThreadExecutor();
	private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

	/** 后台枚举进行中标记，防止重复提交。 */
	private boolean loadingAsync = false;

	/** 下一次后台枚举完成后是否恢复原页码与焦点（包变化刷新场景）。 */
	private boolean refreshKeepPosition = false;

	/**
	 * 图标外观（全局图标包 / 单应用覆盖）已变化，待按新指纹就地重算图标。
	 * <p>置位期间<b>保留 pageIndex 与 focusPos</b>：图标外观变化只影响「图标长什么样」，
	 * 不影响列表内容与顺序，若照旧实现整表判失效，用户给第 3 页的应用换个图标后
	 * 就会被扔回第 1 页，连续换多个图标时每次都要重新翻页。
	 */
	private boolean iconAppearanceChanged = false;

	/** 轻量图标刷新进行中标记，防止重复提交。 */
	private boolean iconRefreshRunning = false;

	/** 检测到图标外观变化时的旧指纹（用于区分「全局换图标包」与「单应用覆盖」）。 */
	private String staleIconState;

	/**
	 * 「图标外观已变化、但还没被轻量刷新确认落地」的强制重建标记。
	 * 轻量刷新成功回包即清除；若它没跑成（异常 / 取不到图标包），
	 * 由后台校准判定列表不一致后重建兜底（见 loadAppsAsync）。
	 */
	private boolean forceIconRebuild = false;

	/** 系统图标未加载完成前的占位图标（懒加载） */
	private Drawable placeholderIcon;

	/** 滑动翻页阈值（px，由 dp 换算）与最小速度（px/ms） */
	private float swipeThreshold;
	private float swipeMinVel;
	/** 复用于根视图与每个 cell 的滑动手势监听 */
	private View.OnTouchListener swipeTouchListener;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_menu;
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
			ContextCompat.registerReceiver(requireContext(), packageReceiver, pkgFilter,
					ContextCompat.RECEIVER_NOT_EXPORTED);
			KeydroidxLog.i("Menu", "已注册包变化广播接收器（ADDED/REMOVED/REPLACED）");
		} catch (Exception e) {
			KeydroidxLog.e("Menu", "注册包变化广播失败", e);
		}

		IntentFilter freezeFilter = new IntentFilter();
		freezeFilter.addAction(KeydroidxFreezeManager.ACTION_FREEZE_STATE_CHANGED);
		try {
			// 必须用独立的 freezeReceiver：同一实例再注册会替换掉上面的 pkgFilter，
			// 而两个 filter 又不能合并（pkgFilter 带 dataScheme，冻结广播无 data）。
			// targetSdk 34 起必须显式声明导出性，否则 Android 14+ 注册时抛 SecurityException；
			// 冻结广播来自系统与应用自身，声明 NOT_EXPORTED。
			ContextCompat.registerReceiver(requireContext(), freezeReceiver, freezeFilter,
					ContextCompat.RECEIVER_NOT_EXPORTED);
			KeydroidxLog.i("Menu", "已注册冻结状态广播接收器");
		} catch (Exception e) {
			KeydroidxLog.e("Menu", "注册冻结状态广播失败", e);
		}

		// 进入功能表时主动校正冻结缓存——这是本 Bug 的兜底修复：
		// 一键冻结通常从桌面快捷开关触发，此刻功能表不在屏上、接收器未注册，
		// freezeAll 发出的变更广播会被丢失，导致本 Fragment 跨实例残留的静态
		// frozenStateCache 里仍是冻结前的 false，buildCurrentPage 命中后不画冰块。
		// 解法与用户判断一致：「桌面自己冻结、桌面自己知道」——直接读本进程内
		// executeFreeze 成功过的 getKnownFrozenSet() 预写 true，既清掉陈旧 false，
		// 又绕过 pm disable-user 后 PMS 对高 targetSdk 包的数秒级状态更新延迟。
		invalidateFrozenCache();
		int prewritten = 0;
		for (String p : KeydroidxFreezeManager.getInstance(requireContext()).getKnownFrozenSet()) {
			frozenStateCache.put(p, true);
			prewritten++;
		}
		if (prewritten > 0) {
			KeydroidxLog.i("Menu", "进入功能表预写冻结缓存: " + prewritten + " 个（来自桌面已知冻结集）");
		}

		appGrid = view.findViewById(R.id.appGrid);
		tvPage = view.findViewById(R.id.menuPage);

		// 先初始化滑动监听（在 buildCurrentPage 之前，使每个 cell 都能挂载）
		initSwipeListener(view);

		// 先尝试同步计算行数：从桌面进入功能表时 midPanel 早已布局完成，
		// panelH 实测值立即可用，首帧就能按正确行数/行高构建一版到位。
		// 若仍按默认行数构建、post 后再重建，用户会看到一版「行高被拉伸」
		// 的网格一闪而过（进入功能表闪烁的根源）。
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		boolean panelReady = host.getMidPanelHeight() > 0;
		if (panelReady) {
			computeRowsPerPage();
		}

		// 按行数预分配页内数组，供首次同步构建使用
		cellViews = new View[perPage];
		pageItems = new KeydroidxAppItem[perPage];

		// 有进程内缓存且行数已实测时先同步构建一版：零 IPC，首帧直接出图。
		// 原先全部构建都推迟到 view.post() 之后，期间 appGrid 是空的，
		// 用户会看到「只有标题、没有图标」的空白功能表闪一下。
		boolean hadCache = applyCachedItems();
		if (hadCache && panelReady) {
			buildCurrentPage();
			applyInitialFocus();
			KeydroidxLog.i("Menu", "复用进程内应用列表缓存，首帧直接构建：" + items.size() + " 项");
		}
		final boolean builtFirstPage = hadCache && panelReady;

		// 延迟到 midPanel 布局完成后再按真实高度计算行数（冷启动时 panelH 尚不可用）
		view.post(() -> {
			if (!isAdded()) return;
			int oldRows = rowsPerPage;
			computeRowsPerPage();
			boolean rowsChanged = rowsPerPage != oldRows;
			if (rowsChanged) {
				// 行数变化：重新分配页内数组
				cellViews = new View[perPage];
				pageItems = new KeydroidxAppItem[perPage];
			}
			if (applyCachedItems()) {
				// 首帧已按正确行数构建且行数未变 → 跳过重建，消除闪烁
				if (!builtFirstPage || rowsChanged) {
					buildCurrentPage();
					applyInitialFocus();
				}
				KeydroidxLog.i("Menu", "功能表初始化完成：共 " + items.size()
						+ " 项，" + totalPages + " 页，每页 " + perPage
						+ " 格（" + COLS + "×" + rowsPerPage + "）");
				// 图标外观变化（换图标 / 恢复默认图标返回）：只就地重算图标，
				// 不重新枚举应用列表、不重建网格、不动页码与焦点。
				if (iconAppearanceChanged) {
					refreshIconsAsync();
				}
				// 缓存命中也要静默后台校准一次：Fragment View 销毁期间（在返回栈中）
				// 包变化广播接收器已注销，期间安装/卸载的应用不会进缓存；
				// 不校准的话新装应用会一直缺席，直到进程重启。
				// 校准在后台枚举，结果与当前一致时不重绘（见 loadAppsAsync）。
				refreshKeepPosition = true;
				loadAppsAsync();
			} else {
				// 无缓存：后台枚举，避免主线程被 queryIntentActivities 阻塞
				loadAppsAsync();
			}
		});
	}

	/**
	 * 把进程内缓存的应用列表套用到当前实例。
	 *
	 * @return true=缓存可用并已填充 items；false=无缓存
	 */
	private boolean applyCachedItems() {
		synchronized (cachedItems) {
			if (cachedItems.isEmpty()) {
				return false;
			}
			// 图标包 / 单应用覆盖变化 → 缓存里的图标已过期。
			// 注意：这里刻意<b>不再</b>清空 items/cachedItems、也不再重置 pageIndex/totalPages。
			// 旧实现把「图标外观变化」直接等同于「整表缓存失效」，导致用户给第 3 页的应用
			// 换完图标返回时被扔回第 1 页（连续换多个图标每次都要重新翻页），且首帧是空网格。
			// 现在只置待刷新标志：首帧沿用现有数据出图（页码、焦点都不动），
			// 图标本身由 refreshIconsAsync() 就地重算替换。
			String currentState = KeydroidxSettingsStorage.getIconStateFingerprint(requireContext());
			if (!currentState.equals(cachedIconState)) {
				KeydroidxLog.i("Menu", "图标外观已变化（" + cachedIconState + " → " + currentState
						+ "），保留当前页与焦点，改为就地刷新图标");
				markIconAppearanceChanged();
			}
			items.clear();
			items.addAll(cachedItems);
		}
		totalPages = Math.max(1, (int) Math.ceil((double) items.size() / perPage));
		// 翻页后缓存页码可能越界，收敛回有效范围
		if (pageIndex > totalPages - 1) {
			pageIndex = Math.max(0, totalPages - 1);
		}
		return true;
	}

	/**
	 * 后台枚举应用列表并回主线程构建网格。
	 * <p>
	 * 仅在缓存缺失时（首次进入 / 安装卸载后）触发。枚举含 2 次
	 * {@code queryIntentActivities} 与逐个 {@code loadLabel}，是重 IPC，
	 * 放在主线程会让功能表打开瞬间卡住并长时间空白。
	 */
	private void loadAppsAsync() {
		if (loadingAsync) return;
		loadingAsync = true;
		final Context appCtx = requireContext().getApplicationContext();
		// 包变化刷新时希望保持原页码与焦点；首次进入时为 -1（定位到第一格）
		final int restorePage = refreshKeepPosition ? pageIndex : -1;
		final int restoreFocus = refreshKeepPosition ? focusPos : -1;
		refreshKeepPosition = false;
		LIST_EXECUTOR.execute(new Runnable() {
			@Override
			public void run() {
				final List<KeydroidxAppItem> built = buildAppList(appCtx);
				MAIN_HANDLER.post(new Runnable() {
					@Override
					public void run() {
						loadingAsync = false;
						if (!isAdded() || getView() == null) return;
						// 校准场景（缓存命中后后台重新枚举）：数据无变化时跳过重建，
						// 避免每次进入功能表都白画一遍网格。
						// isSameItemSet：内容相同、仅顺序不同时同样不重建 —— 图标外观变化会让
						// 应用在「已命中图标包 / 未命中」两个分组间移动从而改变顺序，
						// 若照旧重建，用户刚改完图标的应用会在眼前跳到别处。
						// forceIconRebuild：图标外观已变但轻量刷新还没确认落地（例如全局换包）
						// 时，即使判不出差异也必须重建，否则会残留旧图标。
						if (!forceIconRebuild && (isSameMenuList(built, items)
								|| isSameItemSet(built, items))) {
							forceIconRebuild = false;
							KeydroidxLog.d("Menu", "后台校准：应用列表无变化，跳过重建");
							return;
						}
						forceIconRebuild = false;
						items.clear();
						items.addAll(built);
						totalPages = Math.max(1, (int) Math.ceil((double) items.size() / perPage));
						synchronized (cachedItems) {
							cachedItems.clear();
							cachedItems.addAll(built);
						}
						// 卸载后页数减少时收敛到最后一页
						if (restorePage > totalPages - 1) {
							pageIndex = Math.max(0, totalPages - 1);
						} else if (restorePage >= 0) {
							pageIndex = restorePage;
						}
						buildCurrentPage();
						if (restoreFocus >= 0) {
							int count = Math.min(perPage, items.size() - pageIndex * perPage);
							setFocusPos(Math.min(restoreFocus, Math.max(0, count - 1)));
						} else {
							setFocusPos(0);
						}
						KeydroidxLog.i("Menu", "后台枚举完成并构建：共 " + items.size() + " 项，"
								+ totalPages + " 页");
						// 图标包映射表在本方法（后台线程）内已按需解析完成，
						// 因此列表构建完成即图标即最终态，无需再异步补刷（详见 KeydroidxIconResolver）
					}
				});
			}
		});
	}

	/** 清空进程内应用列表缓存（包变化 / 冻结状态变化时调用，下次进入重新枚举）。 */
	private static void invalidateCachedItems() {
		synchronized (cachedItems) {
			cachedItems.clear();
		}
	}

	/**
	 * 按类型+名称+启动组件+图标来源逐项比对两个应用列表是否内容一致（后台校准去重用）。
	 * <p>图标来源（{@code iconPackName} / {@code iconOverridden}）必须参与比对：
	 * 换图标包或改单应用覆盖后，列表的「内容」其实变了（图标变了），
	 * 只比 label/组件会误判为「无变化」而跳过重建，屏幕上仍是旧图标。</p>
	 */
	private static boolean isSameMenuList(List<KeydroidxAppItem> a, List<KeydroidxAppItem> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			KeydroidxAppItem x = a.get(i);
			KeydroidxAppItem y = b.get(i);
			if (x == null || y == null) return false;
			if (x.type != y.type || !TextUtils.equals(x.label, y.label)) return false;
			if (x.iconOverridden != y.iconOverridden
					|| !TextUtils.equals(x.iconPackName, y.iconPackName)) {
				return false;
			}
			ComponentName cx = x.launchIntent != null ? x.launchIntent.getComponent() : null;
			ComponentName cy = y.launchIntent != null ? y.launchIntent.getComponent() : null;
			if (!TextUtils.equals(cx != null ? cx.flattenToString() : null,
					cy != null ? cy.flattenToString() : null)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 两个列表是否「内容相同、仅顺序或图标外观不同」（按结构标识计数比对，与顺序无关）。
	 * <p>用途：图标外观变化会让应用在 {@code buildAppList} 的「已命中图标包 / 未命中」
	 * 两个分组之间移动，从而改变列表顺序（内容并未增删）。此时若照旧重建列表，
	 * 用户刚改完图标的应用会在眼前跳到别处 —— 页码虽然保住了，但要找的应用跑了。
	 * 因此顺序由图标外观派生的场景一律保留用户当前看到的顺序，
	 * 等真正的内容变化（安装 / 卸载 / 改名）再回到标准顺序。
	 */
	private static boolean isSameItemSet(List<KeydroidxAppItem> a, List<KeydroidxAppItem> b) {
		if (a.size() != b.size()) return false;
		Map<String, Integer> counts = new HashMap<>();
		for (KeydroidxAppItem x : a) {
			String key = itemStructuralKey(x);
			Integer c = counts.get(key);
			counts.put(key, c == null ? 1 : c + 1);
		}
		for (KeydroidxAppItem y : b) {
			Integer c = counts.get(itemStructuralKey(y));
			if (c == null || c <= 0) return false;
			counts.put(itemStructuralKey(y), c - 1);
		}
		return true;
	}

	/** 与图标外观无关的结构标识：类型 + 显示名 + 启动组件（用于 isSameItemSet 计数比对）。 */
	private static String itemStructuralKey(KeydroidxAppItem item) {
		if (item == null) return "null";
		ComponentName cn = item.launchIntent != null ? item.launchIntent.getComponent() : null;
		return item.type + "|" + item.label + "|" + (cn != null ? cn.flattenToString() : "");
	}

	/**
	 * 冻结状态缓存（仅主线程访问）。
	 * {@code isAppFrozen} 是 PackageManager Binder 调用，网格每格都要查一次，
	 * 一页 12 格就是 12 次 IPC，全部堆在主线程。缓存后翻页/重建零 IPC。
	 */
	private static final Map<String, Boolean> frozenStateCache = new HashMap<>();

	/** 取冻结状态：先查缓存，未命中才做 Binder 查询并写入缓存。 */
	private boolean getCachedFrozen(String pkg) {
		if (pkg == null) return false;
		Boolean cached = frozenStateCache.get(pkg);
		if (cached != null) return cached;
		boolean frozen = KeydroidxFreezeManager.getInstance(requireContext()).isAppFrozen(pkg);
		frozenStateCache.put(pkg, frozen);
		return frozen;
	}

	/** 冻结状态变化后清空状态缓存（下次构建网格重新查询）。 */
	private static void invalidateFrozenCache() {
		frozenStateCache.clear();
	}

	@Override
	public void onDestroyView() {
		try {
			requireContext().unregisterReceiver(packageReceiver);
			KeydroidxLog.i("Menu", "已注销包变化广播接收器");
		} catch (Exception ignore) {
			KeydroidxLog.w(TAG, "unregister package receiver failed failed: " + ignore.getMessage());
			// 未注册或已注销，忽略
		}
		try {
			requireContext().unregisterReceiver(freezeReceiver);
			KeydroidxLog.i("Menu", "已注销冻结状态广播接收器");
		} catch (Exception ignore) {
			KeydroidxLog.w(TAG, "unregister freeze receiver failed failed: " + ignore.getMessage());
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
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		if (panelH <= 0) {
			// panelH 尚未布局完成，保持默认值，稍后由 post 回调重新计算
			KeydroidxLog.w("Menu", "computeRowsPerPage: panelH 尚未可用，保持默认 rowsPerPage=" + rowsPerPage);
			return;
		}
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float fontScale = KeydroidxSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1.0f;

		// 实测反推：可用设计高度 = panelH(px) / density / scale
		float availDesign = panelH / density / scale;
		// 标题区预算（13sp 标题行高 ×fontScale + appGrid 底部 padding 2dp，顶部已收紧为 0）
		float availForGrid = Math.max(0f, availDesign - titleBudgetDp(fontScale) - 2f);

		// 单行单元格所需的绝对最小安全设计高度（图标框随字号放大 → 行数自适应减少）
		float minSafeRowHDp = minRowHDp(fontScale);

		int rows = (int) (availForGrid / minSafeRowHDp);
		// 安全兜底校验：如果均分后的高度小于最小安全高度，减去一行
		while (rows > 2 && (availForGrid / rows) < minSafeRowHDp) {
			rows--;
		}
		rows = Math.max(2, Math.min(8, rows));
		rowsPerPage = rows;
		perPage = COLS * rowsPerPage;
		KeydroidxLog.i("Menu", "computeRowsPerPage: rowsPerPage=" + rowsPerPage
				+ " panelH=" + panelH + " scale=" + scale + " density=" + density
				+ " fontScale=" + fontScale + " iconBoxDp=" + iconBoxDp(fontScale)
				+ " minSafeRowHDp=" + minSafeRowHDp
				+ " availDesign=" + availDesign + " availForGrid=" + availForGrid);
	}

	// ---- 加载真实安卓应用 ----

	/**
	 * 构建完整应用列表（枚举 + 排序 + 固定槽位 + 特殊入口）。
	 * <p>
	 * <b>可在后台线程调用</b>：全程只用传入的 appCtx，不触碰 Fragment/UI 状态。
	 * 内部含 2 次 {@code queryIntentActivities} 与逐个 {@code loadLabel}，是重 IPC。
	 *
	 * @return 新的应用列表（调用方负责写回 items 与缓存）
	 */
	private List<KeydroidxAppItem> buildAppList(Context appCtx) {
		long loadStart = System.currentTimeMillis();
		PackageManager pm = appCtx.getPackageManager();

		// 图标缓存：内存 + 磁盘 + 后台线程加载（避免主线程逐个 loadIcon IPC 卡顿）
		KeydroidxAppIconCache.init(appCtx);
		// 图标包映射表：本方法运行在后台线程，此处提前解析（首次约 10ms，之后纯内存），
		// 保证列表构建完成时命中结果即为最终态
		KeydroidxIconPack activePack = KeydroidxIconPackManager.get()
				.findPack(KeydroidxSettingsStorage.getIconPackId(appCtx));
		if (activePack != null) {
			activePack.ensureLoaded(appCtx);
		}

		Intent main = new Intent(Intent.ACTION_MAIN, null);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		// flags=0：只返回「已安装 + 已启用 + 可启动」的组件（与系统桌面一致）。
		// 绝不混入 MATCH_DISABLED_COMPONENTS / MATCH_UNINSTALLED_PACKAGES——
		// 那会把停用组件（主题别名、CarPlay 入口）与卸载残留包也枚举进来，
		// 导致启动报 ActivityNotFoundException 或同包出现多个图标。
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
		KeydroidxLog.i("Menu", "queryIntentActivities (仅启用应用) 返回 " + list.size() + " 个可启动应用");

		// 先全部放入临时池 pool，后续再按固定槽位提取
		List<KeydroidxAppItem> pool = new ArrayList<>();
		Set<String> seenPackages = new HashSet<>();
		String selfPkg = appCtx.getPackageName();
		for (ResolveInfo ri : list) {
			ActivityInfo ai = ri.activityInfo;
			if (ai == null) {
				KeydroidxLog.w("Menu", "跳过空 activityInfo");
				continue;
			}
			boolean appEnabled = ai.applicationInfo == null || ai.applicationInfo.enabled;
			boolean compEnabled = ai.enabled && appEnabled;
			KeydroidxLog.d("Menu", "枚举入口: " + ai.packageName + "/" + ai.name
					+ " enabled=" + compEnabled
					+ " (componentEnabled=" + ai.enabled
					+ ", appEnabled=" + appEnabled + ")");
			if (ai.packageName.equals(selfPkg)) {
				KeydroidxLog.d("Menu", "排除桌面自身: " + ai.packageName);
				continue;
			}
			// 过滤同应用的多图标入口（如部分应用提供的多种样式启动别名）
			if (!seenPackages.add(ai.packageName)) {
				KeydroidxLog.d("Menu", "跳过重复包名入口: " + ai.packageName + "/" + ai.name
						+ " (首个入口已选中)");
				continue;
			}
			KeydroidxLog.d("Menu", "选中入口: " + ai.packageName + "/" + ai.name
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
			// 系统图标交给 buildCurrentPage 里的 KeydroidxAppIconCache 后台加载。
			Drawable icon = null;
			Intent launch = new Intent(Intent.ACTION_MAIN);
			launch.addCategory(Intent.CATEGORY_LAUNCHER);
			launch.setClassName(ai.packageName, ai.name);
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			KeydroidxAppItem item = new KeydroidxAppItem(KeydroidxAppItem.TYPE_APP, label, icon, launch);

			// 图标包解析（单应用覆盖 → 全局图标包）：命中则直接取图；未命中留给
			// buildCurrentPage 后台加载应用原图标（冻结应用走 loadIconWithFallback 降级）
			applyIconPackResult(item, KeydroidxIconResolver.resolve(
					appCtx, ai.packageName, launch.getComponent(), label), appCtx);

			pool.add(item);
		}

		// 按名称排序，保证 pool 中的顺序稳定
		Collections.sort(pool, new Comparator<KeydroidxAppItem>() {
			@Override
			public int compare(KeydroidxAppItem a, KeydroidxAppItem b) {
				return a.label.compareToIgnoreCase(b.label);
			}
		});

		// —— 第一页固定槽位：按参考图顺序从应用池点名 ——
		List<KeydroidxAppItem> pinned = new ArrayList<>();
		for (int s = 0; s < PINNED_SLOTS.length; s++) {
			KeydroidxAppItem hit = null;
			for (String pkg : PINNED_SLOTS[s]) {
				hit = pollByPackage(pool, pkg);
			if (hit != null) {
				KeydroidxLog.d("Menu", "固定槽位 " + (s + 1) + " 命中: " + pkg + " -> " + hit.label);
				// 固定槽位图标属于内置 S60 图标包：仅当前使用内置 S60 时套用；
				// 已切到外部图标包 / 不使用图标包时保留图标包解析结果或应用原图标。
				// 例外：用户在功能表里手动指定过图标（单应用覆盖）时，用户的选择优先级最高，
				// 不能被固定槽位图标覆盖掉。
				String pinnedPkg = hit.launchIntent != null && hit.launchIntent.getComponent() != null
						? hit.launchIntent.getComponent().getPackageName() : null;
				if (pinnedPkg != null && KeydroidxSettingsStorage.hasIconOverride(appCtx, pinnedPkg)) {
					KeydroidxLog.d("Menu", "  -> 该应用存在图标覆盖，保留用户指定图标（不动固定槽位图标）");
				} else if (isBuiltinPackActive(appCtx)) {
					Drawable s60icon = safeDrawable(appCtx, PINNED_SLOT_ICONS[s]);
					if (s60icon != null) {
						s60icon.setFilterBitmap(false);
						hit.icon = s60icon;
						KeydroidxLog.d("Menu", "  -> 已替换为固定槽位 S60 图标");
					}
				}
				break;
			}
		}
		if (hit != null) {
			pinned.add(hit);
			} else {
				KeydroidxLog.d("Menu", "固定槽位 " + (s + 1) + " 未命中，跳过（候选包均不存在）");
			}
	}

	// 被冻结（包级停用）的应用单独枚举后追加，不混入正常应用枚举，保证列表确定性
	addFrozenApps(pool, appCtx, pm, selfPkg);

	// 最终顺序：固定槽位 → 应用程序 → S60匹配应用（按名） → 未匹配应用（按名）
	// （桌面设置入口已移除：主界面右软键即可进入，功能表不再重复提供）
	List<KeydroidxAppItem> result = new ArrayList<>();
	result.addAll(pinned);

	// 应用程序图标：S60 2007 3D 时代的「应用程序文件夹」图标（黄文件夹 + 应用方块）
	Drawable boxIcon = safeDrawable(appCtx, R.drawable.s60_box);
	if (boxIcon == null) boxIcon = safeDrawable(appCtx, R.drawable.ic_keydroidx_box);
	result.add(new KeydroidxAppItem(KeydroidxAppItem.TYPE_BOX, "应用程序", boxIcon, null));
	// 原始 J2ME-Loader 主界面（启动器/文件选择器/应用列表）入口
	Drawable mainIcon = safeDrawable(appCtx, R.mipmap.ic_launcher);
	if (mainIcon == null) mainIcon = boxIcon;
	result.add(new KeydroidxAppItem(KeydroidxAppItem.TYPE_MAIN, "J2ME Loader", mainIcon, null));
	KeydroidxLog.d("Menu", "已追加特殊入口：J2ME 加载器（TYPE_MAIN，进入 J2meLoaderActivity）");
	// 通知中心：读取系统通知并展示，可清除（见 docs/通知中心功能设计.md）
	Drawable notifIcon = io.github.cctyl.nokia.common.ui.KeydroidxIcons.get(appCtx,
			io.github.cctyl.nokia.common.ui.KeydroidxIcons.ICON_NOTIFICATIONS, 0xFFFFFFFF, 20);
	result.add(new KeydroidxAppItem(KeydroidxAppItem.TYPE_NOTIFICATION, "通知中心", notifIcon, null));

		// 将 pool 拆分为「被当前图标包命中」与「未命中」，命中的排在前面。
		// 使用构建 pool 时记录的 iconPackName，避免二次解析导致分组不一致。
		List<KeydroidxAppItem> matchedPool = new ArrayList<>();
		List<KeydroidxAppItem> unmatchedPool = new ArrayList<>();
		for (KeydroidxAppItem app : pool) {
			if (app.iconPackName != null) {
				matchedPool.add(app);
			} else {
				unmatchedPool.add(app);
			}
		}
		// 两组内部均按名称排序
		Comparator<KeydroidxAppItem> labelCmp = (a, b) -> a.label.compareToIgnoreCase(b.label);
		Collections.sort(matchedPool, labelCmp);
		Collections.sort(unmatchedPool, labelCmp);

		result.addAll(matchedPool);
		result.addAll(unmatchedPool);

		KeydroidxLog.i("Menu", "最终列表（固定槽位 " + pinned.size() + " + 特殊入口 + 匹配 " + matchedPool.size()
				+ " + 未匹配 " + unmatchedPool.size() + "）共 " + result.size() + " 项");
		KeydroidxLog.i("Menu", "buildAppList 耗时 " + (System.currentTimeMillis() - loadStart)
				+ "ms（枚举 + label，不含系统图标 IPC）");
		// 记录本次构建用的图标外观指纹：图标偏好变化后缓存自动失效
		cachedIconState = KeydroidxSettingsStorage.getIconStateFingerprint(appCtx);
		return result;
		}

		/**
		* 包安装/卸载/替换后刷新应用列表：缓存失效并重新枚举，尽量保持当前页与焦点位置。
		* 卸载导致当前页变空（越界）时，焦点收敛到新列表末尾。
		*/
		private void refreshAppList() {
		if (!isAdded() || getView() == null) return;
		KeydroidxLog.i("Menu", "刷新应用列表（包变化触发）");
		int oldPage = pageIndex;
		int oldFocus = focusPos;
		// 缓存已失效，走后台重新枚举（不再在主线程做重 IPC），完成后恢复原页码与焦点
		refreshKeepPosition = true;
		invalidateCachedItems();
		loadAppsAsync();
		KeydroidxLog.d("Menu", "已提交后台刷新：page=" + (oldPage + 1) + " focus=" + oldFocus);
		}

		/**
		 * 图标包 / 单应用图标覆盖变化后的外部刷新入口（图标包设置页调用）。
		 * <p>只重算图标外观，不重新枚举应用列表、不动页码与焦点；若此刻 View 尚未就绪
		 * （功能表还在返回栈里），留给 {@link #applyCachedItems()} 的指纹检测处理。
		 */
		public void onIconPackChanged() {
			if (!isAdded() || getView() == null) return;
			markIconAppearanceChanged();
			refreshIconsAsync();
		}

		/**
		 * 标记「图标外观已变化，待就地重算」，并记下变化前的指纹
		 * （用于区分「全局换图标包」与「单应用覆盖」）。
		 */
		private void markIconAppearanceChanged() {
			if (!iconAppearanceChanged) {
				staleIconState = cachedIconState;
			}
			iconAppearanceChanged = true;
			// 先假定需要重建；轻量刷新成功落地后会清掉它（见 refreshIconsAsync 的回包）
			forceIconRebuild = true;
		}

		/**
		 * 图标外观变化后的轻量刷新：只重算每个应用的图标来源并按需重新取图，
		 * <b>不重新枚举应用列表、不重建网格、不动页码与焦点</b>。
		 * <p>与旧的「清空缓存 + 后台全量重枚举」相比，代价从 2 次
		 * {@code queryIntentActivities} + N 次 {@code loadLabel} 降为 O(N) 次内存查表 +
		 * O(K) 次取图（K = 外观真正变化的项数，单应用换图标时通常为 1），
		 * 因此返回功能表时既不空白也不闪烁。
		 * <p>items 与静态 cachedItems 共享同一批 item 对象引用，原地改字段即等价于同步缓存。
		 * <p>全局换图标包不走这条路：那种情况下所有命中应用的图标都变，而且
		 * 「第一页固定槽位图标只属于内置 S60 包」的规则需要整表重建才能对齐，
		 * 交给调用方的 loadAppsAsync 兜底（页码与焦点同样保持不变）。
		 */
		private void refreshIconsAsync() {
			if (iconRefreshRunning) return;
			final String newState = KeydroidxSettingsStorage.getIconStateFingerprint(requireContext());
			if (!TextUtils.equals(packIdOf(staleIconState), packIdOf(newState))) {
				KeydroidxLog.i("Menu", "检测到全局图标包切换（" + staleIconState + " → " + newState
						+ "），改走后台重建（页码与焦点保持不变）");
				// 本次交给后台重建负责：forceIconRebuild 保持置位（重建落地后才清），
				// 这里先复位「待就地刷新」标记，避免每次进入功能表都重复走这个分支
				iconAppearanceChanged = false;
				staleIconState = null;
				return;
			}
			iconRefreshRunning = true;
			final Context appCtx = requireContext().getApplicationContext();
			final List<KeydroidxAppItem> snapshot = new ArrayList<>(items);
			LIST_EXECUTOR.execute(new Runnable() {
				@Override
				public void run() {
					int changed = 0;
					boolean ok = true;
					try {
						for (KeydroidxAppItem item : snapshot) {
							if (item == null || item.type != KeydroidxAppItem.TYPE_APP
									|| item.launchIntent == null
									|| item.launchIntent.getComponent() == null) {
								continue;
							}
							ComponentName cn = item.launchIntent.getComponent();
							KeydroidxIconResolver.Hit hit = KeydroidxIconResolver.resolve(
									appCtx, cn.getPackageName(), cn, item.label);
							boolean wasPack = item.iconPackName != null;
							if (hit != null) {
								// 外观没变的项直接跳过，避免整表重新取图
								// （单应用换图标时只有 1 项会变）
								if (wasPack && TextUtils.equals(item.iconPackName, hit.iconName)
										&& item.iconOverridden == hit.override) {
									continue;
								}
								applyIconPackResult(item, hit, appCtx);
							} else if (wasPack) {
								// 覆盖被清除 / 换包后不再命中 → 回退应用原图标
								// （applyIconPackResult 会清空 iconPackName/iconOverridden/icon，
								//   icon 置空后由 updateCurrentPageIcons 走占位图 + 异步补图）
								applyIconPackResult(item, null, appCtx);
							} else {
								continue;   // 本来就没有图标包图标，外观没变
							}
							// 被改动的应用若正好是「第一页固定槽位」，按 buildAppList 的规则套用
							// 槽位专属 S60 图标（例如清掉覆盖后），否则会与整表重建结果不一致
							applyPinnedSlotIcon(appCtx, item);
							changed++;
						}
					} catch (Exception e) {
						// 取图异常：保持 forceIconRebuild 置位，交给后台校准重建兜底
						ok = false;
						KeydroidxLog.w("Menu", "图标就地刷新异常，转由后台校准重建兜底", e);
					}
					final int fChanged = changed;
					final boolean fOk = ok;
					MAIN_HANDLER.post(new Runnable() {
						@Override
						public void run() {
							iconRefreshRunning = false;
							iconAppearanceChanged = false;
							staleIconState = null;
							if (fOk) {
								forceIconRebuild = false;
								// 指纹落定：本实例缓存已按新外观重算，后续进入不再判为过期。
								// 若这次重算有遗漏（如列表刚被后台校准换成新对象），
								// loadAppsAsync 仍会因 iconPackName 不一致而重建兜底。
								cachedIconState = newState;
							}
							if (!isAdded() || getView() == null) return;
							updateCurrentPageIcons();
							KeydroidxLog.i("Menu", "图标就地刷新完成：扫描 " + snapshot.size()
									+ " 项，外观变化 " + fChanged + " 项（未重新枚举应用列表）");
						}
					});
				}
			});
		}

		/** 指纹前缀 = 全局图标包 ID（见 KeydroidxSettingsStorage.getIconStateFingerprint）。 */
		private static String packIdOf(String fingerprint) {
			if (fingerprint == null) return null;
			int sep = fingerprint.indexOf('#');
			return sep >= 0 ? fingerprint.substring(0, sep) : fingerprint;
		}

		/**
		 * 主线程就地替换当前页图标：不重建网格、不动页码与焦点，只把新的 Drawable 塞进 ImageView。
		 * <p>取图路径必须与 {@code buildCurrentPage()} 一致：cell(LinearLayout) →
		 * iconContainer(FrameLayout) → child0 = ImageView。（历史实现 refreshAfterIconInit
		 * 直接取 cell.getChildAt(0) 再判 {@code instanceof ImageView}，拿到的是 iconContainer，
		 * 恒不成立 —— 等于空操作，故重写。）
		 * <p>{@code item.icon == null}（覆盖被清除 / 图标包未命中）时走占位图 + 异步补应用原图标，
		 * 口径与 buildCurrentPage() 完全相同。
		 */
		private void updateCurrentPageIcons() {
			if (cellViews == null || pageItems == null) return;
			int updated = 0;
			for (int i = 0; i < perPage && i < cellViews.length; i++) {
				KeydroidxAppItem item = i < pageItems.length ? pageItems[i] : null;
				View cell = cellViews[i];
				if (item == null || cell == null) continue;
				ImageView iv = findCellIconView(cell);
				if (iv == null) continue;
				if (item.icon != null) {
					item.icon.setFilterBitmap(false);
					iv.setImageDrawable(item.icon);
					updated++;
				} else if (item.type == KeydroidxAppItem.TYPE_APP && item.launchIntent != null
						&& item.launchIntent.getComponent() != null) {
					final String asyncPkg = item.launchIntent.getComponent().getPackageName();
					final ComponentName cn = item.launchIntent.getComponent();
					final KeydroidxAppItem fItem = item;
					iv.setImageDrawable(getPlaceholderIcon());
					iv.setTag(asyncPkg);
					KeydroidxAppIconCache.loadAsync(requireContext(), asyncPkg, cn, item.label,
							(loadedPkg, d) -> {
								// 校验同 buildCurrentPage：cell 归属未变、且期间未被图标包替换
								if (d == null || iv.getTag() == null
										|| !iv.getTag().equals(loadedPkg)) return;
								if (fItem.iconPackName != null) return;
								d.setFilterBitmap(false);
								iv.setImageDrawable(d);
								fItem.icon = d;
							});
					updated++;
				}
			}
			if (updated > 0) {
				KeydroidxLog.i("Menu", "图标外观变化：原地替换当前页 " + updated
						+ " 个图标（未重建网格、页码与焦点不变）");
			}
		}

		/** 从 cell 取出图标 ImageView：cell → iconContainer(FrameLayout) → child0（冻结冰块叠加层不算）。 */
		private static ImageView findCellIconView(View cell) {
			if (!(cell instanceof LinearLayout)) return null;
			View first = ((LinearLayout) cell).getChildAt(0);
			if (!(first instanceof FrameLayout)) return null;
			View iv = ((FrameLayout) first).getChildAt(0);
			return iv instanceof ImageView ? (ImageView) iv : null;
		}

	/** 在应用池中按包名查找第一个命中的项并移除，返回之；未命中返回 null。 */
	@Nullable
	private static KeydroidxAppItem pollByPackage(List<KeydroidxAppItem> pool, String pkg) {
		for (int i = 0; i < pool.size(); i++) {
			KeydroidxAppItem app = pool.get(i);
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
	private void addFrozenApps(List<KeydroidxAppItem> pool, Context appCtx,
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
			KeydroidxLog.e("Menu", "冻结应用枚举失败", e);
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
			KeydroidxAppItem item = new KeydroidxAppItem(KeydroidxAppItem.TYPE_APP, label, null, launch);
			applyIconPackResult(item, KeydroidxIconResolver.resolve(
					appCtx, ai.packageName, launch.getComponent(), label), appCtx);
			pool.add(item);
			added++;
			KeydroidxLog.d("Menu", "追加冻结应用: " + ai.packageName + "/" + ai.name);
		}
		if (added > 0) {
			KeydroidxLog.i("Menu", "追加冻结应用 " + added + " 个");
		}
	}

	/** pool 中是否已存在指定包名的应用项 */
	private static boolean poolContainsPackage(List<KeydroidxAppItem> pool, String pkg) {
		for (KeydroidxAppItem app : pool) {
			if (app.launchIntent != null && app.launchIntent.getComponent() != null
					&& pkg.equals(app.launchIntent.getComponent().getPackageName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 把图标包解析结果应用到列表项。
	 * <p>命中时直接取图（内置 S60 为 nodpi 位图 → 关闭缩放过滤更锐利）；未命中（或取图失败）
	 * 时把 {@code iconPackName} 留空，由 {@code buildCurrentPage} 后台加载应用原图标，
	 * 冻结/停用应用也能正常出图（走 {@code loadIconWithFallback} 降级）。</p>
	 */
	private static void applyIconPackResult(KeydroidxAppItem item, KeydroidxIconResolver.Hit hit, Context appCtx) {
		if (item == null) return;
		item.iconPackName = hit != null ? hit.iconName : null;
		item.iconOverridden = hit != null && hit.override;
		if (hit == null) {
			// 未命中 → 回退应用原图标：必须把旧图标一并清掉。
			// buildAppList 里 item 是新对象（icon 本来为 null）无影响；
			// 但「就地刷新」场景（换图标后返回、清覆盖、换包）若不清理，
			// item.icon 会残留上一个图标包的图，回退不到应用原图标。
			item.icon = null;
			return;
		}
		KeydroidxIconPack pack = KeydroidxIconPackManager.get().findPack(hit.packId);
		if (pack == null) {
			item.icon = null;
			return;
		}
		Drawable icon = pack.getIconByName(appCtx, hit.iconName);
		if (icon == null) {
			// 图标包里取不到图（外部包被卸载 / 资源损坏）→ 视为未命中，回退应用原图标
			item.iconPackName = null;
			item.iconOverridden = false;
			item.icon = null;
			return;
		}
		icon.setFilterBitmap(false);
		item.icon = icon;
		// 兼容字段：内置 S60 命中时同步资源 ID（排序/日志仍可读）
		item.s60IconResId = KeydroidxS60Icons.idOf(hit.iconName);
	}

	/** 当前全局图标包是否为内置 S60（固定槽位图标只属于内置 S60 包） */
	private static boolean isBuiltinPackActive(Context ctx) {
		return KeydroidxAdwIconPack.ID_BUILTIN.equals(KeydroidxSettingsStorage.getIconPackId(ctx));
	}

	/**
	 * 套用「第一页固定槽位」专属 S60 图标，规则与 {@code buildAppList} 完全一致：
	 * 仅当使用内置 S60 图标包、且该应用没有被用户手动指定过图标时生效。
	 * <p>就地刷新路径必须复用同一规则，否则「清掉覆盖 / 换图标」之后固定槽位的图标
	 * 会与整表重建的结果不一致。
	 *
	 * @return true = 已套用槽位图标
	 */
	private static boolean applyPinnedSlotIcon(Context appCtx, KeydroidxAppItem item) {
		if (item == null || item.type != KeydroidxAppItem.TYPE_APP
				|| item.launchIntent == null || item.launchIntent.getComponent() == null) {
			return false;
		}
		String pkg = item.launchIntent.getComponent().getPackageName();
		if (KeydroidxSettingsStorage.hasIconOverride(appCtx, pkg)) return false;
		if (!isBuiltinPackActive(appCtx)) return false;
		for (int s = 0; s < PINNED_SLOTS.length; s++) {
			for (String candidate : PINNED_SLOTS[s]) {
				if (!candidate.equals(pkg)) continue;
				Drawable s60icon = safeDrawable(appCtx, PINNED_SLOT_ICONS[s]);
				if (s60icon == null) return false;
				s60icon.setFilterBitmap(false);
				item.icon = s60icon;
				return true;
			}
		}
		return false;
	}

	private static Drawable safeDrawable(Context ctx, int resId) {
		try {
			Drawable d = ContextCompat.getDrawable(ctx, resId);
			// API 19 上多个 ImageView 共享同一 Bitmap 时，硬件加速渲染可能触发
			// Adreno GL_INVALID_OPERATION 导致图标变黑；mutate 隔离 Drawable 状态
			if (d != null) {
				d = d.mutate();
			}
			return d;
		} catch (Exception e) {
			KeydroidxLog.w("Menu", "加载图标失败 res=" + resId);
			return null;
		}
	}

	/** 系统图标未加载完成前的占位图标（懒加载 + mutate 隔离，避免共享 Bitmap 变黑） */
	private Drawable getPlaceholderIcon() {
		if (placeholderIcon == null) {
			try {
				Drawable d = ContextCompat.getDrawable(requireContext(), R.mipmap.ic_launcher);
				if (d != null) {
					// 占位图与真实图标做同样的栅格化：API 26+ 的 ic_launcher 是自适应图标，
					// 两条路径渲染一致，异步换图瞬间才不会出现尺寸跳变
					d.mutate();
					d = KeydroidxAppIconCache.normalizeForDisplay(
							requireContext().getResources(), d);
				}
				placeholderIcon = d;
			} catch (Exception e) {
				KeydroidxLog.w("Menu", "加载占位图标失败");
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
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		float fontScale = KeydroidxSettingsStorage.getFontScale(requireContext());
		if (fontScale <= 0f) fontScale = 1.0f;
		float availForGrid = Math.max(0f, availDesign - titleBudgetDp(fontScale) - 2f);
		float rowActualDp = rowsPerPage > 0 ? (availForGrid / rowsPerPage) : minRowHDp(fontScale);
		int rowH = KeydroidxDimens.dp(getResources(), Math.round(rowActualDp));

		int start = pageIndex * perPage;
		int count = Math.min(perPage, items.size() - start);
		KeydroidxLog.d("Menu", "buildCurrentPage 页=" + (pageIndex + 1) + "/" + totalPages
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
				cell.setPadding(KeydroidxDimens.dp(getResources(), 4), KeydroidxDimens.dp(getResources(), 4), KeydroidxDimens.dp(getResources(), 4), KeydroidxDimens.dp(getResources(), 4));

				if (pos < count) {
					KeydroidxAppItem item = items.get(start + pos);
					pageItems[pos] = item;

					String pkg = null;
					if (item.launchIntent != null) {
						if (item.launchIntent.getComponent() != null) {
							pkg = item.launchIntent.getComponent().getPackageName();
						} else if (!TextUtils.isEmpty(item.launchIntent.getPackage())) {
							pkg = item.launchIntent.getPackage();
						}
					}

					FrameLayout iconContainer = new FrameLayout(requireContext());
					// 图标框随字号放大（大字号档位下图标同步变大，不再缩在中间）；
					// 行高预算 minRowHDp() 用同一个 iconBoxDp()，两者始终一致
					int iconBox = KeydroidxDimens.dp(getResources(), iconBoxDp(fontScale));
					iconContainer.setLayoutParams(new LinearLayout.LayoutParams(iconBox, iconBox));

					ImageView iv = new ImageView(requireContext());
					iv.setLayoutParams(new FrameLayout.LayoutParams(
							FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
					if (item.icon != null) {
						// 关闭缩放过滤：S60 图标为 nodpi 位图，最近邻缩放更锐利、契合复古风格，
						// 避免 36dp 内降采样发虚（API 19 尤其明显）；真实应用图标密度感知，影响甚微。
						item.icon.setFilterBitmap(false);
						iv.setImageDrawable(item.icon);
					} else if (item.type == KeydroidxAppItem.TYPE_APP
							&& item.launchIntent != null
							&& item.launchIntent.getComponent() != null) {
						// 图标包未命中 → 先显示占位，后台线程加载（图标包/应用原图标，内存+磁盘缓存复用）
						iv.setImageDrawable(getPlaceholderIcon());
						final String asyncPkg = item.launchIntent.getComponent().getPackageName();
						final ComponentName cn = item.launchIntent.getComponent();
						final KeydroidxAppItem fItem = item;
						final String asyncLabel = item.label;
						iv.setTag(asyncPkg);
						// 缓存键由缓存层按当前图标外观自行计算（见 KeydroidxAppIconCache.loadAsync）
						KeydroidxAppIconCache.loadAsync(requireContext(), asyncPkg, cn, asyncLabel,
								(loadedPkg, d) -> {
							// 校验：cell 仍属于该应用（翻页/重建后 tag 变化则跳过），
							// 且该应用未被图标包替换（图标包优先级高于系统图标）
							if (d == null || iv.getTag() == null
									|| !iv.getTag().equals(loadedPkg)) return;
							if (fItem.iconPackName != null) return;
							d.setFilterBitmap(false);
							iv.setImageDrawable(d);
							fItem.icon = d;
						});
					}
					iconContainer.addView(iv);

					// 若已被系统级真实冻结，用冰块效果全包围覆盖图标；若仅在名单中未冻结，显示雪花角标
					boolean isRealFrozen = getCachedFrozen(pkg);
					boolean isInList = pkg != null
							&& KeydroidxFreezeManager.getInstance(requireContext()).isInFreezeList(pkg);
					if (isRealFrozen) {
						ImageView iceCover = new ImageView(requireContext());
						FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
						coverLp.gravity = Gravity.CENTER;
						iceCover.setLayoutParams(coverLp);
						iceCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
						iceCover.setImageResource(R.drawable.ic_keydroidx_ice_block_cover);
						iconContainer.addView(iceCover);
					} else if (isInList) {
						ImageView badgeIv = new ImageView(requireContext());
						int badgeSize = KeydroidxDimens.dp(getResources(), 14);
						FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSize, badgeSize);
						badgeLp.gravity = Gravity.BOTTOM | Gravity.END;
						badgeIv.setLayoutParams(badgeLp);
						badgeIv.setImageResource(R.drawable.ic_keydroidx_ice_badge);
						iconContainer.addView(badgeIv);
					}

					TextView tv = new TextView(requireContext());
					tv.setLayoutParams(new LinearLayout.LayoutParams(
							LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
					tv.setText(item.label);
					tv.setTextColor(0xFFFFFFFF);
					KeydroidxFontManager.textSize(tv, 9);
					tv.setSingleLine(true);
					tv.setEllipsize(TextUtils.TruncateAt.END);
					tv.setMaxWidth(KeydroidxDimens.dp(getResources(), 72));
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


	// ---- KeydroidxFocusHost 接口 ----

	@Override
	public boolean onDirection(int direction) {
		int pos = focusPos;
		int row = pos / COLS;
		int col = pos % COLS;
		int count = Math.min(perPage, items.size() - pageIndex * perPage);

		switch (direction) {
			case KeydroidxKeyBinding.ACTION_UP:
				if (row > 0 && (pos - COLS) < count) {
					// 页内上移
					setFocusPos(pos - COLS);
				} else if (pageIndex > 0) {
					// 已到本页顶部 → 翻上一页
					pagePrev();
				}
				return true;
			case KeydroidxKeyBinding.ACTION_DOWN:
				if (row < rowsPerPage - 1 && (pos + COLS) < count) {
					// 页内下移
					setFocusPos(pos + COLS);
				} else if (pageIndex < totalPages - 1) {
					// 已到本页底部 → 翻下一页
					pageNext();
				}
				return true;
			case KeydroidxKeyBinding.ACTION_LEFT:
				// 仅在本行内移动：到最左端回绕到本行最右端
				if (col > 0) {
					setFocusPos(pos - 1);
				} else {
					setFocusPos(pos + COLS - 1);
				}
				return true;
			case KeydroidxKeyBinding.ACTION_RIGHT:
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
			KeydroidxLog.d("Menu", "翻页(下/左滑) -> " + (pageIndex + 1) + "/" + totalPages
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
			KeydroidxLog.d("Menu", "翻页(上/右滑) -> " + (pageIndex + 1) + "/" + totalPages
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
		swipeThreshold = KeydroidxDimens.dp(getResources(), 24);   // 位移阈值（dp）
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
			KeydroidxLog.d("Menu", "initSwipeListener 已挂载到 midPanel（覆盖空白区）");
		}
		KeydroidxLog.d("Menu", "initSwipeListener 已挂载滑动翻页监听（根视图 + midPanel + 每个 cell 复用）");
	}

	@Override
	public boolean onSelect() {
		int global = pageIndex * perPage + focusPos;
		if (global < 0 || global >= items.size()) {
			KeydroidxLog.w("Menu", "onSelect 越界 global=" + global);
			return false;
		}
		KeydroidxAppItem item = items.get(global);
		if (item == null) return false;
		KeydroidxLog.i("Menu", "onSelect type=" + item.type + " label=" + item.label);

		if (item.type == KeydroidxAppItem.TYPE_BOX) {
			((KeydroidxDesktopActivity) requireActivity()).openBox();
			return true;
		}
		if (item.type == KeydroidxAppItem.TYPE_MAIN) {
			try {
				Intent main = new Intent(requireActivity(), J2meLoaderActivity.class);
				main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
						| Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
				startActivity(main);
				KeydroidxLog.i("Menu", "进入原始 J2ME-Loader 主界面 J2meLoaderActivity");
			} catch (Exception e) {
				KeydroidxLog.e("Menu", "启动 J2meLoaderActivity 失败", e);
			}
			return true;
		}
		if (item.type == KeydroidxAppItem.TYPE_SETTINGS) {
			((KeydroidxDesktopActivity) requireActivity()).openDesktopSettings();
			return true;
		}
		if (item.type == KeydroidxAppItem.TYPE_NOTIFICATION) {
			((KeydroidxDesktopActivity) requireActivity()).openNotificationCenter();
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

			if (pkg != null && KeydroidxFreezeManager.getInstance(requireContext()).isAppFrozen(pkg)) {
				KeydroidxFreezeManager.getInstance(requireContext()).unfreezeAndLaunch(item.launchIntent, pkg, item.label);
				return true;
			}

			try {
				startActivity(item.launchIntent);
				KeydroidxLog.i("Menu", "启动应用 " + item.label);
			} catch (Exception e) {
				// 兜底：组件可能因应用更新/状态变化而失效，重新解析当前启用入口再试一次
				if (pkg != null && retryWithLaunchIntent(pkg, item.label)) {
					return true;
				}
				KeydroidxLog.e("Menu", "启动失败 " + item.label, e);
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
			KeydroidxLog.i("Menu", "兜底启动成功 " + label + " -> " + retry.getComponent());
			return true;
		} catch (Exception e2) {
			KeydroidxLog.e("Menu", "兜底启动也失败 " + label, e2);
			return false;
		}
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键 = "选项"：仅对安卓原生应用弹出选项菜单；特殊入口保持原确认动作
		int global = pageIndex * perPage + focusPos;
		if (global >= 0 && global < items.size()) {
			KeydroidxAppItem item = items.get(global);
			if (item != null && item.type == KeydroidxAppItem.TYPE_APP) {
				showAppOptionsMenu(item);
				return true;
			}
		}
		return onSelect();
	}

	/**
	 * 弹出诺基亚风格选项菜单（卸载 / 应用设置），仅针对安卓原生应用。
	 */
	private void showAppOptionsMenu(KeydroidxAppItem item) {
		if (item == null || item.launchIntent == null) {
			KeydroidxLog.w("Menu", "showAppOptionsMenu: item 或 launchIntent 为 null，忽略");
			return;
		}
		String resolvedPkg = item.launchIntent.getPackage();
		if (TextUtils.isEmpty(resolvedPkg) && item.launchIntent.getComponent() != null) {
			resolvedPkg = item.launchIntent.getComponent().getPackageName();
		}
		if (TextUtils.isEmpty(resolvedPkg)) {
			KeydroidxLog.w("Menu", "showAppOptionsMenu: 无法解析包名，忽略 " + item.label);
			return;
		}
		final String pkg = resolvedPkg;
		KeydroidxLog.i("Menu", "弹出选项菜单: " + item.label + " pkg=" + pkg);
		List<KeydroidxOptionsDialog.OptionItem> options = new ArrayList<>();

		// 冻结 / 移出冻结列表选项
		boolean inFreezeList = KeydroidxFreezeManager.getInstance(requireContext()).isInFreezeList(pkg);
		boolean isFrozen = KeydroidxFreezeManager.getInstance(requireContext()).isAppFrozen(pkg);
		if (inFreezeList) {
			if (isFrozen) {
				options.add(new KeydroidxOptionsDialog.OptionItem(R.drawable.ic_keydroidx_freeze,
						"解冻应用", true, false, () -> {
					KeydroidxFreezeManager.getInstance(requireContext()).unfreezeApp(pkg, (success, msg) -> {
						if (isAdded()) {
							Toast.makeText(requireContext(), success ? ("已解冻: " + item.label) : ("解冻失败: " + msg), Toast.LENGTH_SHORT).show();
							invalidateFrozenCache();
							buildCurrentPage();
						}
					});
				}));
			} else {
				options.add(new KeydroidxOptionsDialog.OptionItem(R.drawable.ic_keydroidx_freeze,
						"立即冻结", true, false, () -> {
					KeydroidxFreezeManager.getInstance(requireContext()).freezeApp(pkg, (success, msg) -> {
						if (isAdded()) {
							Toast.makeText(requireContext(), success ? ("已冻结: " + item.label) : ("冻结失败: " + msg), Toast.LENGTH_SHORT).show();
							invalidateFrozenCache();
							buildCurrentPage();
						}
					});
				}));
			}
			options.add(new KeydroidxOptionsDialog.OptionItem(android.R.drawable.ic_menu_close_clear_cancel,
					"移出冻结列表", true, false, () -> {
				KeydroidxFreezeManager.getInstance(requireContext()).removeFromFreezeList(pkg);
				Toast.makeText(requireContext(), "已移出冻结列表", Toast.LENGTH_SHORT).show();
				invalidateFrozenCache();
				buildCurrentPage();
			}));
		} else {
			options.add(new KeydroidxOptionsDialog.OptionItem(R.drawable.ic_keydroidx_freeze,
					"加入冻结列表", true, false, () -> {
				KeydroidxFreezeManager.getInstance(requireContext()).addToFreezeList(pkg);
				Toast.makeText(requireContext(), "已加入冻结列表", Toast.LENGTH_SHORT).show();
				invalidateFrozenCache();
				buildCurrentPage();
			}));
		}
		// 更换图标：先选图标包（内置 S60 + 已装图标包），再在图标网格里挑一个图标，
		// 保存为该应用的图标覆盖（只影响这一个应用；不选则保持全局图标包解析结果）
		options.add(new KeydroidxOptionsDialog.OptionItem(
				io.github.cctyl.nokia.common.ui.KeydroidxIcons.ICON_PALETTE,
				"更换图标", true, false, () -> {
			KeydroidxLog.i("Menu", "选项菜单-更换图标: " + item.label + " pkg=" + pkg);
			((KeydroidxDesktopActivity) requireActivity()).openFragment(
					KeydroidxIconPackSettingsFragment.newInstanceForApp(pkg, item.label));
		}));
		// 已有覆盖的应用才显示「恢复默认图标」
		if (KeydroidxIconResolver.hasOverride(requireContext(), pkg)) {
			options.add(new KeydroidxOptionsDialog.OptionItem(android.R.drawable.ic_menu_revert,
					"恢复默认图标", true, false, () -> {
				KeydroidxSettingsStorage.clearIconOverride(requireContext(), pkg);
				KeydroidxIconResolver.invalidatePackage(requireContext(), pkg);
				Toast.makeText(requireContext(), "已恢复默认图标", Toast.LENGTH_SHORT).show();
				KeydroidxLog.i("Menu", "选项菜单-恢复默认图标: " + item.label + " pkg=" + pkg);
				// 只重算图标外观，不再整表重新枚举：当前页、页码、焦点都不动
				markIconAppearanceChanged();
				refreshIconsAsync();
			}));
		}
		options.add(new KeydroidxOptionsDialog.OptionItem(android.R.drawable.ic_menu_delete,
				"卸载", true, false, () -> {
			KeydroidxLog.i("Menu", "选项菜单-卸载: " + item.label + " pkg=" + pkg);
			try {
				Intent uninstall = new Intent(Intent.ACTION_DELETE,
						Uri.fromParts("package", pkg, null));
				uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity(uninstall);
			} catch (Exception e) {
				KeydroidxLog.e("Menu", "卸载跳转失败 " + item.label, e);
			}
		}));
		options.add(new KeydroidxOptionsDialog.OptionItem(android.R.drawable.ic_menu_manage,
				"应用设置", true, false, () -> {
			KeydroidxLog.i("Menu", "选项菜单-应用设置: " + item.label + " pkg=" + pkg);
			try {
				Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
						Uri.fromParts("package", pkg, null));
				settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity(settings);
			} catch (Exception e) {
				KeydroidxLog.e("Menu", "应用设置跳转失败 " + item.label, e);
			}
		}));
		KeydroidxOptionsDialog.show(getParentFragmentManager(), item.label, options);
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

	// ---- KeydroidxPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

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

	/**
	 * 首帧 / 重建后的焦点定位。
	 * <p>图标外观变化（iconAppearanceChanged，即从「更换图标」等页面返回）时停在原来那一格，
	 * 用户返回后高亮仍在刚才那个应用上，可以立刻再按「选项 → 更换图标」连续操作；
	 * 其余场景沿用「定位到第一格」。焦点位置按本页实际项数收敛，避免落在空槽上。
	 */
	private void applyInitialFocus() {
		int count = Math.min(perPage, Math.max(0, items.size() - pageIndex * perPage));
		if (!iconAppearanceChanged || count <= 0) {
			setFocusPos(0);
			return;
		}
		setFocusPos(Math.min(focusPos, count - 1));
	}

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
				v.setBackground(KeydroidxTheme.createSelectionDrawable(requireContext(), 4));
				selectedView = v;
				return;
			}
		}
		selectedView = null;
	}
}
