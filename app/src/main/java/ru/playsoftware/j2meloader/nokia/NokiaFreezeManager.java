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

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.permission.NokiaPermissionManager;
import java.util.Collections;
import java.util.HashSet;
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
public class NokiaFreezeManager {

	private static final String TAG = "NokiaFreezeManager";
	private static final String PREF_NAME = "nokia_freeze_config";
	private static final String KEY_FROZEN_LIST = "frozen_packages";

	/** 广播：当冻结状态或冻结列表发生变化时发送，通知功能表和桌面快捷栏刷新图标角标 */
	public static final String ACTION_FREEZE_STATE_CHANGED = "ru.playsoftware.j2meloader.nokia.ACTION_FREEZE_STATE_CHANGED";
	/** 广播 extra：发生变更的包名（单包冻结/解冻时携带，供接收方预写缓存） */
	public static final String EXTRA_PACKAGE = "extra_package";
	/** 广播 extra：该包预期冻结状态（true=已冻结，false=已解冻） */
	public static final String EXTRA_FROZEN = "extra_frozen";

	private static volatile NokiaFreezeManager sInstance;
	private final Context appContext;
	private final SharedPreferences prefs;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Set<String> frozenList = new HashSet<>();

	private NokiaFreezeManager(Context context) {
		this.appContext = context.getApplicationContext();
		this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
		loadList();
	}

	public static NokiaFreezeManager getInstance(Context context) {
		if (sInstance == null) {
			synchronized (NokiaFreezeManager.class) {
				if (sInstance == null) {
					sInstance = new NokiaFreezeManager(context);
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
	 * 添加到冻结名单
	 */
	public synchronized void addToFreezeList(String packageName) {
		if (packageName == null || packageName.trim().isEmpty()) return;
		if (packageName.equals(appContext.getPackageName())) return; // 禁止把自己加入冻结名单
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
			NokiaLog.w(TAG, "isAppFrozen err pkg=" + packageName + " msg=" + e.getMessage());
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
				NokiaLog.i(TAG, "正在解冻应用: " + targetPkg);
				executeUnfreeze(targetPkg);
				// 短暂等待系统恢复组件
				try {
					Thread.sleep(100);
				} catch (InterruptedException ignored) {}
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
						NokiaLog.i(TAG, "成功解冻并启动: " + (label != null ? label : targetPkg)
								+ " -> " + intent.getComponent());
					} else {
						// 启动 Intent 解析为空：极大可能是缺少读取应用列表权限导致
						handleLaunchFailurePermissionRepair(targetPkg, label);
					}
				} catch (Exception e) {
					NokiaLog.e(TAG, "解冻后启动失败: " + targetPkg, e);
					Toast.makeText(appContext, "启动失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
				}
			});
		});
	}

	/**
	 * 当解冻后无法解析到启动 Intent 时触发的权限自愈流程
	 */
	private void handleLaunchFailurePermissionRepair(String targetPkg, String label) {
		NokiaDesktopActivity desktopActivity = NokiaDesktopActivity.getInstance();
		if (desktopActivity != null && !desktopActivity.isFinishing()) {
			NokiaLog.w(TAG, "解析启动 Intent 失败，检查并引导应用列表权限自愈: " + targetPkg);
			NokiaPermissionManager.requestAppListPermission(desktopActivity,
					"需要应用列表权限以定位并启动应用",
					new com.hjq.permissions.OnPermissionCallback() {
						@Override
						public void onGranted(java.util.List<String> permissions, boolean allGranted) {
							// 权限修复成功，自动断点续传重新启动目标应用
							NokiaLog.i(TAG, "应用列表权限自愈成功，自动重试启动: " + targetPkg);
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

			int successCount = 0;
			for (String pkg : list) {
				if (executeFreeze(pkg)) {
					successCount++;
				}
			}

			final int total = list.size();
			final int success = successCount;
			mainHandler.post(() -> {
				notifyStateChanged();
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

			int successCount = 0;
			for (String pkg : list) {
				if (executeUnfreeze(pkg)) {
					successCount++;
				}
			}

			final int total = list.size();
			final int success = successCount;
			mainHandler.post(() -> {
				notifyStateChanged();
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
		if (packageName == null || packageName.equals(appContext.getPackageName())) return false;
		NokiaLog.i(TAG, "executeFreeze: " + packageName);

		// 1. mini_shizuku Shell (最通用稳妥)
		try {
			if (Shizuku.isRunning()) {
				// Android 7.0+ 推荐 pm disable-user --user 0 ；同时强制停止
				String cmd = "am force-stop " + packageName + " ; pm disable-user --user 0 " + packageName + " || pm hide " + packageName;
				boolean res = Shizuku.exec(cmd);
				NokiaLog.i(TAG, "已通过 mini_shizuku 执行冻结: " + packageName + " res=" + res);
				return res;
			}
		} catch (Throwable e) {
			NokiaLog.w(TAG, "mini_shizuku 执行冻结异常: " + e.getMessage());
		}

		// 2. DevicePolicyManager 设备管理员 (针对设备所有者模式)
		try {
			DevicePolicyManager dpm = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName admin = new ComponentName(appContext, NokiaLockReceiver.class);
			if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (dpm.isDeviceOwnerApp(appContext.getPackageName()) || dpm.isProfileOwnerApp(appContext.getPackageName())) {
					boolean res = dpm.setApplicationHidden(admin, packageName, true);
					NokiaLog.i(TAG, "DevicePolicyManager.setApplicationHidden: " + res);
					return res;
				}
			}
		} catch (Throwable e) {
			NokiaLog.w(TAG, "DevicePolicyManager 冻结失败: " + e.getMessage());
		}

		return false;
	}

	/**
	 * 底层执行解冻
	 */
	private boolean executeUnfreeze(String packageName) {
		if (packageName == null) return false;
		NokiaLog.i(TAG, "executeUnfreeze: " + packageName);

		// 1. mini_shizuku Shell
		try {
			if (Shizuku.isRunning()) {
				String cmd = "pm enable " + packageName + " ; pm default-state --user 0 " + packageName + " ; pm unhide " + packageName;
				boolean res = Shizuku.exec(cmd);
				NokiaLog.i(TAG, "已通过 mini_shizuku 执行解冻: " + packageName + " res=" + res);
				return res;
			}
		} catch (Throwable e) {
			NokiaLog.w(TAG, "mini_shizuku 执行解冻异常: " + e.getMessage());
		}

		// 2. DevicePolicyManager
		try {
			DevicePolicyManager dpm = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
			ComponentName admin = new ComponentName(appContext, NokiaLockReceiver.class);
			if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				if (dpm.isDeviceOwnerApp(appContext.getPackageName()) || dpm.isProfileOwnerApp(appContext.getPackageName())) {
					boolean res = dpm.setApplicationHidden(admin, packageName, false);
					NokiaLog.i(TAG, "DevicePolicyManager.setApplicationHidden(false): " + res);
					return res;
				}
			}
		} catch (Throwable e) {
			NokiaLog.w(TAG, "DevicePolicyManager 解冻失败: " + e.getMessage());
		}

		return false;
	}
}
