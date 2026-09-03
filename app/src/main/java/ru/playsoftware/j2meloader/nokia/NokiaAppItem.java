package ru.playsoftware.j2meloader.nokia;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/**
 * 功能表中的一项。可以是真实安卓应用（TYPE_APP），也可以是特殊入口
 * （TYPE_BOX 百宝箱 / TYPE_SETTINGS 桌面设置 / TYPE_MAIN 原始 J2ME-Loader 主界面）。
 * 注：原 TYPE_KEYBIND（按键绑定）入口已移除，按键绑定改由「桌面设置」进入。
 */
public class NokiaAppItem {

	public static final int TYPE_APP = 0;
	public static final int TYPE_BOX = 1;
	public static final int TYPE_SETTINGS = 3;
	public static final int TYPE_MAIN = 4;
	/** 通知中心（系统通知列表，可清除） */
	public static final int TYPE_NOTIFICATION = 5;

	/** 类型：TYPE_APP / TYPE_BOX / TYPE_SETTINGS / TYPE_MAIN / TYPE_NOTIFICATION */
	public final int type;
	/** 显示名称 */
	public final String label;
	/** 图标（可后续替换，如换为 S60 风格图标） */
	public Drawable icon;
	/** 启动该应用的 Intent（特殊入口为 null，可被设置以携带 J2ME 数据） */
	public Intent launchIntent;
	/** S60 图标资源 ID（构建列表时记录，用于排序分组；0 = 未匹配） */
	public int s60IconResId;

	public NokiaAppItem(int type, String label, Drawable icon, Intent launchIntent) {
		this.type = type;
		this.label = label;
		this.icon = icon;
		this.launchIntent = launchIntent;
	}
}
