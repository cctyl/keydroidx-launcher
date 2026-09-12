package ru.playsoftware.j2meloader.nokia;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.permission.KeydroidxPermissionManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 诺基亚启动器内置应用冻结管理器。
 * <p>
 * 支持通过 mini_shizuku (Shell / ADB 权限) 或 DevicePolicyManager (设备管理员/免Root/小黑屋模式)
 * 实现应用的无缝冻结与解冻启动。
 */
public class KeydroidxFreezeManager {

	private static final String TAG = "KeydroidxFreezeManager";
	private static final String PREF_NAME = "nokia_freeze_config";
	private static final String KEY_FROZEN_LIST = "frozen_packages";

	/** 广播：当冻结状态或冻结列表发生变化时发送，通知功能表和桌面快捷栏刷新图标角标 */
	public static final String ACTION_FREEZE_STATE_CHANGED = "ru.playsoftware.j2meloader.nokia.ACTION_FREEZE_STATE_CHANGED";
	/** 广播 extra：发生变更的包名（单包冻结/解冻时携带，供接收方预写缓存） */
	public static final String EXTRA_PACKAGE = "extra_package";
	/** 广播 extra：该包预期冻结状态（true=已冻结，false=已解冻） */
	public static final String EXTRA_FROZEN = "extra_frozen";
	/** 广播 extra：批量发生变更的包名列表（一键冻结/解冻完成后携带，接收方据此批量预写缓存） */
	public static final String EXTRA_PACKAGES = "extra_packages";

	private static volatile KeydroidxFreezeManager sInstance;
	private final Context appContext;
	private final SharedPreferences prefs;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Set<String> frozenList = new HashSet<>();

	/**
	 * 运行期已知冻结集合：本进程内 executeFreeze 成功过的包。
	 * <p>用于功能表进入时预写冻结缓存，绕过 {@code pm disable-user} 之后 PackageManagerService
	 * 对高 targetSdk 包的数秒级状态更新延迟——桌面自己冻结的，桌面当然知道结果，
	 * 不必等 PMS 反馈、也不必依赖广播能否被功能表收到。
	 * <p>仅本进程生命周期内有效；进程重启后清空，由 {@link #isAppFrozen} 直接查询 PMS
	 * （此时状态早已稳定，无延迟问题）。
	 */
	private final Set<String> knownFrozen = new HashSet<>();

	private KeydroidxFreezeManager(Context context) {
		this.appContext = context.getApplicationContext();
		this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
		loadList();
	}

	public static KeydroidxFreezeManager getInstance(Context context) {
		if (sInstance == null) {
			synchronized (KeydroidxFreezeManager.class) {
				if (sInstance == null) {
					sInstance = new KeydroidxFreezeManager(context);
				}
			}
		}
		return sInstance;
	}

	private synchronized void loadList() {
		Set<String> saved = prefs.getStringSet(KEY_FROZEN_LIST, null);
		frozenList.clear();
		if (saved != null) {
			frozenList.addAll(saved);
		}
	}

	private synchronized void saveList() {
		prefs.edit().putStringSet(KEY_FROZEN_LIST, new HashSet<>(frozenList)).apply();
	}

	/**
	 * 应用是否在冻结名单中
	 */
	public synchronized boolean isInFreezeList(String packageName) {
		return packageName != null && frozenList.contains(packageName);
	}

	/**
	 * 获取冻结名单副本
	 */
	public synchronized Set<String> getFreezeList() {
		return Collections.unmodifiableSet(new HashSet<>(frozenList));
	}

	/**
	 * 检查包名是否属于保护包（本启动器各变体、Shizuku 核心服务等，严禁冻结）
	 */
	public boolean isProtectedPackage(String packageName) {
		if (packageName == null || packageName.trim().isEmpty()) return true;
		if (packageName.equals(appContext.getPackageName())) return true;
		if ("io.github.cctyl.nokia".equals(packageName)
				|| "io.github.cctyl.nokia.debug".equals(packageName)) {
			return true;
		}
		if ("moe.shizuku.privileged.api".equals(packageName)) {
			return true;
		}
		return false;
	}

	/**
	 * 添加到冻结名单
	 */
	public synchronized void addToFreezeList(String packageName) {
		if (isProtectedPackage(packageName)) return; // 禁止将保护包加入冻结名单
		frozenList.add(packageName);
		saveList();
		notifyStateChanged();
	}

	/**
	 * 从冻结名单中移除（如果当前处于冻结状态，会尝试将其解冻）
	 */
	public synchronized void removeFromFreezeList(String packageName) {
		if (packageName == null) return;
		frozenList.remove(packageName);
		saveList();
		// 异步解冻
		unfreezeApp(packageName, null);
		notifyStateChanged();
	}

	/**
	 * 检查应用当前在系统层面是否真实处于冻结（停用/隐藏）状态
	 */
	public boolean isAppFrozen(String packageName) {
		if (packageName == null) return false;
		try {
			PackageManager pm = appContext.getPackageManager();
			ApplicationInfo ai;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				ai = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
			} else {
				ai = pm.getApplicationInfo(packageName, PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS);
			}
			if (ai != null) {
				return !ai.enabled;
			}
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "isAppFrozen err pkg=" + packageName + " msg=" + e.getMessage());
		}
		return false;
	}

	/**
	 * 发送冻结状态变更广播
	 */
	public void notifyStateChanged() {
		Intent intent = new Intent(ACTION_FREEZE_STATE_CHANGED);
		intent.setPackage(appContext.getPackageName());
		appContext.sendBroadcast(intent);
	}

	/**
	 * 发送携带预期冻结状态的变更广播（单包冻结/解冻成功后调用）。
	 * <p>pm disable-user 通过 Shizuku 返回成功后，PackageManagerService 对部分包（尤其
	 * targetSdk 较高的包）的状态更新存在数秒级延迟，导致接收方即时查询 getApplicationInfo
	 * 仍返回旧状态、毒化缓存。携带此 extra 后，接收方可优先用预期值预写缓存，避免延迟。
	 *
	 * @param pkg     发生变更的包名
	 * @param frozen  预期冻结状态（true=已冻结，false=已解冻）
	 */
	public void notifyStateChanged(String pkg, boolean frozen) {
		Intent intent = new Intent(ACTION_FREEZE_STATE_CHANGED);
		intent.setPackage(appContext.getPackageName());
		intent.putExtra(EXTRA_PACKAGE, pkg);
		intent.putExtra(EXTRA_FROZEN, frozen);
		appContext.sendBroadcast(intent);
	}

	/**
	 * 发送携带批量预期冻结状态的变更广播（一键冻结/解冻完成后调用）。
	 * <p>与单包版本同理：功能表若恰好正在前台，可据此一次性预写整批缓存，
	 * 避免逐包查询 PMS 的状态更新延迟导致冰块不立即显示。
	 * <p><b>注意</b>：广播是 best-effort——一键冻结通常从桌面快捷开关触发，此刻功能表
	 * 不在屏上、其接收器未注册，广播会被丢失。因此这只是「锦上添花」；真正的兜底
	 * 在功能表 {@code onPageCreated} 里通过 {@link #getKnownFrozenSet()} 主动预写。
	 *
	 * @param pkgs   发生变更的包名集合
	 * @param frozen 预期冻结状态（true=已冻结，false=已解冻）
	 */
	public void notifyStateChanged(Collection<String> pkgs, boolean frozen) {
		Intent intent = new Intent(ACTION_FREEZE_STATE_CHANGED);
		intent.setPackage(appContext.getPackageName());
		intent.putExtra(EXTRA_FROZEN, frozen);
		intent.putStringArrayListExtra(EXTRA_PACKAGES, new ArrayList<>(pkgs));
		appContext.sendBroadcast(intent);
	}

	/**
	 * 本进程内桌面已成功冻结的包名集合（不可变副本）。
	 * <p>供功能表进入时预写冻结缓存——桌面自己执行过 executeFreeze 的包，结果桌面当然知道，
	 * 无需依赖广播是否被功能表收到，也无需等待 PMS 状态更新延迟。
	 */
	public synchronized Set<String> getKnownFrozenSet() {
		return Collections.unmodifiableSet(new HashSet<>(knownFrozen));
	}

	private synchronized void markKnownFrozen(String pkg) {
		if (pkg != null) knownFrozen.add(pkg);
	}

	private synchronized void unmarkKnownFrozen(String pkg) {
		if (pkg != null) knownFrozen.remove(pkg);
	}

	public interface FreezeCallback {
		void onResult(boolean success, String message);
	}

	/**
	 * 冻结单个应用
	 */
	public void freezeApp(String packageName, FreezeCallback callback) {
		executor.execute(() -> {
			boolean ok = executeFreeze(packageName);
			mainHandler.post(() -> {
				// 成功时携带预期状态预写缓存，避免 PMS 状态更新延迟导致冰块不立即显示
				if (ok) {
					notifyStateChanged(packageName, true);
				} else {
					notifyStateChanged();
				}
				if (callback != null) {
					callback.onResult(ok, ok ? "已冻结" : "冻结失败，请检查 mini_shizuku 权限");
				}
			});
		});
	}

	/**
	 * 解冻单个应用
	 */
	public void unfreezeApp(String packageName, FreezeCallback callback) {
		executor.execute(() -> {
			boolean ok = executeUnfreeze(packageName);
			mainHandler.post(() -> {
				if (ok) {
					notifyStateChanged(packageName, false);
				} else {
					notifyStateChanged();
				}
				if (callback != null) {
					callback.onResult(ok, ok ? "已解冻" : "解冻失败");
				}
			});
		});
	}

	/**
	 * 解冻并启动应用（点击快捷方式或功能表项时调用）
	 */
	public void unfreezeAndLaunch(Intent launchIntent, String packageName, String label) {
		if (packageName == null && launchIntent != null && launchIntent.getComponent() != null) {
			packageName = launchIntent.getComponent().getPackageName();
		}
		final String targetPkg = packageName;
		executor.execute(() -> {
			if (targetPkg != null && isAppFrozen(targetPkg)) {
				KeydroidxLog.i(TAG, "正在解冻应用: " + targetPkg);
				executeUnfreeze(targetPkg);
				// 短暂等待系统恢复组件
				try {
					Thread.sleep(100);
				} catch (InterruptedException ignored) {
					KeydroidxLog.w(TAG, "sleep failed: " + ignored.getMessage());
				}
			}
			mainHandler.post(() -> {
				if (targetPkg != null) {
					notifyStateChanged(targetPkg, false);
				} else {
					notifyStateChanged();
				}
				try {
					Intent intent = null;
					PackageManager pm = appContext.getPackageManager();
					// 解冻后重新解析当前「启用」的启动入口，绝不复用传入的 intent——
					// 传入组件可能指向停用的主题别名/失效入口（如 MT 的 MainNoBgIcon），
					// 解冻包不会连带启用该组件，直接启动会抛 ActivityNotFoundException。
					if (targetPkg != null) {
						intent = pm.getLaunchIntentForPackage(targetPkg);
					}
					if (intent == null) {
						intent = launchIntent;
					}
					if (intent != null) {
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
						appContext.startActivity(intent);
						KeydroidxLog.i(TAG, "成功解冻并启动: " + (label != null ? label : targetPkg)
								+ " -> " + intent.getComponent());
					} else {
						// 启动 Intent 解析为空：极大可能是缺少读取应用列表权限导致
						handleLaunchFailurePermissionRepair(targetPkg, label);
					}
				} catch (Exception e) {
					KeydroidxLog.e(TAG, "解冻后启动失败: " + targetPkg, e);
					Toast.makeText(appContext, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
				}
			});
		});
	}

	/**
	 * 当解冻后无法解析到启动 Intent 时触发的权限自愈流程
	 */
	private void handleLaunchFailurePermissionRepair(String targetPkg, String label) {
		KeydroidxDesktopActivity desktopActivity = KeydroidxDesktopActivity.getInstance();
		if (desktopActivity != null && !desktopActivity.isFinishing()) {
			KeydroidxLog.w(TAG, "解析启动 Intent 失败，检查并引导应用列表权限自愈: " + targetPkg);
			KeydroidxPermissionManager.requestAppListPermission(desktopActivity,
					"需要应用列表权限以定位并启动应用",
					new com.hjq.permissions.OnPermissionCallback() {
						@Override
						public void onGranted(java.util.List<String> permissions, boolean allGranted) {
							// 权限修复成功，自动断点续传重新启动目标应用
							KeydroidxLog.i(TAG, "应用列表权限自愈成功，自动重试启动: " + targetPkg);
							unfreezeAndLaunch(null, targetPkg, label);
						}

						@Override
						public void onDenied(java.util.List<String> permissions, boolean doNotAskAgain) {
							Toast.makeText(appContext, "缺少权限，无法启动应用", Toast.LENGTH_SHORT).show();
						}
					});
		} else {
			Toast.makeText(appContext, "无法启动应用", Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * 一键冻结所有名单中的应用
	 */
	public void freezeAll(FreezeCallback callback) {
		executor.execute(() -> {
			Set<String> list;
			synchronized (this) {
				list = new HashSet<>(frozenList);
			}
			if (list.isEmpty()) {
				mainHandler.post(() -> {
					if (callback != null) callback.onResult(true, "冻结名单为空");
					Toast.makeText(appContext, "冻结名单为空，请在功能表中添加", Toast.LENGTH_SHORT).show();
				});
				return;
			}

			List<String> succeeded = new ArrayList<>();
			for (String pkg : list) {
				if (executeFreeze(pkg)) {
					succeeded.add(pkg);
				}
			}

			final int total = list.size();
			final int success = succeeded.size();
			final List<String> done = succeeded;
			mainHandler.post(() -> {
				// 携带整批成功包 + 预期 true：若功能表正在前台可一次性预写缓存，
				// 绕过 PMS 状态更新延迟。功能表不在前台时广播丢失，由其
				// onPageCreated 读 getKnownFrozenSet() 兜底（见 KeydroidxMenuFragment）。
				notifyStateChanged(done, true);
				String msg = "已一键冻结 " + success + "/" + total + " 个应用";
				Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show();
				if (callback != null) {
					callback.onResult(success > 0, msg);
				}
			});
		});
	}

	/**
	 * 一键解冻所有名单中的应用
	 */
	public void unfreezeAll(FreezeCallback callback) {
		executor.execute(() -> {
			Set<String> list;
			synchronized (this) {
				list = new HashSet<>(frozenList);
			}
			if (list.isEmpty()) {
				mainHandler.post(() -> {
					if (callback != null) callback.onResult(true, "冻结名单为空");
					Toast.makeText(appContext, "冻结名单为空", Toast.LENGTH_SHORT).show();
				});
				return;
			}

			List<String> succeeded = new ArrayList<>();
			for (String pkg : list) {
				if (executeUnfreeze(pkg)) {
					succeeded.add(pkg);
				}
			}

			final int total = list.size();
			final int success = succeeded.size();
			final List<String> done = succeeded;
			mainHandler.post(() -> {
				notifyStateChanged(done, false);
				String msg = "已一键解冻 " + success + "/" + total + " 个应用";
				Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show();
				if (callback != null) {
					callback.onResult(success > 0, msg);
				}
			});
		});
	}

	/**
	 * 底层执行冻结：优先使用 mini_shizuku Shell (pm disable-user / pm hide)，其次 DevicePolicyManager
	 */
	private boolean executeFreeze(String packageName) {
		if (isProtectedPackage(packageName)) return false;
		KeydroidxLog.i(TAG, "executeFreeze: " + packageName);

		// 1. mini_shizuku Shell (最通用稳妥)
		try {
			if (Shizuku.isRunning()) {
				// Android 7.0+ 推荐 pm disable-user --user 0 ；同时强制停止
				String cmd = "am force-stop " + packageName + " ; pm disable-user --user 0 " + packageName + " || pm hide " + packageName;
				boolean res = Shizuku.exec(cmd);
				KeydroidxLog.i(TAG, "已通过 mini_shizuku 执行冻结: " + packageName + " res=" + res);
				if (res) markKnownFrozen(packageName);
				return res;
			}
		} catch (Throwable e) {
			KeydroidxLog.w(TAG, "mini_shizuku 执行冻结异常: " + e.getMessage());
		}

		// 2. DevicePolicyManager 设备管理员 (针对设备所有者模式)
		try {
			DevicePolicyManager dpm = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName admin = new ComponentName(appContext, KeydroidxLockReceiver.class);
			if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (dpm.isDeviceOwnerApp(appContext.getPackageName()) || dpm.isProfileOwnerApp(appContext.getPackageName())) {
					boolean res = dpm.setApplicationHidden(admin, packageName, true);
					KeydroidxLog.i(TAG, "DevicePolicyManager.setApplicationHidden: " + res);
					if (res) markKnownFrozen(packageName);
					return res;
				}
			}
		} catch (Throwable e) {
			KeydroidxLog.w(TAG, "DevicePolicyManager 冻结失败: " + e.getMessage());
		}

		return false;
	}

	/**
	 * 底层执行解冻
	 */
	private boolean executeUnfreeze(String packageName) {
		if (packageName == null) return false;
		KeydroidxLog.i(TAG, "executeUnfreeze: " + packageName);

		// 1. mini_shizuku Shell
		try {
			if (Shizuku.isRunning()) {
				String cmd = "pm enable " + packageName + " ; pm default-state --user 0 " + packageName + " ; pm unhide " + packageName;
				boolean res = Shizuku.exec(cmd);
				KeydroidxLog.i(TAG, "已通过 mini_shizuku 执行解冻: " + packageName + " res=" + res);
				if (res) unmarkKnownFrozen(packageName);
				return res;
			}
		} catch (Throwable e) {
			KeydroidxLog.w(TAG, "mini_shizuku 执行解冻异常: " + e.getMessage());
		}

		// 2. DevicePolicyManager
		try {
			DevicePolicyManager dpm = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName admin = new ComponentName(appContext, KeydroidxLockReceiver.class);
			if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (dpm.isDeviceOwnerApp(appContext.getPackageName()) || dpm.isProfileOwnerApp(appContext.getPackageName())) {
					boolean res = dpm.setApplicationHidden(admin, packageName, false);
					KeydroidxLog.i(TAG, "DevicePolicyManager.setApplicationHidden(false): " + res);
					if (res) unmarkKnownFrozen(packageName);
					return res;
				}
			}
		} catch (Throwable e) {
			KeydroidxLog.w(TAG, "DevicePolicyManager 解冻失败: " + e.getMessage());
		}

		return false;
	}
}
