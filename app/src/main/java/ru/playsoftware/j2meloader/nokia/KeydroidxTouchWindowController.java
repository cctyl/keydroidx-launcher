package ru.playsoftware.j2meloader.nokia;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.lang.ref.WeakReference;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 触屏模式双窗口解耦中枢控制器。
 * <p>
 * 将主业务屏幕（桌面 / JAR 游戏 / 弹窗）与虚拟物理键盘物理隔离为两个独立 Window：
 * 1. 主业务窗口：受控收缩在上半屏，完全不感知虚拟按键的存在，弹窗与布局自然归位；
 * 2. 虚拟按键窗口：使用独立 Panel Window 贴在屏幕下半部（Gravity.BOTTOM），
 *    设置 FLAG_NOT_FOCUSABLE 永不争夺输入焦点，触摸点击直接向前台 Activity 投递真实物理按键；
 * 3. 联动关闭 J2ME-Loader 原生虚拟键盘，彻底避免重叠。
 */
public final class KeydroidxTouchWindowController {

	private static final String TAG = "TouchWindow";
	private static final int KEYPAD_DP_HEIGHT = 240;

	private static View sCurrentKeypadView = null;
	private static Activity sBoundActivity = null;
	private static WeakReference<Dialog> sActiveDialog = null;

	private KeydroidxTouchWindowController() {}

	public static Dialog getActiveDialog() {
		if (sActiveDialog != null) {
			Dialog d = sActiveDialog.get();
			if (d != null && d.isShowing()) {
				return d;
			}
		}
		return null;
	}

	/** 计算虚拟按键的标准物理像素高度（240dp） */
	public static int getKeypadHeightPx(Context context) {
		DisplayMetrics dm = context.getResources().getDisplayMetrics();
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, KEYPAD_DP_HEIGHT, dm));
	}

	/**
	 * 调整 Activity 主窗口的尺寸边界：
	 * 触屏模式下将窗口压缩在上半屏，为底部独立键盘空出真实物理空间；
	 * 非触屏模式下恢复全屏。
	 */
	public static void applyActivityWindowBounds(Activity activity) {
		if (activity == null || activity.isFinishing()) return;
		Window window = activity.getWindow();
		if (window == null) return;
		WindowManager.LayoutParams p = window.getAttributes();
		boolean touchMode = KeydroidxSettingsStorage.isTouchMode(activity);
		if (touchMode) {
			int keypadHeightPx = getKeypadHeightPx(activity);
			DisplayMetrics dm = activity.getResources().getDisplayMetrics();
			int screenHeight = dm.heightPixels;
			p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
			p.width = WindowManager.LayoutParams.MATCH_PARENT;
			p.height = Math.max(0, screenHeight - keypadHeightPx);
			KeydroidxLog.d(TAG, "applyActivityWindowBounds: 触屏模式，主窗口高度设为 " + p.height);
		} else {
			p.gravity = Gravity.FILL;
			p.width = WindowManager.LayoutParams.MATCH_PARENT;
			p.height = WindowManager.LayoutParams.MATCH_PARENT;
			KeydroidxLog.d(TAG, "applyActivityWindowBounds: 物理机模式，主窗口恢复全屏");
		}
		window.setAttributes(p);
	}

	/**
	 * 调整 Dialog 窗口的坐标边界：
	 * 触屏模式下将弹窗窗口底部吸附在虚拟键盘上沿（params.y = keypadHeightPx），
	 * 保证弹窗始终处于上半屏，绝对不遮盖底部虚拟键盘；
	 * 非触屏模式下贴底居中（params.y = 0）。
	 */
	public static void applyDialogWindowBounds(Dialog dialog) {
		if (dialog == null) return;
		sActiveDialog = new WeakReference<>(dialog);
		Window window = dialog.getWindow();
		if (window == null) return;
		View decorView = window.getDecorView();
		if (decorView != null) {
			decorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
				@Override
				public void onViewAttachedToWindow(View v) {}

				@Override
				public void onViewDetachedFromWindow(View v) {
					if (sActiveDialog != null && sActiveDialog.get() == dialog) {
						sActiveDialog = null;
					}
				}
			});
		}
		Context context = dialog.getContext();
		boolean touchMode = KeydroidxSettingsStorage.isTouchMode(context);
		if (touchMode) {
			int keypadHeightPx = getKeypadHeightPx(context);
			window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
			window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
			WindowManager.LayoutParams lp = window.getAttributes();
			lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
			lp.y = keypadHeightPx;
			lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
			lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
			window.setAttributes(lp);
			View decor = window.getDecorView();
			if (decor != null) {
				decor.post(() -> {
					if (dialog.isShowing() && KeydroidxSettingsStorage.isTouchMode(context)) {
						WindowManager.LayoutParams p = window.getAttributes();
						p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
						p.y = keypadHeightPx;
						p.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
						p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
						window.setAttributes(p);
						window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
						window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
					}
				});
			}
			KeydroidxLog.d(TAG, "applyDialogWindowBounds: 触屏模式弹窗抬升并穿透触摸 y=" + keypadHeightPx);
		} else {
			window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
			WindowManager.LayoutParams lp = window.getAttributes();
			if (lp.y > 0) {
				lp.y = 0;
				window.setAttributes(lp);
			}
		}
	}

	/**
	 * 在前台 Activity 挂载独立虚拟键盘 Window（TYPE_APPLICATION_PANEL，无需悬浮窗权限）
	 */
	public static void attachKeypadWindow(Activity activity) {
		if (activity == null || activity.isFinishing()) return;
		if (!KeydroidxSettingsStorage.isTouchMode(activity)) {
			detachKeypadWindow(activity);
			return;
		}

		Window window = activity.getWindow();
		if (window == null) return;
		View decorView = window.getDecorView();
		if (decorView == null) return;

		decorView.post(() -> {
			if (activity.isFinishing() || activity.isDestroyed()) return;
			if (!KeydroidxSettingsStorage.isTouchMode(activity)) return;

			// 若已有挂载在旧 Activity 上的窗口，先清理
			if (sCurrentKeypadView != null) {
				detachKeypadWindow(sBoundActivity);
			}

			try {
				int keypadHeightPx = getKeypadHeightPx(activity);
				WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
						WindowManager.LayoutParams.MATCH_PARENT,
						keypadHeightPx,
						WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
						WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
								| WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
								| WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
						android.graphics.PixelFormat.TRANSLUCENT
				);
				lp.token = decorView.getWindowToken();
				lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

				LayoutInflater inflater = LayoutInflater.from(activity);
				View keypadContainer = inflater.inflate(R.layout.keydroidx_virtual_keypad, null);

				KeydroidxVirtualKeypadView keypadView = keypadContainer.findViewById(R.id.virtualKeypadView);
				if (keypadView == null && keypadContainer instanceof KeydroidxVirtualKeypadView) {
					keypadView = (KeydroidxVirtualKeypadView) keypadContainer;
				}

				if (keypadView != null) {
					keypadView.setOnVirtualKeyEventListener(new KeydroidxVirtualKeypadView.OnVirtualKeyEventListener() {
						@Override
						public void onVirtualKeyDown(int action, int defaultKeyCode) {
							sendKeyToForeground(activity, action, defaultKeyCode, KeyEvent.ACTION_DOWN);
						}

						@Override
						public void onVirtualKeyUp(int action, int defaultKeyCode) {
							sendKeyToForeground(activity, action, defaultKeyCode, KeyEvent.ACTION_UP);
						}
					});
				}

				activity.getWindowManager().addView(keypadContainer, lp);
				sCurrentKeypadView = keypadContainer;
				sBoundActivity = activity;
				KeydroidxLog.i(TAG, "attachKeypadWindow 成功挂载独立键盘窗口至 " + activity.getClass().getSimpleName());
			} catch (Exception e) {
				KeydroidxLog.e(TAG, "attachKeypadWindow 挂载失败: " + e.getMessage(), e);
			}
		});
	}

	/** 从当前 Activity 脱附并销毁独立虚拟键盘 Window */
	public static void detachKeypadWindow(Activity activity) {
		if (sCurrentKeypadView != null) {
			try {
				Activity act = sBoundActivity != null ? sBoundActivity : activity;
				if (act != null) {
					act.getWindowManager().removeViewImmediate(sCurrentKeypadView);
				}
			} catch (Exception ignored) {
			} finally {
				sCurrentKeypadView = null;
				sBoundActivity = null;
				KeydroidxLog.i(TAG, "detachKeypadWindow 独立键盘窗口已移除");
			}
		}
	}

	/** 向前台 Activity 发送虚拟按键事件 */
	private static void sendKeyToForeground(Activity activity, int action, int defaultKeyCode, int keyAction) {
		if (activity == null || activity.isFinishing()) return;
		long now = SystemClock.uptimeMillis();
		int keyCode = defaultKeyCode;

		// 桌面环境下尝试优先使用自定义绑定的键码
		if (activity instanceof KeydroidxDesktopActivity) {
			KeydroidxKeyBinding binding = ((KeydroidxDesktopActivity) activity).getKeyBinding();
			if (binding != null && action >= 0) {
				int bound = binding.getKeyCode(action);
				if (KeydroidxKeyBinding.isBound(bound)) {
					keyCode = bound;
				}
			}
		}

		KeyEvent event = new KeyEvent(now, now, keyAction, keyCode, 0);

		// 若当前正有弹窗显示在前台，直接将按键投递给该弹窗，确保弹窗能够直接响应软键/返回/方向键
		Dialog activeDialog = getActiveDialog();
		if (activeDialog != null && activeDialog.isShowing()) {
			KeydroidxLog.i(TAG, "前台有活跃弹窗，直接投递按键 keyCode=" + keyCode + " action=" + keyAction);
			activeDialog.dispatchKeyEvent(event);
			return;
		}

		activity.dispatchKeyEvent(event);
	}

	/**
	 * 切换触屏模式状态时，联动关闭 J2ME-Loader 全局 profile 与所有应用的虚拟键盘配置，
	 * 从数据源根除两套键盘重叠问题。
	 */
	public static void syncTouchModeToJ2meProfiles(Context context, boolean isTouchMode) {
		if (!isTouchMode) return;
		new Thread(() -> {
			try {
				// 1. 关闭全局 Profile 的 ShowKeyboard
				File globalDir = new File(Config.getProfilesDir(), KeydroidxGlobalProfile.PROFILE_NAME);
				if (globalDir.exists()) {
					ProfileModel globalConfig = ProfilesManager.loadConfig(globalDir);
					if (globalConfig != null && globalConfig.showKeyboard) {
						globalConfig.showKeyboard = false;
						ProfilesManager.saveConfig(globalConfig);
						KeydroidxLog.i(TAG, "触屏模式联动：已自动关闭全局 Profile 的虚拟键盘");
					}
				}
				// 2. 遍历已安装应用的配置目录，自动关闭单应用已开启的原生虚拟键盘
				File configsDir = new File(new File(Config.getProfilesDir()).getParentFile(), Config.MIDLET_CONFIGS_DIR);
				if (configsDir.exists() && configsDir.isDirectory()) {
					File[] appDirs = configsDir.listFiles();
					if (appDirs != null) {
						for (File dir : appDirs) {
							if (dir.isDirectory()) {
								ProfileModel appConfig = ProfilesManager.loadConfig(dir);
								if (appConfig != null && appConfig.showKeyboard) {
									appConfig.showKeyboard = false;
									ProfilesManager.saveConfig(appConfig);
									KeydroidxLog.i(TAG, "触屏模式联动：已自动关闭 " + dir.getName() + " 的虚拟键盘");
								}
							}
						}
					}
				}
			} catch (Throwable t) {
				KeydroidxLog.w(TAG, "syncTouchModeToJ2meProfiles 异常: " + t.getMessage());
			}
		}, "sync-touch-profiles").start();
	}
}
