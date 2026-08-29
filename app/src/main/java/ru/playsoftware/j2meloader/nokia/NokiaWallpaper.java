package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaTheme;

/**
 * 桌面壁纸管理（桌面设置 → 外观与显示 → 壁纸设置）。
 * <p>
 * 背景机制：{@code activity_nokia.xml} 里有一个全屏 {@code R.id.wallpaper} View，
 * 由 {@link NokiaDesktopActivity#applyCurrentTheme()} 统一设置背景；所有页面 Fragment 的根视图
 * 都被 {@link NokiaPageFragment#onViewCreated} 强制透明化，因此<b>桌面、功能表、百宝箱、设置等
 * 全部页面共用这一张全屏背景</b>——设置图片后所有页面同步生效，无需逐页处理。
 * <p>
 * 存储策略：用户选择的图片经解码 → EXIF 纠正方向 → 按屏幕尺寸等比压缩后，
 * 以 PNG 存入应用内部存储 {@code files/wallpaper.png}。不使用 Uri 持久权限，
 * 避免源文件被删/被移动/权限回收导致壁纸失效，且 Android 4.4 上行为一致。
 * <p>
 * 解码结果在进程内缓存：{@code applyCurrentTheme()} 每次切换 Fragment 都会调用，
 * 若每次重新解码全屏位图会造成明显卡顿。
 */
public final class NokiaWallpaper {

	private static final String TAG = "Wallpaper";

	/** 壁纸在内部存储中的固定文件名。 */
	private static final String FILE_NAME = "wallpaper.png";

	/** 导入时先把 Uri 落到本地临时文件（用于 EXIF 读取 + 两次解码复用）。 */
	private static final String TMP_NAME = "wallpaper_import.tmp";

	/** 解码阶段的长边上限，防止超大图 OOM（内存约 2048*2048*4 ≈ 16MB）。 */
	private static final int MAX_DECODE_SIDE = 2048;

	/** 进程内解码缓存（不 recycle 旧图：可能仍被正在绘制的 Drawable 持有）。 */
	private static Bitmap cachedBitmap;

	private NokiaWallpaper() {
	}

	/** 壁纸文件位置。 */
	public static File getWallpaperFile(Context ctx) {
		return new File(ctx.getFilesDir(), FILE_NAME);
	}

	/** 是否已设置自定义壁纸（以内部存储文件是否存在为唯一依据）。 */
	public static boolean hasCustomWallpaper(Context ctx) {
		File f = getWallpaperFile(ctx);
		return f.exists() && f.length() > 0;
	}

	/**
	 * 生成全屏背景 Drawable。
	 * <ul>
	 *   <li>未设置自定义壁纸：返回当前主题三段式渐变（与原先行为一致）；</li>
	 *   <li>已设置：返回 {@code 主题渐变 + 自定义图片} 的 {@link LayerDrawable}，
	 *       下层渐变用于「适应屏幕」模式留边区域的填充。</li>
	 * </ul>
	 */
	public static Drawable createWallpaperDrawable(Context ctx, NokiaTheme.ThemeDef theme) {
		Drawable base = NokiaTheme.createBackgroundDrawable(theme);
		Bitmap bmp = getCachedBitmap(ctx);
		if (bmp == null) {
			return base;
		}
		int mode = NokiaSettingsStorage.getWallpaperScale(ctx);
		return new LayerDrawable(new Drawable[]{base, new WallpaperDrawable(bmp, mode)});
	}

	/** 丢弃解码缓存（导入/清除/切换缩放模式后调用）。 */
	public static synchronized void invalidateCache() {
		cachedBitmap = null;
	}

	/**
	 * 导入第一步：<b>必须在 {@code onActivityResult} 里同步调用</b>（主线程），
	 * 把 Uri 指向的内容复制到缓存文件。
	 * <p>
	 * <b>为什么必须同步：</b>文件选择器返回的 Uri 只授予临时读权限，且仅在
	 * {@code onActivityResult} 期间可靠。此前把复制放进后台线程，线程真正启动时
	 * Activity 已恢复前台、临时授权被系统回收，MediaStore 抛
	 * {@code SecurityException: ... has no access to content://media/external/...}
	 * （Android 13 实测必现）。因此 IO 必须在权限有效期内完成；
	 * 解码 / 压缩 / 落盘等 CPU 密集步骤仍交给后台线程（见 {@link #finalizeImport}）。
	 * 字体导入（{@code NokiaFontManager.importFontFromUri}）正是因为全程同步才没踩这个坑。
	 *
	 * @return 缓存文件（调用方在 {@link #finalizeImport} 返回后无需再处理，内部会删除）；
	 *         失败返回 null
	 */
	public static File copyToCacheSync(Context ctx, Uri uri) {
		File tmp = new File(ctx.getCacheDir(), TMP_NAME);
		if (tmp.exists()) {
			tmp.delete();
		}
		File parent = tmp.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		boolean ok = copyToCache(ctx, uri, tmp);
		if (!ok) {
			NokiaLog.w(TAG, "壁纸导入失败：无法读取所选文件（Uri 读权限不足或文件不可读）");
			return null;
		}
		NokiaLog.i(TAG, "已复制到缓存: " + tmp.length() / 1024 + "KB");
		return tmp;
	}

	/**
	 * 导入第二步：<b>在后台线程调用</b>——解码缓存文件 → 纠正方向 → 等比压缩 → 写入内部存储。
	 *
	 * @param tmp {@link #copyToCacheSync} 返回的缓存文件（本方法结束时会被删除）
	 * @return true 表示导入成功并已刷新缓存
	 */
	public static boolean finalizeImport(Context ctx, File tmp) {
		try {
			if (tmp == null || !tmp.exists()) {
				NokiaLog.w(TAG, "壁纸导入失败：缓存文件不存在");
				return false;
			}
			int degrees = readOrientation(tmp);
			Bitmap bmp = decodeFile(tmp, MAX_DECODE_SIDE);
			if (bmp == null) {
				NokiaLog.w(TAG, "壁纸导入失败：图片解码失败（格式不支持或文件损坏）");
				return false;
			}
			bmp = rotate(bmp, degrees);

			DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
			int maxSide = Math.max(dm.widthPixels, dm.heightPixels);
			bmp = scaleDown(bmp, maxSide);
			if (bmp == null) {
				return false;
			}

			if (!save(ctx, bmp)) {
				bmp.recycle();
				return false;
			}
			synchronized (NokiaWallpaper.class) {
				cachedBitmap = bmp;
			}
			NokiaLog.i(TAG, "壁纸导入完成: " + bmp.getWidth() + "x" + bmp.getHeight()
					+ " mode=" + NokiaSettingsStorage.getWallpaperScale(ctx));
			return true;
		} finally {
			if (tmp != null && tmp.exists()) {
				tmp.delete();
			}
		}
	}

	/** 清除自定义壁纸，回退到主题渐变背景。 */
	public static synchronized void clear(Context ctx) {
		File f = getWallpaperFile(ctx);
		if (f.exists()) {
			f.delete();
		}
		cachedBitmap = null;
		NokiaLog.i(TAG, "已清除自定义壁纸，回退主题背景");
	}

	// ---- 内部实现 ----

	private static synchronized Bitmap getCachedBitmap(Context ctx) {
		if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
			return cachedBitmap;
		}
		if (!hasCustomWallpaper(ctx)) {
			return null;
		}
		try {
			Bitmap bmp = BitmapFactory.decodeFile(getWallpaperFile(ctx).getAbsolutePath());
			if (bmp == null) {
				NokiaLog.w(TAG, "壁纸文件解码失败，回退主题背景");
				return null;
			}
			cachedBitmap = bmp;
			return bmp;
		} catch (OutOfMemoryError e) {
			NokiaLog.e(TAG, "壁纸解码内存不足，回退主题背景");
			return null;
		}
	}

	/**
	 * 把 Uri 内容复制到本地文件。
	 * <b>必须在 Uri 读权限有效期内调用</b>（即 {@code onActivityResult} 内同步调用）。
	 */
	private static boolean copyToCache(Context ctx, Uri uri, File dst) {
		InputStream in = null;
		OutputStream out = null;
		try {
			in = ctx.getContentResolver().openInputStream(uri);
			if (in == null) {
				NokiaLog.w(TAG, "openInputStream 返回 null: " + uri);
				return false;
			}
			out = new FileOutputStream(dst);
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
			}
			out.flush();
			return true;
		} catch (SecurityException e) {
			// Uri 读权限已被回收（例如放到后台线程才读取）。属于可预期的权限问题，单独提示。
			NokiaLog.e(TAG, "无权限读取所选 Uri（临时授权已失效？）: " + uri, e);
			return false;
		} catch (Exception e) {
			NokiaLog.e(TAG, "复制所选图片到缓存失败: " + uri, e);
			return false;
		} finally {
			closeQuietly(in);
			closeQuietly(out);
		}
	}

	/**
	 * 读取 JPEG 方向信息。
	 * 使用平台 {@link ExifInterface}（API 5+，虽已 deprecated 但在 4.4 上可用），
	 * 避免为此引入 androidx.exifinterface 依赖；任何异常都退化为不旋转。
	 */
	@SuppressWarnings("deprecation")
	private static int readOrientation(File file) {
		try {
			ExifInterface exif = new ExifInterface(file.getAbsolutePath());
			int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
					ExifInterface.ORIENTATION_NORMAL);
			switch (o) {
				case ExifInterface.ORIENTATION_ROTATE_90:
					return 90;
				case ExifInterface.ORIENTATION_ROTATE_180:
					return 180;
				case ExifInterface.ORIENTATION_ROTATE_270:
					return 270;
				default:
					return 0;
			}
		} catch (Throwable t) {
			return 0;
		}
	}

	private static Bitmap decodeFile(File f, int maxSide) {
		String path = f.getAbsolutePath();
		BitmapFactory.Options opts = new BitmapFactory.Options();
		opts.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(path, opts);
		int w = opts.outWidth;
		int h = opts.outHeight;
		if (w <= 0 || h <= 0) {
			NokiaLog.w(TAG, "无法读取图片尺寸: " + path);
			return null;
		}
		int sample = 1;
		while (sample < 64 && (w / sample > maxSide || h / sample > maxSide)) {
			sample <<= 1;
		}
		opts.inJustDecodeBounds = false;
		opts.inSampleSize = sample;
		opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
		try {
			return BitmapFactory.decodeFile(path, opts);
		} catch (OutOfMemoryError e) {
			NokiaLog.w(TAG, "解码图片内存不足，降采样重试 sample=" + sample);
			opts.inSampleSize = sample * 2;
			try {
				return BitmapFactory.decodeFile(path, opts);
			} catch (OutOfMemoryError e2) {
				NokiaLog.e(TAG, "降采样后仍内存不足，放弃导入");
				return null;
			}
		}
	}

	private static Bitmap rotate(Bitmap src, int degrees) {
		if (src == null || degrees == 0) {
			return src;
		}
		Matrix m = new Matrix();
		m.postRotate(degrees);
		try {
			Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
			if (out != src) {
				src.recycle();
			}
			return out;
		} catch (OutOfMemoryError e) {
			NokiaLog.w(TAG, "旋转壁纸图片失败（内存不足），沿用原图方向");
			return src;
		}
	}

	/** 把长边压到 maxSide 以内（短边等比），避免存储超大位图。 */
	private static Bitmap scaleDown(Bitmap src, int maxSide) {
		if (src == null) {
			return null;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		int longest = Math.max(w, h);
		if (longest <= maxSide) {
			return src;
		}
		float s = maxSide / (float) longest;
		int nw = Math.max(1, Math.round(w * s));
		int nh = Math.max(1, Math.round(h * s));
		try {
			Bitmap out = Bitmap.createScaledBitmap(src, nw, nh, true);
			if (out != src) {
				src.recycle();
			}
			return out;
		} catch (OutOfMemoryError e) {
			NokiaLog.w(TAG, "缩放壁纸图片失败（内存不足），沿用原尺寸");
			return src;
		}
	}

	private static boolean save(Context ctx, Bitmap bmp) {
		File f = getWallpaperFile(ctx);
		File parent = f.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		OutputStream out = null;
		try {
			out = new FileOutputStream(f);
			boolean ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
			out.flush();
			NokiaLog.i(TAG, "壁纸已保存: " + f.getAbsolutePath() + " ("
					+ f.length() / 1024 + "KB)");
			return ok;
		} catch (Exception e) {
			NokiaLog.e(TAG, "保存壁纸失败", e);
			return false;
		} finally {
			closeQuietly(out);
		}
	}

	private static void closeQuietly(Closeable c) {
		if (c == null) {
			return;
		}
		try {
			c.close();
		} catch (Exception ignored) {
		}
	}

	/**
	 * 按缩放模式把位图绘制到全屏背景上。
	 * 不用 {@code BitmapDrawable} 的 gravity：{@code Gravity.apply} 对 CENTER 只做居中不缩放，
	 * 无法表达「居中裁剪」，因此自行按 bounds 计算目标矩形。
	 */
	static final class WallpaperDrawable extends Drawable {

		private final Bitmap bitmap;
		private final int mode;
		private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
		private final Rect bounds = new Rect();
		private final Rect dst = new Rect();

		WallpaperDrawable(@NonNull Bitmap bitmap, int mode) {
			this.bitmap = bitmap;
			this.mode = mode;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			if (bitmap.isRecycled()) {
				return;
			}
			copyBounds(bounds);
			int vw = bounds.width();
			int vh = bounds.height();
			int bw = bitmap.getWidth();
			int bh = bitmap.getHeight();
			if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) {
				return;
			}

			// 拉伸铺满：忽略宽高比，直接填满
			if (mode == NokiaSettingsStorage.WALLPAPER_SCALE_STRETCH) {
				canvas.drawBitmap(bitmap, null, bounds, paint);
				return;
			}

			// 适应屏幕取较小比例（完整显示，留边）；居中裁剪取较大比例（铺满，裁掉溢出）
			float s = (mode == NokiaSettingsStorage.WALLPAPER_SCALE_FIT)
					? Math.min(vw / (float) bw, vh / (float) bh)
					: Math.max(vw / (float) bw, vh / (float) bh);
			int dw = Math.round(bw * s);
			int dh = Math.round(bh * s);
			int left = bounds.left + (vw - dw) / 2;
			int top = bounds.top + (vh - dh) / 2;
			dst.set(left, top, left + dw, top + dh);

			if (mode == NokiaSettingsStorage.WALLPAPER_SCALE_FIT) {
				canvas.drawBitmap(bitmap, null, dst, paint);
			} else {
				// 居中裁剪：目标矩形比可视区大，需裁剪掉溢出部分
				int save = canvas.save();
				canvas.clipRect(bounds);
				canvas.drawBitmap(bitmap, null, dst, paint);
				canvas.restoreToCount(save);
			}
		}

		@Override
		public void setAlpha(int alpha) {
			paint.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			paint.setColorFilter(colorFilter);
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}

		@Override
		public int getIntrinsicWidth() {
			return bitmap.isRecycled() ? -1 : bitmap.getWidth();
		}

		@Override
		public int getIntrinsicHeight() {
			return bitmap.isRecycled() ? -1 : bitmap.getHeight();
		}
	}
}
