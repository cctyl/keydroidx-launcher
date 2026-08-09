package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.R;

/**
 * mini_shizuku → root 激活页（占位）。
 * <p>
 * 当前版本暂未实现 root 激活功能，页面仅展示居中提示文案，右软键「返回」与返回键回到上一层。
 */
public class ShizukuRootFragment extends NokiaPageFragment {

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_shizuku_root;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		NokiaLog.i("ShizukuRoot", "root 激活占位页初始化完成（暂未实现）");
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		// 占位页无列表导航，返回 false 不消费
		return false;
	}

	@Override
	public boolean onSelect() {
		// 无列表项可确认，消费掉即可
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键隐藏
		return false;
	}

	@Override
	public boolean onSoftRight() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage ----

	@Override
	public String getPageTitle() {
		return "root 激活";
	}

	@Override
	public String getSoftLeftText() {
		return null; // 隐藏左软键
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}
}
