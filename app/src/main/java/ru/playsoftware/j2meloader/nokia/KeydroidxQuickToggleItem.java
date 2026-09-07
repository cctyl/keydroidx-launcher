package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.ui.KeydroidxIcons;

import android.content.Context;

import ru.playsoftware.j2meloader.R;

public class KeydroidxQuickToggleItem {

	public static final int TYPE_WIFI = 0;        // WLAN / WiFi
	public static final int TYPE_DATA = 1;        // 移动数据
	public static final int TYPE_BLUETOOTH = 2;   // 蓝牙
	public static final int TYPE_AIRPLANE = 3;    // 飞行模式
	public static final int TYPE_TORCH = 4;       // 手电筒
	public static final int TYPE_SOUND = 5;       // 情景模式（响铃/震动/静音）
	public static final int TYPE_ROTATE = 6;      // 自动旋转
	public static final int TYPE_LOCK = 7;        // 一键锁屏
	public static final int TYPE_BRIGHTNESS = 8;  // 屏幕亮度
	public static final int TYPE_LOCATION = 9;    // 位置信息 / GPS
	public static final int TYPE_HOTSPOT = 10;    // 个人热点
	public static final int TYPE_SAVER = 11;      // 省电模式
	public static final int TYPE_FREEZE = 12;     // 一键冻结
	public static final int TYPE_UNFREEZE = 13;   // 一键解冻
	public static final int TYPE_CLEAN_BG = 14;   // 清理后台
	// 电源类：需 mini_shizuku（shell）权限，瞬态动作无开关状态，触发前须二次确认
	public static final int TYPE_SHUTDOWN = 15;   // 关机
	public static final int TYPE_REBOOT = 16;     // 重启
	public static final int TYPE_RECOVERY = 17;   // 重启到 Recovery
	public static final int TYPE_FASTBOOT = 18;   // 重启到 Fastboot / Bootloader
	public static final int TYPE_COUNT = 19;

	public final int type;
	public final String id;
	public String name;
	public int iconRes;
	public boolean enabled;

	public KeydroidxQuickToggleItem(int type, String id, String name, int iconRes, boolean enabled) {
		this.type = type;
		this.id = id;
		this.name = name;
		this.iconRes = iconRes;
		this.enabled = enabled;
	}

	/**
	 * 获取图标字符（无 Context 版本）。
	 * 仅用于拿不到 Context 的兜底场景：对亮度这类<b>随状态变化</b>的开关，
	 * 只能返回默认档位的图标。需要正确反映当前状态时请用
	 * {@link #getIconUnicode(Context)}。
	 */
	public String getIconUnicode() {
		return getIconUnicode(null);
	}

	/**
	 * 获取图标字符，传入 Context 以便按当前系统状态返回合适的图标。
	 * 目前只有亮度会随档位切换图标（低/中/高/自动四档）。
	 */
	public String getIconUnicode(Context context) {
		switch (type) {
			case TYPE_BRIGHTNESS:
				return KeydroidxQuickToggleManager.getBrightnessIconUnicode(context);
			case TYPE_WIFI: return KeydroidxIcons.TOGGLE_WIFI;
			case TYPE_DATA: return KeydroidxIcons.TOGGLE_DATA;
			case TYPE_BLUETOOTH: return KeydroidxIcons.TOGGLE_BLUETOOTH;
			case TYPE_AIRPLANE: return KeydroidxIcons.TOGGLE_AIRPLANE;
			case TYPE_TORCH: return KeydroidxIcons.TOGGLE_TORCH;
			case TYPE_SOUND: return KeydroidxIcons.TOGGLE_SOUND;
			case TYPE_ROTATE: return KeydroidxIcons.TOGGLE_ROTATE;
			case TYPE_LOCK: return KeydroidxIcons.TOGGLE_LOCK;
			case TYPE_LOCATION: return KeydroidxIcons.TOGGLE_LOCATION;
			case TYPE_HOTSPOT: return KeydroidxIcons.TOGGLE_HOTSPOT;
			case TYPE_SAVER: return KeydroidxIcons.TOGGLE_SAVER;
			case TYPE_FREEZE: return KeydroidxIcons.TOGGLE_FREEZE;
			case TYPE_UNFREEZE: return KeydroidxIcons.TOGGLE_UNFREEZE;
			case TYPE_CLEAN_BG: return KeydroidxIcons.TOGGLE_CLEAN_BG;
			case TYPE_SHUTDOWN: return KeydroidxIcons.TOGGLE_SHUTDOWN;
			case TYPE_REBOOT: return KeydroidxIcons.TOGGLE_REBOOT;
			case TYPE_RECOVERY: return KeydroidxIcons.TOGGLE_RECOVERY;
			case TYPE_FASTBOOT: return KeydroidxIcons.TOGGLE_FASTBOOT;
			default: return KeydroidxIcons.ICON_SETTINGS;
		}
	}

	/**
	 * 是否为电源类操作（关机 / 重启 / Recovery / Fastboot）。
	 * 这类操作破坏性且不可逆，桌面触发前必须二次确认，且不做开关状态乐观更新。
	 */
	public static boolean isPowerAction(int type) {
		return type == TYPE_SHUTDOWN || type == TYPE_REBOOT
				|| type == TYPE_RECOVERY || type == TYPE_FASTBOOT;
	}

	public static KeydroidxQuickToggleItem createDefault(int type) {
		switch (type) {
			case TYPE_WIFI:
				return new KeydroidxQuickToggleItem(TYPE_WIFI, "wifi", "WLAN", R.drawable.ic_keydroidx_wifi, true);
			case TYPE_DATA:
				return new KeydroidxQuickToggleItem(TYPE_DATA, "data", "移动数据", R.drawable.ic_keydroidx_data, true);
			case TYPE_BLUETOOTH:
				return new KeydroidxQuickToggleItem(TYPE_BLUETOOTH, "bluetooth", "蓝牙", R.drawable.ic_keydroidx_bluetooth, true);
			case TYPE_AIRPLANE:
				return new KeydroidxQuickToggleItem(TYPE_AIRPLANE, "airplane", "飞行模式", R.drawable.ic_keydroidx_airplane, true);
			case TYPE_TORCH:
				return new KeydroidxQuickToggleItem(TYPE_TORCH, "torch", "手电筒", R.drawable.ic_keydroidx_torch, true);
			case TYPE_SOUND:
				return new KeydroidxQuickToggleItem(TYPE_SOUND, "sound", "情景模式", R.drawable.ic_keydroidx_sound, true);
			case TYPE_ROTATE:
				return new KeydroidxQuickToggleItem(TYPE_ROTATE, "rotate", "自动旋转", R.drawable.ic_keydroidx_rotate, true);
			case TYPE_LOCK:
				return new KeydroidxQuickToggleItem(TYPE_LOCK, "lock", "一键锁屏", R.drawable.ic_keydroidx_lock, true);
			case TYPE_BRIGHTNESS:
				return new KeydroidxQuickToggleItem(TYPE_BRIGHTNESS, "brightness", "屏幕亮度", R.drawable.ic_keydroidx_brightness, false);
			case TYPE_LOCATION:
				return new KeydroidxQuickToggleItem(TYPE_LOCATION, "location", "位置信息", R.drawable.ic_keydroidx_location, false);
			case TYPE_HOTSPOT:
				return new KeydroidxQuickToggleItem(TYPE_HOTSPOT, "hotspot", "个人热点", R.drawable.ic_keydroidx_hotspot, false);
			case TYPE_SAVER:
				return new KeydroidxQuickToggleItem(TYPE_SAVER, "saver", "省电模式", R.drawable.ic_keydroidx_saver, false);
			case TYPE_FREEZE:
				return new KeydroidxQuickToggleItem(TYPE_FREEZE, "freeze", "一键冻结", R.drawable.ic_keydroidx_freeze, true);
			case TYPE_UNFREEZE:
				return new KeydroidxQuickToggleItem(TYPE_UNFREEZE, "unfreeze", "一键解冻", R.drawable.ic_keydroidx_unfreeze, false);
			case TYPE_CLEAN_BG:
				return new KeydroidxQuickToggleItem(TYPE_CLEAN_BG, "clean_bg", "清理后台", R.drawable.ic_keydroidx_clean, true);
			// 电源类默认全部关闭：破坏性且不可逆，需用户在设置里主动启用
			case TYPE_SHUTDOWN:
				return new KeydroidxQuickToggleItem(TYPE_SHUTDOWN, "shutdown", "关机", R.drawable.ic_keydroidx_settings, false);
			case TYPE_REBOOT:
				return new KeydroidxQuickToggleItem(TYPE_REBOOT, "reboot", "重启", R.drawable.ic_keydroidx_settings, false);
			case TYPE_RECOVERY:
				return new KeydroidxQuickToggleItem(TYPE_RECOVERY, "recovery", "重启到Recovery", R.drawable.ic_keydroidx_settings, false);
			case TYPE_FASTBOOT:
				return new KeydroidxQuickToggleItem(TYPE_FASTBOOT, "fastboot", "重启到Fastboot", R.drawable.ic_keydroidx_settings, false);
			default:
				return new KeydroidxQuickToggleItem(type, "unknown_" + type, "未知开关", R.drawable.ic_keydroidx_settings, false);
		}
	}
}
