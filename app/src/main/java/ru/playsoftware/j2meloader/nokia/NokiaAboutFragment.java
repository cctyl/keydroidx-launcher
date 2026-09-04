package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;

import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.ui.about.NokiaAboutConfig;

/**
 * 桌面关于页：复用 common 的 {@link io.github.cctyl.nokia.common.ui.about.NokiaAboutFragment}
 * 统一页面（内容装配、检查更新、详细日志开关、点阵字体自适应等全部走 common），
 * 仅在此薄子类里覆写右软键/返回键，使其弹出设置栈。
 *
 * <p>原因：launcher 的 {@code NokiaDesktopActivity} 触摸右软键走
 * {@code dispatchActionToHost}，未消费时没有 exitCurrent 兜底；若沿用 common 默认
 * {@code onSoftRight()->onBack()->false}，触屏点「返回」不会弹栈。故显式调用
 * {@link NokiaDesktopActivity#exitCurrent()}，保留触屏与物理按键一致的返回体验。</p>
 *
 * <p>config 由 {@link NokiaDesktopSettingsFragment} 通过 {@link #newInstance(NokiaAboutConfig)}
 * 注入；内容包括 appName/version/icon（createDefault 自动读取）、repoUrl、videoUrl、
 * author、acknowledgements、extraStatement，以及检查更新（带 flavor 后缀剥离）与详细日志开关。</p>
 */
public class NokiaAboutFragment extends io.github.cctyl.nokia.common.ui.about.NokiaAboutFragment {

	public static NokiaAboutFragment newInstance(@Nullable NokiaAboutConfig config) {
		NokiaAboutFragment f = new NokiaAboutFragment();
		if (config != null) {
			Bundle args = new Bundle();
			args.putSerializable(ARG_CONFIG, config);
			f.setArguments(args);
		}
		return f;
	}

	@Override
	public boolean onSoftRight() {
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).exitCurrent();
			return true;
		}
		return super.onSoftRight();
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}
}
