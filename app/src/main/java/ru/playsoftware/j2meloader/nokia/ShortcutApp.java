package ru.playsoftware.j2meloader.nokia;

import android.content.Intent;
import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;
import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * 快捷栏应用数据模型。支持安卓应用与 J2ME(JAR) 应用。
 * 可序列化为 JSON 存入 SharedPreferences。
 */
public class ShortcutApp {
	private static final String TAG = "ShortcutApp";


	public static final int TYPE_ANDROID = 0;
	public static final int TYPE_J2ME = 1;

	public final int type;
	public final String label;       // 显示名称
	public final String appKey;      // 安卓: packageName/activityName;  J2ME: pathExt
	public final String iconPath;    // J2ME 图标路径（安卓为 null，从 PackageManager 实时加载）
	public String intentUri;   // 用于序列化安卓 Intent 的 URI

	/** 安卓应用构造器 */
	public ShortcutApp(int type, String label, String appKey, Intent launchIntent) {
		this.type = type;
		this.label = label;
		this.appKey = appKey;
		this.iconPath = null;
		if (launchIntent != null) {
			this.intentUri = launchIntent.toUri(Intent.URI_INTENT_SCHEME);
		}
	}

	/** J2ME 应用构造器 */
	public ShortcutApp(int type, String label, String appKey, String iconPath) {
		this.type = type;
		this.label = label;
		this.appKey = appKey;
		this.iconPath = iconPath;
	}

	/** 从 JSON 反序列化 */
	public static ShortcutApp fromJson(JSONObject json) throws JSONException {
		int type = json.getInt("type");
		String label = json.getString("label");
		String appKey = json.optString("appKey", "");
		String iconPath = json.optString("iconPath", null);
		String intentUri = json.optString("intentUri", null);
		if (type == TYPE_J2ME) {
			ShortcutApp app = new ShortcutApp(type, label, appKey, iconPath);
			app.intentUri = intentUri;
			return app;
		} else {
			Intent intent = null;
			if (intentUri != null && !intentUri.isEmpty()) {
				try {
					intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
				} catch (Exception ignored) {
					KeydroidxLog.w(TAG, "fromJson failed: " + ignored.getMessage());
				}
			}
			return new ShortcutApp(type, label, appKey, intent);
		}
	}

	/** 序列化为 JSON */
	public JSONObject toJson() throws JSONException {
		JSONObject json = new JSONObject();
		json.put("type", type);
		json.put("label", label);
		json.put("appKey", appKey != null ? appKey : "");
		if (iconPath != null) {
			json.put("iconPath", iconPath);
		}
		if (intentUri != null) {
			json.put("intentUri", intentUri);
		}
		return json;
	}

	/** 获取安卓启动 Intent */
	public Intent getLaunchIntent() {
		if (type != TYPE_ANDROID || intentUri == null) return null;
		try {
			return Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "getLaunchIntent failed: " + e.getMessage());
			return null;
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (!(obj instanceof ShortcutApp)) return false;
		ShortcutApp other = (ShortcutApp) obj;
		return this.type == other.type && this.appKey.equals(other.appKey);
	}

	@Override
	public int hashCode() {
		return (type * 31) + appKey.hashCode();
	}
}
