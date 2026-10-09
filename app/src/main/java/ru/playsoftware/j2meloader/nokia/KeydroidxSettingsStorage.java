package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import ru.playsoftware.j2meloader.R;

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
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.playsoftware.j2meloader.BuildConfig;

/**
 * 原键桌面设置的 SharedPreferences 封装。
 * 管理快捷栏应用列表、壁纸、软键映射等设置项的读写。
 */
public class KeydroidxSettingsStorage {
	private static final String TAG = "KeydroidxSettingsStorage";


	private static final String PREFS_NAME = "nokia_desktop_settings";
	private static final String KEY_SHORTCUT_APPS = "shortcut_apps";
	private static final String KEY_WALLPAPER = "wallpaper";
	private static final String KEY_SOFT_LEFT_ACTION = "soft_left_action";
	private static final String KEY_SOFT_RIGHT_ACTION = "soft_right_action";
	private static final String KEY_PROTECTED_PACKAGES = "protected_packages";

	private final SharedPreferences prefs;
	private final Context context;

	public KeydroidxSettingsStorage(Context context) {
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
		synchronized (KeydroidxSettingsStorage.class) {
			List<ShortcutApp> result = new ArrayList<>();
			String json = prefs.getString(KEY_SHORTCUT_APPS, null);
			if (json == null) {
				// 首次启动：生成默认快捷应用（仅已安装的应用会被加入），并持久化
				KeydroidxLog.i("SettingsStorage", "shortcut_apps 未配置，生成默认快捷应用");
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
	 *   随后在后台校验一遍目标是否仍存在，若发现已卸载的残留项则清理后<b>再回调一次</b>
	 *   （详见 {@link #pruneUnavailableAsync}）；正常情况只有第一次回调。
	 * - 首次未配置：在后台线程构建默认快捷应用（含 PackageManager 批量查询）并持久化，
	 *   完成后回主线程回调。
	 */
	public void getShortcutAppsAsync(final OnShortcutAppsLoaded callback) {
		if (callback == null) return;
		final String json = prefs.getString(KEY_SHORTCUT_APPS, null);
		if (json != null) {
			// 已配置：同步解析（毫秒级，无 IPC），立即回调
			callback.onLoaded(parseShortcutApps(json));
			// 随后后台校验一遍：卸载掉的应用不会通知本应用，这里兜底把它从快捷栏里剔掉。
			// 有剔除时才回写并再次回调（触发快捷栏重建），正常情况下零写盘、零重建。
			pruneUnavailableAsync(callback);
			return;
		}
		// 首次启动：后台线程构建默认快捷应用（含 PackageManager 批量查询）
		KeydroidxLog.i("SettingsStorage", "shortcut_apps 未配置，后台生成默认快捷应用");
		final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
                @Override
                public void run() {
                        long start = System.currentTimeMillis();
                        final List<ShortcutApp> defaults;
                        try {
                                defaults = buildDefaultShortcutApps();
                        } catch (Throwable t) {
                                // 最后防线：默认构建里任何未预料的异常（如 ROM 层 IPC bug）都不允许带崩进程。
                                // 不落盘（保留 null），下次冷启动会重新尝试构建。
                                KeydroidxLog.e("SettingsStorage", "构建默认快捷应用失败，本次返回空列表且不落盘", t);
                                mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                                callback.onLoaded(new ArrayList<>());
                                        }
                                });
                                return;
                        }
                        long elapsed = System.currentTimeMillis() - start;
				// 写回前 double-check：构建默认值期间，设置页等可能已经保存了用户配置，
				// 此时禁止覆盖，否则用户的 7 个选择会被"回滚"成默认值（安卓/J2ME 全部丢失）。
				final List<ShortcutApp> actual;
				synchronized (KeydroidxSettingsStorage.class) {
					if (prefs.getString(KEY_SHORTCUT_APPS, null) == null) {
						KeydroidxLog.i("SettingsStorage", "后台生成默认快捷应用完成: " + defaults.size()
								+ " 个，耗时 " + elapsed + "ms，落盘");
						setShortcutApps(defaults);
					} else {
						KeydroidxLog.w("SettingsStorage", "后台默认构建完成，但期间已有用户配置，放弃覆盖");
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
				KeydroidxLog.w("SettingsStorage", "快捷栏配置存在同包重复入口，已去重: "
						+ arr.length() + " -> " + unique.size());
			}
			KeydroidxLog.i("SettingsStorage", "getShortcutApps: 从存储读取 " + result.size() + " 个应用");
		} catch (JSONException e) {
			KeydroidxLog.e("SettingsStorage", "getShortcutApps 解析失败", e);
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
	 * 后台校验已保存的快捷栏配置，把已卸载的应用剔除掉；有剔除时回写并回调清理后的列表。
	 * <p>
	 * 快捷栏是 SharedPreferences 里的一份 JSON 快照，应用被卸载后不会有任何通知，
	 * 快照里的条目会变成点不开的「幽灵图标」。这里在每次加载配置时按目标是否还存在过滤一次，
	 * 覆盖全部卸载路径（功能表卸载、百宝箱卸载 JAR、桌面不可见期间被卸载），
	 * 不依赖 {@link Intent#ACTION_PACKAGE_REMOVED} 是否送达。
	 * <p>
	 * 无失效项时既不写盘也不回调（避免无谓地重建快捷栏）。
	 */
	public void pruneUnavailableAsync(final OnShortcutAppsLoaded callback) {
		if (callback == null) return;
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final List<ShortcutApp> cleaned;
				synchronized (KeydroidxSettingsStorage.class) {
					// 重新读取而不是复用入参：校验期间设置页可能刚保存过，
					// 拿旧列表回写会把用户的选择覆盖掉
					List<ShortcutApp> stored = parseShortcutApps(
							prefs.getString(KEY_SHORTCUT_APPS, null));
					cleaned = filterUnavailable(stored);
					if (cleaned.size() == stored.size()) {
						return;
					}
					setShortcutApps(cleaned);
				}
				KeydroidxLog.i("SettingsStorage", "快捷栏已清理失效项，剩余 " + cleaned.size() + " 个");
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						callback.onLoaded(cleaned);
					}
				});
			}
		}, "prune-shortcuts").start();
	}

	/**
	 * 过滤掉目标已不存在的快捷项（安卓：包名未安装；J2ME：应用数据目录已删除）。
	 * <strong>仅在后台线程调用</strong>——含 PackageManager IPC 与文件 IO。
	 *
	 * @return 清理后的新列表（不改动入参）；无失效项时返回等长的新列表
	 */
	public List<ShortcutApp> filterUnavailable(List<ShortcutApp> apps) {
		if (apps == null || apps.isEmpty()) {
			return apps;
		}
		List<ShortcutApp> kept = new ArrayList<>(apps.size());
		List<String> removed = new ArrayList<>();
		PackageManager pm = context.getPackageManager();
		for (ShortcutApp app : apps) {
			if (isShortcutAvailable(pm, app)) {
				kept.add(app);
			} else {
				removed.add(app.label + " (" + app.appKey + ")");
			}
		}
		if (!removed.isEmpty()) {
			KeydroidxLog.i("SettingsStorage", "快捷栏发现已失效应用 " + removed.size()
					+ " 个，剔除: " + removed);
		}
		return kept;
	}

	/** 快捷项目标是否仍然存在。仅在后台线程调用。 */
	private boolean isShortcutAvailable(PackageManager pm, ShortcutApp app) {
		if (app.type == ShortcutApp.TYPE_ANDROID) {
			String pkg = packageOf(app);
			if (TextUtils.isEmpty(pkg)) {
				// 解析不出包名就无法判定，宁可保留也不擅自删用户配置
				return true;
			}
			try {
				pm.getPackageInfo(pkg, 0);
				return true;
			} catch (PackageManager.NameNotFoundException e) {
				KeydroidxLog.w(TAG, "getPackageInfo failed: " + e.getMessage());
				return false;
			} catch (Throwable t) {
				// 与 addPackageApp 同理：部分 ROM 的 CTA 钩子会对后台线程 PackageManager IPC 抛异常
				KeydroidxLog.w("SettingsStorage", "校验快捷栏应用是否安装失败，保留: " + pkg);
				return true;
			}
		}
		// J2ME：appKey(pathExt) 就是应用数据目录的绝对路径，目录被删说明 JAR 已被卸载
		if (TextUtils.isEmpty(app.appKey)) {
			return true;
		}
		File appDir = new File(app.appKey);
		if (appDir.isDirectory()) {
			return true;
		}
		// 目录不在，但连它的父目录（模拟器应用根目录）都访问不到时，说明是存储未挂载
		// 或根目录被整体搬走，不是"这个应用被卸载"——保留配置，避免误删用户勾选的快捷项
		File parent = appDir.getParentFile();
		if (parent == null || !parent.isDirectory()) {
			KeydroidxLog.w("SettingsStorage", "快捷项目录不可访问（存储未挂载？），保留: "
					+ app.label + " -> " + app.appKey);
			return true;
		}
		return false;
	}

	/** 取快捷项对应的安卓包名（appKey 的 "/" 前半段）；取不到返回 null。 */
	public static String packageOf(ShortcutApp app) {
		if (app.type != ShortcutApp.TYPE_ANDROID || TextUtils.isEmpty(app.appKey)) return null;
		int slash = app.appKey.indexOf('/');
		return slash > 0 ? app.appKey.substring(0, slash) : app.appKey;
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

		KeydroidxLog.i("SettingsStorage", "默认快捷应用生成完成: " + defaults.size() + " 个");
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
                	KeydroidxLog.w(TAG, "getPackageInfo failed: " + e.getMessage());
                        continue; // 未安装，尝试下一个
                }
                Intent launch;
                try {
                        launch = pm.getLaunchIntentForPackage(pkg);
                } catch (Throwable t) {
                        // 展讯等 ROM 的 CTA 权限钩子可能对后台线程的 PackageManager IPC 抛 NPE（系统层 bug），
                        // 这里兜底跳过该项，避免「构建默认快捷栏」的后台线程把整个进程带崩
                        KeydroidxLog.w("SettingsStorage", "查询音乐应用启动 Intent 失败，跳过: " + label + " (" + pkg + ")");
                        continue;
                }
                if (launch == null || launch.getComponent() == null) {
                        continue;
                }
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
			String appKey = launch.getComponent().getPackageName() + "/"
					+ launch.getComponent().getClassName();
			out.add(new ShortcutApp(ShortcutApp.TYPE_ANDROID, label, appKey, launch));
			KeydroidxLog.i("SettingsStorage", "默认音乐应用已加入: " + label + " -> " + appKey);
			return; // 仅取第一个
		}
		KeydroidxLog.i("SettingsStorage", "未找到已安装的音乐 app，跳过音乐快捷项");
	}

	/** 通过包名检查是否已安装，并取主启动 Activity 加入默认列表 */
	private void addPackageApp(PackageManager pm, List<ShortcutApp> out, String pkg, String label) {
        try {
                pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
                KeydroidxLog.i("SettingsStorage", "默认应用未安装，跳过: " + label + " (" + pkg + ")");
                return;
        }
        Intent launch;
        try {
                launch = pm.getLaunchIntentForPackage(pkg);
        } catch (Throwable t) {
                // 同 addMusicApp：ROM 层 CTA 钩子可能对后台线程 IPC 抛 NPE，兜底跳过
                KeydroidxLog.w("SettingsStorage", "查询默认应用启动 Intent 失败，跳过: " + label + " (" + pkg + ")");
                return;
        }
        if (launch == null || launch.getComponent() == null) {
			KeydroidxLog.w("SettingsStorage", "默认应用无启动 Intent，跳过: " + label + " (" + pkg + ")");
			return;
		}
		launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		String appKey = launch.getComponent().getPackageName() + "/"
				+ launch.getComponent().getClassName();
		out.add(new ShortcutApp(ShortcutApp.TYPE_ANDROID, label, appKey, launch));
		KeydroidxLog.i("SettingsStorage", "默认应用已加入: " + label + " -> " + appKey);
	}

	/** 通过系统隐式 Intent 解析出可用 Activity 并加入默认列表 */
	private void addActionApp(PackageManager pm, List<ShortcutApp> out, String key, String label) {
                Intent intent = buildActionIntent(key);
                if (intent == null) return;
                ResolveInfo ri;
                try {
                        ri = pm.resolveActivity(intent, 0);
                } catch (Throwable t) {
                        // 同 addMusicApp：ROM 层 CTA 钩子可能对后台线程 IPC 抛 NPE，兜底跳过
                        KeydroidxLog.w("SettingsStorage", "解析系统应用失败，跳过: " + label + " (" + key + ")");
                        return;
                }
                if (ri == null || ri.activityInfo == null) {
			KeydroidxLog.i("SettingsStorage", "默认应用无可用 Activity，跳过: " + label + " (" + key + ")");
			return;
		}
		ActivityInfo ai = ri.activityInfo;
		Intent launch = new Intent(Intent.ACTION_MAIN);
		launch.addCategory(Intent.CATEGORY_LAUNCHER);
		launch.setClassName(ai.packageName, ai.name);
		launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		String appKey = ai.packageName + "/" + ai.name;
		out.add(new ShortcutApp(ShortcutApp.TYPE_ANDROID, label, appKey, launch));
		KeydroidxLog.i("SettingsStorage", "默认应用已加入: " + label + " -> " + appKey);
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
				KeydroidxLog.e("SettingsStorage", "setShortcutApps 序列化失败: " + app.label, e);
			}
		}
		prefs.edit().putString(KEY_SHORTCUT_APPS, arr.toString()).apply();
		KeydroidxLog.i("SettingsStorage", "setShortcutApps: 保存 " + apps.size() + " 个应用");
	}

	// ── 壁纸 ──

	public static final String WALLPAPER_DEFAULT = "default";           // 经典深蓝
	public static final String WALLPAPER_OBSIDIAN_BLACK = "obsidian_black"; // 曜石黑
	public static final String WALLPAPER_CYAN_SEA = "cyan_sea";         // 青翠碧海
	public static final String WALLPAPER_EMERALD_GREEN = "emerald_green"; // 翡翠深绿
	public static final String WALLPAPER_WINE_PURPLE = "wine_purple";   // 典雅酒红/紫
	public static final String WALLPAPER_AMBER_GOLD = "amber_gold";     // 琥珀暖金
	public static final String WALLPAPER_CUSTOM = "custom";             // 自定义图片壁纸

	public String getWallpaper() {
		return prefs.getString(KEY_WALLPAPER, WALLPAPER_DEFAULT);
	}

	public void setWallpaper(String wallpaperId) {
		prefs.edit().putString(KEY_WALLPAPER, wallpaperId).apply();
		KeydroidxLog.i("SettingsStorage", "setWallpaper: " + wallpaperId);
	}

	// ── 自定义壁纸缩放模式（桌面设置 → 外观与显示 → 壁纸设置） ──

	/** 缩放模式：居中裁剪（铺满屏幕，裁掉溢出部分）。 */
	public static final int WALLPAPER_SCALE_CROP = 0;
	/** 缩放模式：拉伸铺满（忽略宽高比，可能变形）。 */
	public static final int WALLPAPER_SCALE_STRETCH = 1;
	/** 缩放模式：适应屏幕（完整显示，留边处显示主题背景色）。 */
	public static final int WALLPAPER_SCALE_FIT = 2;

	private static final String KEY_WALLPAPER_SCALE = "wallpaper_scale";

	/**
	 * 读取自定义壁纸的缩放模式，默认居中裁剪。
	 * 静态方法：供 {@link KeydroidxWallpaper} 在构建背景 Drawable 时读取。
	 */
	public static int getWallpaperScale(Context ctx) {
		int mode = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getInt(KEY_WALLPAPER_SCALE, WALLPAPER_SCALE_CROP);
		if (mode < WALLPAPER_SCALE_CROP || mode > WALLPAPER_SCALE_FIT) {
			return WALLPAPER_SCALE_CROP;
		}
		return mode;
	}

	/** 保存自定义壁纸的缩放模式。 */
	public static void setWallpaperScale(Context ctx, int mode) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putInt(KEY_WALLPAPER_SCALE, mode).apply();
		KeydroidxLog.i("SettingsStorage", "setWallpaperScale: " + mode);
	}

	/** 获取当前壁纸对应的内置 Drawable Resource ID（如果为 custom 则返回 0） */
	public int getWallpaperDrawableRes() {
		String wp = getWallpaper();
		if (WALLPAPER_OBSIDIAN_BLACK.equals(wp)) {
			return R.drawable.bg_keydroidx_obsidian_black;
		} else if (WALLPAPER_CYAN_SEA.equals(wp)) {
			return R.drawable.bg_keydroidx_cyan_sea;
		} else if (WALLPAPER_EMERALD_GREEN.equals(wp)) {
			return R.drawable.bg_keydroidx_emerald_green;
		} else if (WALLPAPER_WINE_PURPLE.equals(wp)) {
			return R.drawable.bg_keydroidx_wine_purple;
		} else if (WALLPAPER_AMBER_GOLD.equals(wp)) {
			return R.drawable.bg_keydroidx_amber_gold;
		}
		return R.drawable.bg_keydroidx_desktop;
	}

	// ── 左右软键 ──

	public String getSoftLeftAction() {
		return prefs.getString(KEY_SOFT_LEFT_ACTION, "album");
	}

	public void setSoftLeftAction(String action) {
		prefs.edit().putString(KEY_SOFT_LEFT_ACTION, action).apply();
		KeydroidxLog.i("SettingsStorage", "setSoftLeftAction: " + action);
	}

	public String getSoftRightAction() {
		return prefs.getString(KEY_SOFT_RIGHT_ACTION, "contacts");
	}

	public void setSoftRightAction(String action) {
		prefs.edit().putString(KEY_SOFT_RIGHT_ACTION, action).apply();
		KeydroidxLog.i("SettingsStorage", "setSoftRightAction: " + action);
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
			KeydroidxLog.i("SettingsStorage", "getProtectedPackages: 读取 " + result.size() + " 个包");
		} catch (JSONException e) {
			KeydroidxLog.e("SettingsStorage", "getProtectedPackages 解析失败", e);
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
		KeydroidxLog.i("SettingsStorage", "setProtectedPackages: 保存 " + arr.length() + " 个包");
	}

	// ── 字体大小与字体样式 ──

	private static final String KEY_FONT_SCALE = "font_scale";
	private static final String KEY_FONT_ID = "font_id";

	/**
	 * 读取当前选用的字体 ID，默认方舟像素体 12px (ark_12px)。
	 */
	public String getFontId() {
		return getFontId(context);
	}

	public void setFontId(String fontId) {
		setFontId(context, fontId);
	}

	public static String getFontId(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getString(KEY_FONT_ID, KeydroidxFontManager.FONT_ID_ARK_12PX);
	}

	/**
	 * 保存当前选用的字体 ID。
	 */
	public static void setFontId(Context ctx, String fontId) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putString(KEY_FONT_ID, fontId).apply();
		KeydroidxFontManager.setCurrentFontId(fontId);
		KeydroidxFontManager.invalidate();
		notifySettingsChanged(ctx);
		KeydroidxLog.i("SettingsStorage", "setFontId: " + fontId);
	}

	/**
	 * 读取用户字体缩放系数（桌面设置 → 字体大小），默认 1.0。
	 * 静态方法：供 {@link KeydroidxBaseActivity#attachBaseContext} 在 Activity 早期读取。
	 */
	public static float getFontScale(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getFloat(KEY_FONT_SCALE, 1f);
	}

	/** 保存用户字体缩放系数。 */
	public static void setFontScale(Context ctx, float scale) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putFloat(KEY_FONT_SCALE, scale).apply();
		notifySettingsChanged(ctx);
		KeydroidxLog.i("SettingsStorage", "setFontScale: " + scale);
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

	/**
	 * 保存日志记录开关（true=详细日志）。
	 * 同时同步到 common 的日志分级开关，保证两个存储一致（common 初始化时读的是它自己的 SP）。
	 */
	public static void setFileLogEnabled(Context ctx, boolean enabled) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(KEY_LOG_FILE, enabled).apply();
		KeydroidxLog.setDetailedLogEnabled(ctx, enabled);
		KeydroidxLog.i("SettingsStorage", "setFileLogEnabled: " + enabled);
	}

	// ── 通知使用权提示开关 ──

	private static final String KEY_NOTIFY_ACCESS_PROMPT_DISABLED = "notify_access_prompt_disabled";

	/**
	 * 是否已关闭「通知使用权」授予提示（用户在桌面提示弹窗中选了「不再提示」）。
	 * 关闭后桌面不再主动弹窗，用户仍可从「桌面设置 → 系统与权限 → 通知中心 → 通知使用权」手动开启。
	 */
	public static boolean isNotifyAccessPromptDisabled(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getBoolean(KEY_NOTIFY_ACCESS_PROMPT_DISABLED, false);
	}

        /** 设置是否关闭「通知使用权」授予提示。 */
        public static void setNotifyAccessPromptDisabled(Context ctx, boolean disabled) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_NOTIFY_ACCESS_PROMPT_DISABLED, disabled).apply();
                KeydroidxLog.i("SettingsStorage", "setNotifyAccessPromptDisabled: " + disabled);
        }

        // ── 通知中心（桌面设置 → 系统与权限 → 通知中心） ──

        private static final String KEY_NOTIFICATION_BAR_ENABLED = "notification_bar_enabled";
        private static final String KEY_NOTIFICATION_SHOW_ONGOING = "notification_show_ongoing";

        /**
         * 桌面是否显示通知条（有通知时出现在顶部快捷栏下方）。默认开启。
         * 关闭后仍可从「功能表 → 通知中心」进入列表页。
         */
        public static boolean isNotificationBarEnabled(Context ctx) {
                return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .getBoolean(KEY_NOTIFICATION_BAR_ENABLED, true);
        }

        /** 设置桌面通知条开关。 */
        public static void setNotificationBarEnabled(Context ctx, boolean enabled) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_NOTIFICATION_BAR_ENABLED, enabled).apply();
                KeydroidxLog.i("SettingsStorage", "setNotificationBarEnabled: " + enabled);
        }

        /**
         * 通知列表是否显示常驻通知（FLAG_ONGOING_EVENT，如音乐播放中、下载中）。
         * 默认关闭：这类通知无法清除，混在列表里会干扰「未读」语义。
         */
        public static boolean isNotificationShowOngoing(Context ctx) {
                return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .getBoolean(KEY_NOTIFICATION_SHOW_ONGOING, false);
        }

        /** 设置是否显示常驻通知。 */
        public static void setNotificationShowOngoing(Context ctx, boolean show) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(KEY_NOTIFICATION_SHOW_ONGOING, show).apply();
                KeydroidxLog.i("SettingsStorage", "setNotificationShowOngoing: " + show);
        }

	// ── 桌面保活开关（桌面设置 → 系统与权限 → 保活服务） ──

	private static final String KEY_DESKTOP_KEEPALIVE = "desktop_keepalive_enabled";

	/**
	 * 是否启用桌面常驻保活前台服务，默认开启。
	 * <p>开启：桌面 onCreate 拉起 {@link KeydroidxDesktopKeepAliveService} 常驻前台，
	 * 进程拿到前台优先级（adj），按 HOME 秒回、拦截器 socket 不易断；
	 * 代价是通知栏长期占用一条常驻通知。
	 * <p>关闭：不再拉起该服务；关闭瞬间由设置页停掉正在运行的服务并撤下通知。
	 * 桌面功能不受影响，只是进程更容易被系统回收（按 HOME 需冷启动）。
	 */
	public static boolean isDesktopKeepAliveEnabled(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getBoolean(KEY_DESKTOP_KEEPALIVE, true);
	}

	/** 保存桌面保活开关（切换瞬间的 start/stop 由设置页负责）。 */
	public static void setDesktopKeepAliveEnabled(Context ctx, boolean enabled) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(KEY_DESKTOP_KEEPALIVE, enabled).apply();
		KeydroidxLog.i("SettingsStorage", "setDesktopKeepAliveEnabled: " + enabled);
	}

	// ── 触屏模式（屏幕分为两部分：上半部桌面内容，下半部虚拟触摸按键） ──

	private static final String KEY_TOUCH_MODE = "touch_mode_enabled";

	/** 是否开启触屏模式。默认关闭（实体按键机模式）。 */
	public static boolean isTouchMode(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getBoolean(KEY_TOUCH_MODE, false);
	}

	/** 设置触屏模式开关。 */
	public static void setTouchMode(Context ctx, boolean enabled) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(KEY_TOUCH_MODE, enabled).apply();
		KeydroidxLog.i(TAG, "setTouchMode: " + enabled);
	}

	public boolean isTouchModeEnabled() {
		return prefs.getBoolean(KEY_TOUCH_MODE, false);
	}

	public void setTouchModeEnabled(boolean enabled) {
		prefs.edit().putBoolean(KEY_TOUCH_MODE, enabled).apply();
		KeydroidxLog.i(TAG, "setTouchModeEnabled: " + enabled);
	}

	// ── 授权模式（mini_shizuku 设置页：root 模式 / mini_shizuku 模式，双轨制） ──

	/** 授权模式：mini_shizuku 模式（shell 身份，电脑 adb 激活）。默认值，兼容最广。 */
	public static final int AUTH_MODE_SHIZUKU = 0;
	/** 授权模式：root 模式（root 身份，桌面内 root 激活，功能最全）。 */
	public static final int AUTH_MODE_ROOT = 1;

	private static final String KEY_AUTH_MODE = "auth_mode";

	/**
	 * 读取授权模式。默认 mini_shizuku 模式（开箱即用、兼容最广）。
	 * <p>注意：模式是「用户期望的服务端身份」，实际以 WHOAMI 探测到的 uid 为准，
	 * 两者不一致时设置页会提示重新激活（见设计文档 §3.4）。
	 */
	public static int getAuthMode(Context ctx) {
		int mode = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getInt(KEY_AUTH_MODE, AUTH_MODE_SHIZUKU);
		if (mode != AUTH_MODE_SHIZUKU && mode != AUTH_MODE_ROOT) {
			return AUTH_MODE_SHIZUKU;
		}
		return mode;
	}

	/** 保存授权模式。 */
	public static void setAuthMode(Context ctx, int mode) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putInt(KEY_AUTH_MODE, mode).apply();
		KeydroidxLog.i("SettingsStorage", "setAuthMode: " + getAuthModeName(mode));
	}

	/** 授权模式中文名（菜单展示 / 日志复用）。 */
	public static String getAuthModeName(int mode) {
		return mode == AUTH_MODE_ROOT ? "root 模式" : "mini_shizuku 模式";
	}

	// ── mini_shizuku 重启恢复（服务端是 app_process 独立进程，重启后消失） ──

	/**
	 * 是否「历史上曾成功激活过 mini_shizuku」。默认 false。
	 * <p>用途：adb（mini_shizuku）模式下服务离线时，只有曾经激活过的用户才值得被提醒重新激活，
	 * 未用过的人不打扰（需求 2026-10）。置位点：探测到服务在线（任意身份）与 root 激活成功。
	 */
	private static final String KEY_SHIZUKU_EVER_ACTIVATED = "shizuku_ever_activated";

	/**
	 * 上一次已弹过「服务已失效」提醒的开机令牌（见 {@link #currentBootToken()}）。默认 -1（从未提醒）。
	 * <p>用途：同一次开机内只提醒一次；重启后令牌变化，会再提醒一次。
	 */
	private static final String KEY_SHIZUKU_NOTIFIED_BOOT_TOKEN = "shizuku_notified_boot_token";

	/**
	 * 上一次「自动激活尝试」所处的开机令牌（默认 -1 = 从未尝试）。
	 * <p>用途：同一次开机内只自动尝试一次，避免反复弹 su 授权框；重启后令牌变化，会重新尝试。
	 */
	private static final String KEY_SHIZUKU_AUTO_ACTIVATE_BOOT_TOKEN = "shizuku_auto_activate_boot_token";

	/** 读取「曾激活过 mini_shizuku」标志。 */
	public static boolean hasShizukuEverActivated(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getBoolean(KEY_SHIZUKU_EVER_ACTIVATED, false);
	}

	/** 置位「曾激活过 mini_shizuku」（幂等，已置位时不重复写盘）。 */
	public static void setShizukuEverActivated(Context ctx, boolean activated) {
		if (!activated) {
			ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
					.edit().putBoolean(KEY_SHIZUKU_EVER_ACTIVATED, false).apply();
			return;
		}
		if (hasShizukuEverActivated(ctx)) {
			return;
		}
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putBoolean(KEY_SHIZUKU_EVER_ACTIVATED, true).apply();
		KeydroidxLog.i("SettingsStorage", "mini_shizuku 曾激活标志已置位");
	}

	/**
	 * 本次开机的令牌：{@code System.currentTimeMillis() - SystemClock.elapsedRealtime()}，
	 * 近似等于「开机时刻的 wall clock」，重启后必变、同一次开机内进程反复重启也不变。
	 * <p>用它能精确区分「重启后首次进桌面」，无需 BOOT_COMPLETED 广播与额外权限。
	 */
	public static long currentBootToken() {
		return System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
	}

	/** 上一次已提醒过的开机令牌（-1 = 从未提醒）。 */
	public static long getShizukuNotifiedBootToken(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getLong(KEY_SHIZUKU_NOTIFIED_BOOT_TOKEN, -1L);
	}

	/** 记录「本次开机已提醒过」的开机令牌。 */
	public static void setShizukuNotifiedBootToken(Context ctx, long token) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putLong(KEY_SHIZUKU_NOTIFIED_BOOT_TOKEN, token).apply();
	}

	/** 上一次自动激活尝试所处的开机令牌（-1 = 从未尝试）。 */
	public static long getShizukuAutoActivateBootToken(Context ctx) {
		return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getLong(KEY_SHIZUKU_AUTO_ACTIVATE_BOOT_TOKEN, -1L);
	}

	/** 记录「本次开机已自动激活尝试过」的开机令牌。 */
	public static void setShizukuAutoActivateBootToken(Context ctx, long token) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putLong(KEY_SHIZUKU_AUTO_ACTIVATE_BOOT_TOKEN, token).apply();
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
		KeydroidxLog.i("SettingsStorage", "setPowerInterceptorMode: " + mode
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

	// ── 图标包与单应用图标覆盖（桌面设置 → 外观与显示 → 图标包；功能表 → 选项 → 更换图标） ──

	/** 默认图标包：内置 S60 */
	public static final String ICON_PACK_DEFAULT = "s60_builtin";

	private static final String KEY_ICON_PACK_ID = "icon_pack_id";
	private static final String KEY_ICON_OVERRIDES = "icon_overrides";

	/** 覆盖表内存缓存：{pkg -> [packId, iconName]}；写入即失效，避免网格逐项解析 JSON */
	private static volatile Map<String, String[]> iconOverrideCache;

	/** 当前全局图标包 ID；未设置或为空时返回默认的内置 S60 包 */
	public static String getIconPackId(Context ctx) {
		String id = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.getString(KEY_ICON_PACK_ID, ICON_PACK_DEFAULT);
		return (id == null || id.isEmpty()) ? ICON_PACK_DEFAULT : id;
	}

	/** 保存全局图标包 ID（{@code none} 表示不使用图标包，全部用应用原图标） */
	public static void setIconPackId(Context ctx, String packId) {
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putString(KEY_ICON_PACK_ID, packId).apply();
		notifySettingsChanged(ctx);
		KeydroidxLog.i("SettingsStorage", "setIconPackId: " + packId);
	}

	/** 该应用是否存在单应用图标覆盖 */
	public static boolean hasIconOverride(Context ctx, String pkg) {
		return overrides(ctx).containsKey(pkg);
	}

	/** 单应用覆盖所属的图标包 ID；无覆盖返回 null */
	public static String getIconOverridePack(Context ctx, String pkg) {
		String[] v = overrides(ctx).get(pkg);
		return v != null ? v[0] : null;
	}

	/** 单应用覆盖的图标名；无覆盖返回 null */
	public static String getIconOverrideName(Context ctx, String pkg) {
		String[] v = overrides(ctx).get(pkg);
		return v != null ? v[1] : null;
	}

	/** 写入单应用图标覆盖（覆盖同包旧值） */
	public static void setIconOverride(Context ctx, String pkg, String packId, String iconName) {
		if (pkg == null || packId == null || iconName == null) return;
		Map<String, String[]> map = new HashMap<>(overrides(ctx));
		map.put(pkg, new String[]{packId, iconName});
		writeOverrides(ctx, map);
		KeydroidxLog.i("SettingsStorage", "setIconOverride: " + pkg + " → " + packId + "/" + iconName);
	}

	/**
	 * 清空<b>全部</b>单应用图标覆盖（图标包设置页的「重置全部图标」）。
	 * 调用方通常还需把全局图标包一并设为 {@link KeydroidxIconPackManager#PACK_NONE}，
	 * 才能让所有图标都回到应用原图标。
	 *
	 * @return 被清除的覆盖条数（0 表示本来就没有覆盖）
	 */
	public static int clearAllIconOverrides(Context ctx) {
		Map<String, String[]> map = overrides(ctx);
		if (map.isEmpty()) return 0;
		int count = map.size();
		writeOverrides(ctx, new HashMap<>());
		KeydroidxLog.i("SettingsStorage", "clearAllIconOverrides: 清除 " + count + " 条覆盖");
		return count;
	}

	/** 清除单应用图标覆盖（恢复为按全局图标包解析） */
	public static void clearIconOverride(Context ctx, String pkg) {
		Map<String, String[]> map = new HashMap<>(overrides(ctx));
		if (map.remove(pkg) == null) return;
		writeOverrides(ctx, map);
		KeydroidxLog.i("SettingsStorage", "clearIconOverride: " + pkg);
	}

	/** 被单应用覆盖过的包名集合 */
	public static Set<String> getIconOverriddenPackages(Context ctx) {
		return new HashSet<>(overrides(ctx).keySet());
	}

	/**
	 * 图标外观状态指纹：全局图标包 ID + 全部单应用覆盖的紧凑摘要。
	 * <p>用途：功能表把「已解析图标」连同指纹一起缓存，指纹变化（切换图标包 / 改覆盖）时
	 * 缓存自动失效重建，避免返回功能表时仍显示旧图标。</p>
	 */
	public static String getIconStateFingerprint(Context ctx) {
		String packId = getIconPackId(ctx);
		Map<String, String[]> map = overrides(ctx);
		if (map.isEmpty()) {
			return packId;
		}
		int hash = 0;
		for (Map.Entry<String, String[]> e : map.entrySet()) {
			hash = hash * 31 + e.getKey().hashCode();
			hash = hash * 31 + e.getValue()[0].hashCode();
			hash = hash * 31 + e.getValue()[1].hashCode();
		}
		return packId + "#" + map.size() + "#" + Integer.toHexString(hash);
	}

	/** 覆盖表（带进程内缓存，命中为纯内存操作） */
	private static Map<String, String[]> overrides(Context ctx) {
		Map<String, String[]> cached = iconOverrideCache;
		if (cached != null) return cached;
		synchronized (KeydroidxSettingsStorage.class) {
			if (iconOverrideCache != null) return iconOverrideCache;
			Map<String, String[]> map = new HashMap<>();
			try {
				String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
						.getString(KEY_ICON_OVERRIDES, null);
				if (json != null && !json.isEmpty()) {
					JSONObject obj = new JSONObject(json);
					java.util.Iterator<String> keys = obj.keys();
					while (keys.hasNext()) {
						String pkg = keys.next();
						JSONObject item = obj.optJSONObject(pkg);
						if (item == null) continue;
						String pack = item.optString("pack", null);
						String icon = item.optString("icon", null);
						if (pack == null || pack.isEmpty() || icon == null || icon.isEmpty()) continue;
						map.put(pkg, new String[]{pack, icon});
					}
				}
			} catch (Exception e) {
				// 历史脏数据：忽略整表，退化为「无覆盖」而不是崩溃
				KeydroidxLog.w("SettingsStorage", "icon_overrides 解析失败: " + e.getMessage());
			}
			map = java.util.Collections.unmodifiableMap(map);
			iconOverrideCache = map;
			return map;
		}
	}

	private static void writeOverrides(Context ctx, Map<String, String[]> map) {
		JSONObject obj = new JSONObject();
		for (Map.Entry<String, String[]> e : map.entrySet()) {
			try {
				JSONObject item = new JSONObject();
				item.put("pack", e.getValue()[0]);
				item.put("icon", e.getValue()[1]);
				obj.put(e.getKey(), item);
			} catch (JSONException ex) {
				KeydroidxLog.e("SettingsStorage", "序列化图标覆盖失败: " + e.getKey(), ex);
			}
		}
		ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
				.edit().putString(KEY_ICON_OVERRIDES, obj.toString()).apply();
		iconOverrideCache = null;
		notifySettingsChanged(ctx);
	}

	// ── 主题设置 ──

	public String getThemeId() {
		return prefs.getString(KEY_WALLPAPER, KeydroidxTheme.THEME_CLASSIC_BLUE);
	}

	public KeydroidxTheme.ThemeDef getTheme() {
		return KeydroidxTheme.getTheme(getThemeId());
	}

	public void setThemeId(String themeId) {
		prefs.edit().putString(KEY_WALLPAPER, themeId).apply();
		notifySettingsChanged(context);
		KeydroidxLog.i("SettingsStorage", "setThemeId: " + themeId);
	}

	private static void notifySettingsChanged(Context ctx) {
		if (ctx == null) return;
		try {
			Uri uri = Uri.parse("content://" + ctx.getPackageName() + ".keyprovider/settings");
			ctx.getContentResolver().notifyChange(uri, null);
			Uri keysUri = Uri.parse("content://" + ctx.getPackageName() + ".keyprovider/keys");
			ctx.getContentResolver().notifyChange(keysUri, null);
		} catch (Exception e) {
			KeydroidxLog.w("SettingsStorage", "notifySettingsChanged failed: " + e.getMessage());
		}
	}
}