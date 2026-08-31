package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaFeedbackFragment;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

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
import ru.playsoftware.j2meloader.R;

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
			host.openFragment(new NokiaAboutFragment());
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
}
