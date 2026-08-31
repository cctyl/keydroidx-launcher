package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import com.google.gson.GsonBuilder;

import io.github.cctyl.nokia.common.log.NokiaLog;
import java.io.File;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.keyboard.KeyMapper;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.SparseIntArrayAdapter;

/**
 * 全局 JAR 设置（profile）管理器。
 *
 * 目标：提供一个与单个 JAR 设置界面完全一致的「全局 profile」，
 * 每个 JAR 首次启动时会自动从这里复制配置（与 J2ME-Loader 原生的「默认 profile」机制一致）。
 * 用户在桌面「按键绑定」里设置的 7 个核心动作（上/下/左/右/确认/左软键/右软键）
 * 会默认同步进该 profile 的按键映射（KeyMappings），除非用户在全局设置里手动改过映射。
 */
public final class NokiaGlobalProfile {

	public static final String PROFILE_NAME = "nokia_global";
	public static final String EXTRA_GLOBAL_PROFILE = "nokia_global_profile";

	/** 记录「上次自动同步写入的按键映射」序列化值，用于判断用户是否手动改过。 */
	private static final String PREF_SYNC_KEY = "nokia_global_keymap_sync";

	private NokiaGlobalProfile() {
	}

	/**
	 * 确保全局 profile 存在并设为默认。
	 * 不存在则创建（写入默认 config.json 并注入桌面按键绑定），并设为 PREF_DEFAULT_PROFILE。
	 */
	public static void ensureGlobalProfile(Context context) {
		Context app = context.getApplicationContext();
		File profileDir = new File(Config.getProfilesDir(), PROFILE_NAME);
		//noinspection ResultOfMethodCallIgnored
		profileDir.mkdirs();

		File configFile = new File(profileDir, Config.MIDLET_CONFIG_FILE);
		if (!configFile.exists()) {
			ProfileModel params = new ProfileModel(profileDir);
			// 注入桌面按键绑定作为默认按键映射
			params.keyMappings = buildDesktopKeyMappings(app);
			ProfilesManager.saveConfig(params);
			saveSyncMarker(app, params.keyMappings);
			NokiaLog.i("GlobalProfile", "创建全局 profile 并注入桌面按键绑定");
		} else {
			NokiaLog.i("GlobalProfile", "全局 profile 已存在，跳过创建");
		}

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
		if (!PROFILE_NAME.equals(prefs.getString(Constants.PREF_DEFAULT_PROFILE, null))) {
			prefs.edit().putString(Constants.PREF_DEFAULT_PROFILE, PROFILE_NAME).apply();
			NokiaLog.i("GlobalProfile", "设置默认 profile = " + PROFILE_NAME);
		}
	}

	/**
	 * 把「全局 profile」的配置覆盖到单个 JAR 的 config 目录。
	 * 与首次安装 JAR 时自动套用默认 profile 的逻辑一致（{@link FileUtils#copyFiles} +
	 * {@link Config#startApp} 中计算 configDir 的方式）：configDir 不存在会自动创建，
	 * 只覆盖 config.json / 键盘布局等 profile 文件，不删改 per-app 的 dex/icon/data。
	 * 供百宝箱「同步全部」按已装 JAR 列表逐个调用，避免扫磁盘目录漏掉未启动过的 JAR。
	 * 在后台线程调用；返回是否同步成功（pathExt 非空且能定位 configDir）。
	 */
	public static boolean syncAppConfig(Context context, String name, String pathExt) {
		if (pathExt == null) {
			NokiaLog.w("GlobalProfile", "syncAppConfig: pathExt 为空，跳过 " + name);
			return false;
		}
		Context app = context.getApplicationContext();
		ensureGlobalProfile(app);
		File globalDir = new File(Config.getProfilesDir(), PROFILE_NAME);
		File appDirFile = new File(pathExt);
		File parent = appDirFile.getParentFile();
		File grand = parent == null ? null : parent.getParentFile();
		if (grand == null) {
			NokiaLog.w("GlobalProfile", "syncAppConfig: 无法定位 workDir, pathExt=" + pathExt);
			return false;
		}
		File configDir = new File(grand.getPath() + Config.MIDLET_CONFIGS_DIR + appDirFile.getName());
		FileUtils.copyFiles(globalDir, configDir, null);
		NokiaLog.i("GlobalProfile", "syncAppConfig: 已同步 " + name + " -> " + configDir.getAbsolutePath());
		return true;
	}

	/** 打开全局 JAR 设置界面（与编辑单个 JAR 设置完全相同的界面）。 */
	public static void openGlobalSettings(Context context) {
		ensureGlobalProfile(context);
		Intent intent = new Intent(Constants.ACTION_EDIT_PROFILE, Uri.parse(PROFILE_NAME),
				context, ru.playsoftware.j2meloader.config.ConfigActivity.class);
		intent.putExtra(EXTRA_GLOBAL_PROFILE, true);
		context.startActivity(intent);
	}

	/** 判断当前 Intent 是否在编辑「全局 profile」（用于让 KeyMapperActivity 以桌面绑定为基线）。 */
	public static boolean isGlobalProfile(Intent intent) {
		return intent != null && intent.getBooleanExtra(EXTRA_GLOBAL_PROFILE, false);
	}

	/**
	 * 桌面按键绑定变化后调用：把 7 个核心动作同步到全局 profile 的 keyMappings。
	 * 若用户在全局设置里手动改过映射，则尊重用户、停止自动同步。
	 */
	public static void syncKeyBindings(Context context) {
		Context app = context.getApplicationContext();
		File profileDir = new File(Config.getProfilesDir(), PROFILE_NAME);
		if (!profileDir.exists()) {
			NokiaLog.i("GlobalProfile", "全局 profile 不存在，先创建");
			ensureGlobalProfile(context);
			return;
		}
		ProfileModel params = ProfilesManager.loadConfig(profileDir);
		if (params == null) {
			params = new ProfileModel(profileDir);
		}

		SparseIntArray desktopNow = buildDesktopKeyMappings(app);
		String desktopJson = serialize(desktopNow);
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
		String lastSynced = prefs.getString(PREF_SYNC_KEY, null);

		SparseIntArray current = params.keyMappings;

		if (current == null) {
			// 用户没配过按键（或重置为默认）→ 跟随桌面
			params.keyMappings = desktopNow;
			ProfilesManager.saveConfig(params);
			prefs.edit().putString(PREF_SYNC_KEY, desktopJson).apply();
			NokiaLog.i("GlobalProfile", "同步桌面绑定到全局 profile（current=null，跟随桌面）");
			return;
		}
		if (equalMaps(current, desktopNow)) {
			// 已与桌面一致，刷新同步标记
			prefs.edit().putString(PREF_SYNC_KEY, desktopJson).apply();
			NokiaLog.i("GlobalProfile", "全局按键映射已与桌面一致，无需修改");
			return;
		}
		if (lastSynced != null && serialize(current).equals(lastSynced)) {
			// 当前值正是上次我们同步写入的（桌面变了）→ 更新
			params.keyMappings = desktopNow;
			ProfilesManager.saveConfig(params);
			prefs.edit().putString(PREF_SYNC_KEY, desktopJson).apply();
			NokiaLog.i("GlobalProfile", "桌面绑定已变更，更新全局 profile 按键映射");
			return;
		}
		// 用户手动改过 → 尊重用户，停止同步，但记录当前值作为新基线
		prefs.edit().putString(PREF_SYNC_KEY, serialize(current)).apply();
		NokiaLog.i("GlobalProfile", "检测到用户手动修改了全局按键映射，停止自动同步");
	}

	/**
	 * 根据桌面「按键绑定」生成 J2ME 的按键映射（android keyCode -> MIDP key）。
	 * 以 J2ME 默认映射为基底，叠加 7 个核心动作的桌面绑定（优先级更高）。
	 * 其余按键（数字键、*、# 等）沿用 J2ME-Loader 默认值。
	 */
	public static SparseIntArray buildDesktopKeyMappings(Context context) {
		SparseIntArray map = KeyMapper.getDefaultKeyMap().clone();
		NokiaKeyBinding nkb = new NokiaKeyBinding(context);
		inject(nkb, NokiaKeyBinding.ACTION_UP, Canvas.KEY_UP, map);
		inject(nkb, NokiaKeyBinding.ACTION_DOWN, Canvas.KEY_DOWN, map);
		inject(nkb, NokiaKeyBinding.ACTION_LEFT, Canvas.KEY_LEFT, map);
		inject(nkb, NokiaKeyBinding.ACTION_RIGHT, Canvas.KEY_RIGHT, map);
		inject(nkb, NokiaKeyBinding.ACTION_SELECT, Canvas.KEY_FIRE, map);
		inject(nkb, NokiaKeyBinding.ACTION_SOFT_LEFT, Canvas.KEY_SOFT_LEFT, map);
		inject(nkb, NokiaKeyBinding.ACTION_SOFT_RIGHT, Canvas.KEY_SOFT_RIGHT, map);
		return map;
	}

	private static void inject(NokiaKeyBinding nkb, int action, int midpKey, SparseIntArray map) {
		int kc = nkb.getKeyCode(action);
		if (kc != KeyEvent.KEYCODE_UNKNOWN) {
			map.put(kc, midpKey); // 桌面绑定优先级高，覆盖 J2ME 默认
		}
	}

	private static void saveSyncMarker(Context app, SparseIntArray map) {
		PreferenceManager.getDefaultSharedPreferences(app)
				.edit().putString(PREF_SYNC_KEY, serialize(map)).apply();
	}

	private static String serialize(SparseIntArray arr) {
		return new GsonBuilder()
				.registerTypeAdapter(SparseIntArray.class, new SparseIntArrayAdapter())
				.create().toJson(arr);
	}

	private static boolean equalMaps(SparseIntArray a, SparseIntArray b) {
		if (a == b) return true;
		if (a == null || b == null || a.size() != b.size()) return false;
		for (int i = 0, size = a.size(); i < size; i++) {
			if (a.keyAt(i) != b.keyAt(i) || a.valueAt(i) != b.valueAt(i)) {
				return false;
			}
		}
		return true;
	}
}
