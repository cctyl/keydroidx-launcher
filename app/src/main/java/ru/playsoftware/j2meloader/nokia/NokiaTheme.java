package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import java.util.ArrayList;
import java.util.List;

public class NokiaTheme {

	public static final String THEME_CLASSIC_BLUE = "classic_blue";
	public static final String THEME_OBSIDIAN_BLACK = "obsidian_black";
	public static final String THEME_CYAN_SEA = "cyan_sea";
	public static final String THEME_EMERALD_GREEN = "emerald_green";
	public static final String THEME_WINE_PURPLE = "wine_purple";
	public static final String THEME_AMBER_GOLD = "amber_gold";

	public static class ThemeDef {
		public final String id;
		public final String name;
		public final int accentColor;
		public final int softKeyStartColor;
		public final int softKeyEndColor;
		public final int bgStartColor;
		public final int bgCenterColor;
		public final int bgEndColor;
		public final int focusColor;

		public ThemeDef(String id, String name, int accentColor,
						int softKeyStartColor, int softKeyEndColor,
						int bgStartColor, int bgCenterColor, int bgEndColor,
						int focusColor) {
			this.id = id;
			this.name = name;
			this.accentColor = accentColor;
			this.softKeyStartColor = softKeyStartColor;
			this.softKeyEndColor = softKeyEndColor;
			this.bgStartColor = bgStartColor;
			this.bgCenterColor = bgCenterColor;
			this.bgEndColor = bgEndColor;
			this.focusColor = focusColor;
		}
	}

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

	/** 获取当前系统选择的主题定义 */
	public static ThemeDef getSelectedTheme(Context context) {
		NokiaSettingsStorage storage = new NokiaSettingsStorage(context);
		return storage.getTheme();
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

	/** 创建带有当前主题选中色的圆角 Drawable（替代静态 bg_nokia_selected_dark） */
	public static GradientDrawable createSelectionDrawable(Context context, float radiusDp) {
		ThemeDef theme = getSelectedTheme(context);
		GradientDrawable gd = new GradientDrawable();
		gd.setShape(GradientDrawable.RECTANGLE);
		gd.setColor(theme.focusColor);
		int px = NokiaDimens.dp(context.getResources(), (int) radiusDp);
		gd.setCornerRadius(px);
		return gd;
	}
}
