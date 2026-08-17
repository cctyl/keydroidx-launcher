/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
 * Copyright 2019-2022 Yury Kharchenko
 * Copyright 2022-2024 Arman Jussupgaliyev
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

package javax.microedition.shell;

import static ru.playsoftware.j2meloader.util.Constants.*;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.Screen;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.commands.AbstractSoftKeysBar;
import javax.microedition.lcdui.commands.ScreenSoftBar;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.location.LocationProviderImpl;
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity;
import ru.playsoftware.j2meloader.nokia.NokiaKeyBinding;
import ru.playsoftware.j2meloader.nokia.NokiaMidletKeepAliveService;
import ru.playsoftware.j2meloader.nokia.NokiaOptionsDialog;
import ru.playsoftware.j2meloader.util.MidletStateStore;
import ru.playsoftware.mini_shizuku.Shizuku;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.databinding.ActivityMicroBinding;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.LogUtils;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean visible;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private MicroLoader microLoader;
	private String appName;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;
	/** 诺基亚键码表（9 元素，索引=NokiaKeyBinding 动作；Intent extra 优先，SP 兜底） */
	private int[] nokiaKeyCodes;

	public ActivityMicroBinding binding;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		ContextHolder.setCurrentActivity(this);

		binding = ActivityMicroBinding.inflate(getLayoutInflater());
		View view = binding.getRoot();
		setContentView(view);
		setSupportActionBar(binding.toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
		binding.toolbar.setVisibility(View.GONE);

		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		actionBarEnabled = false;
		statusBarEnabled = sp.getBoolean(PREF_STATUSBAR, false);
		if (sp.getBoolean(PREF_ADD_CUTOUT_AREA, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			getWindow().getAttributes().layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}
		if (sp.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		ContextHolder.setVibration(sp.getBoolean(PREF_VIBRATION, true));
		Canvas.setScreenshotRawMode(sp.getBoolean(PREF_SCREENSHOT_SWITCH, false));
		Intent intent = getIntent();
		if (BuildConfig.FULL_EMULATOR) {
			appName = intent.getStringExtra(KEY_MIDLET_NAME);
			Uri data = intent.getData();
			if (data == null) {
				showErrorDialog("Invalid intent: app path is null");
				return;
			}
			appPath = data.toString();
		} else {
			appName = getTitle().toString();
			appPath = getApplicationInfo().dataDir + "/files/converted/midlet";
			File dir = new File(appPath);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new RuntimeException("Can't access file system");
			}
		}
		String arguments = intent.getStringExtra(KEY_START_ARGUMENTS);
		if (arguments != null) {
			MidletSystem.setProperty("com.nokia.mid.cmdline", arguments);
			String[] arr = arguments.split(";");
			for (String s: arr) {
				if (s.length() == 0) {
					continue;
				}
				if (s.contains("=")) {
					int i = s.indexOf('=');
					String k = s.substring(0, i);
					String v = s.substring(i + 1);
					MidletSystem.setProperty(k, v);
				} else {
					MidletSystem.setProperty(s, "");
				}
			}
		}
		MidletSystem.setProperty("com.nokia.mid.cmdline.instance", "1");
		loadKeyCodes(intent);
		microLoader = new MicroLoader(this, appPath);
		// 挂机复用/切换判定（须在 microLoader.init() 之前：init 会清 MIDlet 缓存目录、
		// 重复初始化 Display，且新建 MIDlet 会静默抛弃挂机实例）
		if (MidletThread.hasInstance()) {
			String running = MidletThread.getRunningAppPath();
			if (appPath.equals(running)) {
				// 分支 R2：同一 jar 挂机恢复 —— 复用进程内 MIDlet，重挂 UI
				restoreFromBackground();
				return;
			}
			// 分支 S：切换到其它 jar —— 销毁当前实例，startAfterDestroy 在进程死后拉起新 jar
			switchToApp(appName, appPath, arguments);
			return;
		}
		if (!microLoader.init()) {
			Config.startApp(this, appName, appPath, true, arguments);
			finish();
			return;
		}
		microLoader.applyConfiguration();
		VirtualKeyboard vk = ContextHolder.getVk();
		int orientation = microLoader.getOrientation();
		if (vk != null) {
			vk.setView(binding.overlayView);
			binding.overlayView.addLayer(vk);
			if (vk.isPhone()) {
				orientation = ORIENTATION_PORTRAIT;
			}
		}
		setOrientation(orientation);
		menuKey = microLoader.getMenuKeyCode();
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		// 分支 N 追加：登记挂机状态（供桌面判定“哪个 jar 在运行/挂机”与 R2 恢复）
		MidletThread.runningAppPath = appPath;
		MidletThread.savedOrientation = orientation;
		MidletThread.savedMenuKey = menuKey;
		MidletStateStore.write(this, appPath, appName);

		try {
			loadMIDlet();
		} catch (Exception e) {
			e.printStackTrace();
			showErrorDialog(e.toString());
		}
	}

	/**
	 * 分支 R2：同一 jar 挂机恢复（Activity 已销毁、:midlet 进程与 MIDlet 实例存活）。
	 * 跳过 microLoader.init()（清缓存目录）与 loadMIDlet()（新建 MIDlet），
	 * 恢复 VK/方向/菜单键后重挂挂机时的 Displayable；onResume → resumeApp() 续跑。
	 * <p>
	 * 视图恢复采用「丢弃旧 view + 惰性重建」而非重挂：SurfaceView 是打洞渲染，
	 * 与所属 Window 绑定，跨 Activity 重挂不会绘制（实测白屏）；getDisplayableView()
	 * 会按需重建 layout/innerView/回调，surfaceCreated → repaintInternal → jar paint() 恢复画面。
	 */
	private void restoreFromBackground() {
		VirtualKeyboard vk = ContextHolder.getVk();
		int orientation = MidletThread.getSavedOrientation();
		if (vk != null) {
			vk.setView(binding.overlayView);
			binding.overlayView.addLayer(vk);
			if (vk.isPhone()) {
				orientation = ORIENTATION_PORTRAIT;
			}
		}
		setOrientation(orientation >= 0 ? orientation : ORIENTATION_DEFAULT);
		menuKey = MidletThread.getSavedMenuKey();
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		Displayable displayable = MidletThread.getCurrentDisplayable();
		if (displayable != null) {
			displayable.clearDisplayableView(); // 丢弃旧 Activity 的 SurfaceView（跨窗口不可重挂）
			setCurrent(displayable);            // getDisplayableView() 惰性重建 → surfaceCreated → 重绘
		}
		Log.i("MicroActivity", "restoreFromBackground: 复用挂机 MIDlet(重建视图) -> " + MidletThread.getRunningAppPath());
	}

	/** 分支 S：切换到其它 jar —— 销毁当前实例，startAfterDestroy 会在进程死后拉起新 jar。 */
	private void switchToApp(String appName, String appPath, String arguments) {
		Log.i("MicroActivity", "switchToApp: " + MidletThread.getRunningAppPath() + " -> " + appPath);
		MidletThread.startAfterDestroy = new String[]{appName, appPath, arguments};
		finish(); // 先回桌面（下方是桌面/当前界面），销毁与重启在后台完成
		MidletThread.destroyApp();
	}

	/** 读取键码表：Intent extra 优先（桌面传最新绑定），缺省回退 SP（新进程首读必为最新）。 */
	private void loadKeyCodes(Intent intent) {
		int[] extra = intent != null ? intent.getIntArrayExtra(Constants.KEY_KEYCODES) : null;
		if (extra == null || extra.length != NokiaKeyBinding.ACTION_COUNT) {
			extra = NokiaKeyBinding.loadKeyCodes(this);
		}
		nokiaKeyCodes = extra;
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		loadKeyCodes(intent);
		if (!MidletThread.hasInstance()) {
			return; // 销毁竞态/进程刚重启：交给现有流程
		}
		String path;
		if (BuildConfig.FULL_EMULATOR) {
			Uri data = intent.getData();
			path = data == null ? null : data.toString();
		} else {
			path = appPath;
		}
		if (path == null || path.equals(MidletThread.getRunningAppPath())) {
			return; // 同一 jar：无动作，onResume → resumeApp() 续跑（恢复路径 R1）
		}
		switchToApp(intent.getStringExtra(Constants.KEY_MIDLET_NAME), path,
				intent.getStringExtra(Constants.KEY_START_ARGUMENTS));
	}

	public void lockNightMode() {
		int current = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (current == Configuration.UI_MODE_NIGHT_YES) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		} else {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		visible = true;
		MidletThread.resumeApp();
		NokiaMidletKeepAliveService.stop(this);
		reportMidletForeground();
	}

	@Override
	public void onPause() {
		visible = false;
		hideSoftInput();
		MidletThread.pauseApp();
		super.onPause();
	}

	@Override
	protected void onStop() {
		super.onStop();
		// 挂机保活：Activity 不可见且 MIDlet 仍在运行（覆盖绿键/红键/Home 全部离开路径；
		// 绿键「后台运行」路径已在动作内先行启动，此处幂等）
		if (MidletThread.hasInstance() && MidletThread.getRunningAppPath() != null) {
			NokiaMidletKeepAliveService.start(this, appName,
					MidletThread.getRunningAppPath(), nokiaKeyCodes);
		}
	}

	/**
	 * jar 前台快速上报页面状态（false）给 native 拦截器：
	 * 使红键决策立即落入「回桌面」分支，闭合前台窗口 2s 轮询窗口。
	 * mini_shizuku 未运行时静默失败（无副作用）；jar 离开后由桌面重新上报真实状态。
	 */
	private void reportMidletForeground() {
		new Thread(() -> {
			try {
				Shizuku.setPageState(false);
			} catch (Exception ignored) {
			}
		}, "midlet-page-state").start();
	}

	private void hideSoftInput() {
		if (inputMethodManager != null) {
			IBinder windowToken = binding.displayableContainer.getWindowToken();
			inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
				current instanceof Canvas) {
			hideSystemUI();
		}
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void setOrientation(int orientation) {
		switch (orientation) {
			case ORIENTATION_AUTO:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
				break;
			case ORIENTATION_PORTRAIT:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
				break;
			case ORIENTATION_LANDSCAPE:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
				break;
			case ORIENTATION_DEFAULT:
			default:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				break;
		}
	}

	private void loadMIDlet() throws Exception {
		LinkedHashMap<String, String> midlets = microLoader.loadMIDletList();
		int size = midlets.size();
		String[] midletsNameArray = midlets.values().toArray(new String[0]);
		String[] midletsClassArray = midlets.keySet().toArray(new String[0]);
		if (size == 0) {
			throw new Exception("No MIDlets found");
		} else if (size == 1) {
			MidletThread.create(microLoader, midletsClassArray[0]);
		} else {
			showMidletDialog(midletsNameArray, midletsClassArray);
		}
	}

	private void showMidletDialog(String[] names, final String[] classes) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.select_dialog_title)
				.setItems(names, (d, n) -> {
					String clazz = classes[n];
					ErrorReporter errorReporter = ACRA.getErrorReporter();
					String report = errorReporter.getCustomData(Constants.KEY_APPCENTER_ATTACHMENT);
					StringBuilder sb = new StringBuilder();
					if (report != null) {
						sb.append(report).append("\n");
					}
					sb.append("Begin app: ").append(names[n]).append(", ").append(clazz);
					errorReporter.putCustomData(Constants.KEY_APPCENTER_ATTACHMENT, sb.toString());
					MidletThread.create(microLoader, clazz);
					MidletThread.resumeApp();
				})
				.setOnCancelListener(d -> {
					d.dismiss();
					MidletThread.notifyDestroyed();
				});
		builder.show();
	}

	void showErrorDialog(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.error)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.notifyDestroyed());
		builder.setOnCancelListener(dialogInterface -> MidletThread.notifyDestroyed());
		builder.show();
	}

	private int getToolBarHeight() {
		int[] attrs = new int[]{androidx.appcompat.R.attr.actionBarSize};
		TypedArray ta = obtainStyledAttributes(attrs);
		int toolBarHeight = ta.getDimensionPixelSize(0, -1);
		ta.recycle();
		return toolBarHeight;
	}

	private void hideSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!statusBarEnabled) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
		} else if (!statusBarEnabled) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	private void showSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	public void setCurrent(Displayable displayable) {
		MidletThread.currentDisplayable = displayable; // 挂机状态载体同步（R2 重挂 UI 用）
		ViewHandler.postEvent(new SetCurrentEvent(current, displayable));
		current = displayable;
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return visible;
	}

	public void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					hideSoftInput();
					MidletThread.destroyApp();
				})
				.setNeutralButton(R.string.action_settings, (d, w) -> {
					hideSoftInput();
					Config.startApp(this, appName, appPath, true);
					MidletThread.destroyApp();
				})
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
	}

	/** 弹出挂机三菜单（绿键）：继续 / 退出 / 后台运行。复用 NokiaOptionsDialog（键码表注入模式）。 */
	private void showHangupMenu() {
		java.util.List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_media_play,
				"继续", true, false, null)); // 仅关闭弹窗继续运行
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_close_clear_cancel,
				"退出", true, false, this::exitMidlet));
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_more,
				"后台运行", true, false, this::runInBackground));
		NokiaOptionsDialog.show(getSupportFragmentManager(), appName, items, nokiaKeyCodes);
	}

	/** 三菜单「退出」：先 finish 立即回桌面（用户不必看到 END 键过渡画面），销毁在后台完成。 */
	private void exitMidlet() {
		hideSoftInput();
		finish();
		MidletThread.destroyApp();
	}

	/**
	 * 三菜单「后台运行」：显式回诺基亚桌面（不设默认桌面也能回到本桌面），
	 * 本 Activity 保持 stopped（singleTask 不销毁），jar 转挂机，下次进入走 R1 快速续跑。
	 */
	private void runInBackground() {
		hideSoftInput();
		// 挂机动作发生时 Activity 仍前台：立即起保活通知（规避 Android 12+ 后台 FGS 限制）
		if (MidletThread.hasInstance() && MidletThread.getRunningAppPath() != null) {
			NokiaMidletKeepAliveService.start(this, appName,
					MidletThread.getRunningAppPath(), nokiaKeyCodes);
		}
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		intent.setClassName(this, NokiaDesktopActivity.class.getName());
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			startActivity(intent);
		} catch (Exception e) {
			// 极端场景（桌面不可用）：退回隐式 HOME
			Log.e("MicroActivity", "runInBackground: 显式回桌面失败", e);
			Intent fallback = new Intent(Intent.ACTION_MAIN);
			fallback.addCategory(Intent.CATEGORY_HOME);
			fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(fallback);
		}
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		// 1. 挂机菜单键（绿键）拦截
		int hangupKey = nokiaKeyCodes == null ? KeyEvent.KEYCODE_UNKNOWN
				: nokiaKeyCodes[NokiaKeyBinding.ACTION_HANGUP];
		if (hangupKey != KeyEvent.KEYCODE_UNKNOWN && event.getKeyCode() == hangupKey) {
			if (event.getAction() == KeyEvent.ACTION_UP
					&& (event.getFlags() & KeyEvent.FLAG_CANCELED) == 0
					&& !isFinishing()) {
				showHangupMenu();
			}
			return true;
		}

		// 2. Screen 模式（TextBox、Form、List、Alert）：通过诺基亚键码表路由到底部软键栏
		if (current instanceof Screen) {
			return dispatchScreenKey(event);
		}

		// 3. Canvas 模式：KEYCODE_MENU 透传给 MIDlet，其余走系统
		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
			if (current instanceof Canvas && binding.displayableContainer.dispatchKeyEvent(event)) {
				return true;
			}
		}
		return super.dispatchKeyEvent(event);
	}

	/** 最近一次在 Screen 内按下的诺基亚动作（DOWN 时记录，UP 时消费执行）。 */
	private int pendingScreenAction = -1;

	/**
	 * 在 Screen（TextBox/Form/List/Alert）中，将诺基亚按键映射到底部软键栏操作。
	 * 左软键 → 打开「操作/菜单」；右软键 → 清除/返回；方向键/确认键 → 菜单导航。
	 * <p>
	 * 采用 DOWN/UP 配对：DOWN 时用 {@link NokiaKeyBinding#resolveAction(int[], KeyEvent)}
	 * 解析动作并记录，UP 时按记录执行。绝不依赖 menuKey/keyCode 做 UP 期二次判断，
	 * 避免 menuKey 默认值（KEYCODE_BACK）与右软键冲突导致误触发左软键。
	 */
	private boolean dispatchScreenKey(KeyEvent event) {
		Screen screen = (Screen) current;
		AbstractSoftKeysBar softBar = screen.getSoftBar();
		if (softBar == null) return super.dispatchKeyEvent(event);

		int keyCode = event.getKeyCode();

		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			// DOWN：用键码表解析动作（此时 resolveAction 才有效）
			int action = NokiaKeyBinding.resolveAction(nokiaKeyCodes, event);
			if (action >= 0) {
				pendingScreenAction = action;
				return true;
			}
			// 键码表未命中（如默认方案未绑定的键）：用标准兜底
			if (keyCode == KeyEvent.KEYCODE_BACK) {
				pendingScreenAction = NokiaKeyBinding.ACTION_SOFT_RIGHT;
				return true;
			}
			if (keyCode == KeyEvent.KEYCODE_MENU) {
				pendingScreenAction = NokiaKeyBinding.ACTION_SOFT_LEFT;
				return true;
			}
			// 方向键/数字键等交给系统（编辑框光标、输入、菜单 ListView 导航）
			return super.dispatchKeyEvent(event);
		}

		if (event.getAction() == KeyEvent.ACTION_UP) {
			int act = pendingScreenAction;
			pendingScreenAction = -1;
			if ((event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) {
				return act >= 0;
			}
			switch (act) {
				case NokiaKeyBinding.ACTION_SOFT_LEFT: {
					if (softBar.isMenuShowing()) {
						softBar.performCurrentMenuSelection();
						return true;
					}
					if (softBar instanceof ScreenSoftBar) {
						Button leftBtn = ((ScreenSoftBar) softBar).getLeftButton();
						if (leftBtn.getVisibility() == View.VISIBLE) {
							leftBtn.performClick();
						}
					}
					return true;
				}
				case NokiaKeyBinding.ACTION_SOFT_RIGHT: {
					if (softBar.isMenuShowing()) {
						softBar.closeMenu();
						return true;
					}
					if (softBar instanceof ScreenSoftBar) {
						Button rightBtn = ((ScreenSoftBar) softBar).getRightButton();
						if (rightBtn.getVisibility() == View.VISIBLE) {
							rightBtn.performClick();
							return true;
						}
					}
					return true;
				}
				case NokiaKeyBinding.ACTION_SELECT: {
					if (softBar.isMenuShowing()) {
						softBar.performCurrentMenuSelection();
						return true;
					}
					if (softBar instanceof ScreenSoftBar) {
						Button midBtn = ((ScreenSoftBar) softBar).getMiddleButton();
						if (midBtn.getVisibility() == View.VISIBLE) {
							midBtn.performClick();
							return true;
						}
					}
					return super.dispatchKeyEvent(event);
				}
				default:
					return super.dispatchKeyEvent(event);
			}
		}

		// ACTION_REPEAT 等：已由 DOWN 消费，直接吞掉
		return true;
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		return super.onKeyUp(keyCode, event);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public void onBackPressed() {
		// Intentionally overridden by empty due to support for back-key remapping.
	}

	@Override
	public void openOptionsMenu() {
		// 诺基亚风格下，ActionBar 已隐藏，openOptionsMenu 被禁用
		// 所有菜单操作由底部软键栏（ScreenSoftBar）处理
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.midlet_displayable, menu);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			menu.findItem(R.id.action_lock_orientation).setVisible(true);
		}
		if (actionBarEnabled) {
			menu.findItem(R.id.action_ime_keyboard).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			menu.findItem(R.id.action_take_screenshot).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}
		if (inputMethodManager == null) {
			menu.findItem(R.id.action_ime_keyboard).setVisible(false);
		}
		if (ContextHolder.getVk() == null) {
			menu.findItem(R.id.action_submenu_vk).setVisible(false);
		}
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (current instanceof Canvas) {
			menu.setGroupVisible(R.id.action_group_canvas, true);
			VirtualKeyboard vk = ContextHolder.getVk();
			if (vk != null) {
				boolean visible = vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF;
				menu.findItem(R.id.action_layout_edit_finish).setVisible(visible);
			}
		} else {
			menu.setGroupVisible(R.id.action_group_canvas, false);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_exit_midlet) {
			showExitConfirmation();
		} else if (id == R.id.action_save_log) {
			saveLog();
		} else if (id == R.id.action_lock_orientation) {
			if (item.isChecked()) {
				VirtualKeyboard vk = ContextHolder.getVk();
				int orientation = vk != null && vk.isPhone() ? ORIENTATION_PORTRAIT : microLoader.getOrientation();
				setOrientation(orientation);
				item.setChecked(false);
			} else {
				item.setChecked(true);
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
			}
		} else if (id == R.id.action_ime_keyboard) {
			inputMethodManager.toggleSoftInputFromWindow(binding.displayableContainer.getWindowToken(),
					InputMethodManager.SHOW_FORCED, 0);
		} else if (id == R.id.action_take_screenshot) {
			takeScreenshot();
		} else if (id == R.id.action_limit_fps) {
			showLimitFpsDialog();
		} else if (ContextHolder.getVk() != null) {
			// Handled only when virtual keyboard is enabled
			handleVkOptions(id);
		}
		return true;
	}

	private void handleVkOptions(int id) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (id == R.id.action_layout_edit_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
			Toast.makeText(this, R.string.layout_edit_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_scale_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
			Toast.makeText(this, R.string.layout_scale_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_edit_finish) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
			Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
			showSaveVkAlert(false);
		} else if (id == R.id.action_layout_switch) {
			showSetLayoutDialog();
		} else if (id == R.id.action_hide_buttons) {
			showHideButtonDialog();
		}
	}

	@SuppressLint("CheckResult")
	private void takeScreenshot() {
		microLoader.takeScreenshot((Canvas) current, new SingleObserver<String>() {
			@Override
			public void onSubscribe(@NonNull Disposable d) {
			}

			@Override
			public void onSuccess(@NonNull String s) {
				Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
						+ " " + s, Toast.LENGTH_LONG).show();
			}

			@Override
			public void onError(@NonNull Throwable e) {
				e.printStackTrace();
				Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		boolean[] states = vk.getKeysVisibility();
		boolean[] changed = states.clone();
		new AlertDialog.Builder(this)
				.setTitle(R.string.hide_buttons)
				.setMultiChoiceItems(vk.getKeyNames(), changed, (dialog, which, isChecked) -> {})
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					if (!Arrays.equals(states, changed)) {
						vk.setKeysVisibility(changed);
						showSaveVkAlert(true);
					}
				}).show();
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.CONFIRMATION_REQUIRED);
		builder.setMessage(R.string.pref_vk_save_alert);
		builder.setNegativeButton(android.R.string.no, null);
		AlertDialog dialog = builder.create();

		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk.isPhone()) {
			AppCompatCheckBox cb = new AppCompatCheckBox(this);
			cb.setText(R.string.opt_save_screen_params);
			cb.setChecked(keepScreenPreferred);

			TypedValue out = new TypedValue();
			getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, out, true);
			int paddingH = getResources().getDimensionPixelOffset(out.resourceId);
			int paddingT = getResources().getDimensionPixelOffset(androidx.appcompat.R.dimen.abc_dialog_padding_top_material);
			dialog.setView(cb, paddingH, paddingT, paddingH, 0);

			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) -> {
				if (cb.isChecked()) {
					vk.saveScreenParams();
				}
				vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
			});
		} else {
			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) ->
					ContextHolder.getVk().onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
		}
		dialog.show();
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.layout_switch)
				.setSingleChoiceItems(R.array.PREF_VK_TYPE_ENTRIES, vk.getLayout(), null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					vk.setLayout(((AlertDialog) d).getListView().getCheckedItemPosition());
					if (vk.isPhone()) {
						setOrientation(ORIENTATION_PORTRAIT);
					} else {
						setOrientation(microLoader.getOrientation());
					}
				});
		builder.show();
	}

	private void showLimitFpsDialog() {
		EditText editText = new EditText(this);
		editText.setHint(R.string.unlimited);
		editText.setInputType(InputType.TYPE_CLASS_NUMBER);
		editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
		editText.setMaxLines(1);
		editText.setSingleLine(true);
		float density = getResources().getDisplayMetrics().density;
		LinearLayout linearLayout = new LinearLayout(this);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		int margin = (int) (density * 20);
		params.setMargins(margin, 0, margin, 0);
		linearLayout.addView(editText, params);
		int paddingVertical = (int) (density * 16);
		int paddingHorizontal = (int) (density * 8);
		editText.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
		new AlertDialog.Builder(this)
				.setTitle(R.string.PREF_LIMIT_FPS)
				.setView(linearLayout)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					Editable text = editText.getText();
					int fps = 0;
					try {
						fps = TextUtils.isEmpty(text) ? 0 : Integer.parseInt(text.toString().trim());
					} catch (NumberFormatException ignored) {
					}
					microLoader.setLimitFps(fps);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.reset, ((d, which) -> microLoader.setLimitFps(-1)))
				.show();
	}

	@Override
	public boolean onContextItemSelected(@NonNull MenuItem item) {
		if (current instanceof Form) {
			((Form) current).contextMenuItemSelected(item);
		} else if (current instanceof List) {
			AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
			((List) current).contextMenuItemSelected(item, info.position);
		}

		return super.onContextItemSelected(item);
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	public String getAppName() {
		return appName;
	}

	private class SetCurrentEvent extends SimpleEvent {
		private final Displayable current;
		private final Displayable next;

		private SetCurrentEvent(Displayable current, Displayable next) {
			this.current = current;
			this.next = next;
		}

		@Override
		public void process() {
			closeOptionsMenu();
			if (current != null) {
				current.clearDisplayableView();
			}
			if (next instanceof Alert) {
				return;
			}
			binding.displayableContainer.removeAllViews();
			ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) binding.toolbar.getLayoutParams();
			// 诺基亚风格：始终隐藏顶部 ActionBar，toolbarHeight=0
			actionBar.hide();
			binding.toolbar.setVisibility(View.GONE);
			layoutParams.height = 0;
			binding.toolbar.setLayoutParams(layoutParams);
			// 全屏沉浸式：隐藏状态栏和导航栏，MIDlet 内容铺满整屏
			hideSystemUI();
			binding.overlayView.setLocation(0, 0);
			invalidateOptionsMenu();
			if (next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
				if (next instanceof TextBox) {
					// 进入编辑界面后，焦点自动落到输入框（而非底部软键/菜单）
					binding.displayableContainer.post(() -> {
						if (next instanceof TextBox) {
							((TextBox) next).requestTextFocus();
						}
					});
				}
			}
		}
	}

	@Override
	protected void onDestroy() {
		binding = null;
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == 1) {
			synchronized (LocationProviderImpl.permissionLock) {
				LocationProviderImpl.permissionLock.notify();
			}
			LocationProviderImpl.permissionResult = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
		}
	}
}
