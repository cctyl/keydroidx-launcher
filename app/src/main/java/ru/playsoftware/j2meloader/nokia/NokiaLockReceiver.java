package ru.playsoftware.j2meloader.nokia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import io.github.cctyl.nokia.common.log.NokiaLog;

/**
 * 锁屏广播接收器：供 native 拦截器通过 {@code am broadcast} 触发 Device Admin 锁屏。
 * <p>
 * native 拦截器在 C/F 状态（亮屏·主界面 / 亮屏·锁屏界面）按下挂机键时，
 * 发送 {@link #ACTION_LOCK_SCREEN} 广播，本接收器调用
 * {@link NokiaLockScreen#lock(Context)} 执行 Device Admin 锁屏。
 * 这样不依赖 {@code input keyevent}（各 ROM 兼容性差），全版本通用。
 */
public class NokiaLockReceiver extends BroadcastReceiver {

	/** 锁屏广播 action */
	public static final String ACTION_LOCK_SCREEN = "ru.playsoftware.j2meloader.nokia.LOCK_SCREEN";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (ACTION_LOCK_SCREEN.equals(intent.getAction())) {
			NokiaLog.i("LockReceiver", "收到锁屏广播，执行 Device Admin 锁屏");
			NokiaLockScreen.lock(context);
		}
	}
}
