package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.cctyl.nokia.common.log.NokiaLog;
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
public class NokiaQuickToggleStorage {

	private static final String PREFS_NAME = "nokia_quick_toggles";
	private static final String KEY_TOGGLE_LIST = "toggle_list";
	private static final String KEY_INITIALIZED = "toggles_initialized";

	private final SharedPreferences prefs;

	public NokiaQuickToggleStorage(Context context) {
		this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		initDefaultsIfNeeded();
	}

	private void initDefaultsIfNeeded() {
		if (prefs.getBoolean(KEY_INITIALIZED, false)) return;
		NokiaLog.i("QuickToggleStorage", "首次启动，初始化默认快捷开关列表");
		List<NokiaQuickToggleItem> defaults = new ArrayList<>();
		for (int i = 0; i < NokiaQuickToggleItem.TYPE_COUNT; i++) {
			defaults.add(NokiaQuickToggleItem.createDefault(i));
		}
		setToggles(defaults);
		prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
	}

	public List<NokiaQuickToggleItem> getToggles() {
		String jsonStr = prefs.getString(KEY_TOGGLE_LIST, null);
		if (jsonStr == null || jsonStr.trim().isEmpty()) {
			List<NokiaQuickToggleItem> defaults = new ArrayList<>();
			for (int i = 0; i < NokiaQuickToggleItem.TYPE_COUNT; i++) {
				defaults.add(NokiaQuickToggleItem.createDefault(i));
			}
			return defaults;
		}

		List<NokiaQuickToggleItem> list = new ArrayList<>();
		boolean[] seen = new boolean[NokiaQuickToggleItem.TYPE_COUNT];
		try {
			JSONArray array = new JSONArray(jsonStr);
			for (int i = 0; i < array.length(); i++) {
				JSONObject obj = array.getJSONObject(i);
				int type = obj.optInt("type", -1);
				if (type >= 0 && type < NokiaQuickToggleItem.TYPE_COUNT) {
					seen[type] = true;
					NokiaQuickToggleItem item = NokiaQuickToggleItem.createDefault(type);
					item.enabled = obj.optBoolean("enabled", item.enabled);
					list.add(item);
				}
			}
		} catch (JSONException e) {
			NokiaLog.e("QuickToggleStorage", "解析快捷开关列表 JSON 失败: " + e.getMessage());
		}

		// 补充可能新增的开关类型
		for (int i = 0; i < NokiaQuickToggleItem.TYPE_COUNT; i++) {
			if (!seen[i]) {
				list.add(NokiaQuickToggleItem.createDefault(i));
			}
		}
		return list;
	}

	public static List<NokiaQuickToggleItem> getEnabledToggles(Context context) {
		return new NokiaQuickToggleStorage(context).getEnabledToggles();
	}

	public static List<NokiaQuickToggleItem> getToggles(Context context) {
		return new NokiaQuickToggleStorage(context).getToggles();
	}

	public List<NokiaQuickToggleItem> getEnabledToggles() {
		List<NokiaQuickToggleItem> all = getToggles();
		List<NokiaQuickToggleItem> result = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			NokiaQuickToggleItem item = all.get(i);
			if (item.enabled) {
				result.add(item);
			}
		}
		return result;
	}

	public void setToggles(List<NokiaQuickToggleItem> list) {
		JSONArray array = new JSONArray();
		for (int i = 0; i < list.size(); i++) {
			NokiaQuickToggleItem item = list.get(i);
			JSONObject obj = new JSONObject();
			try {
				obj.put("type", item.type);
				obj.put("id", item.id);
				obj.put("enabled", item.enabled);
				array.put(obj);
			} catch (JSONException ignored) {}
		}
		prefs.edit().putString(KEY_TOGGLE_LIST, array.toString()).apply();
		NokiaLog.i("QuickToggleStorage", "快捷开关列表已保存，共 " + list.size() + " 项");
	}

	public void resetToDefaults() {
		List<NokiaQuickToggleItem> defaults = new ArrayList<>();
		for (int i = 0; i < NokiaQuickToggleItem.TYPE_COUNT; i++) {
			defaults.add(NokiaQuickToggleItem.createDefault(i));
		}
		setToggles(defaults);
	}

	public void swapToggles(int from, int to) {
		List<NokiaQuickToggleItem> list = getToggles();
		if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) return;
		Collections.swap(list, from, to);
		setToggles(list);
	}
}
