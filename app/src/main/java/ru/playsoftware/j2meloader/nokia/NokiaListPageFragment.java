package ru.playsoftware.j2meloader.nokia;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import ru.playsoftware.j2meloader.R;

/**
 * 纵向列表页基类（模板方法模式，强制循环导航 + 焦点管理）。
 * <p>
 * 继承自 {@link NokiaPageFragment}，专注于「纵向单列菜单/列表」页面，收编了各页面
 * 重复手抄的焦点三件套（{@code setFocusIndex} / {@code clearFocusBackground} /
 * {@code applyFocusBackground} / {@code scrollToVisible} / {@code constrainScrollHeight}），
 * 并把方向键导航固化为<b>循环导航</b>：
 * <ul>
 *   <li>列表顶部按「上」→ 跳转到列表末尾；</li>
 *   <li>列表底部按「下」→ 跳转到列表开头；</li>
 *   <li>左右方向键默认消费无效果，子类可通过 {@link #onLeftRight(int)} 钩子实现左右切页签等逻辑。</li>
 * </ul>
 * 子类只需在 {@link #onPageCreated(View, Bundle)} 中填充 {@link #itemViews} 和
 * {@link #listScroll}，其余导航/焦点/滚动全部继承。<b>禁止覆写 {@link #onDirection(int)}</b>
 * —— 循环导航是强制行为，和基类缩放四件套一样的防漏抄思路。
 * <p>
 * 网格类（功能表、百宝箱等）和分页类继续直接继承 {@link NokiaPageFragment}，不进本基类。
 *
 * @see NokiaPageFragment
 * @see #onDirection(int)
 * @see #onLeftRight(int)
 * @see #isDirectionEnabled()
 */
public abstract class NokiaListPageFragment extends NokiaPageFragment {

	/** 列表项视图数组，子类在 {@link #onPageCreated} 中填充。 */
	protected View[] itemViews;

	/** 列表的 ScrollView 容器，子类在 {@link #onPageCreated} 中赋值。 */
	protected ScrollView listScroll;

	/** 当前焦点索引。 */
	protected int focusIndex = -1;

	/** 当前高亮选中的视图（用于清除旧高亮）。 */
	private View selectedView;

	/**
	 * 子类可覆写此方法临时禁用方向键导航（如 {@code NokiaKeyBindFragment} 的确认弹窗态）。
	 * 返回 false 时方向键仍被消费（不穿透），但焦点不移动。
	 */
	protected boolean isDirectionEnabled() {
		return true;
	}

	/**
	 * 列表项数量。默认从 {@link #itemViews} 长度获取；若 itemViews 尚未初始化或
	 * 子类有特殊计算需求，可覆写此方法。
	 */
	protected int getItemCount() {
		return itemViews != null ? itemViews.length : 0;
	}

	// ---- 方向键导航（final，禁止覆写）----

	/**
	 * 方向键导航：<b>循环导航</b>。
	 * <ul>
	 *   <li>{@code ACTION_UP}：非首项上移，首项跳转到末尾；</li>
	 *   <li>{@code ACTION_DOWN}：非末项下移，末项跳转到开头；</li>
	 *   <li>{@code ACTION_LEFT / ACTION_RIGHT}：调用 {@link #onLeftRight(int)} 钩子，默认消费无效果。</li>
	 * </ul>
	 * 子类可通过 {@link #isDirectionEnabled()} 和 {@link #onLeftRight(int)} 做有限定制，
	 * 但上下循环逻辑不可绕过。
	 */
	@Override
	public final boolean onDirection(int direction) {
		if (!isDirectionEnabled()) return true;
		int count = getItemCount();
		if (count == 0) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				setFocusIndex(focusIndex > 0 ? focusIndex - 1 : count - 1);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				setFocusIndex(focusIndex < count - 1 ? focusIndex + 1 : 0);
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
				return onLeftRight(direction);
			default:
				return false;
		}
	}

	/**
	 * 左右方向键的处理钩子。<b>默认消费（返回 true，无效果）</b>。
	 * 子类可覆写实现左右切页签（如 {@code NokiaBackgroundManagerFragment}）或其它操作。
	 *
	 * @param direction {@code NokiaKeyBinding.ACTION_LEFT} 或 {@code ACTION_RIGHT}
	 * @return true 表示已消费该事件
	 */
	protected boolean onLeftRight(int direction) {
		return true;
	}

	// ---- 焦点管理 ----

	/**
	 * 设置焦点到指定索引项：清除旧高亮 → 更新焦点索引 → 应用新高亮 → 滚动到可见。
	 * 索引越界时静默忽略。
	 */
	protected void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
		clearFocusBackground();
		focusIndex = index;
		applyFocusBackground();
		scrollToVisible(index);
	}

	private void clearFocusBackground() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyFocusBackground() {
		if (focusIndex >= 0 && focusIndex < itemViews.length && itemViews[focusIndex] != null) {
			itemViews[focusIndex].setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			selectedView = itemViews[focusIndex];
		}
	}

	// ---- 滚动跟随 ----

	/**
	 * 确保焦点行在 ScrollView 可视区域内。当焦点行移出可视区时，用 {@code smoothScrollTo}
	 * 平滑滚动到可见位置。
	 */
	private void scrollToVisible(int index) {
		if (listScroll == null || itemViews == null
				|| index < 0 || index >= itemViews.length) return;
		final View item = itemViews[index];
		if (item == null) return;
		listScroll.post(() -> {
			int scrollY = listScroll.getScrollY();
			int itemTop = item.getTop();
			int itemBottom = item.getBottom();
			int svHeight = listScroll.getHeight();
			if (svHeight <= 0) return;
			if (itemTop < scrollY) {
				listScroll.smoothScrollTo(0, itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				listScroll.smoothScrollTo(0, itemBottom - svHeight);
			}
		});
	}

	/**
	 * 约束 ScrollView 高度，使其底部正好落在中间面板可视区底边。
	 * 在 {@link #onPageCreated} 中调用一次即可。
	 *
	 * @param root   Fragment 根视图（用于获取父布局和 scale）
	 * @param scroll 要约束的 ScrollView，通常就是 {@link #listScroll}
	 */
	protected void constrainScrollHeight(@NonNull View root, @NonNull ScrollView scroll) {
		root.post(() -> {
			View parent = (View) root.getParent();
			if (!(parent instanceof View)) return;
			int panelH = parent.getHeight();
			float scale = root.getScaleX();
			if (scale <= 0) scale = 1;
			int visibleH = (int) (panelH / scale);
			int headH = scroll.getTop();
			int scrollH = visibleH - headH;
			if (scrollH > 0) {
				ViewGroup.LayoutParams lp = scroll.getLayoutParams();
				lp.height = scrollH;
				scroll.setLayoutParams(lp);
			}
		});
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		itemViews = null;
		listScroll = null;
		selectedView = null;
	}
}