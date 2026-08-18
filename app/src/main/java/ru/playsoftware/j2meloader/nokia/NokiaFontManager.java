package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 诺基亚桌面字体中枢管理器。
 * 负责全局字体（内置方舟像素体、自定义外部导入字体、系统默认字体）的加载、缓存与分发。
 */
public class NokiaFontManager {

	private static final String TAG = "NokiaFontManager";

	public static final String FONT_ID_SYSTEM = "system_default";
	public static final String FONT_ID_ARK_12PX = "ark_pixel_12px";
	public static final String FONT_ID_ARK_16PX = "ark_pixel_16px";
	public static final String FONT_ID_CUSTOM_PREFIX = "custom_";

	private static final Map<String, Typeface> sTypefaceCache = new HashMap<>();
	private static String sCurrentFontId = null;
	private static Typeface sCurrentTypeface = null;

	public static class FontItem {
		public final String id;
		public final String name;
		public final String description;
		public final boolean isCustom;

		public FontItem(String id, String name, String description, boolean isCustom) {
			this.id = id;
			this.name = name;
			this.description = description;
			this.isCustom = isCustom;
		}
	}

	/**
	 * 清除内存缓存，触发重新获取。
	 */
	public static void invalidate() {
		sCurrentFontId = null;
		sCurrentTypeface = null;
		sTypefaceCache.clear();
	}

	/**
	 * 获取当前全局生效的 Typeface（null 代表系统默认）。
	 */
	public static Typeface getGlobalTypeface(Context context) {
		if (context == null) return null;
		String fontId = NokiaSettingsStorage.getFontId(context);
		if (sCurrentFontId != null && sCurrentFontId.equals(fontId) && sCurrentTypeface != null) {
			return sCurrentTypeface;
		}

		sCurrentFontId = fontId;
		sCurrentTypeface = loadTypeface(context, fontId);
		return sCurrentTypeface;
	}

	/**
	 * 加载指定 ID 的字体。
	 */
	public static Typeface loadTypeface(Context context, String fontId) {
		if (fontId == null || FONT_ID_SYSTEM.equals(fontId)) {
			return null;
		}

		if (sTypefaceCache.containsKey(fontId)) {
			return sTypefaceCache.get(fontId);
		}

		Typeface tf = null;
		try {
			if (FONT_ID_ARK_12PX.equals(fontId)) {
				tf = Typeface.createFromAsset(context.getAssets(), "fonts/ArkPixel-12px.ttf");
			} else if (FONT_ID_ARK_16PX.equals(fontId)) {
				tf = Typeface.createFromAsset(context.getAssets(), "fonts/ArkPixel-16px.ttf");
			} else if (fontId.startsWith(FONT_ID_CUSTOM_PREFIX)) {
				File fontDir = new File(context.getFilesDir(), "fonts");
				File fontFile = new File(fontDir, fontId.substring(FONT_ID_CUSTOM_PREFIX.length()));
				if (fontFile.exists() && fontFile.canRead()) {
					tf = Typeface.createFromFile(fontFile);
				}
			}
		} catch (Throwable t) {
			NokiaLog.e(TAG, "加载字体失败: " + fontId, t);
		}

		if (tf != null) {
			sTypefaceCache.put(fontId, tf);
		}
		return tf;
	}

	/**
	 * 获取可用字体列表（内置 + 自定义）。
	 */
	public static List<FontItem> getAvailableFonts(Context context) {
		List<FontItem> list = new ArrayList<>();
		list.add(new FontItem(FONT_ID_ARK_12PX, "方舟像素体 (12px 推荐)", "复刻诺基亚 S40 经典 12 点阵，紧凑精致", false));
		list.add(new FontItem(FONT_ID_ARK_16PX, "方舟像素体 (16px 大字)", "适合中高分辨率或偏好更大字号的用户", false));
		list.add(new FontItem(FONT_ID_SYSTEM, "系统默认字体", "系统原生无衬线字体 (Roboto / 默认)", false));

		// 扫描自定义字体目录
		File fontDir = new File(context.getFilesDir(), "fonts");
		if (fontDir.exists() && fontDir.isDirectory()) {
			File[] files = fontDir.listFiles();
			if (files != null) {
				for (File f : files) {
					if (f.isFile() && (f.getName().endsWith(".ttf") || f.getName().endsWith(".otf"))) {
						list.add(new FontItem(FONT_ID_CUSTOM_PREFIX + f.getName(), f.getName(), "自定义导入字体文件 (" + (f.length() / 1024) + " KB)", true));
					}
				}
			}
		}
		return list;
	}

	/**
	 * 从 Uri 导入外部字体文件并存入应用私有目录。
	 * @return 导入后的 fontId，若失败返回 null
	 */
	public static String importFontFromUri(Context context, Uri uri) {
		if (context == null || uri == null) return null;
		try {
			String fileName = null;
			Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
			if (cursor != null) {
				try {
					if (cursor.moveToFirst()) {
						int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
						if (nameIndex >= 0) {
							fileName = cursor.getString(nameIndex);
						}
					}
				} finally {
					cursor.close();
				}
			}

			if (fileName == null || (!fileName.toLowerCase().endsWith(".ttf") && !fileName.toLowerCase().endsWith(".otf"))) {
				fileName = "imported_font_" + System.currentTimeMillis() + ".ttf";
			}

			File fontDir = new File(context.getFilesDir(), "fonts");
			if (!fontDir.exists()) {
				fontDir.mkdirs();
			}

			File destFile = new File(fontDir, fileName);
			try (InputStream in = context.getContentResolver().openInputStream(uri);
			     OutputStream out = new FileOutputStream(destFile)) {
				if (in == null) return null;
				byte[] buffer = new byte[8192];
				int read;
				while ((read = in.read(buffer)) != -1) {
					out.write(buffer, 0, read);
				}
				out.flush();
			}

			// 尝试解析，测试字体是否有效
			try {
				Typeface tf = Typeface.createFromFile(destFile);
				if (tf != null) {
					String fontId = FONT_ID_CUSTOM_PREFIX + fileName;
					sTypefaceCache.put(fontId, tf);
					return fontId;
				}
			} catch (Exception ex) {
				destFile.delete();
				NokiaLog.e(TAG, "导入字体文件解析损坏", ex);
			}
		} catch (Throwable t) {
			NokiaLog.e(TAG, "从 Uri 复制字体文件失败", t);
		}
		return null;
	}

	/**
	 * 递归遍历给整个 View 树应用全局像素字体。
	 * 特别保护：如果 TextView 已经应用了 Material Icons 矢量图标库，则不覆盖图标！
	 */
	public static void applyFontToViewHierarchy(View view) {
		if (view == null) return;
		Typeface globalTf = getGlobalTypeface(view.getContext());
		applyFontInternal(view, globalTf);
	}

	private static void applyFontInternal(View view, Typeface globalTf) {
		if (view instanceof TextView) {
			TextView tv = (TextView) view;
			Typeface currentTf = tv.getTypeface();
			Typeface iconTf = NokiaIcons.getTypeface(view.getContext());
			// 保护图标：绝不覆盖 Material Icons
			if (currentTf != null && currentTf == iconTf) {
				return;
			}
			if (globalTf != null) {
				tv.setTypeface(globalTf);
				// 针对像素字体优化：关闭次像素与抗锯齿毛边，使点阵更加清晰硬朗
				tv.setPaintFlags(tv.getPaintFlags() & ~Paint.SUBPIXEL_TEXT_FLAG);
			} else {
				tv.setTypeface(Typeface.DEFAULT);
			}
		} else if (view instanceof ViewGroup) {
			ViewGroup vg = (ViewGroup) view;
			int childCount = vg.getChildCount();
			for (int i = 0; i < childCount; i++) {
				applyFontInternal(vg.getChildAt(i), globalTf);
			}
		}
	}
}
