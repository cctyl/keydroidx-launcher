package ru.playsoftware.j2meloader.nokia;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconResolver;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用图标加载与缓存工具（功能表等网格页专用）。
 *
 * <p>背景：{@code PackageManager.loadIcon()/getActivityIcon()} 是重 IPC 调用，
 * 在低端设备上于主线程逐个加载会让功能表首次进入卡顿约 1 秒（性能瓶颈根因）。
 * 且每次进入功能表都重新枚举 + 加载，无任何复用。</p>
 *
 * <p>三层策略（能缓存就缓存）：</p>
 * <ol>
 *   <li><b>内存缓存</b> LruCache&lt;缓存键, Drawable&gt;：进程内二次进入功能表秒出</li>
 *   <li><b>磁盘缓存</b> cacheDir/app_icons_v5/&lt;包名&gt;.png：跨进程冷启动秒出；
 *       用包 lastUpdateTime + 缓存键双重校验，应用更新或图标包切换后自动失效重建</li>
 *   <li><b>图标包 / 系统 PackageManager</b> 后台线程解析：先问
 *       {@link KeydroidxIconResolver}（单应用覆盖 → 全局图标包），未命中再回退
 *       {@link #loadIconWithFallback} 取应用原图标</li>
 * </ol>
 *
 * <p><b>缓存键</b>：{@link KeydroidxIconResolver#buildCacheKey} 生成，形如
 * {@code 包名|图标包ID|图标名}；未命中图标包时退化为纯包名。图标包切换、写入/清除单应用
 * 覆盖后键自然变化，绝不会串图。</p>
 *
 * <p>全部加载在后台线程执行，回调回到主线程，主线程不做任何 IPC。</p>
 */
public final class KeydroidxAppIconCache {

	/** 加载完成回调（主线程） */
	public interface IconCallback {
		/** @param packageName 包名；@param icon 图标，null 表示加载失败 */
		void onLoaded(String packageName, Drawable icon);
	}

	private static final int MEM_MAX = 192;
	private static final String PREFS = "nokia_app_icon_cache";
	private static final String KEY_UPDATE_TIME = "pkg_update_time";
	/** 缓存键校验副表：文件名 → 完整缓存键，避免 hashCode 冲突或包名碰撞导致串图 */
	private static final String KEY_CACHE_KEY = "cache_key:";

	private static LruCache<String, Drawable> memCache;
	private static File diskDir;
	private static SharedPreferences prefs;
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);

	private KeydroidxAppIconCache() {
	}

	/** 初始化缓存目录与内存缓存（幂等）。可在任意调用前显式调用，内部也自动调用。 */
	public static void init(Context context) {
		if (memCache != null) return;
		Context ctx = context.getApplicationContext();
		memCache = new LruCache<>(MEM_MAX);
		prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		// v5：缓存键从「仅包名」升级为「包名|图标包ID|图标名」，并新增缓存键校验副表；
		// 换目录名让 v2/v3/v4 的旧文件一次性重建，避免与图标包结果互相污染。
		diskDir = new File(ctx.getCacheDir(), "app_icons_v5");
		if (!diskDir.exists() && !diskDir.mkdirs()) {
			KeydroidxLog.w("AppIconCache", "创建磁盘缓存目录失败: " + diskDir.getAbsolutePath());
		}
		KeydroidxLog.i("AppIconCache", "初始化完成，磁盘缓存目录: " + diskDir.getAbsolutePath());
	}

	/** 同步读内存缓存（主线程安全，O(1)），未命中返回 null */
	public static Drawable getFromMemory(String cacheKey) {
		return memCache != null ? memCache.get(cacheKey) : null;
	}

	/**
	 * 异步加载应用图标（带图标包解析）：内存 → 磁盘 → 后台解析。
	 * <p>缓存键由 {@link KeydroidxIconResolver#buildCacheKey} 统一生成，
	 * 调用方无需关心；键的变化（切换图标包 / 写覆盖）天然隔离不同来源的图标。</p>
	 *
	 * @param label 应用显示名（透传给图标包解析，便于将来支持按名称匹配的图标包）
	 */
	public static void loadAsync(Context context, String packageName, ComponentName component,
								 IconCallback callback) {
		loadAsync(context, packageName, component, null, callback);
	}

	/**
	 * 异步加载应用图标（带图标包解析）。
	 *
	 * @param label 应用显示名（透传给图标包解析）
	 */
	public static void loadAsync(Context context, String packageName, ComponentName component,
								 String label, IconCallback callback) {
		init(context);
		// 主线程快路径：按当前图标外观算键查内存缓存（命中即同步回调，零 IPC）
		String fastKey = KeydroidxIconResolver.buildCacheKey(context, packageName, component, label);
		Drawable mem = memCache.get(fastKey);
		if (mem != null) {
			KeydroidxLog.d("AppIconCache", packageName + " 内存缓存命中");
			if (callback != null) callback.onLoaded(packageName, mem);
			return;
		}
		EXEC.execute(() -> loadInternal(context, packageName, component, label, callback));
	}

	private static void loadInternal(Context context, String packageName, ComponentName component,
									 String label, IconCallback callback) {
		// 后台重算缓存键：保证「落盘的键」与「实际加载到的图标」严格一致
		// （主线程算键时图标包可能尚未解析完成，此时算出的键会退化为纯包名）
		String cacheKey = KeydroidxIconResolver.buildCacheKey(context, packageName, component, label);
		Drawable cached = memCache.get(cacheKey);
		if (cached != null) {
			KeydroidxLog.d("AppIconCache", packageName + " 内存缓存命中(后台)");
			post(packageName, cached, callback);
			return;
		}
		PackageManager pm = context.getPackageManager();
		long lastUpdate = -1;
		ApplicationInfo appInfo = null;
		try {
			PackageInfo pi = pm.getPackageInfo(packageName, 0);
			lastUpdate = pi.lastUpdateTime;
			appInfo = pi.applicationInfo;
		} catch (Exception e) {
			// 包已卸载等异常 → 视为失效，走系统重新加载（失败回调 null）
			KeydroidxLog.w("AppIconCache", "查询包更新时间失败 " + packageName + ": " + e.getMessage());
		}

		File file = new File(diskDir, fileNameFor(cacheKey));
		// 1. 磁盘缓存命中（包未更新 + 缓存键一致）
		if (lastUpdate >= 0 && prefs.getLong(KEY_UPDATE_TIME + ":" + packageName, -1) >= lastUpdate
				&& cacheKey.equals(prefs.getString(KEY_CACHE_KEY + file.getName(), null))) {
			try {
				Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
				if (bmp != null) {
					Drawable d = new BitmapDrawable(context.getResources(), bmp);
					memCache.put(cacheKey, d);
					KeydroidxLog.d("AppIconCache", packageName + " 磁盘缓存命中");
					post(packageName, d, callback);
					return;
				}
			} catch (Exception e) {
				KeydroidxLog.w("AppIconCache", "读取磁盘缓存失败 " + packageName + ": " + e.getMessage());
			}
		}

		// 2. 图标包优先（单应用覆盖 → 全局图标包），未命中再取应用原图标
		Drawable icon = KeydroidxIconResolver.resolvePackIcon(context, packageName, component, label);
		if (icon == null) {
			icon = loadIconWithFallback(pm, packageName, component, appInfo);
		}
		if (icon != null) {
			// 自适应图标统一栅格化为位图，保证内存/磁盘/占位图三条路径渲染一致
			Drawable normalized = normalizeForDisplay(context.getResources(), icon);
			memCache.put(cacheKey, normalized);
			saveToDisk(cacheKey, normalized, file);
			if (lastUpdate >= 0) {
				prefs.edit()
						.putLong(KEY_UPDATE_TIME + ":" + packageName, lastUpdate)
						.putString(KEY_CACHE_KEY + file.getName(), cacheKey)
						.apply();
			}
			KeydroidxLog.i("AppIconCache", packageName + " 加载完成并写入缓存 (key=" + cacheKey + ")");
			post(packageName, normalized, callback);
			return;
		}
		post(packageName, null, callback);
	}

	/**
	 * 加载应用图标：Activity 图标优先，失败降级为应用级图标。
	 *
	 * <p><b>为什么必须降级</b>：冻结（{@code pm disable-user}）会把包的
	 * {@code ApplicationInfo.enabled} 置 false，而 {@code PackageManager.getActivityIcon()}
	 * 内部是 {@code getActivityInfo(component, GET_ACTIVITIES)}，flags 不含
	 * {@code MATCH_DISABLED_COMPONENTS} 时会被 {@code Settings.isEnabledLPr()} 判为
	 * 「未启用」而直接抛 {@code NameNotFoundException}（异常 message 就是 ComponentInfo 串）。
	 * 不做降级的话，所有冻结应用的图标必然加载失败，网格里只剩占位图（桌面自身图标）。
	 * 应用级图标走资源加载，不受包的启用状态影响。</p>
	 *
	 * @param appInfo 已知的应用信息，可为 null（此时才走 getApplicationIcon 兜底）
	 */
	public static Drawable loadIconWithFallback(PackageManager pm, String packageName,
												ComponentName component, ApplicationInfo appInfo) {
		if (component != null) {
			try {
				Drawable d = pm.getActivityIcon(component);
				if (d != null) return d;
			} catch (Exception e) {
				// 冻结应用属预期情况 → w（Release 不落盘）；卸载残留等也走这里
				KeydroidxLog.w("AppIconCache", "取 Activity 图标失败(冻结应用属正常)，降级应用图标 "
						+ packageName + ": " + e.getMessage());
			}
		}
		if (appInfo != null) {
			try {
				Drawable d = appInfo.loadIcon(pm);
				if (d != null) return d;
			} catch (Exception e) {
				KeydroidxLog.w("AppIconCache", "加载应用图标失败 " + packageName + ": " + e.getMessage());
			}
		}
		try {
			return pm.getApplicationIcon(packageName);
		} catch (Exception e) {
			KeydroidxLog.w("AppIconCache", "加载应用图标失败(兜底) " + packageName + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * 图标栅格化：把 API 26+ 的自适应图标（AdaptiveIconDrawable）按自身遮罩原样渲染成位图，
	 * 使内存缓存、磁盘缓存（PNG）与占位图三条路径渲染结果完全一致；其余图标原样返回。
	 *
	 * <p><b>此处刻意不做任何放大</b>：曾有一版按「前景安全区只占 108dp 画布的约 66%」
	 * 把整体放大 1.5 倍想铺满整幅，副作用是自适应图标自带的圆角/圆形遮罩被挤出画布、
	 * 前景内容贴边被截断（InstallerX、LocalSend、MT 管理器、EKA2L1 等尤其明显）。
	 * 保持原始画布才能得到与系统桌面一致的外观：遮罩完整、内容按设计留白。</p>
	 *
	 * @param res 用于创建 BitmapDrawable
	 * @param icon 原始图标，可为 null（原样返回 null）
	 */
	public static Drawable normalizeForDisplay(Resources res, Drawable icon) {
		if (icon == null) return null;
		if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable) {
			int size = Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
			if (size <= 0) size = 108;
			Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bmp);
			icon.setBounds(0, 0, size, size);
			icon.draw(canvas);
			return new BitmapDrawable(res, bmp);
		}
		return icon;
	}

	/** 把任意 Drawable 渲染成 Bitmap（PNG 写盘用） */
	private static Bitmap drawableToBitmap(Drawable d) {
		if (d instanceof BitmapDrawable) {
			Bitmap b = ((BitmapDrawable) d).getBitmap();
			if (b != null) return b;
		}
		int w = d.getIntrinsicWidth();
		int h = d.getIntrinsicHeight();
		if (w <= 0 || h <= 0) {
			w = 96;
			h = 96;
		}
		try {
			Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bmp);
			d.setBounds(0, 0, w, h);
			d.draw(canvas);
			canvas.setBitmap(null);
			return bmp;
		} catch (Exception e) {
			KeydroidxLog.w("AppIconCache", "Drawable 转 Bitmap 失败: " + e.getMessage());
			return null;
		}
	}

	private static void saveToDisk(String cacheKey, Drawable icon, File file) {
		try {
			Bitmap bmp = drawableToBitmap(icon);
			if (bmp == null) return;
			FileOutputStream fos = new FileOutputStream(file);
			bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
			fos.close();
		} catch (Exception e) {
			KeydroidxLog.w("AppIconCache", "写磁盘缓存失败 " + cacheKey + ": " + e.getMessage());
		}
	}

	/**
	 * 缓存键 → 磁盘文件名：{@code 包名__hash.png}（无图标包时退化为 {@code 包名.png}）。
	 * 包名前缀保证 {@link #invalidate(String)} 能按包名精确清理。
	 */
	private static String fileNameFor(String cacheKey) {
		int sep = cacheKey.indexOf(KeydroidxIconResolver.KEY_SEP);
		if (sep <= 0) {
			return sanitize(cacheKey) + ".png";
		}
		String pkg = cacheKey.substring(0, sep);
		return sanitize(pkg) + "__" + Integer.toHexString(cacheKey.hashCode()) + ".png";
	}

	private static String sanitize(String name) {
		StringBuilder sb = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
					? c : '_');
		}
		return sb.toString();
	}

	/**
	 * 清空全部图标缓存（切换图标包 / 重建时调用）：
	 * 内存清空、磁盘文件删除、缓存键副表清空（该 SharedPreferences 只存缓存元数据）。
	 */
	public static void invalidateAll() {
		if (memCache != null) memCache.evictAll();
		if (prefs != null) prefs.edit().clear().apply();
		int deleted = 0;
		File[] files = diskDir != null ? diskDir.listFiles() : null;
		if (files != null) {
			for (File f : files) {
				if (f.delete()) deleted++;
			}
		}
		KeydroidxLog.i("AppIconCache", "已清空图标缓存：删除磁盘文件 " + deleted + " 个");
	}

	/**
	 * 失效单个应用的图标缓存（写/清单应用图标覆盖时调用）。
	 * 兼容两种文件名：{@code 包名.png} 与 {@code 包名__hash.png}。
	 */
	public static void invalidate(String packageName) {
		if (TextUtils.isEmpty(packageName)) return;
		String prefix = packageName + KeydroidxIconResolver.KEY_SEP;
		if (memCache != null) {
			Map<String, Drawable> snapshot = memCache.snapshot();
			for (String key : snapshot.keySet()) {
				if (packageName.equals(key) || key.startsWith(prefix)) {
					memCache.remove(key);
				}
			}
		}
		File[] files = diskDir != null ? diskDir.listFiles() : null;
		if (files == null) return;
		String plain = packageName + ".png";
		String pfx = packageName + "__";
		int deleted = 0;
		for (File f : files) {
			String name = f.getName();
			if (name.equals(plain) || name.startsWith(pfx)) {
				if (f.delete()) deleted++;
				if (prefs != null) prefs.edit().remove(KEY_CACHE_KEY + name).apply();
			}
		}
		KeydroidxLog.d("AppIconCache", "失效应用缓存 " + packageName + "，删除文件 " + deleted + " 个");
	}

	private static void post(String packageName, Drawable icon, IconCallback callback) {
		if (callback == null) return;
		MAIN.post(() -> callback.onLoaded(packageName, icon));
	}
}
