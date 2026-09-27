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
import android.util.LruCache;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import java.io.File;
import java.io.FileOutputStream;
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
 *   <li><b>内存缓存</b> LruCache&lt;包名, Drawable&gt;：进程内二次进入功能表秒出</li>
 *   <li><b>磁盘缓存</b> cacheDir/app_icons/&lt;包名&gt;.png：跨进程冷启动秒出；
 *       用包 lastUpdateTime 校验，应用更新后自动失效重建</li>
 *   <li><b>系统 PackageManager</b> 后台线程 getActivityIcon：仅前两层未命中时查，
 *       查完写回内存与磁盘</li>
 * </ol>
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
		// v4：v2/v3 的 PNG 是按「自适应图标整体放大 1.5 倍」渲染的，遮罩圆角被挤出画布、
		// 前景贴边被截断；本轮改回原画布渲染，换目录名让旧缓存一次性重建。
		diskDir = new File(ctx.getCacheDir(), "app_icons_v4");
		if (!diskDir.exists() && !diskDir.mkdirs()) {
			KeydroidxLog.w("AppIconCache", "创建磁盘缓存目录失败: " + diskDir.getAbsolutePath());
		}
		KeydroidxLog.i("AppIconCache", "初始化完成，磁盘缓存目录: " + diskDir.getAbsolutePath());
	}

	/** 同步读内存缓存（主线程安全，O(1)），未命中返回 null */
	public static Drawable getFromMemory(String packageName) {
		return memCache != null ? memCache.get(packageName) : null;
	}

	/**
	 * 异步加载应用图标：内存 → 磁盘 → PackageManager，全部在后台线程，主线程回调。
	 * 同一包名并发请求允许重复（内存 put 幂等），保证每个调用方都能收到回调。
	 */
	public static void loadAsync(Context context, String packageName, ComponentName component,
								 IconCallback callback) {
		init(context);
		Drawable mem = memCache.get(packageName);
		if (mem != null) {
			KeydroidxLog.d("AppIconCache", packageName + " 内存缓存命中");
			if (callback != null) callback.onLoaded(packageName, mem);
			return;
		}
		EXEC.execute(() -> loadInternal(context, packageName, component, callback));
	}

	private static void loadInternal(Context context, String packageName,
									 ComponentName component, IconCallback callback) {
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

		File file = new File(diskDir, packageName + ".png");
		// 1. 磁盘缓存命中（且包未被更新）
		if (lastUpdate >= 0 && prefs.getLong(KEY_UPDATE_TIME + ":" + packageName, -1) >= lastUpdate) {
			try {
				Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
				if (bmp != null) {
					Drawable d = new BitmapDrawable(context.getResources(), bmp);
					memCache.put(packageName, d);
					KeydroidxLog.d("AppIconCache", packageName + " 磁盘缓存命中");
					post(packageName, d, callback);
					return;
				}
			} catch (Exception e) {
				KeydroidxLog.w("AppIconCache", "读取磁盘缓存失败 " + packageName + ": " + e.getMessage());
			}
		}

		// 2. PackageManager 加载（重 IPC，后台线程），成功写回内存 + 磁盘
		Drawable icon = loadIconWithFallback(pm, packageName, component, appInfo);
		if (icon != null) {
			// 自适应图标统一栅格化为位图，保证内存/磁盘/占位图三条路径渲染一致
			Drawable normalized = normalizeForDisplay(context.getResources(), icon);
			memCache.put(packageName, normalized);
			saveToDisk(packageName, normalized, file);
			if (lastUpdate >= 0) {
				prefs.edit().putLong(KEY_UPDATE_TIME + ":" + packageName, lastUpdate).apply();
			}
			KeydroidxLog.i("AppIconCache", packageName + " 系统加载完成并写入缓存");
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

	private static void saveToDisk(String packageName, Drawable icon, File file) {
		try {
			Bitmap bmp = drawableToBitmap(icon);
			if (bmp == null) return;
			FileOutputStream fos = new FileOutputStream(file);
			bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
			fos.close();
		} catch (Exception e) {
			KeydroidxLog.w("AppIconCache", "写磁盘缓存失败 " + packageName + ": " + e.getMessage());
		}
	}

	private static void post(String packageName, Drawable icon, IconCallback callback) {
		if (callback == null) return;
		MAIN.post(() -> callback.onLoaded(packageName, icon));
	}
}
