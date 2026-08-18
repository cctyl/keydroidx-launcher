package ru.playsoftware.j2meloader.nokia;

import ru.playsoftware.j2meloader.R;

public class NokiaQuickToggleItem {

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
	public static final int TYPE_COUNT = 12;

	public final int type;
	public final String id;
	public String name;
	public int iconRes;
	public boolean enabled;

	public NokiaQuickToggleItem(int type, String id, String name, int iconRes, boolean enabled) {
		this.type = type;
		this.id = id;
		this.name = name;
		this.iconRes = iconRes;
		this.enabled = enabled;
	}

	public String getIconUnicode() {
		switch (type) {
			case TYPE_WIFI: return NokiaIcons.TOGGLE_WIFI;
			case TYPE_DATA: return NokiaIcons.TOGGLE_DATA;
			case TYPE_BLUETOOTH: return NokiaIcons.TOGGLE_BLUETOOTH;
			case TYPE_AIRPLANE: return NokiaIcons.TOGGLE_AIRPLANE;
			case TYPE_TORCH: return NokiaIcons.TOGGLE_TORCH;
			case TYPE_SOUND: return NokiaIcons.TOGGLE_SOUND;
			case TYPE_ROTATE: return NokiaIcons.TOGGLE_ROTATE;
			case TYPE_LOCK: return NokiaIcons.TOGGLE_LOCK;
			case TYPE_BRIGHTNESS: return NokiaIcons.TOGGLE_BRIGHTNESS;
			case TYPE_LOCATION: return NokiaIcons.TOGGLE_LOCATION;
			case TYPE_HOTSPOT: return NokiaIcons.TOGGLE_HOTSPOT;
			case TYPE_SAVER: return NokiaIcons.TOGGLE_SAVER;
			default: return NokiaIcons.ICON_SETTINGS;
		}
	}

	public static NokiaQuickToggleItem createDefault(int type) {
		switch (type) {
			case TYPE_WIFI:
				return new NokiaQuickToggleItem(TYPE_WIFI, "wifi", "WLAN", R.drawable.ic_nokia_wifi, true);
			case TYPE_DATA:
				return new NokiaQuickToggleItem(TYPE_DATA, "data", "移动数据", R.drawable.ic_nokia_data, true);
			case TYPE_BLUETOOTH:
				return new NokiaQuickToggleItem(TYPE_BLUETOOTH, "bluetooth", "蓝牙", R.drawable.ic_nokia_bluetooth, true);
			case TYPE_AIRPLANE:
				return new NokiaQuickToggleItem(TYPE_AIRPLANE, "airplane", "飞行模式", R.drawable.ic_nokia_airplane, true);
			case TYPE_TORCH:
				return new NokiaQuickToggleItem(TYPE_TORCH, "torch", "手电筒", R.drawable.ic_nokia_torch, true);
			case TYPE_SOUND:
				return new NokiaQuickToggleItem(TYPE_SOUND, "sound", "情景模式", R.drawable.ic_nokia_sound, true);
			case TYPE_ROTATE:
				return new NokiaQuickToggleItem(TYPE_ROTATE, "rotate", "自动旋转", R.drawable.ic_nokia_rotate, true);
			case TYPE_LOCK:
				return new NokiaQuickToggleItem(TYPE_LOCK, "lock", "一键锁屏", R.drawable.ic_nokia_lock, true);
			case TYPE_BRIGHTNESS:
				return new NokiaQuickToggleItem(TYPE_BRIGHTNESS, "brightness", "屏幕亮度", R.drawable.ic_nokia_brightness, false);
			case TYPE_LOCATION:
				return new NokiaQuickToggleItem(TYPE_LOCATION, "location", "位置信息", R.drawable.ic_nokia_location, false);
			case TYPE_HOTSPOT:
				return new NokiaQuickToggleItem(TYPE_HOTSPOT, "hotspot", "个人热点", R.drawable.ic_nokia_hotspot, false);
			case TYPE_SAVER:
				return new NokiaQuickToggleItem(TYPE_SAVER, "saver", "省电模式", R.drawable.ic_nokia_saver, false);
			default:
				return new NokiaQuickToggleItem(type, "unknown_" + type, "未知开关", R.drawable.ic_nokia_settings, false);
		}
	}
}
