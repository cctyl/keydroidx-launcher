/*
 * Copyright 2017-2018 Nikita Shakarun
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

package ru.playsoftware.j2meloader;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDex;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.config.CoreConfigurationBuilder;
import org.acra.config.DialogConfigurationBuilder;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.microedition.util.ContextHolder;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaTheme;
import ru.playsoftware.j2meloader.nokia.LauncherThemeProvider;
import ru.playsoftware.j2meloader.nokia.NokiaSettingsStorage;
import ru.playsoftware.j2meloader.util.Constants;

public class EmulatorApplication extends Application {
	private static final String[] VALID_SIGNATURES = {
			"78EF7758720A9902F731ED706F72C669C39B765C", // GPlay
			"289F84A32207DF89BE749481ED4BD07E15FC268F", // F-Droid
			"FA8AA497194847D5715BAA62C6344D75A936EBA6" // Private
	};

	private final SharedPreferences.OnSharedPreferenceChangeListener themeListener = (sharedPreferences, key) -> {
		if (key.equals(Constants.PREF_THEME)) {
			setNightMode(sharedPreferences.getString(Constants.PREF_THEME, null));
		}
	};

	@SuppressWarnings("ConstantConditions")
	@Override
	protected void attachBaseContext(Context base) {
		long appStart = System.currentTimeMillis();
		super.attachBaseContext(base);
		if (BuildConfig.DEBUG) {
			MultiDex.install(this);
		}
		ContextHolder.setApplication(this);

		// 向 common 注入桌面主题提供者（主进程与 :midlet 进程都需要，J2ME 层换用 common 主题后依赖此注入）
		NokiaTheme.setThemeProvider(new LauncherThemeProvider());

		// 主题与向量图设置必须早期同步完成（毫秒级，直接决定首帧主题），不能延迟
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
		sp.registerOnSharedPreferenceChangeListener(themeListener);
		setNightMode(sp.getString(Constants.PREF_THEME, null));
		AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

		// ACRA 崩溃上报初始化（含签名校验 IPC）延迟到主线程约 2s 后执行，
		// 避免冷启动进程阶段阻塞首帧；仅主进程延迟，:midlet 子进程保持同步初始化（游戏崩溃上报不受影响）。
		if (isMainProcess()) {
			// 文件日志 + 崩溃堆栈落盘：尽早初始化，覆盖冷启动阶段的崩溃。
			// 日志实现统一走 common；目录按生态约定为 <外存>/files/log。
			NokiaLog.init(this);
			// 桌面设置「日志记录」优先：该开关原存在 nokia_desktop_settings，
			// 为兼容老用户继续以它为准，覆盖 common 初始化时的取值。
			NokiaLog.setFileMinLevel(NokiaSettingsStorage.isFileLogEnabled(this)
					? Log.DEBUG : Log.ERROR);
			installCrashHandler();
			new Handler(Looper.getMainLooper()).postDelayed(this::initAcra, 2000);
		} else {
			initAcra();
		}
		long elapsed = System.currentTimeMillis() - appStart;
		android.util.Log.i("EmulatorApp", "attachBaseContext 完成，耗时 " + elapsed + "ms");
	}

	/** ACRA 崩溃上报初始化（签名校验等 IPC 较慢，从首帧路径移出）。 */
	private void initAcra() {
		long start = System.currentTimeMillis();
		try {
			ACRA.init(this, new CoreConfigurationBuilder()
					.withBuildConfigClass(BuildConfig.class)
					.withParallel(false)
					.withSendReportsInDevMode(false)
					.withPluginConfigurations(new DialogConfigurationBuilder()
							.withTitle(getString(R.string.crash_dialog_title))
							.withText(getString(R.string.crash_dialog_message))
							.withPositiveButtonText(getString(R.string.report_crash))
							.withResTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
							.withEnabled(true)
							.build()
					));
			boolean enabled = isSignatureValid() && !BuildConfig.FLAVOR.equals("dev");
			ACRA.getErrorReporter().setEnabled(enabled);
		} catch (Exception e) {
			e.printStackTrace();
		}
		// ACRA 已注册自己的默认异常处理器，重新包装为「先落盘崩溃堆栈，再交给 ACRA」的链式处理。
		installCrashHandler();
		long elapsed = System.currentTimeMillis() - start;
		android.util.Log.i("EmulatorApp", "ACRA 初始化完成，耗时 " + elapsed + "ms");
	}

	/**
	 * 注册崩溃堆栈落盘：任何未捕获异常先同步写入当日日志文件，
	 * 再交给链上原有处理器（系统默认弹「已停止运行」/ ACRA 上报）。
	 * 可多次调用：每次以当前默认处理器为链尾，保证本方法始终在最外层。
	 */
	private void installCrashHandler() {
		try {
			final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
			Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
				NokiaLog.fileCrash(thread, throwable);
				if (prev != null) {
					prev.uncaughtException(thread, throwable);
				} else {
					android.os.Process.killProcess(android.os.Process.myPid());
					System.exit(1);
				}
			});
			android.util.Log.i("EmulatorApp", "崩溃落盘处理器已安装");
		} catch (Exception e) {
			android.util.Log.w("EmulatorApp", "installCrashHandler 失败", e);
		}
	}

	/** 读取 /proc/self/cmdline 判断当前是否为主进程（轻量文件读取，无 Binder IPC）。 */
	private boolean isMainProcess() {
		try {
			java.io.BufferedReader br = new java.io.BufferedReader(
					new java.io.FileReader("/proc/self/cmdline"));
			try {
				String cmdline = br.readLine();
				if (cmdline != null) {
					int end = cmdline.indexOf('\0');
					if (end > 0) {
						cmdline = cmdline.substring(0, end);
					}
					// 子进程（如 :midlet）cmdline 为 "<包名>:<进程名>"，主进程为 "<包名>"
					return !cmdline.contains(getPackageName() + ":");
				}
			} finally {
				br.close();
			}
		} catch (Exception ignore) {
		}
		return true;
	}

	@SuppressLint("PackageManagerGetSignatures")
	private boolean isSignatureValid() {
		try {
			Signature[] signatures;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				PackageInfo info = getPackageManager()
						.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
				signatures = info.signingInfo.getApkContentsSigners();
			} else {
				PackageInfo info = getPackageManager()
						.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
				signatures = info.signatures;
			}
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			for (Signature signature : signatures) {
				md.update(signature.toByteArray());
				String sha1 = String.format("%032X", new BigInteger(1, md.digest()));
				if (Arrays.asList(VALID_SIGNATURES).contains(sha1)) {
					return true;
				}
			}
		} catch (PackageManager.NameNotFoundException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return false;
	}

	void setNightMode(String theme) {
		if (theme == null) {
			theme = getString(R.string.pref_theme_default);
		}
		switch (theme) {
			case "light":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
				break;
			case "dark":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
				break;
			case "auto-battery":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
				break;
			case "auto-time":
				//noinspection deprecation
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_TIME);
				break;
			default:
			case "system":
				AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
				break;
		}
	}
}
