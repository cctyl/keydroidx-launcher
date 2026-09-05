package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.NokiaFontManager;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaFeedbackFragment;
import io.github.cctyl.nokia.common.ui.NokiaIcons;
import io.github.cctyl.nokia.common.ui.about.NokiaAboutConfig;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import io.github.cctyl.nokia.common.util.NokiaDimens;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;

import java.util.regex.Pattern;

/**
 * 桌面设置主菜单（分类层）。纵向列表展示各大类设置入口：
 * 外观与显示 / 按键与操作 / 桌面内容 / 系统与权限 / 高级设置。
 * 选中某项后进入对应的二级分组页（{@link NokiaSettingsGroupFragment} 或 {@link NokiaAdvancedSettingsFragment}）。
 */
public class NokiaDesktopSettingsFragment extends NokiaListPageFragment {

	/** 分类入口：图标（Material Icons 矢量字符） + 名称 + 分组 ID（-1 表示高级设置，-2 表示关于）。 */
	private static final String[] ITEM_ICON_UNICODES = {
			NokiaIcons.ICON_DISPLAY,       // 外观与显示
			NokiaIcons.ICON_KEYPAD,        // 按键与操作
			NokiaIcons.ICON_DESKTOP,       // 桌面内容
			NokiaIcons.ICON_SYSTEM,        // 系统与权限
			NokiaIcons.ICON_ADVANCED,      // 高级设置
			NokiaIcons.ICON_INFO,          // 关于
			NokiaIcons.ICON_FEEDBACK,      // 意见反馈
	};

	private static final String[] ITEM_NAMES = {
			"外观与显示",
			"按键与操作",
			"桌面内容",
			"系统与权限",
			"高级设置",
			"关于",
			"意见反馈",
	};

	private static final int[] ITEM_GROUPS = {
			NokiaSettingsGroupFragment.GROUP_APPEARANCE,
			NokiaSettingsGroupFragment.GROUP_KEYS,
			NokiaSettingsGroupFragment.GROUP_CONTENT,
			NokiaSettingsGroupFragment.GROUP_SYSTEM,
			-1, // 高级设置（独立页面）
			-2, // 关于（独立页面）
			-3, // 意见反馈（独立页面）
	};



	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_desktop_settings;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		listScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view, listScroll);

		itemViews = new View[ITEM_NAMES.length];
		for (int i = 0; i < ITEM_NAMES.length; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4),
					NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4));
			row.setClickable(true);

			// 图标（Material Icons 矢量图标）
			ImageView ivIcon = new ImageView(requireContext());
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20)));
			ivIcon.setImageDrawable(NokiaIcons.get(requireContext(), ITEM_ICON_UNICODES[i], 0xFFFFFFFF, 20));
			row.addView(ivIcon);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			// 名称
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setText(ITEM_NAMES[i]);
			tvName.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tvName, 12);
			row.addView(tvName);

			// 箭头
			TextView tvArrow = new TextView(requireContext());
			tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvArrow.setText(">");
			tvArrow.setTextColor(0xFFAAAAAA);
			NokiaFontManager.textSize(tvArrow, 14);
			row.addView(tvArrow);

			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		// 默认选中第一项
		setFocusIndex(0);

		NokiaLog.i("DesktopSettings", "桌面设置分类菜单初始化完成");
	}

	@Override
	public boolean onSelect() {
		NokiaLog.d("DesktopSettings", "onSelect 当前 focusIndex=" + focusIndex);
		if (focusIndex < 0 || focusIndex >= ITEM_GROUPS.length) return false;
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int group = ITEM_GROUPS[focusIndex];
		if (group == -1) {
			NokiaLog.i("DesktopSettings", "进入高级设置");
			host.openFragment(new NokiaAdvancedSettingsFragment());
		} else if (group == -2) {
			NokiaLog.i("DesktopSettings", "进入关于页面");
			host.openFragment(NokiaAboutFragment.newInstance(buildAboutConfig()));
		} else if (group == -3) {
			NokiaLog.i("DesktopSettings", "进入意见反馈页面");
			host.openFragment(new NokiaFeedbackFragment());
		} else {
			NokiaLog.i("DesktopSettings", "进入设置分组: " + ITEM_NAMES[focusIndex]);
			host.openFragment(NokiaSettingsGroupFragment.newInstance(group));
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
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

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return "桌面设置";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}



	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}

	/**
	 * 构造关于页配置：复用 common 的统一关于页，内容与原自定义页对齐。
	 * 检查更新开启，并传入剥干净的逻辑版本号（见 {@link #stripFlavorSuffix}）。
	 */
	private NokiaAboutConfig buildAboutConfig() {
		return NokiaAboutConfig.createDefault(requireContext())
				.setAuthor("cctyl")
				.setDescription("KeydroidX 桌面是一款专为物理九键 / 全键盘 Android 按键机量身定制的诺基亚 S40/S60 风格桌面启动器，融合 J2ME 与原生应用，纯物理按键驱动，开箱即用获得极简与复古体验。")
				.setRepoUrl("https://github.com/cctyl/keydroidx-launcher")
				.setVideoUrl("https://www.bilibili.com/video/BV1WxMX6yEHX")
				.setAcknowledgements(
						"• J2ME-Loader (nikita36078)\n" +
							"• 方舟像素字体 / Ark Pixel (TakWolf)\n" +
							"• 缝合怪像素字体 / Fusion Pixel (TakWolf)\n" +
							"• Google Material Icons\n" +
							"• S60 图标库 / s60-icon-pack (x1unix)")
				.setShowUpdateCheck(true)
				.setUpdateCurrentVersion(stripFlavorSuffix(BuildConfig.VERSION_NAME))
				.setShowDetailedLogToggle(true);
	}

	/**
	 * 剥离 flavor 渠道后缀（-open/-play/-dev-NNNNN），只保留逻辑版本号。
	 *
	 * <p>AGP 构建会把 productFlavors 的 versionNameSuffix 追加到 defaultConfig.versionName 上
	 * 写进最终 manifest，运行时 {@link BuildConfig#VERSION_NAME} 读到的就是带后缀的串（如 1.3.1-open）。
	 * 渠道后缀并非 pre-release 标记，但 semver 比较会把它当成修饰段判为更小，
	 * 导致与 GitHub 裸 tag（如 1.3.1）误判为「有新版本」。这里按 {@link BuildConfig#FLAVOR}
	 * 把尾部对应后缀剥掉后再交给检查器。</p>
	 */
	private static String stripFlavorSuffix(String versionName) {
		if (versionName == null || versionName.isEmpty()) {
			return versionName;
		}
		String flavor = BuildConfig.FLAVOR;
		if (flavor == null || flavor.isEmpty()) {
			return versionName;
		}
		// 匹配尾部 "-<flavor>" 或 "-<flavor>-<数字>"（dev 的 versionNameSuffix = "-dev-" + digits）
		String pattern = "-" + Pattern.quote(flavor) + "(-\\d+)?$";
		return versionName.replaceFirst(pattern, "");
	}
}
