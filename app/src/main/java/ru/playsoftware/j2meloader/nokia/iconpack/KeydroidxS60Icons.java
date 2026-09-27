package ru.playsoftware.j2meloader.nokia.iconpack;

import androidx.annotation.DrawableRes;

import java.util.HashMap;
import java.util.Map;

import ru.playsoftware.j2meloader.R;

/**
 * 内置 S60 图标资源表：图标名 → {@code R.drawable.s60_*}。
 *
 * <p><b>为什么用显式数组而不是 {@code getIdentifier()} 反射</b>：
 * release 构建开启资源收缩（shrinkResources）后，只通过字符串名反射引用的资源会被判定为
 * 「未使用」而删除；R8 也可能重命名/剥离相关引用。显式 {@code int[]} 引用能保证 48 个
 * s60_* 位图始终打进包内，同时反查是纯内存 HashMap，命中 O(1)。</p>
 *
 * <p>图标名与 {@code assets/s60/appfilter.xml}、{@code assets/s60/drawable.xml} 中的
 * {@code drawable="s60_xxx"} 完全一致（不含 {@code s60_} 前缀的名字也兼容，
 * 便于与外部 ADW 图标包同名图标对齐）。</p>
 */
public final class KeydroidxS60Icons {

	/** 资源 ID 表（顺序与 {@link #NAMES} 严格一一对应，禁止单独增删） */
	@DrawableRes
	private static final int[] IDS = {
			R.drawable.s60_app,
			R.drawable.s60_app_alt,
			R.drawable.s60_app_logo,
			R.drawable.s60_books,
			R.drawable.s60_browser,
			R.drawable.s60_browser_alt,
			R.drawable.s60_calculator,
			R.drawable.s60_calculator_alt,
			R.drawable.s60_calendar,
			R.drawable.s60_call_log,
			R.drawable.s60_camera,
			R.drawable.s60_camera_alt,
			R.drawable.s60_clock,
			R.drawable.s60_clock_bg,
			R.drawable.s60_clock_hour_hand,
			R.drawable.s60_clock_minute_hand,
			R.drawable.s60_com_android_documentsui,
			R.drawable.s60_contacts,
			R.drawable.s60_dictionary,
			R.drawable.s60_downloads,
			R.drawable.s60_email,
			R.drawable.s60_files,
			R.drawable.s60_files_alt,
			R.drawable.s60_fm_radio,
			R.drawable.s60_gallery,
			R.drawable.s60_gmail,
			R.drawable.s60_google_plus,
			R.drawable.s60_icon_mask,
			R.drawable.s60_mms,
			R.drawable.s60_music,
			R.drawable.s60_navigator,
			R.drawable.s60_notepad,
			R.drawable.s60_remote_control,
			R.drawable.s60_sdcard,
			R.drawable.s60_search,
			R.drawable.s60_settings,
			R.drawable.s60_settings_alt,
			R.drawable.s60_sim,
			R.drawable.s60_skype,
			R.drawable.s60_sound_recorder,
			R.drawable.s60_sync,
			R.drawable.s60_themes,
			R.drawable.s60_video_player,
			R.drawable.s60_viber,
			R.drawable.s60_voice_dialer,
			R.drawable.s60_weather,
			R.drawable.s60_whatsapp,
			R.drawable.s60_youtube,
	};

	/** 图标名表（= 资源名，如 {@code s60_settings}） */
	private static final String[] NAMES = {
			"s60_app",
			"s60_app_alt",
			"s60_app_logo",
			"s60_books",
			"s60_browser",
			"s60_browser_alt",
			"s60_calculator",
			"s60_calculator_alt",
			"s60_calendar",
			"s60_call_log",
			"s60_camera",
			"s60_camera_alt",
			"s60_clock",
			"s60_clock_bg",
			"s60_clock_hour_hand",
			"s60_clock_minute_hand",
			"s60_com_android_documentsui",
			"s60_contacts",
			"s60_dictionary",
			"s60_downloads",
			"s60_email",
			"s60_files",
			"s60_files_alt",
			"s60_fm_radio",
			"s60_gallery",
			"s60_gmail",
			"s60_google_plus",
			"s60_icon_mask",
			"s60_mms",
			"s60_music",
			"s60_navigator",
			"s60_notepad",
			"s60_remote_control",
			"s60_sdcard",
			"s60_search",
			"s60_settings",
			"s60_settings_alt",
			"s60_sim",
			"s60_skype",
			"s60_sound_recorder",
			"s60_sync",
			"s60_themes",
			"s60_video_player",
			"s60_viber",
			"s60_voice_dialer",
			"s60_weather",
			"s60_whatsapp",
			"s60_youtube",
	};

	/** 中文标签（图标挑选页展示用，顺序与 {@link #NAMES} 一一对应） */
	private static final String[] LABELS = {
			"应用",
			"应用（风格2）",
			"应用标志",
			"阅读",
			"浏览器",
			"浏览器（风格2）",
			"计算器",
			"计算器（风格2）",
			"日历",
			"通话记录",
			"相机",
			"相机（风格2）",
			"时钟",
			"时钟表盘",
			"时钟指针-时",
			"时钟指针-分",
			"文件管理",
			"名片夹",
			"词典",
			"下载",
			"电子邮件",
			"文件",
			"文件（风格2）",
			"收音机",
			"多媒体",
			"Gmail",
			"Google+",
			"图标遮罩",
			"信息",
			"音乐",
			"导航",
			"记事本",
			"遥控",
			"存储卡",
			"搜索",
			"设置",
			"设置（风格2）",
			"SIM 卡",
			"Skype",
			"录音",
			"同步",
			"主题",
			"视频",
			"Viber",
			"语音拨号",
			"天气",
			"WhatsApp",
			"YouTube",
	};

	/** 名称 → 资源 ID（类加载时构建一次，命中 O(1)） */
	private static final Map<String, Integer> NAME_TO_ID = new HashMap<>(IDS.length * 2);

	static {
		for (int i = 0; i < IDS.length; i++) {
			NAME_TO_ID.put(NAMES[i], IDS[i]);
			// 兼容不带 s60_ 前缀的写法（与外部 ADW 图标包同名，便于映射表合并）
			if (NAMES[i].startsWith("s60_")) {
				NAME_TO_ID.put(NAMES[i].substring(4), IDS[i]);
			}
		}
	}

	private KeydroidxS60Icons() {
	}

	/** 图标名 → 资源 ID；未知名称返回 0（调用方需按「取图失败」处理） */
	@DrawableRes
	public static int idOf(String iconName) {
		if (iconName == null) return 0;
		Integer id = NAME_TO_ID.get(iconName);
		return id != null ? id : 0;
	}

	/** 图标名是否存在（含不带前缀的兼容写法） */
	public static boolean has(String iconName) {
		return idOf(iconName) != 0;
	}

	/** 图标名 → 中文标签；未知返回原名（挑不到名字时回落，避免列表出现空白项） */
	public static String labelOf(String iconName) {
		if (iconName == null) return "";
		for (int i = 0; i < NAMES.length; i++) {
			if (NAMES[i].equals(iconName) || NAMES[i].substring(4).equals(iconName)) {
				return LABELS[i];
			}
		}
		return iconName;
	}

	/** 内置图标总数（单测/日志用） */
	public static int count() {
		return NAMES.length;
	}
}
