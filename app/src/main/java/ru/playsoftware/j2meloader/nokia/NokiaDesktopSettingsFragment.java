package ru.playsoftware.j2meloader.nokia;

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

import ru.playsoftware.j2meloader.R;

/**
 * 桌面设置主菜单（分类层）。纵向列表展示各大类设置入口：
 * 外观与显示 / 按键与操作 / 桌面内容 / 系统与权限 / 高级设置。
 * 选中某项后进入对应的二级分组页（{@link NokiaSettingsGroupFragment} 或 {@link NokiaAdvancedSettingsFragment}）。
 */
public class NokiaDesktopSettingsFragment extends NokiaPageFragment {

	/** 分类入口：图标 + 名称 + 分组 ID（-1 表示高级设置）。 */
	private static final int[] ITEM_ICONS = {
			R.drawable.s60_gallery,       // 外观与显示
			R.drawable.s60_settings,      // 按键与操作
			R.drawable.ic_nokia_settings, // 桌面内容
			R.drawable.ic_nokia_home,     // 系统与权限
			R.drawable.s60_settings_alt,  // 高级设置
	};

	private static final String[] ITEM_NAMES = {
			"外观与显示",
			"按键与操作",
			"桌面内容",
			"系统与权限",
			"高级设置",
	};

	private static final int[] ITEM_GROUPS = {
			NokiaSettingsGroupFragment.GROUP_APPEARANCE,
			NokiaSettingsGroupFragment.GROUP_KEYS,
			NokiaSettingsGroupFragment.GROUP_CONTENT,
			NokiaSettingsGroupFragment.GROUP_SYSTEM,
			-1, // 高级设置（独立页面）
	};

	private View[] itemViews;
	private ScrollView settingsScroll;
	private int focusIndex = -1;
	private View selectedView = null;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_desktop_settings;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		settingsScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view);

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

			// 图标
			ImageView ivIcon = new ImageView(requireContext());
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 22)));
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
			tvName.setText(ITEM_NAMES[i]);
			tvName.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tvName, 12);
			row.addView(tvName);

			// 箭头
			TextView tvArrow = new TextView(requireContext());
			tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvArrow.setText(">");
			tvArrow.setTextColor(0xFFAAAAAA);
			NokiaDimens.textSize(tvArrow, 14);
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
				return true; // 列表项不响应左右
			default:
				return false;
		}
	}

	@Override
	public boolean onSelect() {
		NokiaLog.d("DesktopSettings", "onSelect 当前 focusIndex=" + focusIndex);
		if (focusIndex < 0 || focusIndex >= ITEM_GROUPS.length) return false;
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int group = ITEM_GROUPS[focusIndex];
		if (group < 0) {
			NokiaLog.i("DesktopSettings", "进入高级设置");
			host.openFragment(new NokiaAdvancedSettingsFragment());
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

	// ---- 焦点管理 ----

	private void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
		clearFocusBackground();
		focusIndex = index;
		applyFocusBackground();
		scrollToVisible(index);
		NokiaLog.d("DesktopSettings", "setFocusIndex -> " + index + " (" + ITEM_NAMES[index] + ")");
	}

	/** 约束 ScrollView 高度，使列表底部正好落在可视区底边（项目多时可滚动）。 */
	private void constrainScrollHeight(View root) {
		if (settingsScroll == null) return;
		root.post(() -> {
			View parent = (View) root.getParent();
			if (!(parent instanceof View)) return;
			int panelH = ((View) parent).getHeight();
			float scale = root.getScaleX();
			if (scale <= 0) scale = 1;
			int visibleH = (int) (panelH / scale);
			int headH = settingsScroll.getTop();
			int scrollH = visibleH - headH;
			if (scrollH > 0) {
				ViewGroup.LayoutParams lp = settingsScroll.getLayoutParams();
				lp.height = scrollH;
				settingsScroll.setLayoutParams(lp);
			}
		});
	}

	/** 确保焦点行在 ScrollView 可见区域内，方向键导航时自动跟随滚动。 */
	private void scrollToVisible(int index) {
		if (settingsScroll == null || itemViews == null
				|| index < 0 || index >= itemViews.length) return;
		final View item = itemViews[index];
		if (item == null) return;
		settingsScroll.post(() -> {
			int scrollY = settingsScroll.getScrollY();
			int itemTop = item.getTop();
			int itemBottom = item.getBottom();
			int svHeight = settingsScroll.getHeight();
			if (svHeight <= 0) return;
			if (itemTop < scrollY) {
				settingsScroll.smoothScrollTo(0, itemTop);
				NokiaLog.d("DesktopSettings", "↑ 滚动至 item " + index + " top=" + itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				settingsScroll.smoothScrollTo(0, itemBottom - svHeight);
				NokiaLog.d("DesktopSettings", "↓ 滚动至 item " + index + " bottom=" + itemBottom + " svH=" + svHeight);
			}
		});
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
