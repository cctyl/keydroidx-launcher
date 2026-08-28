package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;

/**
 * 诺基亚页面契约：声明底部菜单栏差异。
 * <p>
 * 页面实现该接口后，由 {@link NokiaDesktopActivity} 在页面切到前台 / 页面主动请求时
 * 自动装配底部菜单栏（{@code setBottomBar}），页面自身不再直接操作
 * bottomLeft / bottomCenter / bottomRight 三个 TextView。
 * <p>
 * 三个 getter 允许<b>动态取值</b>：页面内部状态（焦点、mode、覆盖模式、向导步骤）变化后，
 * 调用 {@code host.refreshPageBar()} 即可重新装配。
 * <p>
 * 契约主体来自 common {@link io.github.cctyl.nokia.common.ui.page.NokiaPage}：
 * 标题 / 左软键 / 中软键 / 右软键 + 焦点事件
 * （{@link io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost}）。
 * 中软键文案桌面暂不使用，这里给默认实现（null=隐藏），页面需要时再覆写。
 */
public interface NokiaPage extends io.github.cctyl.nokia.common.ui.page.NokiaPage {

	@Override
	default CharSequence getSoftCenterText() {
		return null;
	}
}
