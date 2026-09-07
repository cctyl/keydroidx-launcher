package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.PrintWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * S60 图标匹配「确定性」验证测试（跑在真实设备上，与主应用同进程）。
 *
 * <p>数据来源：与功能表 {@code KeydroidxMenuFragment.loadApps()} 完全一致的方式，
 * 用 PackageManager 枚举设备上所有可启动应用，拿到每个应用的 (包名, 应用名 label)。
 * 这正是用户说的"应用本身就能获取应用列表"。</p>
 *
 * <p>验证逻辑：对每个应用先调用一次 {@link KeydroidxS60IconMap#getIcon(String, String)} 得到首次结果，
 * 再连续调用 100 次，断言每次结果都与首次完全一致（同一应用匹配结果 100% 确定，零随机性）。</p>
 *
 * <p>输出：每个应用的 (包名, label, 匹配到的图标名) 打印到测试输出并导出到
 * /sdcard/Android/data/&lt;package&gt;/files/s60_icon_result.txt，可用 adb pull 查看。</p>
 */
@RunWith(AndroidJUnit4.class)
public class S60IconDeterminismTest {

	private static final int REPEAT = 100;

	@Test
	public void testIconMatchIsDeterministic_100Runs() throws Exception {
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		PackageManager pm = ctx.getPackageManager();

		// ── 与应用功能表 loadApps 相同的枚举方式：获取真实设备应用列表（含 label）──
		Intent main = new Intent(Intent.ACTION_MAIN);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);

		// 构建意图缓存，让三层匹配（精确包名 → label → 意图）全部生效
		KeydroidxS60IconMap.loadFromDisk(ctx);
		KeydroidxS60IconMap.init(pm);

		StringBuilder report = new StringBuilder();
		report.append("包名\t应用名\t匹配图标\n");
		int total = 0;
		int matched = 0;
		int mismatchCount = 0;

		for (ResolveInfo ri : list) {
			if (ri.activityInfo == null) continue;
			String pkg = ri.activityInfo.packageName;
			CharSequence labelCs = ri.loadLabel(pm);
			String label = (labelCs != null && labelCs.length() > 0) ? labelCs.toString() : pkg;
			total++;

			// ── 先跑一次得出匹配结果 ──
			int first = KeydroidxS60IconMap.getIcon(pkg, label);
			if (first != 0) matched++;

			// ── 再跑 100 次，验证每次结果都与第一次一致 ──
			for (int i = 0; i < REPEAT; i++) {
				int cur = KeydroidxS60IconMap.getIcon(pkg, label);
				if (cur != first) {
					mismatchCount++;
					fail("确定性验证失败！应用 " + pkg + " (" + label + ") 第 " + (i + 1)
							+ " 次结果 " + cur + " != 首次结果 " + first);
				}
			}

			String iconName = first == 0 ? "(未匹配,默认图标)" : resourceName(ctx, first);
			String line = pkg + "\t" + label + "\t" + iconName;
			report.append(line).append('\n');
			System.out.println("[S60-DETERM] " + line);
		}

		// ── 导出结果文件，可用 adb pull 查看 ──
		try {
			File out = new File(ctx.getExternalFilesDir(null), "s60_icon_result.txt");
			if (out != null) {
				PrintWriter pw = new PrintWriter(out);
				pw.write(report.toString());
				pw.close();
				System.out.println("[S60-DETERM] 结果已导出: " + out.getAbsolutePath());
			}
		} catch (Exception e) {
			System.out.println("[S60-DETERM] 导出失败: " + e.getMessage());
		}

		System.out.println("[S60-DETERM] 共 " + total + " 个应用，匹配到 S60 图标 " + matched + " 个");
		System.out.println("[S60-DETERM] 每个应用重复匹配 " + REPEAT + " 次，全部与首次一致，确定性验证通过");

		if (total == 0) {
			fail("设备上未枚举到任何可启动应用，测试数据为空");
		}
		assertEquals("存在结果不一致的应用", 0, mismatchCount);
	}

	/** 把资源 ID 转成可读的资源名（如 s60_mms），失败时回退为数字 */
	private static String resourceName(Context ctx, int resId) {
		try {
			return ctx.getResources().getResourceEntryName(resId);
		} catch (Exception e) {
			return String.valueOf(resId);
		}
	}
}
