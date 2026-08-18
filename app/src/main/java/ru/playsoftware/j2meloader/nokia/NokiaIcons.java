package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 诺基亚桌面 Google Material Icons 字体图标库工具类。
 * <p>
 * 内置 2500+ 个矢量图标，单文件 .ttf 驱动，多分辨率 1:1 无损自适应，
 * 支持任意颜色、透明度与尺寸动态渲染。
 */
public final class NokiaIcons {

	private static final String FONT_PATH = "fonts/MaterialIcons-Regular.ttf";
	private static volatile Typeface sTypeface;

	// ---- 常用图标 Unicode 编码常量 ----

	// 桌面小组件
	public static final String ICON_MEMORY = "\ue322";        // memory (RAM/芯片)
	public static final String ICON_STORAGE = "\ue1db";       // sd_storage (存储卡)
	public static final String ICON_USAGE = "\ue8b5";         // schedule (时钟/使用时长)
	public static final String ICON_LOCK = "\ue897";          // lock (锁屏)
	public static final String ICON_BG_MANAGER = "\ue53b";    // layers (后台管理/多任务)
	public static final String ICON_IP = "\ue894";            // language (地球/IP/网络)
	public static final String ICON_CALENDAR = "\ue935";      // calendar_today (日历)
	public static final String ICON_ACTIVITY = "\ue879";      // extension (Activity快捷)
	public static final String ICON_URL = "\ue051";           // link (网址)
	public static final String ICON_QS_TILE = "\uea3b";       // bolt (快捷开关/磁贴)
	public static final String ICON_FREEZE = "\ueb3b";        // ac_unit (雪花/一键冻结)
	public static final String ICON_APP = "\ue5c3";           // apps (应用网格)

	// 桌面设置分类与菜单项
	public static final String ICON_DISPLAY = "\ue3a5";       // brightness_medium / tv (外观与显示)
	public static final String ICON_KEYPAD = "\ue312";        // keyboard / keypad (按键与操作)
	public static final String ICON_DESKTOP = "\ue871";       // dashboard / widgets (桌面内容)
	public static final String ICON_SYSTEM = "\ue8b8";        // settings / system (系统与权限)
	public static final String ICON_ADVANCED = "\ue869";      // build / tune (高级设置)
	public static final String ICON_FONT = "\ue165";          // format_size (字体大小)
	public static final String ICON_WALLPAPER = "\ue3f4";     // image / wallpaper (壁纸设置)
	public static final String ICON_SHORTCUTS = "\ue8f9";     // view_headline (顶部快捷栏设置)
	public static final String ICON_WIDGETS = "\ue871";       // widgets (桌面组件设置)
	public static final String ICON_TOGGLES = "\uea3b";       // bolt / toggle (快捷开关)
	public static final String ICON_LOG = "\ue873";           // description / log (日志记录)
	public static final String ICON_HOME = "\ue88a";          // home (默认桌面设置)
	public static final String ICON_POWER = "\ue8ac";         // power_settings_new (电源键)
	public static final String ICON_TERMINAL = "\ue869";      // terminal / code (mini_shizuku)
	public static final String ICON_PLAY = "\ue037";          // play_arrow (启动/运行)
	public static final String ICON_SORT = "\ue8d2";          // swap_vert / sort (排序)
	public static final String ICON_SHIELD = "\ue8e8";        // verified_user / shield (保护)

	// 快捷开关栏 (Quick Toggles)
	public static final String TOGGLE_WIFI = "\ue63e";        // wifi
	public static final String TOGGLE_BLUETOOTH = "\ue1a7";   // bluetooth
	public static final String TOGGLE_HOTSPOT = "\ue1da";     // wifi_tethering
	public static final String TOGGLE_TORCH = "\uef56";       // flashlight_on
	public static final String TOGGLE_AIRPLANE = "\ue539";    // flight / airplanemode_active
	public static final String TOGGLE_ROTATE = "\ue84d";      // screen_rotation
	public static final String TOGGLE_BRIGHTNESS = "\ue3a6";  // brightness_6
	public static final String TOGGLE_LOCATION = "\ue0c8";    // location_on
	public static final String TOGGLE_SOUND = "\ue050";       // volume_up
	public static final String TOGGLE_DATA = "\ue1e2";        // swap_vert / data_usage
	public static final String TOGGLE_SAVER = "\ue1a4";       // battery_saver
	public static final String TOGGLE_LOCK = "\ue897";        // lock

	// 常用系统/交互图标
	public static final String ICON_CHECK = "\ue5ca";         // check (对勾)
	public static final String ICON_CLOSE = "\ue5cd";         // close (叉)
	public static final String ICON_SETTINGS = "\ue8b8";      // settings (齿轮)
	public static final String ICON_FOLDER = "\ue2c7";        // folder (文件夹)
	public static final String ICON_ARROW_FORWARD = "\ue5c8"; // arrow_forward (向右箭头)
	public static final String ICON_REFRESH = "\ue5d5";       // refresh (刷新)
	public static final String ICON_DELETE = "\ue872";        // delete (垃圾桶)
	public static final String ICON_EDIT = "\ue3c9";          // edit (铅笔)
	public static final String ICON_ADD = "\ue145";           // add (加号)
	public static final String ICON_INFO = "\ue88e";          // info (信息)
	public static final String ICON_SHIZUKU = "\ue869";       // terminal / code (mini_shizuku)
	public static final String ICON_CLEAR_ALL = "\ue0b8";     // clear_all (清除全部)
	public static final String ICON_LOCK_OPEN = "\ue898";     // lock_open (解锁)
	public static final String ICON_POWER_OFF = "\ue8ac";     // power_settings_new (关闭/电源)
	public static final String ICON_POWER_ON = "\ue8ac";      // power_settings_new (开启/电源)
	public static final String ICON_CHECK_BOX = "\ue834";      // check_box (已选复选框)
	public static final String ICON_CHECK_BOX_OUTLINE_BLANK = "\ue835"; // check_box_outline_blank (未选复选框)
	public static final String ICON_RESTORE = "\ue8b3";        // restore / settings_backup_restore (恢复默认)

	private NokiaIcons() {}

	/**
	 * 获取 Material Icons 字体（懒加载单例）。
	 */
	@NonNull
	public static Typeface getTypeface(@NonNull Context context) {
		if (sTypeface == null) {
			synchronized (NokiaIcons.class) {
				if (sTypeface == null) {
					try {
						sTypeface = Typeface.createFromAsset(context.getAssets(), FONT_PATH);
					} catch (Exception e) {
						NokiaLog.e("NokiaIcons", "加载 MaterialIcons 字体失败，回退系统默认", e);
						sTypeface = Typeface.DEFAULT;
					}
				}
			}
		}
		return sTypeface;
	}

	/**
	 * 创建指定 Unicode、颜色和尺寸 (dp) 的矢量字体 Drawable。
	 */
	@NonNull
	public static Drawable get(@NonNull Context context, @NonNull String unicode, int color, int sizeDp) {
		int sizePx = NokiaDimens.dp(context.getResources(), sizeDp);
		return new IconDrawable(getTypeface(context), unicode, color, sizePx);
	}

	/**
	 * 创建默认 24dp 尺寸的矢量字体 Drawable。
	 */
	@NonNull
	public static Drawable get(@NonNull Context context, @NonNull String unicode, int color) {
		return get(context, unicode, color, 24);
	}

	/**
	 * 纯矢量字体图标 Drawable 实现，支持 1:1 像素光栅化、任意染色与 Bounds 计算。
	 */
	public static class IconDrawable extends Drawable {
		private final Typeface typeface;
		private final String text;
		private final TextPaint paint;
		private final int sizePx;
		private final Rect textBounds = new Rect();

		public IconDrawable(@NonNull Typeface typeface, @NonNull String text, int color, int sizePx) {
			this.typeface = typeface;
			this.text = text;
			this.sizePx = sizePx;

			this.paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
			this.paint.setTypeface(typeface);
			this.paint.setTextAlign(Paint.Align.CENTER);
			this.paint.setColor(color);
			this.paint.setTextSize(sizePx);
			setBounds(0, 0, sizePx, sizePx);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect bounds = getBounds();
			if (bounds.isEmpty()) return;

			paint.setTextSize(Math.min(bounds.width(), bounds.height()));
			paint.getTextBounds(text, 0, text.length(), textBounds);

			float x = bounds.exactCenterX();
			// 垂直居中基线计算
			float y = bounds.exactCenterY() - (paint.descent() + paint.ascent()) / 2f;
			canvas.drawText(text, x, y, paint);
		}

		@Override
		public void setAlpha(int alpha) {
			paint.setAlpha(alpha);
			invalidateSelf();
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			paint.setColorFilter(colorFilter);
			invalidateSelf();
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}

		@Override
		public int getIntrinsicWidth() {
			return sizePx;
		}

		@Override
		public int getIntrinsicHeight() {
			return sizePx;
		}

		public void setColor(int color) {
			paint.setColor(color);
			invalidateSelf();
		}
	}
}
