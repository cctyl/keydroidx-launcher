package ru.playsoftware.j2meloader.nokia;

import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.MidletStateStore;

/**
 * jar 应用启动入口（百宝箱 / 顶部快捷栏统一收口）。
 * <p>
 * 挂机语义判定：
 * <ul>
 *   <li>无挂机 或 点的是同一 jar → 直接启动（:midlet 进程内自动走全新加载/续跑分支）；</li>
 *   <li>点的是另一个 jar 且有 jar 在挂机 → 弹确认「后台运行的 xxx 将被停止，是否继续」，
 *       继续 → 销毁旧实例并启动新 jar，取消 → 无事发生。</li>
 * </ul>
 */
public final class NokiaJarLauncher {

	private static final String TAG = "JarLauncher";

	private NokiaJarLauncher() {}

	public static void launch(FragmentActivity act, String name, String path) {
		MidletStateStore.RunningInfo running =
				MidletStateStore.getRunning(act.getApplicationContext());
		if (running == null || running.appPath.equals(path)) {
			NokiaLog.i(TAG, "直接启动: " + name);
			Config.startApp(act, name, path, false);
			return;
		}
		NokiaLog.i(TAG, "挂机中(" + running.appName + ")，启动新 jar 需确认: " + name);
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_PLAY,
				"继续", true, false, () -> {
					NokiaLog.i(TAG, "确认切换: " + running.appName + " -> " + name);
					Config.startApp(act, name, path, false);
				}));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_CLOSE,
				"取消", true, false, () -> NokiaLog.i(TAG, "取消切换，保持挂机: " + running.appName)));
		NokiaOptionsDialog.show(act.getSupportFragmentManager(),
				"后台运行的「" + running.appName + "」将被停止，是否继续？", items);
	}
}
