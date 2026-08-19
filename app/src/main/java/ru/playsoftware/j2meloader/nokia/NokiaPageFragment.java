package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import ru.playsoftware.j2meloader.R;

/**
 * 诺基亚页面 Fragment 抽象基类（模板方法模式，强制规范）。
 * <p>
 * 所有「中间内容区」的页面 Fragment 必须继承本类，禁止直接 extends Fragment。
 * 父类用 final 方法固化每个页面都必须执行的样板逻辑，子类<b>无法绕过</b>：
 * <ol>
 *   <li>{@link #onCreateView}（final）：自动 inflate {@link #getLayoutRes()} 声明的布局；</li>
 *   <li>{@link #onViewCreated}（final）：自动执行
 *       {@link NokiaBaseActivity#scaleMidContent(View, boolean)} 缩放 +
 *       {@link NokiaBaseActivity#fixMidContentHeight(View, boolean)} 高度调整（topAlign 时）+
 *       壁纸设置 + {@link NokiaDesktopActivity#refreshPageBar()} 底部菜单栏装配；</li>
 *   <li>{@link #onPageCreated(View, Bundle)}：子类唯一初始化钩子，替代原先
 *       onViewCreated 中除样板外的部分。</li>
 * </ol>
 * 子类只需实现三个方法：
 * <ul>
 *   <li>{@link #getLayoutRes()}：返回布局资源（宽度固定 240dp）；</li>
 *   <li>{@link #onPageCreated(View, Bundle)}：页面自己的初始化；</li>
 *   <li>特殊页面覆写 {@link #isTopAlign()}（默认 true，百宝箱等居中页返回 false）
 *       与 {@link #getWallpaperRes()}（默认 bg_nokia_menu）。</li>
 * </ul>
 * <p>
 * 背景：历史 bug 全部源于各页面手抄样板时漏写/写错缩放与高度调整
 * （见 docs/NOKIA_DEVELOPMENT_RULES.md「topAlign 缩放 + 根高不匹配 panelH 的二次缩放陷阱」）。
 * 固化为模板方法后，新页面想漏都不可能。
 */
public abstract class NokiaPageFragment extends Fragment implements NokiaPage {

	/** 子类声明页面布局（根宽固定 240dp，根高 match_parent）。 */
	@LayoutRes
	protected abstract int getLayoutRes();

	/** 是否贴容器顶部。true=贴顶（桌面/菜单/设置/向导等）；false=垂直居中（百宝箱）。 */
	protected boolean isTopAlign() {
		return true;
	}

	/** 页面壁纸资源；返回 0 表示不设置。默认深蓝渐变菜单壁纸。 */
	@LayoutRes
	protected int getWallpaperRes() {
		return R.drawable.bg_nokia_menu;
	}

	@Nullable
	@Override
	public final View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
								   @Nullable Bundle savedInstanceState) {
		return inflater.inflate(getLayoutRes(), container, false);
	}

	@Override
	public final void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		// 强制根视图透明，避免子页面 XML 误设不透明背景遮挡全局主题壁纸
		view.setBackgroundResource(0);

		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		boolean topAlign = isTopAlign();
		host.scaleMidContent(view, topAlign);
		// 应用当前全局主题（背景壁纸与软键栏渐变）
		host.applyCurrentTheme();
		// 底部菜单栏由 NokiaPage 声明 + host.refreshPageBar() 自动装配
		host.refreshPageBar();
		// 子类初始化钩子（在此构建动态列表与子 View）
		onPageCreated(view, savedInstanceState);
		// 统一应用全局字体（确保子类动态添加的 TextView 全部生效）
		NokiaFontManager.applyFontToViewHierarchy(view);
	}

	/**
	 * 页面初始化钩子：在缩放、壁纸、底栏装配全部完成之后调用。
	 * 子类在此 findViewById / 构建列表 / 设置焦点等（替代原先 onViewCreated 的剩余部分）。
	 */
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
	}
}
