package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 诺基亚滚动页面 Fragment 抽象基类。
 * <p>
 * 适用于包含 {@link ScrollView} 的文本说明页、长表单页、非纯列表视图的滚动页面等。
 * 子类必须/可选提供 {@link ScrollView}（若未显式提供，基类会自动在根布局中递归查找第一个 ScrollView）。
 * <p>
 * 默认实现了 {@link #onDirection(int)}：当接收到上下按键时，平滑滚动页面（步长为 40dp）。
 * 子类若有部分焦点控件（如按钮），可在重写 {@link #onDirection(int)} 时结合焦点逻辑与 {@link #scrollDown()} / {@link #scrollUp()}。
 */
public abstract class NokiaScrollPageFragment extends NokiaPageFragment {

	protected static final int DEFAULT_SCROLL_STEP_DP = 40;
	protected ScrollView pageScrollView;

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		pageScrollView = findScrollView(view);
		NokiaLog.d("NokiaScroll", "onPageCreated found pageScrollView=" + pageScrollView);
		onScrollPageCreated(view, savedInstanceState);
	}

	/**
	 * 子类页面初始化钩子，替代 {@link #onPageCreated(View, Bundle)}。
	 */
	protected void onScrollPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		// 子类可选实现
	}

	/**
	 * 递归查找布局中的 ScrollView。
	 */
	@Nullable
	protected ScrollView findScrollView(@Nullable View root) {
		if (root instanceof ScrollView) {
			return (ScrollView) root;
		}
		if (root instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) root;
			for (int i = 0; i < group.getChildCount(); i++) {
				ScrollView sv = findScrollView(group.getChildAt(i));
				if (sv != null) {
					return sv;
				}
			}
		}
		return null;
	}

	/**
	 * 获取上下方向键单次滚动的步长（像素）。
	 */
	protected int getScrollStepPx() {
		if (pageScrollView != null && pageScrollView.getHeight() > 0) {
			return (int) (pageScrollView.getHeight() * 0.45f);
		}
		if (getContext() != null) {
			return NokiaDimens.dp(getResources(), 100);
		}
		return 160;
	}

	/**
	 * 向上平滑滚动一个步长。
	 */
	public boolean scrollUp() {
		if (pageScrollView != null) {
			int step = getScrollStepPx();
			NokiaLog.d("NokiaScroll", "scrollUp: height=" + pageScrollView.getHeight() + ", step=" + step + ", scrollY=" + pageScrollView.getScrollY());
			pageScrollView.smoothScrollBy(0, -step);
			return true;
		}
		return false;
	}

	/**
	 * 向下平滑滚动一个步长。
	 */
	public boolean scrollDown() {
		if (pageScrollView != null) {
			int step = getScrollStepPx();
			NokiaLog.d("NokiaScroll", "scrollDown: height=" + pageScrollView.getHeight() + ", step=" + step + ", scrollY=" + pageScrollView.getScrollY());
			pageScrollView.smoothScrollBy(0, step);
			return true;
		}
		return false;
	}

	/**
	 * 当前是否还能向上滚动。
	 */
	public boolean canScrollUp() {
		return pageScrollView != null && pageScrollView.canScrollVertically(-1);
	}

	/**
	 * 当前是否还能向下滚动。
	 */
	public boolean canScrollDown() {
		return pageScrollView != null && pageScrollView.canScrollVertically(1);
	}

	@Override
	public boolean onDirection(int action) {
		NokiaLog.d("NokiaScroll", "onDirection called with action=" + action + ", pageScrollView=" + pageScrollView);
		if (action == NokiaKeyBinding.ACTION_UP) {
			return scrollUp();
		} else if (action == NokiaKeyBinding.ACTION_DOWN) {
			return scrollDown();
		}
		return false;
	}

	@Override
	public boolean onSelect() {
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
	}

	@Override
	public boolean onSoftRight() {
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).exitCurrent();
			return true;
		}
		return false;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}
}
