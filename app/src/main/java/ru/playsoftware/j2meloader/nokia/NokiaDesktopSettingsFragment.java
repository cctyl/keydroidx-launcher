package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面设置主菜单。纵向列表展示各项设置入口。
 * 支持方向键导航（实现 NokiaFocusHost），风格延续 S60 菜单。
 */
public class NokiaDesktopSettingsFragment extends Fragment implements NokiaPage {

	private static final int[] ITEM_ICONS = {
			R.drawable.ic_nokia_settings,   // 快捷栏设置
			R.drawable.s60_gallery,          // 壁纸设置
			R.drawable.s60_settings_alt,     // 桌面组件设置
			R.drawable.s60_settings,         // 按键绑定
			R.drawable.s60_settings,         // 按键绑定向导
			R.drawable.ic_nokia_home,        // 默认桌面设置
	};

	private static final String[] ITEM_NAMES = {
			"顶部快捷栏设置",
			"壁纸设置",
			"桌面组件设置",
			"按键绑定",
			"按键绑定向导",
			"默认桌面设置",
	};

	/** 取列表项名称：第 5 项（默认桌面设置）根据是否已设为默认桌面动态展示状态。 */
	private String getItemDisplayName(int index) {
		if (index == 5) {
			boolean isDefault = ((NokiaDesktopActivity) requireActivity()).isDefaultLauncher();
			return isDefault ? "默认桌面：已设置" : "默认桌面设置";
		}
		return ITEM_NAMES[index];
	}

	private View[] itemViews;
	private int focusIndex = -1;
	private View selectedView = null;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_nokia_desktop_settings, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.scaleMidContent(view, true);

		View wall = host.findViewById(R.id.wallpaper);
		if (wall != null) {
			wall.setBackgroundResource(R.drawable.bg_nokia_menu);
		}
		// 底部菜单栏由 NokiaPage 声明 + host.refreshPageBar() 自动装配
		host.refreshPageBar();

		// 构建设置列表
		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		itemViews = new View[ITEM_NAMES.length];
		for (int i = 0; i < ITEM_NAMES.length; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4), NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4));
			row.setClickable(true);


			// 图标
			ImageView ivIcon = new ImageView(requireContext());
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 22)));
			try {
				ivIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), ITEM_ICONS[i]));
			} catch (Exception ignored) {}
			row.addView(ivIcon);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			// 名称
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setText(getItemDisplayName(i));
			tvName.setTextColor(0xFFFFFFFF);
			tvName.setTextSize(12);
			row.addView(tvName);

			// 箭头
			TextView tvArrow = new TextView(requireContext());
			tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvArrow.setText(">");
			tvArrow.setTextColor(0xFFAAAAAA);
			tvArrow.setTextSize(14);
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
		NokiaLog.i("DesktopSettings", "桌面设置菜单初始化完成");
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		int count = itemViews != null ? itemViews.length : 0;
		if (count == 0) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		int oldIndex = focusIndex;
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > 0) setFocusIndex(focusIndex - 1);
				NokiaLog.d("DesktopSettings", "onDirection 上：old=" + oldIndex + " new=" + focusIndex);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < count - 1) setFocusIndex(focusIndex + 1);
				NokiaLog.d("DesktopSettings", "onDirection 下：old=" + oldIndex + " new=" + focusIndex);
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
				NokiaLog.d("DesktopSettings", "onDirection 左右：focus=" + focusIndex + " 不响应");
				return true; // 列表项不响应左右
			default:
				return false;
		}
	}

	@Override
	public boolean onSelect() {
		NokiaLog.d("DesktopSettings", "onSelect 当前 focusIndex=" + focusIndex);
		if (focusIndex < 0) return false;
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		switch (focusIndex) {
			case 0:
				NokiaLog.i("DesktopSettings", "进入快捷栏设置");
				host.openFragment(new NokiaShortcutSettingsFragment());
				return true;
			case 1:
				NokiaLog.i("DesktopSettings", "壁纸设置（待实现）");
				// TODO: 壁纸设置
				return true;
			case 2:
				NokiaLog.i("DesktopSettings", "进入桌面组件设置");
				host.openFragment(new NokiaWidgetSettingsFragment());
				return true;
			case 3:
				NokiaLog.i("DesktopSettings", "按键绑定");
				host.openFragment(new NokiaKeyBindFragment());
				return true;
			case 4:
				NokiaLog.i("DesktopSettings", "进入按键绑定向导");
				host.getSupportFragmentManager().beginTransaction()
						.replace(R.id.midPanel, new NokiaKeyBindWizardFragment())
						.addToBackStack(null)
						.commit();
				return true;
			case 5:
				NokiaLog.i("DesktopSettings", "默认桌面设置：引导设为默认桌面");
				host.requestSetDefaultLauncher();
				return true;
			default:
				return false;
		}
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

	// ---- 焦点管理 ----

	private void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
		clearFocusBackground();
		focusIndex = index;
		applyFocusBackground();
		NokiaLog.d("DesktopSettings", "setFocusIndex -> " + index + " (" + ITEM_NAMES[index] + ")");
	}


	private void clearFocusBackground() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyFocusBackground() {
		if (focusIndex >= 0 && focusIndex < itemViews.length && itemViews[focusIndex] != null) {
			itemViews[focusIndex].setBackgroundResource(R.drawable.bg_nokia_selected_dark);
			selectedView = itemViews[focusIndex];
		}
	}


	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
