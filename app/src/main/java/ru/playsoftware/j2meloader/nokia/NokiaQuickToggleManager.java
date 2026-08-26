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

	public static boolean isBrightnessHigh(Context context) {
		try {
			int val = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
			return val > 150;
		} catch (Exception e) {
			return false;
		}
	}

	public static void toggleBrightness(final Context context) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				int cur = 128;
				try {
					cur = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
				} catch (Exception ignored) {}

				int target;
				if (cur < 80) {
					target = 150; // 中等
				} else if (cur < 200) {
					target = 255; // 最亮
				} else {
					target = 30;  // 最暗
				}

				if (Shizuku.isRunning()) {
					Shizuku.exec("settings put system screen_brightness " + target);
					return;
				}

				try {
					Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, target);
				} catch (Exception e) {
					openSettings(context, Settings.ACTION_DISPLAY_SETTINGS);
				}
			}
		}).start();
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
