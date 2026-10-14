package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;

/**
 * 诺基亚页面契约：声明底部菜单栏差异。
 * <p>
 * 页面实现该接口后，由 {@link KeydroidxDesktopActivity} 在页面切到前台 / 页面主动请求时
 * 自动装配底部菜单栏（{@code setBottomBar}），页面自身不再直接操作
 * bottomLeft / bottomCenter / bottomRight 三个 TextView。
 * <p>
 * 三个 getter 允许<b>动态取值</b>：页面内部状态（焦点、mode、覆盖模式、向导步骤）变化后，
 * 调用 {@code host.refreshPageBar()} 即可重新装配。
 * <p>
 * 契约主体来自 common {@link io.github.cctyl.nokia.common.ui.page.KeydroidxPage}：
 * 标题 / 左软键 / 中软键 / 右软键 + 焦点事件
 * （{@link io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost}）。
 * <p>
 * 中软键文案桌面端不作为可按键位使用：底栏中栏显示的是 {@link #getPageTitle()}，
 * 只有标题为空时才回退到中软键文案兜底（见 {@link KeydroidxDesktopActivity#refreshPageBar()}），
 * 因此本接口给 {@code getSoftCenterText()} 默认实现（null）。
 * <p>
 * 标题（及左/右软键）允许依赖进入参数，但相关字段必须在 {@code onCreate} 里读
 * {@code getArguments()} —— 底栏装配发生在 {@code onPageCreated} 之后，滞后初始化会读到默认值。
 */
public interface KeydroidxPage extends io.github.cctyl.nokia.common.ui.page.KeydroidxPage {

	@Override
	default CharSequence getSoftCenterText() {
		return null;
	}
}
