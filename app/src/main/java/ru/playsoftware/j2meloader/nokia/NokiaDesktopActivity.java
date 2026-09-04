package ru.playsoftware.j2meloader.nokia;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import io.github.cctyl.nokia.common.model.KeyResolver;
import io.github.cctyl.nokia.common.model.NokiaKeyAction;
import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;
import io.github.cctyl.nokia.common.ui.page.NokiaPageHost;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.nokia.NokiaGlobalProfile;
import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.permission.NokiaPermissionManager;
import com.hjq.permissions.OnPermissionCallback;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 诺基亚风格界面的单一宿主 Activity。
 * 顶部栏与底部栏（共用布局）保持不动，中间区域在碎片之间切换。
 * <p>
 * 作为系统桌面 Launcher（intent-filter 含 HOME/DEFAULT），
 * 每次从其他应用按 Home 键返回时都会触发 onNewIntent()，
 * 此时应清除返回栈并回到桌面待机屏。
 */
public class NokiaDesktopActivity extends NokiaBaseActivity
		implements NokiaPageHost, KeyResolver {

	private static final String ACTION_HOME = Intent.ACTION_MAIN;
	private static final String CATEGORY_HOME = Intent.CATEGORY_HOME;
	private static NokiaDesktopActivity sInstance = null;
	private StatusBarController statusBarController;
	private NokiaKeyBinding keyBinding;
	private NokiaLockServer lockServer;
	/** Activity 是否处于 resumed 状态（延迟任务防重入校验用） */
	private boolean resumedFlag = false;
	/**
	 * 最近一次被本层在 ACTION_DOWN 阶段消费的按键 keyCode。
	 * 用于把对应的 UP / REPEAT 一并拦截：由于 DOWN 没落到 view 层级，
	 * 若放行 UP，系统会基于底部软键曾 setPressed 的状态合成 performClick，
	 * 导致物理确认键一次按压被识别成两次动作（320×480 实测 bug）。
	 * 复位为 KEYCODE_UNKNOWN 表示当前无待配对事件。
	 */
	private int lastHandledDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;
	/** 待在 UP（抬起按键）时执行锁屏的物理按键 keyCode；KEYCODE_UNKNOWN 表示无待处理锁屏。 */
	private int pendingLockScreenKeyCode = KeyEvent.KEYCODE_UNKNOWN;
	/** 最近一次上报给拦截器的页面状态，避免重复发送 TCP */
	private String lastReportedPageState = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		// 壁纸解码预热：全屏 PNG 解码 + 位图分配很慢，先起后台线程，
		// 让首帧先铺主题渐变，解码完成后再由 applyWallpaper 叠加壁纸层。
		NokiaWallpaper.preloadAsync(this, null);
		applyCurrentTheme();
		super.onCreate(savedInstanceState);
		sInstance = this;
		setContentView(R.layout.activity_nokia);
		setupNokiaUi();
		findViewById(R.id.midPanel).setVisibility(View.VISIBLE);

		// 底部软键触摸点击：等效于对应物理软键（修复「桌面设置」等页触摸返回无效）
		bindBottomBarTouch();

		statusBarController = new StatusBarController(this);
		keyBinding = new NokiaKeyBinding(this);

		// 启动锁屏指令服务器（供 native 拦截器 socket 直连，替代 am broadcast）
		lockServer = new NokiaLockServer(this);
		lockServer.start();

		// 监听返回栈变化，自动上报页面状态给拦截器（覆盖 goHome/switchFragment/exitCurrent）
		getSupportFragmentManager().addOnBackStackChangedListener(() -> postReportPageState());

		// 确保全局 JAR 设置 profile 存在并设为默认
		NokiaGlobalProfile.ensureGlobalProfile(this);

		// 首次启动：若按键绑定向导未完成，则进入向导（清数据后 isWizardDone 复位会再次弹出）
		Fragment existing = getSupportFragmentManager().findFragmentById(R.id.midPanel);
		if (existing == null) {
			if (!keyBinding.isWizardDone()) {
				NokiaLog.i("Desktop", "首次启动：进入按键绑定向导");
				loadWizardFragment();
			} else {
				loadDesktopFragment();
			}
		}

		// Android 13+ 通知权限：桌面保活的常驻通知依赖它。
		// 未授权时通知会被系统直接屏蔽——保活优先级照给，但用户既看不到也管不了它，
		// 因此向导完成后（首启不再有更强的界面诉求时）主动申请一次。
		if (keyBinding.isWizardDone()) {
			// 启动时核心权限自检（应用列表+电话状态+通知权限+通知使用权全集）：
			// 覆盖升级用户（向导已完成、不会再走向导后批量申请）以及运行时权限被系统收回的场景，
			// 缺失则一次性诺基亚风格弹窗引导补齐。POST_NOTIFICATIONS 已纳入全集统一申请，
			// 不再单独走原生 requestPermissions。
			checkCorePermissionsOnStartup();
			// 拉起常驻保活前台服务并常驻。【必须在桌面 onCreate 启动】，绝不在 onStop 启动——
			// onStop 往往就是息屏/锁屏发生的瞬间，那时启动前台服务会把它 onCreate/onStartCommand/
			// startForeground 全部挤进【桌面主线程】，恰好砸进「窗口焦点从有变无」的敏感窗口，
			// 曾引发「Application does not have a focused window」ANR → 展讯看门狗强杀进程 →
			// 系统自动 Clearing preferred home → 按 HOME 弹出桌面选择器。
			// 在 onCreate 启动时桌面处于前台态，服务早已常驻，息屏过渡期不再有任何 FGS 启动动作。
			NokiaDesktopKeepAliveService.start(this);
		}
	}

	/** 本次启动是否已做过核心权限自检，避免重复弹窗。 */
	private boolean corePermissionCheckedThisSession = false;

	/**
	 * 启动时核心权限自检：检查核心权限全集（应用列表+电话状态+通知权限+通知使用权）
	 * 是否就绪，缺失则一次性诺基亚风格弹窗引导授权。
	 * <p>覆盖两类场景：
	 * <ul>
	 *   <li>升级用户：向导已完成，不会再走向导后批量申请，新版本新增的权限需求
	 *       只能靠启动时检测补齐；</li>
	 *   <li>运行时权限被系统/用户收回：下次启动桌面时检测到缺失并引导。</li>
	 * </ul>
	 * 已全部就绪则不弹；本次启动拒绝后不再弹，下次启动权限仍缺失会再检测。
	 */
	private void checkCorePermissionsOnStartup() {
		if (corePermissionCheckedThisSession) return;
		corePermissionCheckedThisSession = true;
		// 延迟到窗口就绪后检查，避免 onCreate 早期弹窗时机问题
		getWindow().getDecorView().post(() -> {
			if (isFinishing() || isDestroyed()) return;
			if (NokiaPermissionManager.isCorePermissionsGranted(this)) {
				NokiaLog.i("Desktop", "启动时核心权限自检：全集就绪");
				return;
			}
			NokiaLog.i("Desktop", "启动时核心权限自检：存在缺失项，启动批量引导");
			NokiaPermissionManager.requestCorePermissions(
					this,
					"检测到部分系统权限缺失，桌面需要电话状态、应用列表、通知及通知使用权权限，以显示信号、展示和启动应用、并常驻通知。",
					new OnPermissionCallback() {
						@Override
						public void onGranted(java.util.List<String> permissions, boolean allGranted) {
							NokiaLog.i("Desktop", "启动时核心权限自检：已补齐 " + permissions);
						}

						@Override
						public void onDenied(java.util.List<String> permissions, boolean doNotAskAgain) {
							NokiaLog.w("Desktop", "启动时核心权限自检：用户拒绝 " + permissions + ", doNotAskAgain=" + doNotAskAgain);
						}
					});
		});
	}

	/** 重新从 SharedPreferences 加载按键绑定到内存（向导/绑定界面完成后调用，确保立即生效）。 */
	public void reloadKeyBindings() {
		if (keyBinding != null) {
			keyBinding.reload();
			NokiaLog.i("Desktop", "reloadKeyBindings 完成");
		}
	}

	/** 暴露当前按键绑定实例，供 Fragment 读取（如桌面锁屏按钮展示已绑定键名）。 */
	public NokiaKeyBinding getKeyBinding() {
		return keyBinding;
	}

	/**
	 * 判断本应用当前是否已被系统设为默认桌面（Home / Launcher）。
	 * 用于「桌面设置 → 默认桌面设置」文案状态展示与向导结束后的询问。
	 */
	public boolean isDefaultLauncher() {
		return NokiaLauncherUtils.isDefaultLauncher(this);
	}

	/**
	 * 引导用户设置/更换默认桌面。
	 * <p>
	 * 需求：无论当前是否已把本应用设为默认桌面，按下确认都必须再次弹出可操作的
	 * 默认桌面选择/设置界面。
	 * <ul>
	 *   <li><b>未设置默认</b>：API 29+ 走 {@link RoleManager} 的 {@code ROLE_HOME}
	 *       申请流程（系统弹出默认启动器选择器）；低版本用隐式 HOME Intent 触发系统选择器。</li>
	 *   <li><b>已设置为默认</b>：此时 RoleManager 已把本应用视为 holder，
	 *       {@code createRequestRoleIntent} 不会再弹窗（实测 API 33 无反应），
	 *       因此改为打开系统「默认应用 → 主屏幕」设置页
	 *       {@link Settings#ACTION_HOME_SETTINGS}，让用户可再次进入并切换默认桌面。</li>
	 * </ul>
	 */
	public void requestSetDefaultLauncher() {
		NokiaLog.i("Desktop", "requestSetDefaultLauncher 调用，api=" + Build.VERSION.SDK_INT);
		try {
			if (isDefaultLauncher()) {
				// 已设为默认：RoleManager 不会为已是 holder 的应用再次弹选择器，
				// 直接打开系统「默认应用 → 主屏幕」设置页，保证界面一定能弹出。
				NokiaLog.i("Desktop", "已是默认桌面，改为打开系统默认应用(主屏幕)设置页");
				Intent settings = new Intent(Settings.ACTION_HOME_SETTINGS);
				settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				startActivity(settings);
				return;
			}
			// 未设置默认：走标准默认桌面申请流程
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				RoleManager roleManager = (RoleManager) getSystemService(ROLE_SERVICE);
				if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
					Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME);
					startActivityForResult(intent, REQ_SET_DEFAULT_LAUNCHER);
					return;
				}
			}
			// 旧版本 / RoleManager 不可用时：用隐式 Intent 拉起系统桌面选择器
			Intent intent = new Intent(Intent.ACTION_MAIN);
			intent.addCategory(Intent.CATEGORY_HOME);
			intent.addCategory(Intent.CATEGORY_DEFAULT);
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
		} catch (Exception e) {
			NokiaLog.e("Desktop", "requestSetDefaultLauncher 失败", e);
		}
	}

	/** 申请默认桌面流程的请求码（RoleManager 路径会回调 onActivityResult）。 */
	private static final int REQ_SET_DEFAULT_LAUNCHER = 1001;

	/** 返回 midPanel 的当前真实像素高度（可能为 0 若尚未布局完成）。用于行数空间预算的实测反推。 */
	public int getMidPanelHeight() {
		View mid = findViewById(R.id.midPanel);
		return mid != null ? mid.getHeight() : 0;
	}

	@Override
	public void finish() {
		// common 的通用页面（如意见反馈页）在提交成功后会调用 requireActivity().finish()；
		// 本 Activity 是 HOME，直接 finish 等于退出桌面，因此有返回栈时改为出栈返回上一层。
		FragmentManager fm = getSupportFragmentManager();
		if (fm.getBackStackEntryCount() > 0) {
			NokiaLog.i("Desktop", "finish() 被子页面调用 -> 出栈返回上一层");
			exitCurrent();
			return;
		}
		super.finish();
	}

	/**
	 * common {@link KeyResolver} 实现：把物理按键交给桌面按键绑定解析。
	 * 动作取值与 {@link NokiaKeyBinding#ACTION_UP} 等常量一致（也即 common NokiaKeyAction 的取值）。
	 */
	@Override
	public int resolveAction(@NonNull KeyEvent event) {
		// 纯查询，不做 reload：dispatchKeyEvent 已在每次按键前 reload，避免按键热路径重复加载
		return keyBinding == null ? NokiaKeyAction.UNKNOWN : keyBinding.resolveAction(event);
	}

	/**
	 * 重新读取当前页面的 {@link NokiaPage} 声明并装配底部菜单栏。
	 * <p>
	 * 页面切到前台（onViewCreated / onResume）或内部状态变化（焦点、mode、覆盖模式、向导步骤）
	 * 后调用本方法，替代原来各 Fragment 各自写死 setBottomBar / 直接操作底部 TextView 的散乱写法。
	 */
	public void refreshPageBar() {
		Fragment f = getSupportFragmentManager().findFragmentById(R.id.midPanel);
		// 用 common 的 NokiaPage 判定：桌面页面（NokiaPage 继承它）与 common 自带页面
		// （如意见反馈页）都能被装配底栏。
		if (f instanceof io.github.cctyl.nokia.common.ui.page.NokiaPage) {
			io.github.cctyl.nokia.common.ui.page.NokiaPage page =
					(io.github.cctyl.nokia.common.ui.page.NokiaPage) f;
			CharSequence left = page.getSoftLeftText();
			// 中键：common 页面（如意见反馈页）会用中键承载「选择 / 提交」动作，优先显示它自己声明的文案；
			// 桌面页面不声明中键（默认 null），沿用一贯的「中键显示页面名」。
			CharSequence center = page.getSoftCenterText();
			if (center == null || center.length() == 0) {
				center = page.getPageTitle();
			}
			CharSequence right = page.getSoftRightText();
			NokiaLog.d("Desktop", "refreshPageBar 装配 " + f.getClass().getSimpleName()
					+ " left=" + left + " center=" + center + " right=" + right);
			setBottomBar(left == null ? null : left.toString(),
					center == null ? null : center.toString(),
					right == null ? null : right.toString());
		} else {
			NokiaLog.d("Desktop", "refreshPageBar: 当前 Fragment 未实现 NokiaPage（"
					+ (f != null ? f.getClass().getSimpleName() : "null") + "），忽略");
		}
	}

	// ---- 生命周期 ----

	@Override
	protected void onResume() {
		super.onResume();
		resumedFlag = true;
		// 桌面始终竖屏：从横屏游戏返回时必须强制旋转回竖屏，
		// 否则系统会沿用游戏的横屏方向导致桌面横向显示。
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		// 从按键绑定设置返回后，重新加载最新绑定，避免 Activity 缓存旧映射。
		if (keyBinding != null) {
			keyBinding.reload();
		}
		// 状态栏系统信息（信号/运营商/WiFi/电池等）查询延迟到首帧渲染后执行，
		// 避免冷启动时同步 Binder 调用阻塞首帧；延迟回调前若已 pause 则跳过（防重复注册）。
		scheduleStatusBarStart();
		// 上报当前页面状态给拦截器（强制：jar 曾把 native 端 page_is_main 改为 0，
		// 从 jar 返回桌面时须无条件重报，否则红键在桌面会被误判为子页面）
		reportPageState(true);
		NokiaLog.i("Desktop", "onResume 已调度 StatusBarController 延迟启动");
	}

	/** 延迟启动状态栏控制器（首帧后约 200ms），带 onPause 防重入校验与计时日志。 */
	private void scheduleStatusBarStart() {
		if (statusBarController == null) {
			return;
		}
		final Runnable task = new Runnable() {
			@Override
			public void run() {
				if (!resumedFlag) {
					NokiaLog.d("Desktop", "状态栏延迟启动取消：Activity 已非 resumed");
					return;
				}
				long start = System.currentTimeMillis();
				try {
					statusBarController.start();
				} catch (Exception e) {
					NokiaLog.w("Desktop", "状态栏延迟启动异常: " + e.getMessage());
				}
				long elapsed = System.currentTimeMillis() - start;
				NokiaLog.i("Desktop", "StatusBarController.start 完成，耗时 " + elapsed + "ms");
			}
		};
		getWindow().getDecorView().postDelayed(task, 200);
	}


	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			// onResume 时窗口尚未完全就绪，部分 ROM 会忽略 setRequestedOrientation，
			// 改用窗口获得焦点的时机再次强制竖屏，作为可靠兜底。
			int cur = getResources().getConfiguration().orientation;
			NokiaLog.i("Desktop", "onWindowFocusChanged hasFocus=true, 当前方向="
					+ (cur == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "LANDSCAPE" : "PORTRAIT"));
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
		}
	}

	@Override
	protected void onPause() {
		resumedFlag = false;
		resetLastHandledKeyCode();
		super.onPause();
		if (statusBarController != null) {
			statusBarController.stop();
		}
	}

	/** 全局应用当前主题配色（更新背景壁纸、软键栏渐变及左右软键文字主题色） */
	public void applyCurrentTheme() {
		NokiaSettingsStorage storage = new NokiaSettingsStorage(this);
		NokiaTheme.ThemeDef theme = storage.getTheme();
		applyWallpaper(theme);
		View bottomBar = findViewById(R.id.bottomPanel);
		if (bottomBar != null) {
			bottomBar.setBackground(NokiaTheme.createSoftKeyDrawable(theme));
		}
		TextView bottomLeft = findViewById(R.id.bottomLeft);
		if (bottomLeft != null) {
			bottomLeft.setTextColor(theme.accentColor);
		}
		TextView bottomRight = findViewById(R.id.bottomRight);
		if (bottomRight != null) {
			bottomRight.setTextColor(theme.accentColor);
		}
	}

	/**
	 * 应用全屏背景：先铺主题渐变保证首帧立刻可见，自定义壁纸解码完成后再叠加。
	 * <p>
	 * 壁纸解码（磁盘读取 + 全屏位图分配）在主线程可达数十~数百毫秒，
	 * 同步执行会直接把桌面首帧拖慢；而「渐变 → 壁纸」的切换几乎不可感知。
	 */
	private void applyWallpaper(final NokiaTheme.ThemeDef theme) {
		final View wall = findViewById(R.id.wallpaper);
		if (wall == null) {
			return;
		}
		wall.setBackground(NokiaTheme.createBackgroundDrawable(theme));
		// 未设置自定义壁纸：主题渐变就是最终背景
		if (!NokiaWallpaper.hasCustomWallpaper(this)) {
			return;
		}
		// 已解码：一次性铺好「渐变 + 壁纸」
		if (NokiaWallpaper.isBitmapReady()) {
			wall.setBackground(NokiaWallpaper.createWallpaperDrawable(this, theme));
			return;
		}
		NokiaWallpaper.preloadAsync(this, new Runnable() {
			@Override
			public void run() {
				if (isFinishing()) {
					return;
				}
				View w = findViewById(R.id.wallpaper);
				if (w != null) {
					w.setBackground(NokiaWallpaper.createWallpaperDrawable(NokiaDesktopActivity.this, theme));
				}
			}
		});
	}

	protected void onDestroy() {
		if (sInstance == this) {
			sInstance = null;
		}
		if (lockServer != null) {
			lockServer.stop();
		}
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (statusBarController != null
				&& requestCode == 1001
				&& grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			statusBarController.onPermissionGranted();
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		if (isHomeIntent(intent)) {
			NokiaLog.i("Desktop", "收到 HOME intent，回到桌面待机");
			goHome();
		} else {
			NokiaLog.d("Desktop", "onNewIntent 非 HOME intent，忽略");
		}
	}

	// ---- 物理按键分发（核心） ----

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			// 锁屏动作专用闭环：必须在 UP（抬手）时才真正执行 lockNow()。
			// 若在 DOWN 触发，屏幕息屏与窗口隐藏极快，随后的 UP 事件到达系统时将失去前台接收窗口，
			// 引发 InputDispatcher 5秒超时 ANR。
			if (event.getAction() == KeyEvent.ACTION_UP
					&& event.getKeyCode() == pendingLockScreenKeyCode) {
				pendingLockScreenKeyCode = KeyEvent.KEYCODE_UNKNOWN;
				lastHandledDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;
				NokiaLog.i("Desktop", "锁屏按键抬起（UP）：执行锁屏");
				lockScreen();
				return true;
			}

			// 若此前对应的 DOWN 已被本层消费，则把 UP / REPEAT 一并吞掉：
			// 避免它落入 view 层级（底部软键仍处于 pressed）被系统合成 performClick，
			// 导致一次按压触发两次动作。
			if (event.getKeyCode() == lastHandledDownKeyCode) {
				NokiaLog.d("Desktop", "dispatchKeyEvent 拦截已消费按键的 "
						+ (event.getAction() == KeyEvent.ACTION_UP ? "UP" : "REPEAT")
						+ " " + NokiaKeyBinding.keyName(event.getKeyCode()));
				return true;
			}
			return super.dispatchKeyEvent(event);
		}

		NokiaLog.d("Desktop", "dispatchKeyEvent 收到按下 " + NokiaKeyBinding.keyName(event.getKeyCode()));

		// 每次按键前重新加载绑定，确保从按键绑定设置返回后的修改立即生效
		if (keyBinding != null) {
			keyBinding.reload();
		}

		// 如果当前 Fragment 处于录制态（按键绑定设置 / 首次启动向导），
		// 优先把任意物理键直接喂给它捕获，再走 resolveAction 分发。
		Fragment curForRec = getSupportFragmentManager().findFragmentById(R.id.midPanel);
		boolean recording = curForRec instanceof NokiaKeyRecorder
				&& ((NokiaKeyRecorder) curForRec).isRecording();

		// ---- 长按连发（key repeat）过滤 ----
		// Android 的连发事件 action 仍是 ACTION_DOWN，只是 getRepeatCount() 递增，
		// 因此进不了上面「非 DOWN 分支」的 UP / REPEAT 拦截逻辑，必须在此单独过滤。
		// 否则按住左软键超过约 400ms 就会持续触发动作：实测会反复进出功能表选项弹窗，
		// 并把弹窗首项（冻结 / 解冻）反复执行。
		// 判据只能用 getRepeatCount()：isLongPress() 仅在首个连发事件为 true（Q968 实测）。
		if (event.getRepeatCount() > 0) {
			int repeatAction = keyBinding.resolveAction(event);
			// 方向键放行：「按住方向键连续移动 / 翻页」是 S40 的既有行为，属于预期功能。
			// 录制态下方向键也要吞：此时它是被录制的键码，不参与导航。
			if (recording || !isDirectionAction(repeatAction)) {
				NokiaLog.d("Desktop", "吞掉长按连发 " + NokiaKeyBinding.keyName(event.getKeyCode())
						+ " repeat=" + event.getRepeatCount()
						+ " action=" + NokiaKeyBinding.getActionName(repeatAction));
				return true;
			}
			NokiaLog.d("Desktop", "方向键连发放行 repeat=" + event.getRepeatCount());
		}

		if (recording) {
			NokiaKeyRecorder rec = (NokiaKeyRecorder) curForRec;
			int kc = event.getKeyCode();
			// 录制态下：用户按下的任意物理键（含返回键）都照常录成当前动作的绑定，
			// 不做任何忽略。\"跳过\"只通过屏幕上的触摸按钮触发（onSkipCurrent），
			// 不会在这里用返回键实现。
			NokiaLog.i("Desktop", "录制态捕获按键 " + NokiaKeyBinding.keyName(kc));
			rec.onKeyRecorded(kc);
			lastHandledDownKeyCode = kc;
			return true;
		}

		int action = keyBinding.resolveAction(event);

		if (action < 0) {
			// 数字键 0：在后台管理窗口内一键清理（0 键默认未绑定任何动作，仅在目标页面生效）
			if (event.getKeyCode() == KeyEvent.KEYCODE_0) {
				Fragment bgHost = getSupportFragmentManager().findFragmentById(R.id.midPanel);
				if (bgHost instanceof NokiaBackgroundManagerFragment) {
					NokiaLog.i("Desktop", "数字键 0：后台管理一键清理");
					((NokiaBackgroundManagerFragment) bgHost).onCleanKey();
					lastHandledDownKeyCode = event.getKeyCode();
					return true;
				}
			}
			// 返回键未绑定时兜底为导航返回（非桌面 Fragment），否则交给系统。
			if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
				Fragment backHost = getSupportFragmentManager().findFragmentById(R.id.midPanel);
				if (backHost instanceof NokiaFocusHost
						&& !(backHost instanceof NokiaDesktopFragment)) {
					NokiaLog.d("Desktop", "未绑定返回键 -> host.onBack()");
					((NokiaFocusHost) backHost).onBack();
					lastHandledDownKeyCode = event.getKeyCode();
					return true;
				}
			}
			// 未绑定的按键：允许系统继续处理（如音量键仍然调整音量）
			NokiaLog.d("Desktop", "未绑定的按键 " + NokiaKeyBinding.keyName(event.getKeyCode())
					+ "，交给系统处理");
			resetLastHandledKeyCode();
			return super.dispatchKeyEvent(event);
		}

		NokiaLog.d("Desktop", "解析动作 " + NokiaKeyBinding.getActionName(action)
				+ "(" + action + ")");

		// 锁屏动作：仅在桌面待机屏生效。
		// 关键：DOWN 阶段只记录 keyCode 并拦截，绝不在 DOWN 阶段调用 lockScreen()！
		// 必须等待用户手指抬起（UP）时才调用 lockNow()，确保输入事件成对闭环。
		if (action == NokiaKeyBinding.ACTION_LOCK_SCREEN) {
			Fragment lockHost = getSupportFragmentManager().findFragmentById(R.id.midPanel);
			if (lockHost instanceof NokiaDesktopFragment) {
				NokiaLog.i("Desktop", "锁屏按键按下（DOWN）：已拦截，等待抬起执行");
				pendingLockScreenKeyCode = event.getKeyCode();
				lastHandledDownKeyCode = event.getKeyCode();
				return true;
			}
			NokiaLog.d("Desktop", "锁屏动作当前非桌面，交由系统处理");
			resetLastHandledKeyCode();
			return super.dispatchKeyEvent(event);
		}

		// 底部软键按下视觉反馈（触摸点击不经过此处，由底部栏点击监听处理）
		flashBottomBar(action);

		// 将动作分发给当前中间面板 Fragment；被消费则拦截
		if (dispatchActionToHost(action)) {
			NokiaLog.d("Desktop", "动作 " + NokiaKeyBinding.getActionName(action)
					+ " 已被当前 Fragment 消费");
			lastHandledDownKeyCode = event.getKeyCode();
			return true;
		}

		NokiaLog.d("Desktop", "dispatchKeyEvent 未消费 " + NokiaKeyBinding.keyName(event.getKeyCode())
				+ "，交给系统");
		resetLastHandledKeyCode();
		return super.dispatchKeyEvent(event);
	}

	/** 复位「已消费 DOWN 的 keyCode」，用于本层未消费该按键的路径，避免误吞后续 UP。 */
	private void resetLastHandledKeyCode() {
		lastHandledDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;
		pendingLockScreenKeyCode = KeyEvent.KEYCODE_UNKNOWN;
	}

	/** 是否为方向类动作：方向键的长按连发需保留（连续移动 / 翻页），其余动作一律只响应首次按下。 */
	private static boolean isDirectionAction(int action) {
		return action == NokiaKeyBinding.ACTION_UP
				|| action == NokiaKeyBinding.ACTION_DOWN
				|| action == NokiaKeyBinding.ACTION_LEFT
				|| action == NokiaKeyBinding.ACTION_RIGHT;
	}

	/**
	 * 把动作分发给当前中间面板 Fragment（NokiaFocusHost）。
	 * 物理按键与底部软键触摸点击共用此入口，保证两套交互行为一致。
	 *
	 * @return 是否被 Fragment 消费
	 */
	private boolean dispatchActionToHost(int action) {
		Fragment current = getSupportFragmentManager().findFragmentById(R.id.midPanel);
		if (!(current instanceof NokiaFocusHost)) {
			NokiaLog.d("Desktop", "dispatchActionToHost: 当前非 FocusHost，忽略 action="
					+ NokiaKeyBinding.getActionName(action));
			return false;
		}
		NokiaFocusHost host = (NokiaFocusHost) current;
		boolean handled;
		switch (action) {
			case NokiaKeyBinding.ACTION_UP:
			case NokiaKeyBinding.ACTION_DOWN:
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
				handled = host.onDirection(action);
				break;
			case NokiaKeyBinding.ACTION_SELECT:
				handled = host.onSelect();
				break;
			case NokiaKeyBinding.ACTION_SOFT_LEFT:
				handled = host.onSoftLeft();
				break;
			case NokiaKeyBinding.ACTION_SOFT_RIGHT:
				handled = host.onSoftRight();
				break;
			default:
				handled = false;
		}
		NokiaLog.d("Desktop", "dispatchActionToHost action="
				+ NokiaKeyBinding.getActionName(action) + " handled=" + handled);
		return handled;
	}

	/** 底部软键按下时的视觉反馈（左/确认/右软键），触摸与物理按键共用。 */
	private void flashBottomBar(int action) {
		int id = -1;
		switch (action) {
			case NokiaKeyBinding.ACTION_SOFT_LEFT:
				id = R.id.bottomLeft;
				break;
			case NokiaKeyBinding.ACTION_SOFT_RIGHT:
				id = R.id.bottomRight;
				break;
			case NokiaKeyBinding.ACTION_SELECT:
				id = R.id.bottomCenter;
				break;
			default:
				break;
		}
		if (id <= 0) return;
		View v = findViewById(id);
		if (v != null) {
			v.setPressed(true);
			v.postDelayed(() -> v.setPressed(false), 100);
		}
	}

	/** 为底部三个软键绑定触摸点击：点击等效于对应物理软键（左/确认/右）。 */
	private void bindBottomBarTouch() {
		View left = findViewById(R.id.bottomLeft);
		View center = findViewById(R.id.bottomCenter);
		View right = findViewById(R.id.bottomRight);
		if (left != null) {
			left.setOnClickListener(v -> {
				NokiaLog.i("Desktop", "触摸点击 -> 左软键");
				flashBottomBar(NokiaKeyBinding.ACTION_SOFT_LEFT);
				dispatchActionToHost(NokiaKeyBinding.ACTION_SOFT_LEFT);
			});
		}
		if (center != null) {
			center.setOnClickListener(v -> {
				NokiaLog.i("Desktop", "触摸点击 -> 确认键");
				flashBottomBar(NokiaKeyBinding.ACTION_SELECT);
				dispatchActionToHost(NokiaKeyBinding.ACTION_SELECT);
			});
		}
		if (right != null) {
			right.setOnClickListener(v -> {
				NokiaLog.i("Desktop", "触摸点击 -> 右软键");
				flashBottomBar(NokiaKeyBinding.ACTION_SOFT_RIGHT);
				dispatchActionToHost(NokiaKeyBinding.ACTION_SOFT_RIGHT);
			});
		}
	}

	/** 一键锁屏（委托给 NokiaLockScreen 工具类）。 */
	private void lockScreen() {
		NokiaLockScreen.lock(this);
	}

	private void loadWizardFragment() {
		NokiaLog.i("Desktop", "加载首次启动按键绑定向导 Fragment");
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.midPanel, new NokiaKeyBindWizardFragment())
				.commit();
		postReportPageState();
	}

	// ---- 导航方法 ----

	public void openMenu() {
		NokiaLog.i("Desktop", "导航 -> 功能表");
		switchFragment(new NokiaMenuFragment());
	}

	public void openBox() {
		NokiaLog.i("Desktop", "导航 -> 应用程序");
		switchFragment(new NokiaBoxFragment());
	}

	/** 打开桌面设置界面 */
	public void openDesktopSettings() {
		NokiaLog.i("Desktop", "导航 -> 桌面设置");
		switchFragment(new NokiaDesktopSettingsFragment());
	}

	/** 打开通知中心列表页（功能表格子 / 桌面通知条共用入口）。 */
	public void openNotificationCenter() {
		NokiaLog.i("Desktop", "导航 -> 通知中心");
		switchFragment(new NokiaNotificationCenterFragment());
	}

	/** 通用打开一个 Fragment 并加入返回栈。 */
	public void openFragment(Fragment fragment) {
		switchFragment(fragment);
	}

	public void exitCurrent() {
		FragmentManager fm = getSupportFragmentManager();
		if (fm.getBackStackEntryCount() > 0) {
			NokiaLog.i("Desktop", "exitCurrent 出栈返回上一层");
			fm.popBackStack();
		} else {
			NokiaLog.i("Desktop", "exitCurrent 无返回栈，finish()");
			finish();
		}
	}

	// ---- 页面状态上报（供拦截器区分主界面 vs 子页面） ----

	/**
	 * 延迟到主线程下一帧上报页面状态，确保 Fragment 事务已提交。
	 */
	private void postReportPageState() {
		postReportPageState(false);
	}

	private void postReportPageState(boolean force) {
		new Handler(Looper.getMainLooper()).post(() -> reportPageState(force));
	}

	/**
	 * 检测当前 Fragment 是否为桌面主界面（NokiaDesktopFragment），通过 TCP 上报给
	 * mini_shizuku 服务端，服务端经 JNI 更新 native 全局变量 page_is_main。
	 * 拦截器状态机据此区分：亮屏+诺基亚主界面→锁屏，亮屏+诺基亚子页面→回桌面。
	 * force=true 时跳过去重强制上报（jar 前台时 MicroActivity 会把 native 端改为 0，
	 * 从 jar 返回桌面/挂机回桌面时本进程缓存的 lastReportedPageState 可能仍是 main，
	 * 去重会导致漏报、红键在桌面误判为子页面）。
	 */
	private void reportPageState(boolean force) {
		Fragment f = getSupportFragmentManager().findFragmentById(R.id.midPanel);
		String state = (f instanceof NokiaDesktopFragment) ? "main" : "sub";
		if (!force && state.equals(lastReportedPageState)) return;
		lastReportedPageState = state;
		NokiaLog.i("Desktop", "上报页面状态: " + state
				+ " (fragment=" + (f != null ? f.getClass().getSimpleName() : "null") + ")");
		new Thread(() -> {
			try {
				Shizuku.setPageState("main".equals(state));
			} catch (Exception e) {
				NokiaLog.w("Desktop", "上报页面状态失败: " + e.getMessage());
			}
		}, "nokia-page-state").start();
	}

	public static NokiaDesktopActivity getInstance() {
		return sInstance;
	}

	// ---- 内部方法 ----

	private void goHome() {
		NokiaLog.i("Desktop", "goHome 清空返回栈并加载桌面");
		FragmentManager fm = getSupportFragmentManager();
		if (fm.getBackStackEntryCount() > 0) {
			fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
		}
		Fragment cur = fm.findFragmentById(R.id.midPanel);
		if (!(cur instanceof NokiaDesktopFragment)) {
			fm.beginTransaction()
					.replace(R.id.midPanel, new NokiaDesktopFragment())
					.commitAllowingStateLoss();
		}
		// jar 挂机/红键回桌面场景：native page_is_main 可能已被 MicroActivity 置 0，须强制重报
		postReportPageState(true);
	}

	private boolean isHomeIntent(Intent intent) {
		if (intent == null) return false;
		String action = intent.getAction();
		if (action == null) return false;
		if (!action.equals(ACTION_HOME)) return false;
		return intent.getCategories() != null
				&& intent.getCategories().contains(CATEGORY_HOME);
	}

	private void loadDesktopFragment() {
		NokiaLog.i("Desktop", "加载初始桌面 Fragment");
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.midPanel, new NokiaDesktopFragment())
				.commit();
		postReportPageState();
	}

	private void switchFragment(Fragment fragment) {
		NokiaLog.i("Desktop", "切换中间面板 -> "
				+ (fragment != null ? fragment.getClass().getSimpleName() : "null"));
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.midPanel, fragment)
				.addToBackStack(null)
				.commit();
		postReportPageState();
	}
}
