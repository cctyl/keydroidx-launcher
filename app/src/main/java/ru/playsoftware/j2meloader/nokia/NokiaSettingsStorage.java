package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.playsoftware.j2meloader.BuildConfig;

/**
 * 诺基亚桌面设置的 SharedPreferences 封装。
 * 管理快捷栏应用列表、壁纸、软键映射等设置项的读写。
 */
public class NokiaSettingsStorage {

	private static final String PREFS_NAME = "nokia_desktop_settings";
	private static final String KEY_SHORTCUT_APPS = "shortcut_apps";
	private static final String KEY_WALLPAPER = "wallpaper";
	private static final String KEY_SOFT_LEFT_ACTION = "soft_left_action";
	private static final String KEY_SOFT_RIGHT_ACTION = "soft_right_action";
	private static final String KEY_PROTECTED_PACKAGES = "protected_packages";

	private final SharedPreferences prefs;
	private final Context context;

	public NokiaSettingsStorage(Context context) {
		this.context = context.getApplicationContext();
		prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	// ── 快捷栏应用 ──

	/**
	 * 默认快捷应用清单（按用户期望的顺序）。
	 * 前四项为系统隐式 Intent（相机/电话/短信/浏览器），后四项为已知包名应用。
	 */
	private static final String[][] DEFAULT_APPS = {
			{"action_camera", "相机"},
			{"action_dial", "电话"},
			{"action_sms", "短信"},
			{"action_browser", "浏览器"},
			{"com.tencent.mobileqq", "QQ"},
			{"com.tencent.mm", "微信"},
			{"com.ss.android.ugc.aweme", "抖音"},
			{"tv.danmaku.bili", "bilibili"},
	};

	/**
	 * 音乐类 app 优先级清单。多个音乐 app 并存时仅取第一个已安装的，
	 * 避免快捷栏出现多个音乐入口。
	 */
	private static final String[][] MUSIC_APP_PRIORITY = {
			{"com.netease.cloudmusic", "网易云音乐"},
			{"com.tencent.qqmusic", "QQ音乐"},
			{"com.kugou.android", "酷狗音乐"},
			{"cn.kuwo.player", "酷我音乐"},
			{"cmccwm.mobilemusic", "咪咕音乐"},
			{"com.spotify.music", "Spotify"},
	};

	/** 快捷栏配置异步加载完成回调（均在主线程回调） */
	public interface OnShortcutAppsLoaded {
		void onLoaded(List<ShortcutApp> apps);
	}

	/**
	 * 获取已选择的快捷栏应用列表（同步，仅读 SharedPreferences / 首次同步构建，供设置页等非冷启动路径使用）。
	 * 使用静态锁与 {@link #getShortcutAppsAsync} 的"检查-构建-写回"互斥，
	 * 防止后台线程构建默认值时把用户刚保存的配置覆盖回默认值。
	 */
	public List<ShortcutApp> getShortcutApps() {
		synchronized (NokiaSettingsStorage.class) {
			List<ShortcutApp> result = new ArrayList<>();
			String json = prefs.getString(KEY_SHORTCUT_APPS, null);
			if (json == null) {
				// 首次启动：生成默认快捷应用（仅已安装的应用会被加入），并持久化
				NokiaLog.i("SettingsStorage", "shortcut_apps 未配置，生成默认快捷应用");
				result = buildDefaultShortcutApps();
				setShortcutApps(result);
				return result;
			}
			return parseShortcutApps(json);
		}
	}

	/**
	 * 异步获取快捷栏应用列表（不阻塞主线程，供冷启动路径使用）。
	 * - 已配置：同步解析 JSON（毫秒级，无 PackageManager 查询），直接回调；
	 * - 首次未配置：在后台线程构建默认快捷应用（含 PackageManager 批量查询）并持久化，
	 *   完成后回主线程回调。
	 */
	public void getShortcutAppsAsync(final OnShortcutAppsLoaded callback) {
		if (callback == null) return;
		final String json = prefs.getString(KEY_SHORTCUT_APPS, null);
		if (json != null) {
			// 已配置：同步解析（毫秒级，无 IPC），立即回调
			callback.onLoaded(parseShortcutApps(json));
			return;
		}
		// 首次启动：后台线程构建默认快捷应用（含 PackageManager 批量查询）
		NokiaLog.i("SettingsStorage", "shortcut_apps 未配置，后台生成默认快捷应用");
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				long start = System.currentTimeMillis();
				final List<ShortcutApp> defaults = buildDefaultShortcutApps();
				long elapsed = System.currentTimeMillis() - start;
				// 写回前 double-check：构建默认值期间，设置页等可能已经保存了用户配置，
				// 此时禁止覆盖，否则用户的 7 个选择会被"回滚"成默认值（安卓/J2ME 全部丢失）。
				final List<ShortcutApp> actual;
				synchronized (NokiaSettingsStorage.class) {
					if (prefs.getString(KEY_SHORTCUT_APPS, null) == null) {
						NokiaLog.i("SettingsStorage", "后台生成默认快捷应用完成: " + defaults.size()
								+ " 个，耗时 " + elapsed + "ms，落盘");
						setShortcutApps(defaults);
					} else {
						NokiaLog.w("SettingsStorage", "后台默认构建完成，但期间已有用户配置，放弃覆盖");
					}
					// 以实际存储内容为准回调，避免桌面渲染出与设置页不一致的数据
					actual = parseShortcutApps(prefs.getString(KEY_SHORTCUT_APPS, null));
				}
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						callback.onLoaded(actual);
					}
				});
			}
		}, "build-default-shortcuts").start();
	}

	/** 解析快捷栏 JSON；null/空返回空列表。读取时按去重键过滤，清理历史遗留的同包重复入口。 */
	private List<ShortcutApp> parseShortcutApps(String json) {
		List<ShortcutApp> result = new ArrayList<>();
		if (json == null || json.isEmpty()) {
			return result;
		}
		try {
			JSONArray arr = new JSONArray(json);
			// 按包名（安卓）/ pathExt（J2ME）去重，只保留第一个出现项，
			// 处理设备上同包注册多个 launcher Activity（如系统短信/相机）导致的重复入口
			Map<String, ShortcutApp> unique = new LinkedHashMap<>();
			for (int i = 0; i < arr.length(); i++) {
				ShortcutApp app = ShortcutApp.fromJson(arr.getJSONObject(i));
				unique.put(dedupeKey(app), app);
			}
			result.addAll(unique.values());
			if (unique.size() < arr.length()) {
				NokiaLog.w("SettingsStorage", "快捷栏配置存在同包重复入口，已去重: "
						+ arr.length() + " -> " + unique.size());
			}
			NokiaLog.i("SettingsStorage", "getShortcutApps: 从存储读取 " + result.size() + " 个应用");
		} catch (JSONException e) {
			NokiaLog.e("SettingsStorage", "getShortcutApps 解析失败", e);
		}
		return result;
	}

	/** 快捷项去重键：安卓按包名（appKey 的 "/" 前缀），J2ME 按 pathExt。 */
	private static String dedupeKey(ShortcutApp app) {
		if (app.type == ShortcutApp.TYPE_ANDROID && app.appKey != null) {
			int slash = app.appKey.indexOf('/');
			String pkg = slash > 0 ? app.appKey.substring(0, slash) : app.appKey;
			return "a:" + pkg;
		}
		return "j:" + (app.appKey != null ? app.appKey : app.label);
	}

	/**
	 * 根据已安装应用生成默认快捷栏：遍历 DEFAULT_APPS，
	 * - "action_*" 前缀：用对应的系统隐式 Intent 解析出可用 Activity；
	 * - 包名：检查是否已安装，取主启动 Activity。
	 * 未安装/无可用 Activity 的则跳过。
	 */
	private List<ShortcutApp> buildDefaultShortcutApps() {
		List<ShortcutApp> defaults = new ArrayList<>();
		PackageManager pm = context.getPackageManager();

		for (String[] entry : DEFAULT_APPS) {
			String key = entry[0];
			String label = entry[1];
			if (key.startsWith("action_")) {
				addActionApp(pm, defaults, key, label);
			} else {
				addPackageApp(pm, defaults, key, label);
			}
		}

		// 音乐：多个音乐 app 仅取第一个已安装的
		addMusicApp(pm, defaults);

		NokiaLog.i("SettingsStorage", "默认快捷应用生成完成: " + defaults.size() + " 个");
		return defaults;
	}

	/** 按优先级取第一个已安装的音乐 app 加入默认列表（只加一个） */
	private void addMusicApp(PackageManager pm, List<ShortcutApp> out) {
		for (String[] entry : MUSIC_APP_PRIORITY) {
			String pkg = entry[0];
			String label = entry[1];
			try {
				pm.getPackageInfo(pkg, 0);
			} catch (PackageManager.NameNotFoundException e) {
				continue; // 未安装，尝试下一个
			}
			Intent launch = pm.getLaunchIntentForPackage(pkg);
			if (launch == null || launch.getComponent() == null) {
				continue;
			}
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			String appKey = launch.getComponent().getPackageName() + "/"
					+ launch.getComponent().getClassName();
			out.add(new ShortcutApp(ShortcutApp.TYPE_ANDROID, label, appKey, launch));
			NokiaLog.i("SettingsStorage", "默认音乐应用已加入: " + label + " -> " + appKey);
			return; // 仅取第一个
		}
		NokiaLog.i("SettingsStorage", "未找到已安装的音乐 app，跳过音乐快捷项");
	}

	/** 通过包名检查是否已安装，并取主启动 Activity 加入默认列表 */
	private void addPackageApp(PackageManager pm, List<ShortcutApp> out, String pkg, String label) {
		try {
			pm.getPackageInfo(pkg, 0);
		} catch (PackageManager.NameNotFoundException e) {
			NokiaLog.i("SettingsStorage", "默认应用未安装，跳过: " + label + " (" + pkg + ")");
			return;
		}
		Intent launch = pm.getLaunchIntentForPackage(pkg);
		if (launch == null || launch.getComponent() == null) {
			NokiaLog.w("SettingsStorage", "默认应用无启动 Intent，跳过: " + label + " (" + pkg + ")");
			return;
		}
		launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		String appKey = launch.getComponent().getPackageName() + "/"
				+ launch.getComponent().getClassName();
		out.add(new ShortcutApp(ShortcutApp.TYPE_ANDROID, label, appKey, launch));
		NokiaLog.i("SettingsStorage", "默认应用已加入: " + label + " -> " + appKey);
	}

	/** 通过系统隐式 Intent 解析出可用 Activity 并加入默认列表 */
	private void addActionApp(PackageManager pm, List<ShortcutApp> out, String key, String label) {
		Intent intent = buildActionIntent(key);
		if (intent == null) return;
		ResolveInfo ri = pm.resolveActivity(intent, 0);
		if (ri == null || ri.activityInfo == null) {
			NokiaLog.i("SettingsStorage", "默认应用无可用 Activity，跳过: " + label + " (" + key + ")");
			return;
		}
		ActivityInfo ai = ri.activityInfo;
		Intent launch = new Intent(Intent.ACTION_MAIN);
		launch.addCategory(Intent.CATEGORY_LAUNCHER);
		launch.setClassName(ai.packageName, ai.name);
		launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		String appKey = ai.packageName + "/" + ai.name;
		out.add(new ShortcutApp(ShortcutApp.TYPE_ANDROID, label, appKey, launch));
		NokiaLog.i("SettingsStorage", "默认应用已加入: " + label + " -> " + appKey);
	}

	/** 根据 action key 构造对应的隐式 Intent */
	private Intent buildActionIntent(String key) {
		switch (key) {
			case "action_camera":
				return new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
			case "action_dial":
				return new Intent(Intent.ACTION_DIAL, Uri.parse("tel:"));
			case "action_sms":
				return new Intent(Intent.ACTION_VIEW, Uri.parse("sms:"));
			case "action_browser":
				return new Intent(Intent.ACTION_VIEW, Uri.parse("http://"));
			default:
				return null;
		}
	}

	/** 保存快捷栏应用列表 */
	public void setShortcutApps(List<ShortcutApp> apps) {
		JSONArray arr = new JSONArray();
		for (ShortcutApp app : apps) {
			try {
				arr.put(app.toJson());
			} catch (JSONException e) {
				NokiaLog.e("SettingsStorage", "setShortcutApps 序列化失败: " + app.label, e);
			}
		}
		prefs.edit().putString(KEY_SHORTCUT_APPS, arr.toString()).apply();
		NokiaLog.i("SettingsStorage", "setShortcutApps: 保存 " + apps.size() + " 个应用");
	}

	// ── 壁纸 ──

	public String getWallpaper() {
		return prefs.getString(KEY_WALLPAPER, "default");
	}

	public void setWallpaper(String wallpaperId) {
		prefs.edit().putString(KEY_WALLPAPER, wallpaperId).apply();
		NokiaLog.i("SettingsStorage", "setWallpaper: " + wallpaperId);
	}

	// ── 左右软键 ──

	public String getSoftLeftAction() {
		return prefs.getString(KEY_SOFT_LEFT_ACTION, "album");
	}

	public void setSoftLeftAction(String action) {
		prefs.edit().putString(KEY_SOFT_LEFT_ACTION, action).apply();
		NokiaLog.i("SettingsStorage", "setSoftLeftAction: " + action);
	}

	public String getSoftRightAction() {
		return prefs.getString(KEY_SOFT_RIGHT_ACTION, "contacts");
	}

	public void setSoftRightAction(String action) {
		prefs.edit().putString(KEY_SOFT_RIGHT_ACTION, action).apply();
		NokiaLog.i("SettingsStorage", "setSoftRightAction: " + action);
	}

	// ── 后台管理保护名单 ──

	/**
	 * 读取后台清理保护名单（包名集合）。
	 * 清理后台时这些包名的进程会被跳过，不会被 killBackgroundProcesses。
	 * 存储格式为 JSON 字符串数组；未配置时返回空集合。
	 */
	public Set<String> getProtectedPackages() {
		Set<String> result = new HashSet<>();
		String json = prefs.getString(KEY_PROTECTED_PACKAGES, null);
		if (json == null || json.isEmpty()) {
			return result;
		}
		try {
			JSONArray arr = new JSONArray(json);
			for (int i = 0; i < arr.length(); i++) {
				result.add(arr.getString(i));
			}
			NokiaLog.i("SettingsStorage", "getProtectedPackages: 读取 " + result.size() + " 个包");
		} catch (JSONException e) {
			NokiaLog.e("SettingsStorage", "getProtectedPackages 解析失败", e);
		}
		return result;
	}

	/** 整体保存保护名单（覆盖式写入）。 */
	public void setProtectedPackages(Set<String> packages) {
		JSONArray arr = new JSONArray();
		if (packages != null) {
			for (String pkg : packages) {
				arr.put(pkg);
			}
		}
		prefs.edit().putString(KEY_PROTECTED_PACKAGES, arr.toString()).apply();
		NokiaLog.i("SettingsStorage", "setProtectedPackages: 保存 " + arr.length() + " 个包");
	}

	// ── 字体大小 ──

	private static final String KEY_FONT_SCALE = "font_scale";

	/**
	 * 读取用户字体缩放系数（桌面设置 → 字体大小），默认 1.0。
	 * 静态方法：供 {@link NokiaBaseActivity#attachBaseContext} 在 Activity 早期读取。
	 */
	public static float getFontScale(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getFloat(KEY_FONT_SCALE, 1f);
	}

	/** 保存用户字体缩放系数。 */
	public static void setFontScale(Context ctx, float scale) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putFloat(KEY_FONT_SCALE, scale).apply();
		NokiaLog.i("SettingsStorage", "setFontScale: " + scale);
	}

	// ── 日志记录开关 ──

	private static final String KEY_LOG_FILE = "log_file_enabled";

	/**
	 * 是否输出详细文件日志（桌面设置 → 日志记录）。
	 * 未设置过时按构建类型给默认：debug 开启、release 关闭。
	 * 关闭时文件只记录 ERROR 及以上；开启时记录全部详细日志。
	 */
	public static boolean isFileLogEnabled(Context ctx) {
		SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		if (!sp.contains(KEY_LOG_FILE)) {
			return BuildConfig.DEBUG;
		}
		return sp.getBoolean(KEY_LOG_FILE, true);
	}

	/** 保存日志记录开关（true=详细日志）。 */
	public static void setFileLogEnabled(Context ctx, boolean enabled) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(KEY_LOG_FILE, enabled).apply();
		NokiaLog.i("SettingsStorage", "setFileLogEnabled: " + enabled);
	}

	// ── 电源键拦截方案（高级设置 → 电源键拦截设置） ──

	/** 电源键拦截：关闭。 */
	public static final int POWER_INTERCEPTOR_MODE_OFF = 0;
	/** 电源键拦截：方案1 evdev grab + uinput 回放 + 决策状态机（安卓13 目标方案，实现中）。 */
	public static final int POWER_INTERCEPTOR_MODE_1 = 1;
	/** 电源键拦截：方案2 evdev grab 纯消费（安卓4.4 有效，现行为）。 */
	public static final int POWER_INTERCEPTOR_MODE_2 = 2;
	/**
	 * 电源键拦截：方案3 root（已废弃）。root 激活已移入 mini_shizuku 页面，
	 * 不再在拦截设置中展示；保留常量以兼容已存储的旧值（读取时视为关闭）。
	 */
	public static final int POWER_INTERCEPTOR_MODE_3 = 3;

	private static final String KEY_POWER_INTERCEPTOR_MODE = "power_interceptor_mode";

	/**
	 * 读取电源键拦截方案（高级设置 → 电源键拦截设置），默认关闭。
	 * 旧版本曾存储方案3（root），现已废弃（root 激活移入 mini_shizuku 页面），
	 * 读取到该值时归一化为关闭，避免下游逻辑遇到未知模式。
	 */
	public static int getPowerInterceptorMode(Context ctx) {
		int mode = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getInt(KEY_POWER_INTERCEPTOR_MODE, POWER_INTERCEPTOR_MODE_OFF);
		return mode == POWER_INTERCEPTOR_MODE_3 ? POWER_INTERCEPTOR_MODE_OFF : mode;
	}

	/** 保存电源键拦截方案。 */
	public static void setPowerInterceptorMode(Context ctx, int mode) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putInt(KEY_POWER_INTERCEPTOR_MODE, mode).apply();
		NokiaLog.i("SettingsStorage", "setPowerInterceptorMode: " + mode
				+ " (" + getPowerInterceptorModeName(mode) + ")");
	}

	/** 电源键拦截方案中文名（菜单展示 / 日志复用）。 */
	public static String getPowerInterceptorModeName(int mode) {
		switch (mode) {
			case POWER_INTERCEPTOR_MODE_OFF: return "关闭";
			case POWER_INTERCEPTOR_MODE_1:   return "方案1：grab+回放";
			case POWER_INTERCEPTOR_MODE_2:   return "方案2：纯消费";
			case POWER_INTERCEPTOR_MODE_3:   return "方案3：root";
			default: return "未知(" + mode + ")";
		}
	}
}
