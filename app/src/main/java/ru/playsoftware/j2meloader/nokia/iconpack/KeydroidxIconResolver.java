package ru.playsoftware.j2meloader.nokia.iconpack;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.j2meloader.nokia.KeydroidxAppIconCache;
import ru.playsoftware.j2meloader.nokia.KeydroidxNotificationRepository;
import ru.playsoftware.j2meloader.nokia.KeydroidxSettingsStorage;

/**
 * 统一图标解析入口：桌面内所有展示应用图标的位置都必须经由此处取图。
 *
 * <p>解析优先级（高 → 低）：</p>
 * <ol>
 *   <li><b>单应用图标覆盖</b>（功能表 → 选项 → 更换图标写入的 {packId, iconName}）；</li>
 *   <li><b>全局图标包</b>命中（默认内置 S60 图标包）；</li>
 *   <li><b>应用原图标</b> —— 由调用方走 {@code KeydroidxAppIconCache.loadIconWithFallback}，
 *       冻结/停用应用也能正常取图。</li>
 * </ol>
 *
 * <p><b>主线程不做 IO</b>：图标包映射表（XML）解析只在后台线程进行；主线程调用解析时若映射
 * 尚未就绪，直接按「未命中」返回并触发后台预热，预热完成后通过
 * {@link #warmUpAsync(Context, Runnable)} 的回调让调用方刷新图标。</p>
 */
public final class KeydroidxIconResolver {

	private static final String TAG = "IconResolver";

	/** 缓存键分隔符（包名与图标名中都不会出现） */
	public static final String KEY_SEP = "|";

	private static final List<Runnable> WARM_UP_CALLBACKS = new CopyOnWriteArrayList<>();
	private static volatile boolean warmUpStarted;

	private KeydroidxIconResolver() {
	}

	/** 解析结果：命中哪个图标包的哪个图标名 */
	public static final class Hit {
		public final String packId;
		public final String iconName;
		/** true = 来自单应用覆盖 */
		public final boolean override;

		Hit(String packId, String iconName, boolean override) {
			this.packId = packId;
			this.iconName = iconName;
			this.override = override;
		}

		@Override
		public String toString() {
			return (override ? "override:" : "") + packId + "/" + iconName;
		}
	}

	/**
	 * 解析某应用的图标来源。
	 *
	 * @return 命中的图标包与图标名；null = 未命中（调用方回退应用原图标）
	 */
	public static Hit resolve(Context ctx, String pkg, ComponentName cn, String label) {
		if (ctx == null || TextUtils.isEmpty(pkg)) return null;

		// ── 1. 单应用覆盖（最高优先级） ──
		String overridePack = KeydroidxSettingsStorage.getIconOverridePack(ctx, pkg);
		if (overridePack != null) {
			String overrideIcon = KeydroidxSettingsStorage.getIconOverrideName(ctx, pkg);
			KeydroidxIconPack pack = KeydroidxIconPackManager.get().findPack(overridePack);
			if (pack != null && !TextUtils.isEmpty(overrideIcon)) {
				if (ensureLoaded(ctx, pack) && pack.hasIcon(overrideIcon)) {
					return new Hit(overridePack, overrideIcon, true);
				}
				// 图标包被卸载 / 名字失效：降级到全局图标包，而不是让应用「无图标」
				KeydroidxLog.w(TAG, "覆盖图标不可用，回退全局图标包: " + pkg + " → "
						+ overridePack + "/" + overrideIcon);
			} else {
				KeydroidxLog.w(TAG, "覆盖的图标包不存在，回退全局图标包: " + pkg + " → " + overridePack);
			}
		}

		// ── 2. 全局图标包 ──
		String packId = KeydroidxSettingsStorage.getIconPackId(ctx);
		if (KeydroidxIconPackManager.isNone(packId)) {
			return null; // 用户显式选择「不使用图标包」
		}
		KeydroidxIconPack pack = KeydroidxIconPackManager.get().findPack(packId);
		if (pack == null) {
			KeydroidxLog.w(TAG, "未知图标包 ID，回退内置 S60: " + packId);
			pack = KeydroidxIconPackManager.get().getBuiltin();
		}
		if (!ensureLoaded(ctx, pack)) return null;
		String iconName = pack.getIconNameFor(pkg, cn, label);
		return iconName != null ? new Hit(pack.getId(), iconName, false) : null;
	}

	/** 命中图标包时返回其 Drawable；未命中或取图失败返回 null */
	public static Drawable resolvePackIcon(Context ctx, String pkg, ComponentName cn, String label) {
		Hit hit = resolve(ctx, pkg, cn, label);
		if (hit == null) return null;
		KeydroidxIconPack pack = KeydroidxIconPackManager.get().findPack(hit.packId);
		if (pack == null) return null;
		Drawable d = pack.getIconByName(ctx, hit.iconName);
		if (d == null) {
			KeydroidxLog.w(TAG, "取图失败（回退应用原图标）: " + hit);
		}
		return d;
	}

	/** 该应用是否设置了单应用图标覆盖 */
	public static boolean hasOverride(Context ctx, String pkg) {
		return ctx != null && pkg != null && KeydroidxSettingsStorage.hasIconOverride(ctx, pkg);
	}

	/** 覆盖所属图标包名（设置页/菜单展示用）；无覆盖返回 null */
	public static String getOverridePackId(Context ctx, String pkg) {
		return ctx != null && pkg != null ? KeydroidxSettingsStorage.getIconOverridePack(ctx, pkg) : null;
	}

	/**
	 * 构造图标缓存键：{@code pkg} 或 {@code pkg|packId|iconName}。
	 * 图标包切换、写入/清除覆盖后键自然变化，不会串图。
	 */
	public static String buildCacheKey(Context ctx, String pkg, ComponentName cn, String label) {
		if (TextUtils.isEmpty(pkg)) return "";
		Hit hit = resolve(ctx, pkg, cn, label);
		if (hit == null) return pkg;
		return pkg + KEY_SEP + hit.packId + KEY_SEP + hit.iconName;
	}

	/**
	 * 后台预热当前图标包映射表（幂等，可多次调用）。
	 *
	 * @param onReady 预热完成后的主线程回调（可 null）；若映射已就绪则立即回调
	 */
	public static void warmUpAsync(Context context, final Runnable onReady) {
		if (context == null) return;
		final Context appCtx = context.getApplicationContext();
		final Handler main = new Handler(Looper.getMainLooper());

		String packId = KeydroidxSettingsStorage.getIconPackId(appCtx);
		final KeydroidxIconPack pack = KeydroidxIconPackManager.get().findPack(packId);
		if (pack == null || pack.isLoaded()) {
			if (onReady != null) main.post(onReady);
			return;
		}
		if (onReady != null) WARM_UP_CALLBACKS.add(onReady);
		if (warmUpStarted) return;
		warmUpStarted = true;
		new Thread(new Runnable() {
			@Override
			public void run() {
				long start = System.currentTimeMillis();
				try {
					pack.ensureLoaded(appCtx);
				} catch (Throwable t) {
					KeydroidxLog.w(TAG, "图标包预热失败: " + t);
				} finally {
					warmUpStarted = false;
				}
				KeydroidxLog.i(TAG, "图标包预热结束 " + pack.getId() + "，耗时 "
						+ (System.currentTimeMillis() - start) + "ms");
				main.post(new Runnable() {
					@Override
					public void run() {
						List<Runnable> callbacks = new ArrayList<>(WARM_UP_CALLBACKS);
						WARM_UP_CALLBACKS.clear();
						for (Runnable r : callbacks) {
							try {
								r.run();
							} catch (Throwable t) {
								KeydroidxLog.w(TAG, "图标包预热回调异常: " + t);
							}
						}
					}
				});
			}
		}, "icon-pack-warmup").start();
	}

	/**
	 * 图标包切换：清空「应用 → 图标」缓存并预热新图标包。
	 *
	 * <p><b>刻意不释放各图标包已解析的映射表</b>：图标包设置页的列表行持有这些实例，
	 * 一旦释放（{@code loaded=false}）列表就先渲染成默认占位图、等后台重新解析完再变回来，
	 * 肉眼就是「闪一下」。图标包的升级/卸载由
	 * {@link KeydroidxIconPackManager#refreshInstalledAsync} 按 {@code lastUpdateTime}
	 * 精确识别并只重解析那一个，不需要在切换时全量丢弃。</p>
	 */
	public static void invalidateAll(Context ctx) {
		KeydroidxAppIconCache.invalidateAll();
		// 通知条 / 通知中心自己缓存了一轮应用图标，同样要清，否则显示旧图标
		KeydroidxNotificationRepository.get().clearIconCache();
		KeydroidxLog.i(TAG, "图标包切换：已清空图标缓存");
		warmUpAsync(ctx, null);
	}

	/** 单应用覆盖变化：只失效该应用的图标缓存 */
	public static void invalidatePackage(Context ctx, String pkg) {
		KeydroidxAppIconCache.invalidate(pkg);
		// 通知缓存按包名而不是按覆盖维度，直接整体清掉最省事（条目很少）
		KeydroidxNotificationRepository.get().clearIconCache();
		KeydroidxLog.d(TAG, "失效应用图标缓存: " + pkg);
	}

	/** 主线程不在主线程解析映射表（避免文件 IO 卡顿） */
	private static boolean ensureLoaded(Context ctx, KeydroidxIconPack pack) {
		if (pack.isLoaded()) return true;
		if (Looper.myLooper() == Looper.getMainLooper()) {
			warmUpAsync(ctx, null);
			return false;
		}
		pack.ensureLoaded(ctx);
		return pack.isLoaded();
	}
}
