package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.R;

/**
 * mini_shizuku → adb 激活说明页。
 * <p>
 * 纯文本说明页面：以 ScrollView 滚动展示通过 adb（电脑 + USB 数据线）激活 mini_shizuku
 * 服务的步骤，适配小屏设备（内容超屏可上下滚动）。
 * <p>
 * 页面不参与列表导航，故左软键隐藏、方向键不消费；右软键「返回」与返回键回到上一层。
 */
public class ShizukuAdbFragment extends NokiaPageFragment {

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_shizuku_adb;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		NokiaLog.i("ShizukuAdb", "adb 激活说明页初始化完成");
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		// 纯文本说明页，无列表导航，返回 false 不消费
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
		return "adb 激活";
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
