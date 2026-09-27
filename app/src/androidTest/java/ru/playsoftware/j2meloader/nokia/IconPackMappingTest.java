package ru.playsoftware.j2meloader.nokia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.List;

import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxAdwIconPack;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPackManager;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxS60Icons;

/**
 * 图标包映射验证（跑在真实设备上，与主应用同进程）。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li>内置 S60 包的映射表能正常解析（条目数、可挑选清单、资源表一致）；</li>
 *   <li>映射结果<b>确定性</b>：同一应用重复解析结果恒定一致（纯查表，无随机性）；</li>
 *   <li>未收录应用必定未命中（null），保证调用方能正确回退为应用原图标。</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public class IconPackMappingTest {

	private static final int REPEAT = 20;

	@Test
	public void builtinPackLoadsAndMapsKnownPackages() {
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		KeydroidxAdwIconPack pack = KeydroidxAdwIconPack.builtin();
		pack.ensureLoaded(ctx);

		assertTrue("内置 S60 映射表应解析成功", pack.isLoaded());
		// 映射表由原精确包名表（108 条）+ 固定槽位候选补齐转换而来
		assertTrue("内置映射条目过少: " + pack.getMappedCount(), pack.getMappedCount() >= 100);
		assertFalse("内置可挑选图标清单不应为空", pack.listIconNames().isEmpty());

		// 资源表与映射表必须对得上
		assertEquals("内置 S60 图标数量变化，请同步 assets/s60/drawable.xml 与 KeydroidxS60Icons",
				48, KeydroidxS60Icons.count());
		assertTrue(KeydroidxS60Icons.idOf("s60_settings") != 0);
		assertNotNull("内置图标名应能取到位图", pack.getIconByName(ctx, "s60_settings"));

		// 抽样校验映射正确性（原精确包名表的关键条目）
		assertEquals("s60_mms", pack.getIconNameFor("com.tencent.mm", null, "微信"));
		assertEquals("s60_mms", pack.getIconNameFor("com.android.mms", null, "信息"));
		assertEquals("s60_browser", pack.getIconNameFor("com.android.chrome", null, "Chrome"));
		assertEquals("s60_settings", pack.getIconNameFor("com.android.settings", null, "设置"));
		assertEquals("s60_app", pack.getIconNameFor("com.taobao.taobao", null, "淘宝"));
		assertEquals("s60_video_player", pack.getIconNameFor("com.bilibili.app.in", null, "哔哩哔哩"));
		assertEquals("s60_music", pack.getIconNameFor("com.netease.cloudmusic", null, "网易云音乐"));
	}

	@Test
	public void mappingIsDeterministicAndUnmatchedFallsBack() {
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		KeydroidxAdwIconPack pack = KeydroidxAdwIconPack.builtin();
		pack.ensureLoaded(ctx);
		PackageManager pm = ctx.getPackageManager();

		Intent main = new Intent(Intent.ACTION_MAIN);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
		assertFalse("设备上未枚举到任何可启动应用，测试数据为空", list.isEmpty());

		StringBuilder report = new StringBuilder("包名\t应用名\t图标名\n");
		int total = 0;
		int matched = 0;
		for (ResolveInfo ri : list) {
			if (ri.activityInfo == null) continue;
			String pkg = ri.activityInfo.packageName;
			CharSequence labelCs = ri.loadLabel(pm);
			String label = (labelCs != null && labelCs.length() > 0) ? labelCs.toString() : pkg;
			ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
			total++;

			String first = pack.getIconNameFor(pkg, cn, label);
			if (first != null) matched++;
			report.append(pkg).append('\t').append(label).append('\t')
					.append(first != null ? first : "(未收录 → 原图标)").append('\n');

			for (int i = 0; i < REPEAT; i++) {
				String cur = pack.getIconNameFor(pkg, cn, label);
				assertEquals("映射结果不确定！应用 " + pkg + " (" + label + ")", first, cur);
			}
			if (first != null) {
				// 命中必须能取到图，否则调用方会回退原图标（属于异常，需暴露）
				assertNotNull("命中却取不到图: " + pkg + " → " + first,
						pack.getIconByName(ctx, first));
			}
		}

		// 未收录包必然未命中 → 上层回退应用原图标
		assertNull(pack.getIconNameFor("com.example.definitely.not.installed", null, "不存在"));
		assertFalse(KeydroidxAdwIconPack.builtin().hasIcon("不存在的图标名"));

		System.out.println("[ICON-PACK] 共 " + total + " 个应用，命中内置 S60 " + matched + " 个，"
				+ "每个重复解析 " + REPEAT + " 次结果一致（确定性通过）");
		export(ctx, report.toString());
	}

	@Test
	public void packIdsAreRecognised() {
		assertTrue(KeydroidxIconPackManager.isNone("none"));
		assertFalse(KeydroidxIconPackManager.isNone("s60_builtin"));
		assertNotNull(KeydroidxIconPackManager.get().findPack("s60_builtin"));
		assertNull(KeydroidxIconPackManager.get().findPack("none"));
	}

	private void export(Context ctx, String content) {
		try {
			File dir = ctx.getExternalFilesDir(null);
			if (dir == null) return;
			File out = new File(dir, "icon_pack_result.txt");
			FileOutputStream fos = new FileOutputStream(out);
			fos.write(content.getBytes(Charset.forName("UTF-8")));
			fos.close();
			System.out.println("[ICON-PACK] 结果已导出: " + out.getAbsolutePath());
		} catch (Exception e) {
			System.out.println("[ICON-PACK] 导出失败: " + e.getMessage());
		}
	}
}
