package ru.playsoftware.j2meloader.nokia.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 农历工具锚点测试（数据先经独立 JVM 探针对 lunar-1.7.7 验证过）。
 * 注意：仅覆盖成功路径——失败路径走 KeydroidxLog → android.util.Log，
 * JVM 单元测试环境未配置 returnDefaultValues 会被 not-mocked 拦截。
 */
public class KeydroidxLunarCalendarTest {

	private Date parse(String ymd) throws Exception {
		return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ymd);
	}

	@Test
	public void lunarText_matchesKnownAnchorDates() throws Exception {
		assertEquals("农历八月廿六", KeydroidxLunarCalendar.toLunarText(parse("2026-10-06")));
		assertEquals("农历正月初一", KeydroidxLunarCalendar.toLunarText(parse("2026-02-17")));
		assertEquals("农历闰六月初一", KeydroidxLunarCalendar.toLunarText(parse("2025-07-25")));
		assertEquals("农历腊月初一", KeydroidxLunarCalendar.toLunarText(parse("2100-12-31")));
	}

	@Test
	public void weekLunarText_combinesWeekAndLunar() throws Exception {
		assertEquals("星期二 · 农历八月廿六",
				KeydroidxLunarCalendar.toWeekLunarText(parse("2026-10-06")));
		assertEquals("星期五 · 农历闰六月初一",
				KeydroidxLunarCalendar.toWeekLunarText(parse("2025-07-25")));
	}

	@Test
	public void weekText_standalone() throws Exception {
		assertEquals("星期二", KeydroidxLunarCalendar.getWeekText(parse("2026-10-06")));
	}
}
