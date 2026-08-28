package ru.playsoftware.j2meloader.nokia;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import io.github.cctyl.nokia.common.log.NokiaLog;
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
public final class NokiaAppIconCache {

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

	private NokiaAppIconCache() {
	}

	/** 初始化缓存目录与内存缓存（幂等）。可在任意调用前显式调用，内部也自动调用。 */
	public static void init(Context context) {
		if (memCache != null) return;
		Context ctx = context.getApplicationContext();
		memCache = new LruCache<>(MEM_MAX);
		prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		diskDir = new File(ctx.getCacheDir(), "app_icons");
		if (!diskDir.exists() && !diskDir.mkdirs()) {
			NokiaLog.w("AppIconCache", "创建磁盘缓存目录失败: " + diskDir.getAbsolutePath());
		}
		NokiaLog.i("AppIconCache", "初始化完成，磁盘缓存目录: " + diskDir.getAbsolutePath());
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
			NokiaLog.d("AppIconCache", packageName + " 内存缓存命中");
			if (callback != null) callback.onLoaded(packageName, mem);
			return;
		}
		EXEC.execute(() -> loadInternal(context, packageName, component, callback));
	}

	private static void loadInternal(Context context, String packageName,
									 ComponentName component, IconCallback callback) {
		long lastUpdate = -1;
		try {
			lastUpdate = context.getPackageManager()
					.getPackageInfo(packageName, 0).lastUpdateTime;
		} catch (Exception e) {
			// 包已卸载等异常 → 视为失效，走系统重新加载（失败回调 null）
			NokiaLog.w("AppIconCache", "查询包更新时间失败 " + packageName + ": " + e.getMessage());
		}

		File file = new File(diskDir, packageName + ".png");
		// 1. 磁盘缓存命中（且包未被更新）
		if (lastUpdate >= 0 && prefs.getLong(KEY_UPDATE_TIME + ":" + packageName, -1) >= lastUpdate) {
			try {
				Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
				if (bmp != null) {
					Drawable d = new BitmapDrawable(context.getResources(), bmp);
					memCache.put(packageName, d);
					NokiaLog.d("AppIconCache", packageName + " 磁盘缓存命中");
					post(packageName, d, callback);
					return;
				}
			} catch (Exception e) {
				NokiaLog.w("AppIconCache", "读取磁盘缓存失败 " + packageName + ": " + e.getMessage());
			}
		}

		// 2. PackageManager 加载（重 IPC，后台线程），成功写回内存 + 磁盘
		try {
			Drawable icon = context.getPackageManager().getActivityIcon(component);
			if (icon != null) {
				memCache.put(packageName, icon);
				saveToDisk(packageName, icon, file);
				if (lastUpdate >= 0) {
					prefs.edit().putLong(KEY_UPDATE_TIME + ":" + packageName, lastUpdate).apply();
				}
				NokiaLog.i("AppIconCache", packageName + " 系统加载完成并写入缓存");
				post(packageName, icon, callback);
				return;
			}
		} catch (Exception e) {
			NokiaLog.w("AppIconCache", "系统加载图标失败 " + packageName + ": " + e.getMessage());
		}
		post(packageName, null, callback);
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
			NokiaLog.w("AppIconCache", "Drawable 转 Bitmap 失败: " + e.getMessage());
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
			NokiaLog.w("AppIconCache", "写磁盘缓存失败 " + packageName + ": " + e.getMessage());
		}
	}

	private static void post(String packageName, Drawable icon, IconCallback callback) {
		if (callback == null) return;
		MAIN.post(() -> callback.onLoaded(packageName, icon));
	}
}
