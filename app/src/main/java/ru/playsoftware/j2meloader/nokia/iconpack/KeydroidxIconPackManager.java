package ru.playsoftware.j2meloader.nokia.iconpack;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * 图标包管理器：持有「内置 S60 + 设备已装 ADW 图标包」清单，并按 ID 提供图标包实例。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>枚举（{@code queryIntentActivities}）只在需要展示清单时触发</b>（设置页 / 挑选页），
 *       走 {@link #refreshInstalledAsync} 后台单飞；解析图标包本身另有各自的单飞保护；</li>
 *   <li>{@link #findPack(String)} 是纯内存操作，不触发 IPC：内置包直接返回单例，
 *       {@code adw:<pkg>} 按需构造并缓存 —— 因此「解析当前图标包」不需要先枚举全部图标包；</li>
 *   <li>图标包的映射解析（耗时 IO）由调用方在后台线程调 {@code ensureLoaded}。</li>
 * </ul>
 */
public final class KeydroidxIconPackManager {

	private static final String TAG = "IconPack";

	/** 「不使用图标包」：全部使用应用原图标 */
	public static final String PACK_NONE = "none";

	/** 图标包声明用的两个标准 intent action（ADW / Nova） */
	private static final String ACTION_ADW = "org.adw.ActivityStarter.THEMES";
	private static final String ACTION_NOVA = "com.novalauncher.THEME";

	private static volatile KeydroidxIconPackManager instance;

	private final Map<String, KeydroidxAdwIconPack> packById = new LinkedHashMap<>();
	/** 已装外部图标包（按名称排序，内置包不在其中） */
	private final List<KeydroidxAdwIconPack> installedPacks = new ArrayList<>();
	/** 各外部包的 lastUpdateTime：用于识别图标包被升级后需要重新解析 */
	private final Map<String, Long> packUpdatedAt = new HashMap<>();
	private volatile boolean scanStarted;
	private volatile long lastScanAt;

	private KeydroidxIconPackManager() {
		packById.put(KeydroidxAdwIconPack.ID_BUILTIN, KeydroidxAdwIconPack.builtin());
	}

	public static KeydroidxIconPackManager get() {
		if (instance == null) {
			synchronized (KeydroidxIconPackManager.class) {
				if (instance == null) instance = new KeydroidxIconPackManager();
			}
		}
		return instance;
	}

	/** 是否为「不使用图标包」 */
	public static boolean isNone(String packId) {
		return PACK_NONE.equals(packId);
	}

	/** 内置 S60 图标包 */
	public KeydroidxIconPack getBuiltin() {
		return KeydroidxAdwIconPack.builtin();
	}

	/** 图标包清单快照（内置 S60 恒为第一项，其后为已装外部包） */
	public List<KeydroidxIconPack> getPacks() {
		List<KeydroidxIconPack> out = new ArrayList<>(installedPacks.size() + 1);
		out.add(KeydroidxAdwIconPack.builtin());
		synchronized (this) {
			out.addAll(installedPacks);
		}
		return out;
	}

	/** 已装外部图标包数量（设置页展示「已装 N 个」用） */
	public int getInstalledCount() {
		synchronized (this) {
			return installedPacks.size();
		}
	}

	public boolean isScanned() {
		return lastScanAt > 0;
	}

	/**
	 * 按 ID 取图标包（纯内存，不触发 IPC）。
	 *
	 * @return 图标包实例；{@code none}、空 ID 或非法 ID 返回 null
	 */
	public KeydroidxIconPack findPack(String packId) {
		if (TextUtils.isEmpty(packId) || PACK_NONE.equals(packId)) return null;
		if (KeydroidxAdwIconPack.ID_BUILTIN.equals(packId)) {
			return KeydroidxAdwIconPack.builtin();
		}
		if (!packId.startsWith(KeydroidxAdwIconPack.PREFIX_ADW)) return null;
		synchronized (this) {
			KeydroidxAdwIconPack cached = packById.get(packId);
			if (cached != null) return cached;
			// 尚未枚举到（例如从设置快速恢复）：按 ID 直接构造，标签先用包名，
			// 后续 refreshInstalledAsync 会用真实应用名替换清单中的实例
			KeydroidxAdwIconPack pack = KeydroidxAdwIconPack.fromId(packId, null);
			if (pack != null) packById.put(packId, pack);
			return pack;
		}
	}

	/**
	 * 后台枚举已装图标包（单飞），完成后主线程回调。
	 * 用于设置页 / 挑选页展示清单；解析路径不需要调用本方法。
	 */
	public void refreshInstalledAsync(final Context context, final Runnable onDone) {
		final Context appCtx = context.getApplicationContext();
		final Handler main = new Handler(Looper.getMainLooper());
		synchronized (this) {
			if (scanStarted) {
				KeydroidxLog.d(TAG, "图标包枚举进行中，跳过重复启动");
				if (onDone != null) main.post(onDone);
				return;
			}
			scanStarted = true;
		}
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					scanInstalled(appCtx);
				} catch (Throwable t) {
					KeydroidxLog.w(TAG, "枚举图标包失败: " + t);
				} finally {
					synchronized (KeydroidxIconPackManager.this) {
						scanStarted = false;
					}
				}
				if (onDone != null) main.post(onDone);
			}
		}, "icon-pack-scan").start();
	}

	/** 枚举实现（后台线程）：两个标准 theme action + 包名去重 */
	private void scanInstalled(Context context) {
		long start = System.currentTimeMillis();
		PackageManager pm = context.getPackageManager();
		Set<String> packages = new HashSet<>();
		collect(pm, new Intent(ACTION_ADW), packages);
		collect(pm, new Intent(ACTION_NOVA), packages);
		packages.remove(context.getPackageName());

		List<KeydroidxAdwIconPack> found = new ArrayList<>(packages.size());
		Map<String, Long> updatedAt = new HashMap<>();
		for (String pkg : packages) {
			String label = null;
			long lastUpdate = 0;
			try {
				PackageInfo pi = pm.getPackageInfo(pkg, 0);
				lastUpdate = pi.lastUpdateTime;
				CharSequence cs = pm.getApplicationLabel(pi.applicationInfo);
				if (cs != null) label = cs.toString();
			} catch (Exception e) {
				// 枚举与取标签之间被卸载：跳过
				continue;
			}
			KeydroidxAdwIconPack candidate = KeydroidxAdwIconPack.external(pkg, label);
			updatedAt.put(candidate.getId(), lastUpdate);
			found.add(candidate);
		}
		Collections.sort(found, new Comparator<KeydroidxAdwIconPack>() {
			@Override
			public int compare(KeydroidxAdwIconPack a, KeydroidxAdwIconPack b) {
				return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
			}
		});
		List<KeydroidxAdwIconPack> installed = new ArrayList<>(found.size());
		synchronized (this) {
			// 复用已有实例：实例里可能已经解析好映射表与预览图（设置页的列表行持有同一对象），
			// 每次枚举都换新实例会让预览图先「消失」再慢慢恢复。
			// 只有包被升级（lastUpdateTime 变化）时才 release，强制重新解析映射表。
			Map<String, KeydroidxAdwIconPack> next = new LinkedHashMap<>();
			next.put(KeydroidxAdwIconPack.ID_BUILTIN, KeydroidxAdwIconPack.builtin());
			for (KeydroidxAdwIconPack candidate : found) {
				String id = candidate.getId();
				KeydroidxAdwIconPack existing = packById.get(id);
				if (existing == null) {
					installed.add(candidate);
					next.put(id, candidate);
					continue;
				}
				existing.updateDisplayName(candidate.getDisplayName());
				Long prev = packUpdatedAt.get(id);
				Long now = updatedAt.get(id);
				if (prev != null && now != null && !prev.equals(now)) {
					existing.release(); // 图标包被升级/替换 → 映射表作废
					KeydroidxLog.i(TAG, "图标包已更新，需重新解析: " + id);
				}
				installed.add(existing);
				next.put(id, existing);
			}
			// 清掉已卸载的包，避免 findPack 返回已被卸载的实例
			packById.clear();
			packById.putAll(next);
			packUpdatedAt.clear();
			packUpdatedAt.putAll(updatedAt);
			installedPacks.clear();
			installedPacks.addAll(installed);
			lastScanAt = System.currentTimeMillis();
		}
		KeydroidxLog.i(TAG, "枚举图标包完成：已装 " + installed.size() + " 个，耗时 "
				+ (lastScanAt - start) + "ms");
	}

	private static void collect(PackageManager pm, Intent intent, Set<String> out) {
		List<ResolveInfo> list;
		try {
			list = pm.queryIntentActivities(intent, 0);
		} catch (Throwable t) {
			KeydroidxLog.w(TAG, "查询图标包失败 " + intent.getAction() + ": " + t);
			return;
		}
		if (list == null) return;
		for (ResolveInfo ri : list) {
			if (ri.activityInfo != null && !TextUtils.isEmpty(ri.activityInfo.packageName)) {
				out.add(ri.activityInfo.packageName);
			}
		}
	}

	/**
	 * 强制丢弃全部外部包的已解析映射并清空枚举时间戳，使下次使用时整体重新读取。
	 * <p><b>不要在「切换图标包」时调用</b>：切换只需换个 ID，映射表仍然有效，
	 * 丢弃会让界面先回落成默认占位图再被重新解析回来（可见闪烁）。
	 * 图标包的安装/卸载/升级由 {@link #refreshInstalledAsync} → {@code scanInstalled}
	 * 复用实例 + 比对 {@code lastUpdateTime} 精确处理。
	 * 本方法保留给确实需要「整体重读」的调用方。</p>
	 */
	public void invalidate() {
		synchronized (this) {
			for (KeydroidxAdwIconPack pack : installedPacks) {
				// 释放跨包 Resources 与已解析映射（避免长期持有外部包资源），
				// 但保留清单本身——图标包设置页正在展示它，清空会造成列表闪烁与焦点丢失
				pack.release();
			}
			lastScanAt = 0;
		}
		KeydroidxLog.i(TAG, "图标包缓存已失效（映射表已释放，清单保留待后台重新枚举）");
	}
}
