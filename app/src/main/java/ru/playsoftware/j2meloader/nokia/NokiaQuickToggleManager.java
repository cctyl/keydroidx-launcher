package ru.playsoftware.j2meloader.nokia;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;
import java.lang.reflect.Method;

import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 诺基亚快捷开关执行与状态管理工具类。
 * 遵循三级降级策略：
 * 1. mini_shizuku / ADB Shell 权限（静默、无弹窗、全系统版本一致生效）
 * 2. 系统标准原生 API（速度快，旧版系统免权限，新版系统可能触发系统单次确认弹窗）
 * 3. 跳转系统设置页（保底容错，避免任何异常崩溃）
 */
public class NokiaQuickToggleManager {

	private static final String TAG = "NokiaQuickToggle";

	// 手电筒状态记录与旧版 Camera 句柄
	private static boolean torchOn = false;
	private static Camera legacyCamera = null;

	// ==================== mini_shizuku 在线状态缓存 ====================
	//
	// Shizuku.isRunning() 内部是一次 TCP 连接（见 MiniShizuku），在主线程调用会抛
	// NetworkOnMainThreadException。而开关图标的渲染（buildToggleBar / renderToggleViews）
	// 都跑在主线程且需要据此决定亮度图标，因此这里缓存检测结果，UI 只读缓存，
	// 真正的探测一律放在后台线程。

	/** 缓存的 mini_shizuku 在线状态；null = 尚未探测过。 */
	private static volatile Boolean sShizukuRunning = null;

	/**
	 * 读取缓存的 mini_shizuku 在线状态。<b>不会</b>发起连接，可安全在主线程调用。
	 * 尚未探测过时返回 false（按"未激活"处理，图标显示 brightness_low）。
	 */
	public static boolean isShizukuRunningCached() {
		Boolean v = sShizukuRunning;
		return v != null && v;
	}

	/** mini_shizuku 状态是否已被探测过（供 UI 判断是否需要刷新）。 */
	public static boolean isShizukuStateKnown() {
		return sShizukuRunning != null;
	}

	/**
	 * 在后台线程探测 mini_shizuku 在线状态并更新缓存。
	 * 状态发生变化时回调 {@code onChanged}（主线程），调用方可据此重绘图标。
	 *
	 * @param onChanged 可为 null；仅在状态确实变化（或首次探测出结果）时回调
	 */
	public static void refreshShizukuStateAsync(final Runnable onChanged) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean now;
				try {
					now = Shizuku.isRunning();
				} catch (Throwable t) {
					// 探测本身就是网络操作，任何异常都按"离线"处理，绝不让 UI 崩
					NokiaLog.w(TAG, "探测 mini_shizuku 状态失败，按离线处理", t);
					return;
				}
				Boolean old = sShizukuRunning;
				sShizukuRunning = now;
				NokiaLog.i(TAG, "mini_shizuku 状态: " + old + " -> " + now);
				if (onChanged != null && (old == null || old != now)) {
					new Handler(Looper.getMainLooper()).post(onChanged);
				}
			}
		}, "shizuku-probe").start();
	}

	// ==================== 统一状态与操作分发 ====================

	public static boolean isToggleOn(Context context, int type) {
		switch (type) {
			case NokiaQuickToggleItem.TYPE_WIFI:
				return isWifiOn(context);
			case NokiaQuickToggleItem.TYPE_DATA:
				return isMobileDataOn(context);
			case NokiaQuickToggleItem.TYPE_BLUETOOTH:
				return isBluetoothOn();
			case NokiaQuickToggleItem.TYPE_AIRPLANE:
				return isAirplaneModeOn(context);
			case NokiaQuickToggleItem.TYPE_TORCH:
				return isTorchOn();
			case NokiaQuickToggleItem.TYPE_SOUND:
				return isSoundOn(context);
			case NokiaQuickToggleItem.TYPE_ROTATE:
				return isRotateOn(context);
			case NokiaQuickToggleItem.TYPE_LOCK:
				return false; // 一键锁屏为瞬态操作
			case NokiaQuickToggleItem.TYPE_BRIGHTNESS:
				return isBrightnessHigh(context);
			case NokiaQuickToggleItem.TYPE_LOCATION:
				return isLocationOn(context);
			case NokiaQuickToggleItem.TYPE_HOTSPOT:
				return isHotspotOn(context);
			case NokiaQuickToggleItem.TYPE_SAVER:
				return isSaverOn(context);
			case NokiaQuickToggleItem.TYPE_FREEZE:
			case NokiaQuickToggleItem.TYPE_UNFREEZE:
			case NokiaQuickToggleItem.TYPE_CLEAN_BG:
				return false; // 一键冻结 / 一键解冻 / 清理后台为瞬态动作
			case NokiaQuickToggleItem.TYPE_SHUTDOWN:
			case NokiaQuickToggleItem.TYPE_REBOOT:
			case NokiaQuickToggleItem.TYPE_RECOVERY:
			case NokiaQuickToggleItem.TYPE_FASTBOOT:
				return false; // 电源类为瞬态动作，无持续开关态
			default:
				return false;
		}
	}

	public static void toggle(Context context, int type, boolean targetOn) {
		switch (type) {
			case NokiaQuickToggleItem.TYPE_WIFI:
				toggleWifi(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_DATA:
				toggleMobileData(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_BLUETOOTH:
				toggleBluetooth(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_AIRPLANE:
				toggleAirplaneMode(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_TORCH:
				toggleTorch(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_SOUND:
				toggleSound(context);
				break;
			case NokiaQuickToggleItem.TYPE_ROTATE:
				toggleRotate(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_LOCK:
				toggleLock(context);
				break;
			case NokiaQuickToggleItem.TYPE_BRIGHTNESS:
				toggleBrightness(context);
				break;
			case NokiaQuickToggleItem.TYPE_LOCATION:
				toggleLocation(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_HOTSPOT:
				toggleHotspot(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_SAVER:
				toggleSaver(context, targetOn);
				break;
			case NokiaQuickToggleItem.TYPE_FREEZE:
				toggleFreezeAll(context);
				break;
			case NokiaQuickToggleItem.TYPE_UNFREEZE:
				toggleUnfreezeAll(context);
				break;
			case NokiaQuickToggleItem.TYPE_CLEAN_BG:
				toggleCleanBg(context);
				break;
			case NokiaQuickToggleItem.TYPE_SHUTDOWN:
				toggleShutdown(context);
				break;
			case NokiaQuickToggleItem.TYPE_REBOOT:
				toggleReboot(context);
				break;
			case NokiaQuickToggleItem.TYPE_RECOVERY:
				toggleRebootRecovery(context);
				break;
			case NokiaQuickToggleItem.TYPE_FASTBOOT:
				toggleRebootFastboot(context);
				break;
		}
	}

	// ==================== 1. Wi-Fi / WLAN ====================

	public static boolean isWifiOn(Context context) {
		try {
			WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			if (wm == null) return false;
			int state = wm.getWifiState();
			return state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING;
		} catch (Exception e) {
			return false;
		}
	}

	public static void toggleWifi(final Context context, final boolean targetOn) {
		if (Build.VERSION.SDK_INT < 29) {
			try {
				WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
				if (wm != null) {
					boolean ret = wm.setWifiEnabled(targetOn);
					if (ret) return;
				}
			} catch (Exception e) {
				NokiaLog.w(TAG, "wifi setWifiEnabled failed: " + e.getMessage());
			}
		}

		new Thread(new Runnable() {
			@Override
			public void run() {
				if (Shizuku.isRunning()) {
					String cmd = "svc wifi " + (targetOn ? "enable" : "disable");
					boolean res = Shizuku.exec(cmd);
					NokiaLog.i(TAG, "Shizuku wifi toggle: " + res);
					if (res) return;
				}

				try {
					WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
					if (wm != null && wm.setWifiEnabled(targetOn)) {
						return;
					}
				} catch (Exception ignored) {}

				openSettings(context, Settings.ACTION_WIFI_SETTINGS);
			}
		}).start();
	}

	// ==================== 2. 移动数据 (Mobile Data) ====================

	public static boolean isMobileDataOn(Context context) {
		try {
			return Settings.Global.getInt(context.getContentResolver(), "mobile_data", 0) == 1;
		} catch (Exception e) {
			try {
				return Settings.Secure.getInt(context.getContentResolver(), "mobile_data", 0) == 1;
			} catch (Exception ignored) {
				return false;
			}
		}
	}

	public static void toggleMobileData(final Context context, final boolean targetOn) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (Shizuku.isRunning()) {
					String cmd = "svc data " + (targetOn ? "enable" : "disable")
							+ " ; settings put global mobile_data " + (targetOn ? "1" : "0");
					boolean res = Shizuku.exec(cmd);
					NokiaLog.i(TAG, "Shizuku data toggle: " + res);
					return;
				}

				try {
					ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
					if (cm != null) {
						Method method = cm.getClass().getDeclaredMethod("setMobileDataEnabled", boolean.class);
						method.setAccessible(true);
						method.invoke(cm, targetOn);
						return;
					}
				} catch (Exception e) {
					NokiaLog.w(TAG, "setMobileDataEnabled failed: " + e.getMessage());
				}

				openSettings(context, Settings.ACTION_DATA_ROAMING_SETTINGS);
			}
		}).start();
	}

	// ==================== 3. 蓝牙 (Bluetooth) ====================

	public static boolean isBluetoothOn() {
		try {
			BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
			if (adapter == null) return false;
			int state = adapter.getState();
			return state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON;
		} catch (Exception e) {
			return false;
		}
	}

	public static void toggleBluetooth(final Context context, final boolean targetOn) {
		if (Build.VERSION.SDK_INT < 33) {
			try {
				BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
				if (adapter != null) {
					boolean ret = targetOn ? adapter.enable() : adapter.disable();
					if (ret) return;
				}
			} catch (Exception e) {
				NokiaLog.w(TAG, "bluetooth enable/disable failed: " + e.getMessage());
			}
		}

		new Thread(new Runnable() {
			@Override
			public void run() {
				if (Shizuku.isRunning()) {
					String cmd = "svc bluetooth " + (targetOn ? "enable" : "disable")
							+ " ; cmd bluetooth_manager " + (targetOn ? "enable" : "disable");
					boolean res = Shizuku.exec(cmd);
					NokiaLog.i(TAG, "Shizuku bluetooth toggle: " + res);
					if (res) return;
				}

				try {
					BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
					if (adapter != null) {
						boolean ret = targetOn ? adapter.enable() : adapter.disable();
						if (ret) return;
					}
				} catch (Exception ignored) {}

				openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS);
			}
		}).start();
	}

	// ==================== 4. 飞行模式 (Airplane Mode) ====================

	public static boolean isAirplaneModeOn(Context context) {
		try {
			return Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
		} catch (Exception e) {
			try {
				return Settings.System.getInt(context.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) != 0;
			} catch (Exception ignored) {
				return false;
			}
		}
	}

	public static void toggleAirplaneMode(final Context context, final boolean targetOn) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (Shizuku.isRunning()) {
					String cmd = "settings put global airplane_mode_on " + (targetOn ? "1" : "0")
							+ " ; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + targetOn;
					boolean res = Shizuku.exec(cmd);
					NokiaLog.i(TAG, "Shizuku airplane toggle: " + res);
					if (res) return;
				}

				try {
					Settings.Global.putInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, targetOn ? 1 : 0);
					Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
					intent.putExtra("state", targetOn);
					context.sendBroadcast(intent);
					return;
				} catch (Exception e) {
					NokiaLog.w(TAG, "write airplane_mode_on failed: " + e.getMessage());
				}

				// 尝试 root 切换（针对 4.4 等已 root 设备）
				try {
					String suCmd = "settings put global airplane_mode_on " + (targetOn ? "1" : "0")
							+ " ; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + targetOn;
					Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", suCmd});
					p.waitFor();
					if (p.exitValue() == 0) return;
				} catch (Exception ignored) {}

				openSettings(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS);
			}
		}).start();
	}

	// ==================== 5. 手电筒 (Flashlight / Torch) ====================

	public static boolean isTorchOn() {
		return torchOn;
	}

	public static void toggleTorch(final Context context, final boolean targetOn) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				torchOn = targetOn;
				// Android 6.0+ (API 23+)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					try {
						CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
						if (cm != null) {
							String[] ids = cm.getCameraIdList();
							if (ids != null && ids.length > 0) {
								cm.setTorchMode(ids[0], targetOn);
								return;
							}
						}
					} catch (Throwable t) {
						NokiaLog.w(TAG, "CameraManager setTorchMode error: " + t.getMessage());
					}
				}

				// Android 4.4 - 5.1 兼容 Camera API
				try {
					if (targetOn) {
						if (legacyCamera == null) {
							legacyCamera = Camera.open();
						}
						if (legacyCamera != null) {
							Camera.Parameters p = legacyCamera.getParameters();
							p.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
							legacyCamera.setParameters(p);
							legacyCamera.startPreview();
						}
					} else {
						if (legacyCamera != null) {
							Camera.Parameters p = legacyCamera.getParameters();
							p.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
							legacyCamera.setParameters(p);
							legacyCamera.stopPreview();
							legacyCamera.release();
							legacyCamera = null;
						}
					}
				} catch (Throwable t) {
					NokiaLog.w(TAG, "Legacy camera torch error: " + t.getMessage());
					if (legacyCamera != null) {
						try { legacyCamera.release(); } catch (Exception ignored) {}
						legacyCamera = null;
					}
				}
			}
		}).start();
	}

	// ==================== 6. 情景模式 / 声音 (Sound Profile) ====================

	public static boolean isSoundOn(Context context) {
		try {
			AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			return am != null && am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
		} catch (Exception e) {
			return true;
		}
	}

	public static void toggleSound(Context context) {
		try {
			AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			if (am != null) {
				int mode = am.getRingerMode();
				int nextMode;
				String toastMsg;
				if (mode == AudioManager.RINGER_MODE_NORMAL) {
					nextMode = AudioManager.RINGER_MODE_VIBRATE;
					toastMsg = "已切换为震动模式";
				} else if (mode == AudioManager.RINGER_MODE_VIBRATE) {
					nextMode = AudioManager.RINGER_MODE_SILENT;
					toastMsg = "已切换为静音模式";
				} else {
					nextMode = AudioManager.RINGER_MODE_NORMAL;
					toastMsg = "已切换为标准响铃";
				}
				am.setRingerMode(nextMode);
				Toast.makeText(context.getApplicationContext(), toastMsg, Toast.LENGTH_SHORT).show();
			}
		} catch (Exception e) {
			openSettings(context, Settings.ACTION_SOUND_SETTINGS);
		}
	}

	// ==================== 7. 自动旋转屏幕 (Auto Rotate) ====================

	public static boolean isRotateOn(Context context) {
		try {
			return Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 1;
		} catch (Exception e) {
			return true;
		}
	}

	public static void toggleRotate(final Context context, final boolean targetOn) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (Shizuku.isRunning()) {
					String cmd = "settings put system accelerometer_rotation " + (targetOn ? "1" : "0");
					Shizuku.exec(cmd);
					return;
				}

				try {
					Settings.System.putInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, targetOn ? 1 : 0);
				} catch (Exception e) {
					openSettings(context, Settings.ACTION_DISPLAY_SETTINGS);
				}
			}
		}).start();
	}

	// ==================== 8. 一键锁屏 (Lock Screen) ====================

	public static void toggleLock(Context context) {
		NokiaLockScreen.lock(context);
	}

		// ==================== 9. 屏幕亮度 (Brightness) ====================
	//
	// 亮度是四档循环（低 → 中 → 高 → 自动 → 低），图标随当前档位在同一套太阳图标里切换：
	// brightness_low / brightness_medium / brightness_high / brightness_auto。

	/** 亮度档位：低。 */
	public static final int LEVEL_LOW = 30;
	/** 亮度档位：中。 */
	public static final int LEVEL_MEDIUM = 150;
	/** 亮度档位：高。 */
	public static final int LEVEL_HIGH = 255;
	/** 亮度档位：跟随系统自动亮度（无具体数值）。 */
	public static final int LEVEL_AUTO = -1;
	/** 档位未知，需从系统读取。 */
	private static final int LEVEL_UNKNOWN = -2;

	/**
	 * 最近一次已知的亮度档位。
	 * <p>
	 * 存在的意义：写入亮度是异步的（shell 或 Settings 都可能延迟生效），
	 * 而切换后图标要<b>立即</b>更新；若此时回读系统会拿到切换前的旧值，
	 * 图标就会慢一拍。因此在切档时同步写入该缓存，图标优先读它。
	 */
	private static volatile int sBrightnessLevel = LEVEL_UNKNOWN;

	/** 从系统读取当前亮度档位（自动档优先判断）。 */
	private static int readSystemBrightnessLevel(Context context) {
		try {
			int mode = Settings.System.getInt(context.getContentResolver(),
					Settings.System.SCREEN_BRIGHTNESS_MODE,
					Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
			if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
				return LEVEL_AUTO;
			}
		} catch (Exception ignored) {
		}
		int val = 128;
		try {
			val = Settings.System.getInt(context.getContentResolver(),
					Settings.System.SCREEN_BRIGHTNESS, 128);
		} catch (Exception ignored) {
		}
		if (val >= 200) return LEVEL_HIGH;
		if (val >= 80) return LEVEL_MEDIUM;
		return LEVEL_LOW;
	}

	/**
	 * 当前亮度档位。
	 * <p>
	 * 读取策略：<b>整个进程生命周期内只读一次系统</b>，之后一律走缓存。
	 * <ul>
	 *   <li>首次调用（冷启动后）：读一次 {@link Settings.System} 作为初始档位并缓存；</li>
	 *   <li>之后：直接返回缓存，不再触碰系统设置。</li>
	 * </ul>
	 * 之所以可以这么做，是因为本开关按「用户不会通过其它途径修改亮度」来设计：
	 * 档位的唯一变更来源是 {@link #toggleBrightness}，而它在切档时会同步更新缓存，
	 * 因此缓存与系统始终一致。
	 * <p>
	 * 这样既避免了每次渲染都读系统（{@code renderToggleViews} 在主线程，
	 * 读 {@code Settings.System} 是一次 Binder 调用，频繁调用会拖慢桌面刷新），
	 * 也保证图标在切换后能立即反映新档位。
	 * <p>
	 * 代价：若用户在系统设置/下拉面板里手动改了亮度，本开关的图标会停留在旧档位，
	 * 直到下次冷启动或用户在本开关上切一次档。这是刻意接受的取舍。
	 */
	public static int currentBrightnessLevel(Context context) {
		// 未激活 mini_shizuku 时本开关无法调节亮度（点击会跳系统设置页），
		// 此时一律按"低"显示图标，不读系统真实值——否则会显示成 high/medium，
		// 与"点了没反应"的实际行为不符。
		//
		// 注意：这里走的是缓存状态而非 Shizuku.isRunning()。本方法在主线程渲染图标时
		// 被调用（buildToggleBar → getIconUnicode → getBrightnessIconUnicode），
		// 而 isRunning() 内部是 socket 连接，在主线程调用会抛 NetworkOnMainThreadException。
		if (!isShizukuRunningCached()) {
			return LEVEL_LOW;
		}
		int cached = sBrightnessLevel;
		if (cached != LEVEL_UNKNOWN) {
			return cached;
		}
		int level = context == null ? LEVEL_MEDIUM : readSystemBrightnessLevel(context);
		// 首次读取后即固化：此后不再回读系统（见上面说明）
		sBrightnessLevel = level;
		NokiaLog.i(TAG, "亮度初始档位: " + brightnessLevelName(level));
		return level;
	}

	/** 按当前亮度档位返回对应图标（四个太阳图标属同一套设计语言）。 */
	public static String getBrightnessIconUnicode(Context context) {
		switch (currentBrightnessLevel(context)) {
			case LEVEL_AUTO: return NokiaIcons.TOGGLE_BRIGHTNESS_AUTO;
			case LEVEL_HIGH: return NokiaIcons.TOGGLE_BRIGHTNESS_HIGH;
			case LEVEL_MEDIUM: return NokiaIcons.TOGGLE_BRIGHTNESS_MEDIUM;
			case LEVEL_LOW:
			default: return NokiaIcons.TOGGLE_BRIGHTNESS_LOW;
		}
	}

	/** 亮度档位名称，仅用于日志/提示。 */
	private static String brightnessLevelName(int level) {
		switch (level) {
			case LEVEL_AUTO: return "自动";
			case LEVEL_HIGH: return "高";
			case LEVEL_MEDIUM: return "中";
			case LEVEL_LOW: return "低";
			default: return "未知";
		}
	}

	/**
	 * 亮度的开关态指示。
	 * 只有最低档视为"关"——亮度本身不是二值开关，图标已能表达档位，
	 * 这个小圆点只用来区分"是否处在最暗"。
	 */
	public static boolean isBrightnessHigh(Context context) {
		return currentBrightnessLevel(context) != LEVEL_LOW;
	}

	public static void toggleBrightness(final Context context) {
		if (context == null) return;

		// 本方法由点击触发、运行在<b>主线程</b>。
		//
		// 关键约束（曾据此踩过两次坑，改动前务必看清）：
		//   1) Shizuku.isRunning() / Shizuku.exec() 是 socket 操作，主线程调用会抛
		//      NetworkOnMainThreadException —— 只能放后台线程；
		//   2) 但档位推进与缓存写入<b>必须留在主线程同步完成</b>。调用方在 toggle() 返回后
		//      紧接着就会重绘图标，若把 sBrightnessLevel 的更新丢进子线程，
		//      重绘读到的仍是旧档位，于是出现"图标比 Toast 提示慢一档"的错位。
		//
		// 因此这里的划分是：档位计算/缓存/提示在主线程，仅命令下发在后台。

		// 未激活（按缓存判断，不发起连接）：不推进档位，交系统设置页处理。
		if (!isShizukuRunningCached()) {
			NokiaLog.w(TAG, "mini_shizuku 未激活(缓存)，亮度改为跳转系统设置页");
			// 后台探测一次校准缓存：若用户其实已激活但缓存陈旧，下次点击即可正常切换。
			refreshShizukuStateAsync(null);
			showToastOnMain(context, "需要 mini_shizuku 才能调节亮度");
			openSettings(context, Settings.ACTION_DISPLAY_SETTINGS);
			return;
		}

		// 主线程同步推进档位：低 → 中 → 高 → 自动 → 低
		final int cur = currentBrightnessLevel(context);
		final int target;
		if (cur == LEVEL_AUTO) target = LEVEL_LOW;
		else if (cur == LEVEL_LOW) target = LEVEL_MEDIUM;
		else if (cur == LEVEL_MEDIUM) target = LEVEL_HIGH;
		else target = LEVEL_AUTO;

		// 同步写入缓存，保证 toggle() 返回后图标重绘能拿到新档位（与下面的 Toast 文案一致）
		sBrightnessLevel = target;
		NokiaLog.i(TAG, "亮度档位: " + brightnessLevelName(cur)
				+ " -> " + brightnessLevelName(target));
		showToastOnMain(context, "亮度：" + brightnessLevelName(target));

		// 只有真正的命令下发需要后台线程
		new Thread(new Runnable() {
			@Override
			public void run() {
				dispatchBrightnessCommand(context, target);
			}
		}, "toggle-brightness").start();
	}

	/**
	 * 向 mini_shizuku 下发亮度命令。
	 * <b>调用方需保证运行在后台线程</b>（内部是 socket 操作）。
	 */
	private static void dispatchBrightnessCommand(final Context context, final int target) {
		String cmd = (target == LEVEL_AUTO)
				? "settings put system screen_brightness_mode 1"
				: "settings put system screen_brightness_mode 0"
					+ " ; settings put system screen_brightness " + target;
		boolean sent = Shizuku.exec(cmd);
		NokiaLog.i(TAG, "Shizuku 亮度写入: " + sent);
		if (sent) {
			return;
		}
		// 服务在线但写入失败：回退档位并交系统设置页。
		// 这里在子线程改了档位却无法立即重绘，但用户从设置页返回桌面时
		// onResume 会 renderToggleViews()，图标会被自动修正。
		NokiaLog.w(TAG, "亮度写入失败，跳转系统设置页");
		sBrightnessLevel = LEVEL_LOW;
		showToastOnMain(context, "亮度写入失败");
		openSettings(context, Settings.ACTION_DISPLAY_SETTINGS);
	}

	// ==================== 10. 位置信息 / GPS ====================

	public static boolean isLocationOn(Context context) {
		if (context == null) return false;
		// 1. Android 4.4+ (API 19+) 官方推荐首选：查询 Secure.LOCATION_MODE
		if (Build.VERSION.SDK_INT >= 19) {
			try {
				int mode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
				return mode != Settings.Secure.LOCATION_MODE_OFF;
			} catch (Exception ignored) {}
		}

		// 2. 读取 LOCATION_PROVIDERS_ALLOWED
		try {
			String allowed = Settings.Secure.getString(context.getContentResolver(),
					Settings.Secure.LOCATION_PROVIDERS_ALLOWED);
			if (allowed != null) {
				allowed = allowed.trim();
				if (allowed.isEmpty()) return false;
				boolean hasGps = allowed.contains("gps") && !allowed.contains("-gps");
				boolean hasNetwork = allowed.contains("network") && !allowed.contains("-network");
				return hasGps || hasNetwork;
			}
		} catch (Exception ignored) {}

		// 3. 降级：仅检查 GPS_PROVIDER
		try {
			LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
			if (lm != null) {
				return lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
			}
		} catch (Exception ignored) {}
		return false;
	}

	public static void toggleLocation(final Context context, final boolean targetOn) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				int mode = targetOn ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF;
				String providers = targetOn ? "gps,network" : "";

				if (Shizuku.isRunning()) {
					String cmd = "settings put secure location_mode " + mode
							+ " ; settings put secure location_providers_allowed \"" + providers + "\"";
					if (Build.VERSION.SDK_INT >= 24) {
						cmd += " ; cmd location set-location-enabled " + (targetOn ? "true" : "false");
					}
					boolean res = Shizuku.exec(cmd);
					if (res) return;
				}

				// 尝试 root 切换（针对 4.4 等已 root 设备）
				try {
					String suCmd = "settings put secure location_mode " + mode
							+ " ; settings put secure location_providers_allowed '" + providers + "'";
					Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", suCmd});
					p.waitFor();
					if (p.exitValue() == 0) return;
				} catch (Exception ignored) {}

				openSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS);
			}
		}).start();
	}

	// ==================== 11. 个人热点 (Hotspot / AP) ====================

	public static boolean isHotspotOn(Context context) {
		if (context == null) return false;
		try {
			WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			if (wm != null) {
				// 优先尝试 isWifiApEnabled (Android 8.0+)
				try {
					Method isApEnabled = wm.getClass().getDeclaredMethod("isWifiApEnabled");
					isApEnabled.setAccessible(true);
					return Boolean.TRUE.equals(isApEnabled.invoke(wm));
				} catch (Exception ignored) {}

				// 尝试 getWifiApState (Android 4.4 ~ 7.1)
				try {
					Method getApState = wm.getClass().getDeclaredMethod("getWifiApState");
					getApState.setAccessible(true);
					int state = (Integer) getApState.invoke(wm);
					return state == 12 || state == 13; // 12=ENABLING, 13=ENABLED
				} catch (Exception ignored) {}
			}
		} catch (Exception ignored) {}

		// 检查网络接口是否处于热点状态 (wlan/ap/softap)
		try {
			ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
			if (cm != null) {
				Method getTetheredIfaces = cm.getClass().getDeclaredMethod("getTetheredIfaces");
				getTetheredIfaces.setAccessible(true);
				String[] ifaces = (String[]) getTetheredIfaces.invoke(cm);
				if (ifaces != null) {
					for (String iface : ifaces) {
						if (iface != null && (iface.contains("ap") || iface.contains("softap") || iface.contains("wlan"))) {
							return true;
						}
					}
				}
			}
		} catch (Exception ignored) {}

		return false;
	}

	public static void toggleHotspot(final Context context, final boolean targetOn) {
		// 1. Android 4.4 ~ 7.1 原生反射支持
		try {
			WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
			if (wm != null) {
				// 开启热点时在 4.4 上若 WiFi 开启需先关闭 WiFi
				if (targetOn && wm.isWifiEnabled()) {
					wm.setWifiEnabled(false);
				}
				Method setAp = wm.getClass().getDeclaredMethod("setWifiApEnabled",
						Class.forName("android.net.wifi.WifiConfiguration"), boolean.class);
				setAp.setAccessible(true);
				Boolean res = (Boolean) setAp.invoke(wm, null, targetOn);
				if (Boolean.TRUE.equals(res)) {
					return;
				}
			}
		} catch (Exception ignored) {}

		// 2. Android 8.0+ 若关闭热点，尝试 stopTethering
		if (!targetOn) {
			try {
				ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
				if (cm != null) {
					Method stopTethering = cm.getClass().getDeclaredMethod("stopTethering", int.class);
					stopTethering.setAccessible(true);
					stopTethering.invoke(cm, 0);
					return;
				}
			} catch (Exception ignored) {}
		}

		// 3. Fallback: 调起系统热点/网络共享设置页
		Intent tetherIntent = new Intent();
		tetherIntent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
		tetherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			context.startActivity(tetherIntent);
			return;
		} catch (Exception ignored) {}

		openSettings(context, Settings.ACTION_WIRELESS_SETTINGS);
	}

	// ==================== 12. 省电模式 (Battery Saver) ====================

	public static boolean isSaverOn(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			try {
				PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
				return pm != null && pm.isPowerSaveMode();
			} catch (Exception ignored) {}
		}
		try {
			return Settings.Global.getInt(context.getContentResolver(), "low_power", 0) == 1;
		} catch (Exception ignored) {
			return false;
		}
	}

	public static void toggleSaver(final Context context, final boolean targetOn) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (Shizuku.isRunning()) {
					String cmd = "cmd power set-mode " + (targetOn ? "1" : "0")
							+ " ; settings put global low_power " + (targetOn ? "1" : "0");
					boolean res = Shizuku.exec(cmd);
					if (res) return;
				}

				// 尝试 root 切换
				try {
					String suCmd = "settings put global low_power " + (targetOn ? "1" : "0");
					Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", suCmd});
					p.waitFor();
					if (p.exitValue() == 0) return;
				} catch (Exception ignored) {}

				openSettings(context, Settings.ACTION_BATTERY_SAVER_SETTINGS);
			}
		}).start();
	}

	// ==================== 13. 一键冻结 / 一键解冻 ====================

	public static void toggleFreezeAll(final Context context) {
		if (context == null) return;
		final Context appCtx = context.getApplicationContext();
		NokiaFreezeManager.getInstance(appCtx).freezeAll(null);
	}

	public static void toggleUnfreezeAll(final Context context) {
		if (context == null) return;
		final Context appCtx = context.getApplicationContext();
		NokiaFreezeManager.getInstance(appCtx).unfreezeAll(null);
	}

	// ==================== 14. 清理后台 ====================

	public static void toggleCleanBg(final Context context) {
		if (context == null) return;
		final Context appCtx = context.getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				NokiaSettingsStorage storage = new NokiaSettingsStorage(appCtx);
				java.util.Set<String> protectedSet = storage.getProtectedPackages();
				final int clearedCount = NokiaBgManagerHelper.clearBackgroundTasks(appCtx, protectedSet);
				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						String msg = clearedCount > 0 ? ("已清理 " + clearedCount + " 个后台应用") : "后台已是最新，无须清理";
						Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show();
					}
				});
			}
		}).start();
	}

	// ==================== 15. 电源类：关机 / 重启 / Recovery / Fastboot ====================
	//
	// 均为瞬态、不可逆操作，依赖 mini_shizuku（shell 身份）执行 reboot 命令。
	// 桌面触发前会二次确认（见 NokiaQuickToggleItem#isPowerAction）。

	/** 关机。 */
	public static void toggleShutdown(Context context) {
		execPowerCommand(context, "reboot -p || setprop sys.powerctl shutdown", "关机");
	}

	/** 重启。 */
	public static void toggleReboot(Context context) {
		execPowerCommand(context, "reboot || setprop sys.powerctl reboot", "重启");
	}

	/** 重启到 Recovery。 */
	public static void toggleRebootRecovery(Context context) {
		execPowerCommand(context, "reboot recovery || setprop sys.powerctl reboot,recovery", "重启到 Recovery");
	}

	/** 重启到 Fastboot / Bootloader。 */
	public static void toggleRebootFastboot(Context context) {
		execPowerCommand(context, "reboot bootloader || setprop sys.powerctl reboot,bootloader", "重启到 Fastboot");
	}

	/**
	 * 以 shell 身份执行电源命令。
	 * <p>
	 * 两个要点：
	 * <ul>
	 *   <li>{@code reboot} 成功后系统会立即关机/重启，与 mini_shizuku 服务端的 socket
	 *       随之断开，因此<b>不能等待或回读结果</b>，只能用 {@link Shizuku#exec} 的
	 *       即发即忘语义（只写不读）；用 {@code execWithOutput} 会一直阻塞到连接超时。</li>
	 *   <li>命令串内自带 {@code ||} 回退：部分 ROM 的 toolbox {@code reboot} 不支持
	 *       参数，此时改走 {@code sys.powerctl} 属性。</li>
	 * </ul>
	 */
	private static void execPowerCommand(final Context context, final String command, final String label) {
		if (context == null) return;
		final Context appCtx = context.getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean sent;
				if (!Shizuku.isRunning()) {
					NokiaLog.w(TAG, label + "失败：mini_shizuku 服务未运行");
					sent = false;
				} else {
					sent = Shizuku.exec(command);
					NokiaLog.i(TAG, label + "命令已发送: " + command + " -> " + sent);
				}
				if (!sent) {
					showToastOnMain(appCtx, "需要 mini_shizuku 权限才能" + label);
				} else {
					showToastOnMain(appCtx, "正在" + label + "...");
				}
			}
		}, "power-action").start();
	}

	/** 复用的 Toast 实例：显示新提示前先 cancel 掉上一条。 */
	private static Toast sToast = null;

	/**
	 * 后台线程安全地弹 Toast。
	 * <p>
	 * 复用同一实例并在 show 前 cancel：Toast 内部是队列式入队的，若每次都新建并 show，
	 * 快速连点（亮度四档循环时很容易连点找档位）会让多条提示依次排队显示，
	 * 累计可达数秒，观感上就像单条 Toast 停留过久。cancel 后只保留最新一条。
	 */
	private static void showToastOnMain(final Context appCtx, final String msg) {
		final Context ctx = appCtx == null ? null : appCtx.getApplicationContext();
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				if (ctx == null) return;
				if (sToast != null) {
					sToast.cancel();
				}
				sToast = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT);
				sToast.show();
			}
		});
	}

	// ==================== 辅助方法 ====================

	private static void openSettings(final Context context, final String action) {
		if (context == null) return;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				try {
					Intent intent = new Intent(action);
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					context.startActivity(intent);
				} catch (Exception e) {
					try {
						Intent intent = new Intent(Settings.ACTION_SETTINGS);
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						context.startActivity(intent);
					} catch (Exception ignored) {}
				}
			}
		});
	}
}
