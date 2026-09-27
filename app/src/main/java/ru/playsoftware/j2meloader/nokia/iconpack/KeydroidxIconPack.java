package ru.playsoftware.j2meloader.nokia.iconpack;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;

import java.util.List;

/**
 * 图标包抽象：只负责「应用 → 图标名」的映射与「图标名 → Drawable」的取图，不含任何 UI 逻辑。
 *
 * <p>线程约束：{@link #ensureLoaded(Context)} 必须在后台线程调用（要读 XML / 跨包资源）；
 * {@link #getIconNameFor}、{@link #getIconByName}、{@link #listIconNames} 均为主线程可用的
 * 纯内存查询（解析完成前返回 null / 空列表，由调用方回退应用原图标）。</p>
 */
public abstract class KeydroidxIconPack {

	/** 图标包唯一 ID：{@code s60_builtin} 或 {@code adw:<包名>} */
	public abstract String getId();

	/** 设置页展示名 */
	public abstract String getDisplayName();

	/** 映射表是否已解析完成 */
	public abstract boolean isLoaded();

	/** 解析映射表；幂等，重复调用直接返回。必须在后台线程调用。 */
	public abstract void ensureLoaded(Context context);

	/**
	 * 查某个应用命中的图标名。
	 *
	 * @param pkg   应用包名
	 * @param cn    启动组件（可为 null；为 null 时只按包名匹配）
	 * @param label 应用显示名（保留参数：本项目的映射表匹配不使用名称，供将来扩展）
	 * @return 命中的图标名；null = 未命中（调用方回退应用原图标）
	 */
	public abstract String getIconNameFor(String pkg, ComponentName cn, String label);

	/** 该图标名在本包中是否存在（用于校验历史覆盖是否仍可用） */
	public abstract boolean hasIcon(String iconName);

	/** 按图标名取图；名称非法或资源缺失返回 null */
	public abstract Drawable getIconByName(Context context, String iconName);

	/** 手动挑选用的图标名清单（保持映射文件中的声明顺序） */
	public abstract List<String> listIconNames();

	/** 图标名的展示标签；默认返回名称本身（内置 S60 包返回中文名） */
	public String getIconLabel(String iconName) {
		return iconName;
	}

	@Override
	public String toString() {
		return "IconPack{" + getId() + ", loaded=" + isLoaded() + "}";
	}
}
