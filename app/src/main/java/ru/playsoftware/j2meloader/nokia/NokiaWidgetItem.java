package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.ui.NokiaIcons;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面组件数据模型。type 区分组件类型，label 为显示名称，
 * value 保存附加数据（如网址 URL、应用包名/Activity 等，可编辑类型使用），
 * iconPath 为可选图标路径。支持序列化为 JSON 存入 SharedPreferences。
 */
public class NokiaWidgetItem {

	public static final int TYPE_APP = 0;        // 应用（可编辑）
	public static final int TYPE_URL = 1;        // 网址（可编辑）
	public static final int TYPE_CALENDAR = 2;   // 日历（不可编辑）
	public static final int TYPE_ACTIVITY = 3;   // Activity快捷（可编辑）
	public static final int TYPE_MEMORY = 4;     // 内存信息（不可编辑）
	public static final int TYPE_STORAGE = 5;    // 存储信息（不可编辑）
	public static final int TYPE_USAGE = 6;      // 使用时长（不可编辑）
	public static final int TYPE_LOCK_SCREEN = 7; // 锁屏（不可编辑，提示按下绑定键锁屏）
	public static final int TYPE_BG_MANAGER = 8;  // 后台管理（不可编辑，点击打开后台窗口）
	public static final int TYPE_IP = 9;          // IP地址（不可编辑，点击刷新+复制）
	public static final int TYPE_QS_TILE = 10;    // 快捷开关（磁贴，选择已安装的QS Tile）
	public static final int TYPE_MUSIC_PLAYER = 11; // 正在播放（音乐播放器状态，不可编辑）
	public static final int TYPE_COUNT = 12;

	public static final int MAX_COUNT = 15;

	public final int type;
	public String label;
	public String value;
	public String iconPath;

	public NokiaWidgetItem(int type, String label) {
		this(type, label, null, null);
	}

	public NokiaWidgetItem(int type, String label, String value) {
		this(type, label, value, null);
	}

	public NokiaWidgetItem(int type, String label, String value, String iconPath) {
		this.type = type;
		this.label = label;
		this.value = value;
		this.iconPath = iconPath;
	}

	public static NokiaWidgetItem fromJson(JSONObject json) throws JSONException {
		NokiaWidgetItem item = new NokiaWidgetItem(json.getInt("type"), json.getString("label"));
		item.value = json.optString("value", null);
		item.iconPath = json.optString("iconPath", null);
		return item;
	}

	public JSONObject toJson() throws JSONException {
		JSONObject json = new JSONObject();
		json.put("type", type);
		json.put("label", label != null ? label : "");
		if (value != null) json.put("value", value);
		if (iconPath != null) json.put("iconPath", iconPath);
		return json;
	}

	/** 是否可编辑（应用/网址/Activity 可进入编辑页）。 */
	public boolean isEditable() {
		return type == TYPE_APP || type == TYPE_URL || type == TYPE_ACTIVITY;
	}

	/** 类型名（S1 行右侧的灰色标签，如 [应用]）。 */
	public String getTypeTag() {
		switch (type) {
			case TYPE_APP: return "[应用]";
			case TYPE_URL: return "[网址]";
			case TYPE_CALENDAR: return "[日历]";
			case TYPE_ACTIVITY: return "[Activity]";
			case TYPE_MEMORY: return "[内存]";
			case TYPE_STORAGE: return "[存储]";
			case TYPE_USAGE: return "[时长]";
			case TYPE_LOCK_SCREEN: return "[锁屏]";
			case TYPE_BG_MANAGER: return "[后台]";
			case TYPE_IP: return "[IP]";
			case TYPE_QS_TILE: return "[快捷]";
			case TYPE_MUSIC_PLAYER: return "[音乐]";
			default: return "[" + type + "]";
		}
	}

	/** 类型完整名称（类型选择页使用）。 */
	public static String getTypeName(int type) {
		switch (type) {
			case TYPE_APP: return "应用";
			case TYPE_URL: return "网址";
			case TYPE_CALENDAR: return "日历";
			case TYPE_ACTIVITY: return "Activity快捷";
			case TYPE_MEMORY: return "内存信息";
			case TYPE_STORAGE: return "存储信息";
			case TYPE_USAGE: return "使用时长";
			case TYPE_LOCK_SCREEN: return "锁屏";
			case TYPE_BG_MANAGER: return "后台管理";
			case TYPE_IP: return "IP地址";
			case TYPE_QS_TILE: return "快捷开关";
			case TYPE_MUSIC_PLAYER: return "正在播放";
			default: return "未知";
		}
	}

	/** 类型的默认显示名称（直接添加类型创建组件时使用）。 */
	public static String getDefaultLabel(int type) {
		switch (type) {
			case TYPE_APP: return "应用";
			case TYPE_URL: return "网址";
			case TYPE_CALENDAR: return "日历";
			case TYPE_ACTIVITY: return "Activity快捷";
			case TYPE_MEMORY: return "内存信息";
			case TYPE_STORAGE: return "存储信息";
			case TYPE_USAGE: return "使用时长";
			case TYPE_LOCK_SCREEN: return "锁屏";
			case TYPE_BG_MANAGER: return "后台管理";
			case TYPE_IP: return "IP地址";
			case TYPE_QS_TILE: return "快捷开关";
			case TYPE_MUSIC_PLAYER: return "正在播放";
			default: return "组件";
		}
	}

	/** 以 type + label + value 作为身份标识（iconPath 可能为空，不参与比较）。 */
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		NokiaWidgetItem that = (NokiaWidgetItem) o;
		return type == that.type
				&& Objects.equals(label, that.label)
				&& Objects.equals(value, that.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, label, value);
	}

	/** 获取当前小组件实例对应的 Material Icons 字符。 */
	public String getTypeIconUnicode() {
		return getTypeIconUnicode(this.type);
	}

	/** 类型对应的 Material Icons 矢量字体 Unicode（单色矢量光栅化）。 */
	public static String getTypeIconUnicode(int type) {
		switch (type) {
			case TYPE_APP: return NokiaIcons.ICON_APP;
			case TYPE_URL: return NokiaIcons.ICON_URL;
			case TYPE_CALENDAR: return NokiaIcons.ICON_CALENDAR;
			case TYPE_ACTIVITY: return NokiaIcons.ICON_ACTIVITY;
			case TYPE_MEMORY: return NokiaIcons.ICON_MEMORY;
			case TYPE_STORAGE: return NokiaIcons.ICON_STORAGE;
			case TYPE_USAGE: return NokiaIcons.ICON_USAGE;
			case TYPE_LOCK_SCREEN: return NokiaIcons.ICON_LOCK;
			case TYPE_BG_MANAGER: return NokiaIcons.ICON_BG_MANAGER;
			case TYPE_IP: return NokiaIcons.ICON_IP;
			case TYPE_QS_TILE: return NokiaIcons.ICON_QS_TILE;
			case TYPE_MUSIC_PLAYER: return NokiaIcons.ICON_MUSIC_NOTE;
			default: return NokiaIcons.ICON_APP;
		}
	}

	/** 类型对应的图标资源 ID（S1 行图标 / S6 类型图标）。 */
	public static int getTypeIcon(int type) {
		switch (type) {
			case TYPE_APP: return R.drawable.ic_nokia_box;
			case TYPE_URL: return R.drawable.ic_nokia_web;
			case TYPE_CALENDAR: return R.drawable.ic_nokia_calendar;
			case TYPE_ACTIVITY: return R.drawable.ic_nokia_widget_activity;
			case TYPE_MEMORY: return R.drawable.ic_nokia_widget_memory;
			case TYPE_STORAGE: return R.drawable.ic_nokia_widget_storage;
			case TYPE_USAGE: return R.drawable.ic_nokia_widget_usage;
			case TYPE_LOCK_SCREEN: return R.drawable.ic_nokia_lock;
			case TYPE_BG_MANAGER: return R.drawable.ic_nokia_widget_bg_manager;
			case TYPE_IP: return R.drawable.ic_nokia_widget_ip;
			case TYPE_QS_TILE: return R.drawable.ic_nokia_torch;
			case TYPE_MUSIC_PLAYER: return R.drawable.ic_nokia_music;
			default: return R.drawable.ic_nokia_box;
		}
	}
}
