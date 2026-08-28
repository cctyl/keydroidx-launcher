package ru.playsoftware.j2meloader.nokia;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.cctyl.nokia.common.log.NokiaLog;

/**
 * 设备管理员 Receiver（无 UI）。仅用于支持桌面「锁屏」一键锁屏息屏，
 * 申请的最小策略为 {@code force-lock}。是否真正锁屏取决于用户是否已在系统设置中
 * 授予本应用设备管理员权限。
 */
public class NokiaDeviceAdminReceiver extends DeviceAdminReceiver {

	@Override
	public void onEnabled(Context context, Intent intent) {
		NokiaLog.i("DeviceAdmin", "设备管理员已启用（锁屏功能可用）");
	}

	@Override
	public void onDisabled(Context context, Intent intent) {
		NokiaLog.i("DeviceAdmin", "设备管理员已禁用（锁屏功能不可用）");
	}
}
