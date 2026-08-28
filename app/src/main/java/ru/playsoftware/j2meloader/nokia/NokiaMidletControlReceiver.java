package ru.playsoftware.j2meloader.nokia;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import io.github.cctyl.nokia.common.log.NokiaLog;
import javax.microedition.shell.MidletThread;

/**
 * 挂机 jar 清除通道（运行在 :midlet 进程）。
 * <p>
 * 后台管理组件清除挂机 jar 时发送显式广播（不能用 force-stop /
 * killBackgroundProcesses —— 会连桌面主进程一起杀）。收到后走
 * {@link MidletThread#destroyApp()} 优雅销毁（END 键 → destroyApp(true) →
 * 清状态文件 → killProcess），与三菜单「退出」同一条链路。
 */
public class NokiaMidletControlReceiver extends BroadcastReceiver {

	public static final String ACTION_DESTROY_MIDLET =
			"ru.playsoftware.j2meloader.ACTION_DESTROY_MIDLET";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (!ACTION_DESTROY_MIDLET.equals(intent.getAction())) return;
		if (MidletThread.hasInstance()) {
			NokiaLog.i("MidletControl", "收到清除广播：销毁挂机 MIDlet");
			MidletThread.destroyApp();
		} else {
			// 进程内已无 MIDlet（竞态残留）：直接自杀清场（通知/Service 随进程消亡）
			NokiaLog.i("MidletControl", "收到清除广播：无实例，直接结束 :midlet 进程");
			Process.killProcess(Process.myPid());
		}
	}
}
