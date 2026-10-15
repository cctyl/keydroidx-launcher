package ru.playsoftware.j2meloader.nokia.utils;

import com.nlf.calendar.Lunar;

import java.util.Calendar;
import java.util.Date;

import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * 农历文本转换工具（桌面时钟区第二行「星期二 · 农历八月初六」）。
 * <p>
 * 底层为 GitHub 成熟库 {@code cn.6tail:lunar}（6tail/lunar-java，MIT、零第三方依赖、
 * 官方声明支持 JDK 1.5+）。1.7.7 已扫描字节码确认未引用 API 19 以上才存在的
 * java.* API（无 java.time/stream/Optional 等），无需 desugaring；无反射，
 * 无需 proguard keep 规则。输出已用锚点数据验证：2026-10-06 → 农历八月廿六，
 * 闰月自动带「闰」前缀（如「农历闰六月初一」），覆盖范围 1900~2100。
 * <p>
 * 失败路径一律降级：农历转换异常时返回空串，调用方降级为只显示星期，
 * 不影响桌面其余内容。
 */
public final class KeydroidxLunarCalendar {

	private static final String TAG = "Lunar";

	private static final String[] WEEK_DAYS = {
			"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"
	};

	private KeydroidxLunarCalendar() {
	}

	/**
	 * 桌面时钟区第二行完整文本：「星期二 · 农历八月初六」。
	 * 农历转换失败时只返回「星期X」；星期计算也失败时返回空串（调用方隐藏该行）。
	 */
	public static String toWeekLunarText(Date date) {
		String week = getWeekText(date);
		if (week.isEmpty()) {
			return "";
		}
		String lunar = toLunarText(date);
		if (lunar.isEmpty()) {
			return week;
		}
		return week + " · " + lunar;
	}

	/** 「星期二」样式的星期文本；date 为 null 或计算异常时返回空串。 */
	public static String getWeekText(Date date) {
		if (date == null) {
			return "";
		}
		try {
			Calendar cal = Calendar.getInstance();
			cal.setTime(date);
			return WEEK_DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1];
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "星期计算失败: " + e.getMessage());
			return "";
		}
	}

	/**
	 * 「农历八月初六」样式的农历文本（闰月自动带「闰」，如「农历闰六月初一」；
	 * 十一月/十二月按农历别称输出「冬月」「腊月」）。
	 * 超出库覆盖范围（1900~2100）或转换异常时返回空串。
	 */
	public static String toLunarText(Date date) {
		if (date == null) {
			return "";
		}
		try {
			Lunar lunar = Lunar.fromDate(date);
			return "农历" + lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "农历转换失败: " + e.getMessage());
			return "";
		}
	}
}
