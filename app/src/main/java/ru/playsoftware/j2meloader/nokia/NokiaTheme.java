package ru.playsoftware.j2meloader.nokia;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.util.ArrayList;
import java.util.List;

/**
 * 诺基亚全套主题配色系统。
 * 管理桌面壁纸背景、功能表背景、软键栏底色、高亮选中态、文字主色等。
 */
public class NokiaTheme {

	public static class ThemeDef {
		public final String id;
		public final String name;
		public final int primaryColor;       // 主题主色（如高亮、焦点色 #2196F3）
		public final int softKeyStartColor;  // 软键栏渐变顶色
		public final int softKeyEndColor;    // 软键栏渐变底色
		public final int bgStartColor;       // 页面壁纸渐变顶色
		public final int bgCenterColor;      // 页面壁纸渐变中心色
		public final int bgEndColor;         // 页面壁纸渐变底色
		public final int focusColor;         // 列表选中态高亮半透明色

		public ThemeDef(String id, String name, int primaryColor,
		                int softKeyStartColor, int softKeyEndColor,
		                int bgStartColor, int bgCenterColor, int bgEndColor,
		                int focusColor) {
			this.id = id;
			this.name = name;
			this.primaryColor = primaryColor;
			this.softKeyStartColor = softKeyStartColor;
			this.softKeyEndColor = softKeyEndColor;
			this.bgStartColor = bgStartColor;
			this.bgCenterColor = bgCenterColor;
			this.bgEndColor = bgEndColor;
			this.focusColor = focusColor;
		}
	}

	public static final String THEME_CLASSIC_BLUE = "classic_blue";
	public static final String THEME_OBSIDIAN_BLACK = "obsidian_black";
	public static final String THEME_CYAN_SEA = "cyan_sea";
	public static final String THEME_EMERALD_GREEN = "emerald_green";
	public static final String THEME_WINE_PURPLE = "wine_purple";
	public static final String THEME_AMBER_GOLD = "amber_gold";

	private static final List<ThemeDef> THEMES = new ArrayList<>();

	static {
		// 1. 经典深蓝 (S40/S60 纯正血统)
		THEMES.add(new ThemeDef(
				THEME_CLASSIC_BLUE, "经典深蓝",
				0xFF64B5F6,
				0xFF1A3A6B, 0xFF0D1B3E,
				0xFF0D1B3E, 0xFF1A3A6B, 0xFF0D1B3E,
				0x662196F3
		));
		// 2. 曜石纯黑 (极致省电与暗黑微光)
		THEMES.add(new ThemeDef(
				THEME_OBSIDIAN_BLACK, "曜石纯黑",
				0xFFB0BEC5,
				0xFF212121, 0xFF000000,
				0xFF0A0A0A, 0xFF1C1C1C, 0xFF050505,
				0x6678909C
		));
		// 3. 青海浩渺 (科技青蓝)
		THEMES.add(new ThemeDef(
				THEME_CYAN_SEA, "青海浩渺",
				0xFF4DD0E1,
				0xFF0B3D4F, 0xFF051C24,
				0xFF051C24, 0xFF0B3D4F, 0xFF051C24,
				0x6600BCD4
		));
		// 4. 翡翠幽绿 (复古墨绿)
		THEMES.add(new ThemeDef(
				THEME_EMERALD_GREEN, "翡翠幽绿",
				0xFF81C784,
				0xFF144324, 0xFF0A1F11,
				0xFF0A1F11, 0xFF144324, 0xFF0A1F11,
				0x664CAF50
		));
		// 5. 典雅酒红 (深邃浆果红)
		THEMES.add(new ThemeDef(
				THEME_WINE_PURPLE, "典雅酒红",
				0xFFBA68C8,
				0xFF4A153B, 0xFF21081A,
				0xFF21081A, 0xFF4A153B, 0xFF21081A,
				0x669C27B0
		));
		// 6. 琥珀暖金 (沉稳尊贵金棕)
		THEMES.add(new ThemeDef(
				THEME_AMBER_GOLD, "琥珀暖金",
				0xFFFFB74D,
				0xFF4A2D14, 0xFF241408,
				0xFF241408, 0xFF4A2D14, 0xFF241408,
				0x66FF9800
		));
	}

	public static List<ThemeDef> getThemes() {
		return THEMES;
	}

	public static ThemeDef getTheme(String id) {
		for (ThemeDef t : THEMES) {
			if (t.id.equals(id)) return t;
		}
		return THEMES.get(0);
	}

	/** 创建当前主题背景壁纸 Drawable */
	public static GradientDrawable createBackgroundDrawable(ThemeDef theme) {
		GradientDrawable gd = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{theme.bgStartColor, theme.bgCenterColor, theme.bgEndColor}
		);
		gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
		return gd;
	}

	/** 创建当前主题软键栏背景 Drawable */
	public static GradientDrawable createSoftKeyDrawable(ThemeDef theme) {
		GradientDrawable gd = new GradientDrawable(
				GradientDrawable.Orientation.TOP_BOTTOM,
				new int[]{theme.softKeyStartColor, theme.softKeyEndColor}
		);
		gd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
		return gd;
	}

	/** 创建当前主题选中高亮背景 Drawable */
	public static GradientDrawable createFocusDrawable(ThemeDef theme, float cornerRadiusPx) {
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(theme.focusColor);
		gd.setCornerRadius(cornerRadiusPx);
		return gd;
	}
}
