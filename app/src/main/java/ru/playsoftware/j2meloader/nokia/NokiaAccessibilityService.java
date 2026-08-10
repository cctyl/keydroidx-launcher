package ru.playsoftware.j2meloader.nokia;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * 挂机键拦截无障碍服务（方案四：无障碍按键过滤）。
 * <p>
 * 通过 {@link AccessibilityServiceInfo#FLAG_REQUEST_FILTER_KEY_EVENTS} 在 InputDispatcher 层
 * 拦截物理电源键（挂机键，KEYCODE_POWER），使事件先于 {@code PhoneWindowManager} 到达本服务，
 * 实现「挂机键语义」：
 * <ul>
 *   <li>屏幕亮 + 前台非本应用 → 回到诺基亚桌面（不熄屏）；</li>
 *   <li>屏幕亮 + 前台为本应用 → 锁屏（熄屏）；</li>
 *   <li>屏幕灭 → 放行给系统正常唤醒（普通应用无 DEVICE_POWER 权限，无法自行 wakeUp）。</li>
 * </ul>
 * 总闸为桌面设置「挂机键拦截」开关（{@link NokiaSettingsStorage#isPowerKeyInterceptorEnabled}），
 * 关闭时完全放行，系统 power 键行为不受影响。
 * <p>
 * 与 KeyMapper 拦截 power 键的机制一致（FLAG_REQUEST_FILTER_KEY_EVENTS），
 * 无需 root / shell / adb，用户开启无障碍服务后重启依然生效。
 */
public class NokiaAccessibilityService extends AccessibilityService {

	private static final String TAG = "NokiaA11y";

	/** 注入防抖间隔：避免与系统防误触策略冲突导致重复动作。 */
	private static final long INJECT_DEBOUNCE_MS = 1000;

	/**
	 * {@code GLOBAL_ACTION_LOCK_SCREEN} 的整数值（API 28 起新增）。
	 * 用字面量而非常量引用，避免 API &lt; 28 设备上类加载抛 NoSuchFieldError。
	 */
	private static final int GLOBAL_ACTION_LOCK_SCREEN = 8;

	private long lastInjectTime = 0;
	/** 最近一次窗口状态变化的前台包名缓存（无障碍事件自带，无需窗口内容检索权限）。 */
	private String foregroundPackage = null;
	/** 上次 power DOWN 是否被消费（用于配对消费 UP / REPEAT）。 */
	private boolean lastDownConsumed = false;

	@Override
	protected void onServiceConnected() {
		super.onServiceConnected();
		NokiaLog.i(TAG, "挂机键拦截无障碍服务已连接");

		AccessibilityServiceInfo info = new AccessibilityServiceInfo();
		info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
		info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
		// 关键：请求过滤所有按键事件（含 power 键），使其先于 PhoneWindowManager 到达本服务
		info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
		info.notificationTimeout = 0;
		setServiceInfo(info);
	}

	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
		if (event == null || event.getPackageName() == null) return;
		foregroundPackage = event.getPackageName().toString();
		NokiaLog.d(TAG, "前台窗口包名: " + foregroundPackage);
	}

	@Override
	public boolean onKeyEvent(KeyEvent event) {
		// 诊断日志：任何到达本服务的按键都记录，便于确认是否被调用、
		// 是否被其它无障碍服务（如 KeyMapper）抢先消费。
		if (event != null) {
			NokiaLog.d(TAG, "onKeyEvent: keyCode=" + event.getKeyCode()
					+ " action=" + event.getAction());
		}
		if (event == null || event.getKeyCode() != KeyEvent.KEYCODE_POWER) {
			return false; // 只关心挂机键（物理电源键）
		}
		// 总闸：桌面设置「挂机键拦截」关闭时完全放行
		if (!NokiaSettingsStorage.isPowerKeyInterceptorEnabled(this)) {
			return false;
		}

		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			lastDownConsumed = handlePowerKeyDown();
			return lastDownConsumed;
		}
		// UP / REPEAT：与 DOWN 消费状态配对，避免系统基于 UP 再触发一次动作
		return lastDownConsumed;
	}

	/** 处理挂机键按下。返回 true=消费（系统收不到），false=放行（系统默认处理）。 */
	private boolean handlePowerKeyDown() {
		long now = System.currentTimeMillis();
		if (now - lastInjectTime < INJECT_DEBOUNCE_MS) {
			NokiaLog.d(TAG, "防抖：距上次注入不足 " + INJECT_DEBOUNCE_MS + "ms，消费但跳过注入");
			return true;
		}

		if (!isScreenOn()) {
			// 屏幕灭：放行给系统正常唤醒（普通应用无 DEVICE_POWER 权限无法自行 wakeUp）
			NokiaLog.i(TAG, "屏幕灭，放行系统唤醒");
			return false;
		}

		lastInjectTime = now;

		if (isForegroundSelf()) {
			NokiaLog.i(TAG, "屏幕亮 + 前台为本应用 → 锁屏");
			injectLockScreen();
		} else {
			NokiaLog.i(TAG, "屏幕亮 + 前台非本应用 → 回桌面");
			injectBackToDesktop();
		}
		return true;
	}

	/** 回桌面：精确拉起 NokiaDesktopActivity（不依赖 HOME 归属，比发 HOME intent 更可靠）。 */
	private void injectBackToDesktop() {
		try {
			Intent intent = new Intent(this, NokiaDesktopActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
					| Intent.FLAG_ACTIVITY_SINGLE_TOP
					| Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
			startActivity(intent);
			NokiaLog.i(TAG, "回桌面注入完成");
		} catch (Exception e) {
			NokiaLog.e(TAG, "回桌面注入失败", e);
		}
	}

	/** 锁屏：优先无障碍全局动作（API 28+），备选设备管理员 lockNow（需已激活）。 */
	private void injectLockScreen() {
		if (Build.VERSION.SDK_INT >= 28) {
			try {
				if (performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) {
					NokiaLog.i(TAG, "无障碍全局动作锁屏成功");
					return;
				}
				NokiaLog.w(TAG, "无障碍全局动作锁屏未生效，尝试设备管理员");
			} catch (Throwable t) {
				NokiaLog.e(TAG, "无障碍全局动作锁屏异常", t);
			}
		}
		try {
			DevicePolicyManager dpm =
					(DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName admin = new ComponentName(this, NokiaDeviceAdminReceiver.class);
			if (dpm != null && dpm.isAdminActive(admin)) {
				dpm.lockNow();
				NokiaLog.i(TAG, "设备管理员 lockNow 锁屏成功");
				return;
			}
			NokiaLog.w(TAG, "设备管理员未激活，锁屏通道不可用");
		} catch (Throwable t) {
			NokiaLog.e(TAG, "设备管理员锁屏异常", t);
		}
	}

	/** 屏幕是否亮（5.0+：isInteractive；4.4：isScreenOn 反射）。 */
	private boolean isScreenOn() {
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		if (pm == null) return true;
		if (Build.VERSION.SDK_INT >= 20) {
			return pm.isInteractive();
		}
		try {
			return (Boolean) PowerManager.class.getMethod("isScreenOn").invoke(pm);
		} catch (Throwable t) {
			return true; // 解析失败保守按亮屏
		}
	}

	/** 前台是否为本应用（兼容 debug/release 包名后缀，用前缀匹配）。 */
	private boolean isForegroundSelf() {
		if (foregroundPackage == null) {
			// 缓存未知时用 getRunningTasks 兜底（4.4 可用；5.0+ 受限可能只返回自身任务）
			try {
				ActivityManager am =
						(ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
				List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
				if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
					foregroundPackage = tasks.get(0).topActivity.getPackageName();
					NokiaLog.d(TAG, "getRunningTasks 兜底获取前台包名: " + foregroundPackage);
				}
			} catch (Throwable ignored) {
			}
		}
		return foregroundPackage != null
				&& foregroundPackage.startsWith("io.github.cctyl.nokia");
	}

	/**
	 * 无障碍服务是否已在系统无障碍设置中启用。
	 * 供桌面设置页检测状态并引导用户开启。
	 */
	public static boolean isServiceEnabled(Context context) {
		String serviceName = context.getPackageName() + "/"
				+ NokiaAccessibilityService.class.getName();
		String enabled = Settings.Secure.getString(context.getContentResolver(),
				Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
		return enabled != null && enabled.contains(serviceName);
	}

	@Override
	public void onInterrupt() {
		NokiaLog.w(TAG, "挂机键拦截无障碍服务被中断");
	}
}
