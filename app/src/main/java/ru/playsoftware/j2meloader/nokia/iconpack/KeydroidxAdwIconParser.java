package ru.playsoftware.j2meloader.nokia.iconpack;

import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.cctyl.nokia.common.log.KeydroidxLog;

/**
 * ADW 图标包映射解析器（{@code appfilter.xml} + {@code drawable.xml}）。
 *
 * <p>支持范围（与 Lawnchair 现状对齐）：</p>
 * <ul>
 *   <li>{@code <item component="..." drawable="..."/>} —— 静态图标映射，唯一实现的能力；</li>
 *   <li>{@code component} 支持标准写法 {@code ComponentInfo{pkg/cls}} 与本项目扩展写法
 *       「纯包名」（内置 S60 映射表用纯包名表达「整包映射」）；</li>
 *   <li>{@code <calendar component prefix>}、{@code <iconback>/<iconmask>/<iconupon>/<scale>}、
 *       伪 component（以 {@code :} 开头，如 {@code :BROWSER}）—— 一律忽略并 debug 日志，
 *       未实现即「未命中 → 回退应用原图标」，不会影响主流程。</li>
 * </ul>
 *
 * <p>线程约束：解析含 IO 与 XML 遍历，只能在后台线程调用。</p>
 */
public final class KeydroidxAdwIconParser {

	private static final String TAG = "IconPack";

	/** 解析结果：两张 O(1) 查找表 + 手动挑选清单 */
	public static final class Result {
		/** 组件级映射：{@code pkg/cls} → 图标名 */
		public final Map<String, String> componentTable = new HashMap<>();
		/** 包级映射：{@code pkg} → 图标名（整包映射，优先级低于组件级） */
		public final Map<String, String> packageTable = new HashMap<>();
		/** drawable.xml 中声明的可挑选图标（保持声明顺序） */
		public final List<String> drawableNames = new ArrayList<>();
		/** appfilter 中出现过的图标名（drawable.xml 缺失时的兜底清单） */
		public final Set<String> referencedNames = new LinkedHashSet<>();

		/** 成功解析的映射条目数（组件级 + 包级） */
		public int mappedCount;
		/** 忽略的条目数（伪 component、空 drawable、动态日历等） */
		public int skippedCount;

		public boolean isEmpty() {
			return componentTable.isEmpty() && packageTable.isEmpty();
		}
	}

	private KeydroidxAdwIconParser() {
	}

	/** 用纯文本流创建解析器（assets / res/raw 用） */
	public static XmlPullParser newParser(InputStream in) throws XmlPullParserException {
		XmlPullParser parser = Xml.newPullParser();
		parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
		parser.setInput(in, null);
		return parser;
	}

	/**
	 * 依次解析 appfilter 与 drawable，返回合并结果。
	 * 任一参数为 null 表示该文件不存在（调用方负责按「缺失」策略处理）。
	 */
	public static Result parse(XmlPullParser appfilter, XmlPullParser drawable) {
		Result result = new Result();
		if (appfilter != null) {
			parseAppfilter(appfilter, result);
		}
		if (drawable != null) {
			parseDrawable(drawable, result);
		}
		return result;
	}

	/** 解析 appfilter.xml 的 {@code <item>} 映射 */
	public static void parseAppfilter(XmlPullParser parser, Result out) {
		try {
			int event = parser.getEventType();
			while (event != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.START_TAG) {
					String tag = parser.getName();
					if ("item".equals(tag)) {
						String component = parser.getAttributeValue(null, "component");
						String drawable = parser.getAttributeValue(null, "drawable");
						addMapping(out, component, drawable);
					} else if ("calendar".equals(tag)) {
						// 动态日历（Nova 扩展）：本项目未实现，忽略但不报错
						out.skippedCount++;
						KeydroidxLog.d(TAG, "忽略动态日历映射: "
								+ parser.getAttributeValue(null, "component"));
					} else if ("iconback".equals(tag) || "iconmask".equals(tag)
							|| "iconupon".equals(tag) || "scale".equals(tag)
							|| "dynamic-clock".equals(tag)) {
						// 自动生成回退图标 / 动态时钟：本项目未实现，忽略
						out.skippedCount++;
					}
				}
				event = parser.next();
			}
		} catch (XmlPullParserException | IOException | RuntimeException e) {
			KeydroidxLog.w(TAG, "解析 appfilter 失败(已保留已读条目): " + e.getMessage());
		}
	}

	/** 解析 drawable.xml，收集手动挑选用的图标名 */
	public static void parseDrawable(XmlPullParser parser, Result out) {
		try {
			int event = parser.getEventType();
			while (event != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
					String name = parser.getAttributeValue(null, "drawable");
					if (!TextUtils.isEmpty(name) && !out.drawableNames.contains(name)) {
						out.drawableNames.add(name);
					}
				}
				event = parser.next();
			}
		} catch (XmlPullParserException | IOException | RuntimeException e) {
			KeydroidxLog.w(TAG, "解析 drawable.xml 失败(已保留已读条目): " + e.getMessage());
		}
	}

	/**
	 * 写入一条映射。规则：
	 * <ul>
	 *   <li>{@code ComponentInfo{pkg/cls}} → 组件级表 + （包级表空缺时）包名兜底，
	 *       使同包的其它启动入口也能命中；</li>
	 *   <li>纯包名 → 包级表；</li>
	 *   <li>显式包级条目优先：不会被组件级兜底覆盖。</li>
	 * </ul>
	 */
	private static void addMapping(Result out, String rawComponent, String drawable) {
		if (TextUtils.isEmpty(rawComponent) || TextUtils.isEmpty(drawable)) {
			out.skippedCount++;
			return;
		}
		String component = rawComponent.trim();
		if (component.startsWith("ComponentInfo{")) {
			if (!component.endsWith("}")) {
				out.skippedCount++;
				KeydroidxLog.d(TAG, "忽略非法 ComponentInfo: " + rawComponent);
				return;
			}
			component = component.substring("ComponentInfo{".length(), component.length() - 1).trim();
		}
		if (component.isEmpty() || component.startsWith(":")) {
			// 伪 component（Atom/其它启动器的占位写法）无法对应真实组件
			out.skippedCount++;
			KeydroidxLog.d(TAG, "忽略伪 component: " + rawComponent);
			return;
		}

		out.referencedNames.add(drawable);
		int slash = component.indexOf('/');
		if (slash > 0) {
			out.componentTable.put(component, drawable);
			String pkg = component.substring(0, slash);
			if (!out.packageTable.containsKey(pkg)) {
				out.packageTable.put(pkg, drawable);
			}
		} else {
			out.packageTable.put(component, drawable);
		}
		out.mappedCount++;
	}
}
