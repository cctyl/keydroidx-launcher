package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import io.github.cctyl.nokia.common.log.NokiaLog;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面组件的 SharedPreferences 持久化。
 * 组件列表上限 {@link #MAX_COUNT} 项，以 JSON 数组形式存储。
 * 首次启动时自动写入默认组件（日历、网址、内存、使用时长、音乐播放器）。
 */
public class NokiaWidgetStorage {

	private static final String PREFS_NAME = "nokia_desktop_widgets";
	private static final String KEY_WIDGETS = "widget_list";
	private static final String KEY_INITIALIZED = "widgets_initialized";
	public static final int MAX_COUNT = 15;

	/** 音乐播放器优先级列表（包名, 显示名）。取第一个已安装的。 */
	private static final String[][] MUSIC_APP_PRIORITY = {
			{"com.netease.cloudmusic", "网易云音乐"},
			{"com.tencent.qqmusic", "QQ音乐"},
			{"com.kugou.android", "酷狗音乐"},
			{"cn.kuwo.player", "酷我音乐"},
			{"cmccwm.mobilemusic", "咪咕音乐"},
			{"com.spotify.music", "Spotify"},
	};

	private final SharedPreferences prefs;

	public NokiaWidgetStorage(Context context) {
		prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		initDefaultsIfNeeded(context.getApplicationContext());
	}

	/**
	 * 首次启动时写入默认组件（仅一次，由 KEY_INITIALIZED 守卫）。
	 * 同步执行，因为只跑一次且数据量很小。
	 */
	private void initDefaultsIfNeeded(Context ctx) {
		if (prefs.getBoolean(KEY_INITIALIZED, false)) return;
		NokiaLog.i("WidgetStorage", "首次启动，写入默认组件");

		List<NokiaWidgetItem> defaults = new ArrayList<>();

		// 1. 日历组件
		defaults.add(new NokiaWidgetItem(NokiaWidgetItem.TYPE_CALENDAR, "日历", ""));
		NokiaLog.i("WidgetStorage", "默认组件: 日历");

		// 2. 网址组件: 显示 3g.qq.com，实际跳转 http://wkypub.top:9999
		defaults.add(new NokiaWidgetItem(NokiaWidgetItem.TYPE_URL, "3g.qq.com", "http://wkypub.top:9999"));
		NokiaLog.i("WidgetStorage", "默认组件: 网址 3g.qq.com -> http://wkypub.top:9999");

		// 3. 内存组件
		defaults.add(new NokiaWidgetItem(NokiaWidgetItem.TYPE_MEMORY, "内存", ""));
		NokiaLog.i("WidgetStorage", "默认组件: 内存");

		// 4. 使用时长组件
		defaults.add(new NokiaWidgetItem(NokiaWidgetItem.TYPE_USAGE, "使用时长", ""));
		NokiaLog.i("WidgetStorage", "默认组件: 使用时长");

		// 5. 应用组件：默认音乐播放器（取第一个已安装的）
		String musicAppKey = findMusicApp(ctx);
		if (musicAppKey != null) {
			String musicLabel = findMusicLabel(ctx, musicAppKey);
			defaults.add(new NokiaWidgetItem(NokiaWidgetItem.TYPE_APP, musicLabel, musicAppKey));
			NokiaLog.i("WidgetStorage", "默认组件: 音乐 " + musicLabel + " -> " + musicAppKey);
		} else {
			NokiaLog.i("WidgetStorage", "默认组件: 未找到已安装的音乐应用，跳过");
		}

		setWidgets(defaults);
		prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
		NokiaLog.i("WidgetStorage", "默认组件写入完成，共 " + defaults.size() + " 个");
	}

	/** 按优先级查找第一个已安装的音乐应用，返回 "pkg/cls" 格式。未找到返回 null。 */
	private static String findMusicApp(Context ctx) {
		PackageManager pm = ctx.getPackageManager();
		for (String[] entry : MUSIC_APP_PRIORITY) {
			String pkg = entry[0];
			try {
				pm.getPackageInfo(pkg, 0);
			} catch (PackageManager.NameNotFoundException e) {
				continue;
			}
			Intent launch = pm.getLaunchIntentForPackage(pkg);
			if (launch == null || launch.getComponent() == null) continue;
			return launch.getComponent().getPackageName() + "/" + launch.getComponent().getClassName();
		}
		return null;
	}

	/** 通过 appKey ("pkg/cls") 获取应用显示名。 */
	private static String findMusicLabel(Context ctx, String appKey) {
		PackageManager pm = ctx.getPackageManager();
		for (String[] entry : MUSIC_APP_PRIORITY) {
			String pkg = entry[0];
			String label = entry[1];
			if (appKey.startsWith(pkg + "/")) return label;
		}
		return "音乐";
	}

	/** 读取全部已添加组件，按存储顺序返回。 */
	public List<NokiaWidgetItem> getWidgets() {
		List<NokiaWidgetItem> result = new ArrayList<>();
		String json = prefs.getString(KEY_WIDGETS, null);
		if (json == null || json.isEmpty()) {
			NokiaLog.i("WidgetStorage", "getWidgets: 无已配置组件");
			return result;
		}
		try {
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				result.add(NokiaWidgetItem.fromJson(arr.getJSONObject(i)));
			}
			NokiaLog.i("WidgetStorage", "getWidgets: 读取 " + result.size() + " 个组件");
		} catch (JSONException e) {
			NokiaLog.e("WidgetStorage", "getWidgets 解析失败", e);
		}
		return result;
	}

	/** 整体保存组件列表（新增/删除/排序共用）。 */
	public void setWidgets(List<NokiaWidgetItem> widgets) {
		JSONArray arr = new JSONArray();
		for (NokiaWidgetItem item : widgets) {
			try {
				arr.put(item.toJson());
			} catch (JSONException e) {
				NokiaLog.e("WidgetStorage", "setWidgets 序列化失败: " + item.label, e);
			}
		}
		prefs.edit().putString(KEY_WIDGETS, arr.toString()).apply();
		NokiaLog.i("WidgetStorage", "setWidgets: 保存 " + widgets.size() + " 个组件");
	}

	/** 替换指定下标位置的组件（应用/网址/Activity 编辑「换绑」使用）。下标越界时忽略。 */
	public void updateWidget(int index, NokiaWidgetItem item) {
		List<NokiaWidgetItem> list = getWidgets();
		if (index < 0 || index >= list.size()) {
			NokiaLog.w("WidgetStorage", "updateWidget: 下标越界 index=" + index + " size=" + list.size());
			return;
		}
		list.set(index, item);
		setWidgets(list);
		NokiaLog.i("WidgetStorage", "updateWidget: 更新下标 " + index + " -> " + item.label);
	}

	/** 追加一个组件；已达上限时拒绝并返回 false。 */
	public boolean addWidget(NokiaWidgetItem item) {
		List<NokiaWidgetItem> list = getWidgets();
		if (list.size() >= MAX_COUNT) {
			NokiaLog.w("WidgetStorage", "addWidget: 已达上限 " + MAX_COUNT + "，拒绝添加 " + item.label);
			return false;
		}
		list.add(item);
		setWidgets(list);
		return true;
	}

	/** 删除指定组件（删除模式「删除已选」使用）。 */
	public void removeWidgets(List<NokiaWidgetItem> toRemove) {
		if (toRemove == null || toRemove.isEmpty()) return;
		List<NokiaWidgetItem> list = getWidgets();
		list.removeAll(toRemove);
		setWidgets(list);
		NokiaLog.i("WidgetStorage", "removeWidgets: 删除 " + toRemove.size() + " 个组件");
	}

	/** 是否已达组件数量上限。 */
	public boolean isFull() {
		return getWidgets().size() >= MAX_COUNT;
	}
}
