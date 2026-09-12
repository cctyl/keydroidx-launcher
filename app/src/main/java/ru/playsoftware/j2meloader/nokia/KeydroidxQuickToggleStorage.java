package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面快捷开关配置存储（顺序 + 开启展示状态）。
 * 使用 SharedPreferences + JSON 数组持久化。
 */
public class KeydroidxQuickToggleStorage {
	private static final String TAG = "KeydroidxQuickToggleStorage";


	private static final String PREFS_NAME = "nokia_quick_toggles";
	private static final String KEY_TOGGLE_LIST = "toggle_list";
	private static final String KEY_INITIALIZED = "toggles_initialized";

	private final SharedPreferences prefs;

	public KeydroidxQuickToggleStorage(Context context) {
		this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		initDefaultsIfNeeded();
	}

	private void initDefaultsIfNeeded() {
		if (prefs.getBoolean(KEY_INITIALIZED, false)) return;
		KeydroidxLog.i("QuickToggleStorage", "首次启动，初始化默认快捷开关列表");
		List<KeydroidxQuickToggleItem> defaults = new ArrayList<>();
		for (int i = 0; i < KeydroidxQuickToggleItem.TYPE_COUNT; i++) {
			defaults.add(KeydroidxQuickToggleItem.createDefault(i));
		}
		setToggles(defaults);
		prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
	}

	public List<KeydroidxQuickToggleItem> getToggles() {
		String jsonStr = prefs.getString(KEY_TOGGLE_LIST, null);
		if (jsonStr == null || jsonStr.trim().isEmpty()) {
			List<KeydroidxQuickToggleItem> defaults = new ArrayList<>();
			for (int i = 0; i < KeydroidxQuickToggleItem.TYPE_COUNT; i++) {
				defaults.add(KeydroidxQuickToggleItem.createDefault(i));
			}
			return defaults;
		}

		List<KeydroidxQuickToggleItem> list = new ArrayList<>();
		boolean[] seen = new boolean[KeydroidxQuickToggleItem.TYPE_COUNT];
		try {
			JSONArray array = new JSONArray(jsonStr);
			for (int i = 0; i < array.length(); i++) {
				JSONObject obj = array.getJSONObject(i);
				int type = obj.optInt("type", -1);
				if (type >= 0 && type < KeydroidxQuickToggleItem.TYPE_COUNT) {
					seen[type] = true;
					KeydroidxQuickToggleItem item = KeydroidxQuickToggleItem.createDefault(type);
					item.enabled = obj.optBoolean("enabled", item.enabled);
					list.add(item);
				}
			}
		} catch (JSONException e) {
			KeydroidxLog.e("QuickToggleStorage", "解析快捷开关列表 JSON 失败: " + e.getMessage());
		}

		// 补充可能新增的开关类型
		for (int i = 0; i < KeydroidxQuickToggleItem.TYPE_COUNT; i++) {
			if (!seen[i]) {
				list.add(KeydroidxQuickToggleItem.createDefault(i));
			}
		}
		return list;
	}

	public static List<KeydroidxQuickToggleItem> getEnabledToggles(Context context) {
		return new KeydroidxQuickToggleStorage(context).getEnabledToggles();
	}

	public static List<KeydroidxQuickToggleItem> getToggles(Context context) {
		return new KeydroidxQuickToggleStorage(context).getToggles();
	}

	public List<KeydroidxQuickToggleItem> getEnabledToggles() {
		List<KeydroidxQuickToggleItem> all = getToggles();
		List<KeydroidxQuickToggleItem> result = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			KeydroidxQuickToggleItem item = all.get(i);
			if (item.enabled) {
				result.add(item);
			}
		}
		return result;
	}

	public void setToggles(List<KeydroidxQuickToggleItem> list) {
		JSONArray array = new JSONArray();
		for (int i = 0; i < list.size(); i++) {
			KeydroidxQuickToggleItem item = list.get(i);
			JSONObject obj = new JSONObject();
			try {
				obj.put("type", item.type);
				obj.put("id", item.id);
				obj.put("enabled", item.enabled);
				array.put(obj);
			} catch (JSONException ignored) {
				KeydroidxLog.w(TAG, "put failed: " + ignored.getMessage());
			}
		}
		prefs.edit().putString(KEY_TOGGLE_LIST, array.toString()).apply();
		KeydroidxLog.i("QuickToggleStorage", "快捷开关列表已保存，共 " + list.size() + " 项");
	}

	public void resetToDefaults() {
		List<KeydroidxQuickToggleItem> defaults = new ArrayList<>();
		for (int i = 0; i < KeydroidxQuickToggleItem.TYPE_COUNT; i++) {
			defaults.add(KeydroidxQuickToggleItem.createDefault(i));
		}
		setToggles(defaults);
	}

	public void swapToggles(int from, int to) {
		List<KeydroidxQuickToggleItem> list = getToggles();
		if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) return;
		Collections.swap(list, from, to);
		setToggles(list);
	}
}
