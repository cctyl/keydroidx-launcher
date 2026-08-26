package ru.playsoftware.j2meloader.nokia;

import android.app.ActivityOptions;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

/**
 * 桌面辅助工具类，集中处理 Launcher 状态检测、回到桌面 Intent 构建等逻辑。
 */
public final class NokiaLauncherUtils {

	private static final String NOKIA_ACTIVITY =
			"ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity";

	private NokiaLauncherUtils() {
	}

	/**
	 * 判断当前应用是否已被设为系统默认桌面。
	 */
	public static boolean isDefaultLauncher(Context context) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
				if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
					return roleManager.isRoleHeld(RoleManager.ROLE_HOME);
				}
			}
			Intent homeIntent = new Intent(Intent.ACTION_MAIN);
			homeIntent.addCategory(Intent.CATEGORY_HOME);
			homeIntent.addCategory(Intent.CATEGORY_DEFAULT);
			ResolveInfo resolve = context.getPackageManager().resolveActivity(
					homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
			if (resolve == null || resolve.activityInfo == null) {
				return false;
			}
			return context.getPackageName().equals(resolve.activityInfo.packageName);
		} catch (Exception e) {
			NokiaLog.e("LauncherUtils", "isDefaultLauncher 判断失败", e);
			return false;
		}
	}

	/**
	 * 回到诺基亚桌面。
	 * 若已是默认桌面，发送标准隐式 HOME Intent，由系统 WMS 执行原生的返回桌面/壁纸展开动画；
	 * 若非默认桌面，显式启动 NokiaDesktopActivity，并附带平滑无缝过渡动画，避免生硬的 Activity 推进动画。
	 */
	public static void navigateToHome(Context context) {
		boolean isDefault = isDefaultLauncher(context);
		Intent intent = new Intent(Intent.ACTION_MAIN);
		intent.addCategory(Intent.CATEGORY_HOME);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

		if (isDefault) {
			try {
				context.startActivity(intent);
				NokiaLog.i("LauncherUtils", "navigateToHome: 隐式 HOME 已发送 (默认桌面)");
				return;
			} catch (Exception e) {
				NokiaLog.w("LauncherUtils", "隐式 HOME 失败，降级显式: " + e.getMessage());
			}
		}

		// 非默认桌面或隐式失败：显式拉起 NokiaDesktopActivity
		intent.setClassName(context, NOKIA_ACTIVITY);
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
				ActivityOptions options = ActivityOptions.makeCustomAnimation(
						context, android.R.anim.fade_in, android.R.anim.fade_out);
				context.startActivity(intent, options.toBundle());
			} else {
				context.startActivity(intent);
			}
			NokiaLog.i("LauncherUtils", "navigateToHome: 显式 Desktop 已发送");
		} catch (Exception e) {
			NokiaLog.e("LauncherUtils", "navigateToHome 显式启动失败", e);
		}
	}
}
