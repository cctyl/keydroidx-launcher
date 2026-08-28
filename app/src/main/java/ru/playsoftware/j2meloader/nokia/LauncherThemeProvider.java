package ru.playsoftware.j2meloader.nokia;

import android.content.Context;

import androidx.annotation.NonNull;

import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.ThemeProvider;

/**
 * 桌面侧主题提供者：把桌面本地设置（NokiaSettingsStorage）桥接给 common 的 NokiaTheme。
 * 桌面是 Provider 宿主，直接读本地 SP，不跨进程查询自己。
 * 详见 docs/接入nokia-common实施计划.md。
 */
public class LauncherThemeProvider implements ThemeProvider {

	@Override
	@NonNull
	public NokiaTheme.ThemeDef getCurrentTheme(@NonNull Context context) {
		NokiaSettingsStorage storage = new NokiaSettingsStorage(context);
		return NokiaTheme.getTheme(storage.getThemeId());
	}
}
