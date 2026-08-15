/*
 * Copyright 2018 Nikita Shakarun
 * Copyright 2022 Arman Jussupgaliyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.config;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import androidx.preference.PreferenceManager;

import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.nokia.NokiaGlobalProfile;
import ru.playsoftware.j2meloader.nokia.NokiaKeyBinding;

import static ru.playsoftware.j2meloader.util.Constants.*;

import ru.playsoftware.j2meloader.util.FileUtils;

public class Config {
	public static final String DEX_OPT_CACHE_DIR = "dex_opt";
	public static final String FS_DIR = "/fs/";
	public static final String MIDLET_CONFIG_FILE = "/config.json";
	public static final String MIDLET_CONFIGS_DIR = "/configs/";
	public static final String MIDLET_DATA_DIR = "/data/";
	public static final String MIDLET_DEX_FILE = "/converted.dex";
	public static final String MIDLET_ICON_FILE = "/icon.png";
	public static final String MIDLET_KEY_LAYOUT_FILE = "/VirtualKeyboardLayout";
	public static final String MIDLET_MANIFEST_FILE = MIDLET_DEX_FILE + ".conf";
	public static final String MIDLET_RES_DIR = "/res";
	public static final String MIDLET_RES_FILE = "/res.jar";
	public static final String SCREENSHOTS_DIR;
	public static final String SHADERS_DIR = "/shaders/";

	private static String emulatorDir;
	private static String dataDir;
	private static String configsDir;
	private static String profilesDir;
	private static String appDir;

	private static final SharedPreferences.OnSharedPreferenceChangeListener sPrefListener =
			(sharedPreferences, key) -> {
				if (key.equals(PREF_EMULATOR_DIR)) {
					initDirs(sharedPreferences.getString(key, emulatorDir));
				}
			};

	static {
		Context context = ContextHolder.getAppContext();
		String appName = "J2ME-Loader";
		if (!BuildConfig.FULL_EMULATOR) {
			appName = context.getString(R.string.app_name);
		}
		SCREENSHOTS_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
				+ "/" + appName;
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		String path = FileUtils.isExternalStorageLegacy() ?
				preferences.getString(PREF_EMULATOR_DIR, null) :
				context.getExternalFilesDir(null).getPath();
		if (path == null) {
			path = Environment.getExternalStorageDirectory() + "/" + appName;
		}
		initDirs(path);
		preferences.registerOnSharedPreferenceChangeListener(sPrefListener);
	}

	public static String getEmulatorDir() {
		return emulatorDir;
	}

	public static String getDataDir() {
		return dataDir;
	}

	public static String getConfigsDir() {
		return configsDir;
	}

	public static String getProfilesDir() {
		return profilesDir;
	}

	public static String getAppDir() {
		return appDir;
	}

	public static String getShadersDir() {
		return emulatorDir + SHADERS_DIR;
	}

	public static String getFsInternalDir() {
		return emulatorDir + FS_DIR + "c/";
	}

	public static String getFsExternalDir() {
		if (FileUtils.isExternalStorageLegacy()) {
			return Environment.getExternalStorageDirectory().getPath() + "/";
		} else {
			return emulatorDir + FS_DIR + "e/";
		}
	}

	public static void startApp(Context context, String name, String path, boolean showSettings) {
		startApp(context, name, path, showSettings, null);
	}

	public static void startApp(Context context, String name, String path, boolean showSettings, String arguments) {
		File appDir = new File(path);
		String workDir = appDir.getParentFile().getParent();
		File configDir = new File(workDir + Config.MIDLET_CONFIGS_DIR + appDir.getName());
		if (showSettings) {
			// 用户显式点「设置」：打开设置界面（沿用原行为）
			Log.i("Config", "startApp: 显式打开设置界面 -> " + name);
			Intent intent = new Intent(ACTION_EDIT, Uri.parse(path),
					context, ConfigActivity.class);
			intent.putExtra(KEY_MIDLET_NAME, name);
			intent.putExtra(KEY_START_ARGUMENTS, arguments);
			context.startActivity(intent);
			return;
		}
		if (!configDir.exists()) {
			// 新 JAR：自动套用默认（全局）profile 设置，然后直接启动，不再弹设置界面
			NokiaGlobalProfile.ensureGlobalProfile(context);
			String defProfile = PreferenceManager.getDefaultSharedPreferences(context)
					.getString(PREF_DEFAULT_PROFILE, null);
			if (defProfile != null) {
				File defDir = new File(Config.getProfilesDir(), defProfile);
				if (defDir.exists()) {
					FileUtils.copyFiles(defDir, configDir, null);
					Log.i("Config", "startApp: 新 JAR 套用默认 profile '" + defProfile
							+ "' -> " + configDir.getAbsolutePath());
				} else {
					Log.w("Config", "startApp: 默认 profile 目录不存在: " + defDir);
				}
			} else {
				Log.w("Config", "startApp: 未设置默认 profile，无法套用全局设置");
			}
		}
		if (configDir.exists()) {
			// 已有配置（或已套用全局设置）：直接启动
			Log.i("Config", "startApp: 直接启动 -> " + name);
			Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(path),
					context, MicroActivity.class);
			intent.putExtra(KEY_MIDLET_NAME, name);
			intent.putExtra(KEY_START_ARGUMENTS, arguments);
			// 键码表随 intent 传给 :midlet 进程（挂机菜单键/软键识别；extra 缺省时 MicroActivity 回退读 SP）
			intent.putExtra(KEY_KEYCODES, new NokiaKeyBinding(context).toKeyCodeArray());
			context.startActivity(intent);
		} else {
			// 没有任何兜底配置：仍走设置界面（保持原行为）
			Log.i("Config", "startApp: 无默认配置，退回设置界面 -> " + name);
			Intent intent = new Intent(ACTION_EDIT, Uri.parse(path),
					context, ConfigActivity.class);
			intent.putExtra(KEY_MIDLET_NAME, name);
			intent.putExtra(KEY_START_ARGUMENTS, arguments);
			context.startActivity(intent);
		}
	}

	private static void initDirs(String path) {
		emulatorDir = path;
		dataDir = emulatorDir + MIDLET_DATA_DIR;
		configsDir = emulatorDir + MIDLET_CONFIGS_DIR;
		profilesDir = emulatorDir + "/templates/";
		appDir = emulatorDir + "/converted/";
	}
}
