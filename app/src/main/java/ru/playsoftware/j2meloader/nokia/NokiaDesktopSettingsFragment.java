package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面设置主菜单。纵向列表展示各项设置入口。
 * 支持方向键导航（实现 NokiaFocusHost），风格延续 S60 菜单。
 */
public class NokiaDesktopSettingsFragment extends Fragment implements NokiaPage {

	private static final int[] ITEM_ICONS = {
			R.drawable.s60_settings,       // 字体大小
			R.drawable.ic_nokia_settings,  // 快捷栏设置
			R.drawable.s60_gallery,        // 壁纸设置
			R.drawable.s60_settings_alt,   // 桌面组件设置
			R.drawable.s60_settings,       // 按键绑定
			R.drawable.s60_settings,       // 应用向导
			R.drawable.ic_nokia_home,      // 默认桌面设置
	};

	private static final String[] ITEM_NAMES = {
			"字体大小",
			"顶部快捷栏设置",
			"壁纸设置",
			"桌面组件设置",
			"按键绑定",
			"应用向导",
			"默认桌面设置",
	};

	/** 字体大小档位（桌面设置 → 字体大小），作用于全部应用内文字。 */
	private static final float[] FONT_SCALES = {0.85f, 1.0f, 1.15f, 1.3f};
	private static final String[] FONT_LABELS = {"较小", "标准", "较大", "最大"};

	/** 取列表项名称：第 0 项（字体大小）显示当前档位；第 6 项（默认桌面设置）根据状态动态展示。 */
	private String getItemDisplayName(int index) {
		if (index == 0) {
			float cur = NokiaSettingsStorage.getFontScale(requireContext());
			String label = "标准";
			for (int i = 0; i < FONT_SCALES.length; i++) {
				if (Math.abs(FONT_SCALES[i] - cur) < 0.001f) {
					label = FONT_LABELS[i];
					break;
				}
			}
			return "字体大小：" + label;
		}
		if (index == 6) {
			boolean isDefault = ((NokiaDesktopActivity) requireActivity()).isDefaultLauncher();
			return isDefault ? "默认桌面：已设置" : "默认桌面设置";
		}
		return ITEM_NAMES[index];
	}

	private View[] itemViews;
	private ScrollView settingsScroll;
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

		// 运行时约束 ScrollView 高度，使列表底部正好落在可视区底边（项目多时可滚动）
		settingsScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view);

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
				NokiaLog.i("DesktopSettings", "字体大小设置");
				showFontScaleDialog();
				return true;
			case 1:
				NokiaLog.i("DesktopSettings", "进入快捷栏设置");
				host.openFragment(new NokiaShortcutSettingsFragment());
				return true;
			case 2:
				NokiaLog.i("DesktopSettings", "壁纸设置（待实现）");
				// TODO: 壁纸设置
				return true;
			case 3:
				NokiaLog.i("DesktopSettings", "进入桌面组件设置");
				host.openFragment(new NokiaWidgetSettingsFragment());
				return true;
			case 4:
				NokiaLog.i("DesktopSettings", "按键绑定");
				host.openFragment(new NokiaKeyBindFragment());
				return true;
			case 5:
				NokiaLog.i("DesktopSettings", "进入应用向导");
				host.getSupportFragmentManager().beginTransaction()
						.replace(R.id.midPanel, new NokiaKeyBindWizardFragment())
						.addToBackStack(null)
						.commit();
				return true;
			case 6:
				NokiaLog.i("DesktopSettings", "默认桌面设置：引导设为默认桌面");
				host.requestSetDefaultLauncher();
				return true;
			default:
				return false;
		}
	}

	/** 弹出字体大小选择弹窗（复用通用选项弹窗），选择后保存并重建 Activity 立即生效。 */
	private void showFontScaleDialog() {
		float cur = NokiaSettingsStorage.getFontScale(requireContext());
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		for (int i = 0; i < FONT_LABELS.length; i++) {
			final float scale = FONT_SCALES[i];
			final String label = FONT_LABELS[i];
			String itemLabel = Math.abs(scale - cur) < 0.001f
					? label + "（当前）" : label;
			items.add(new NokiaOptionsDialog.OptionItem(0, itemLabel, true, false, () -> {
				NokiaSettingsStorage.setFontScale(requireContext(), scale);
				NokiaLog.i("DesktopSettings", "字体大小已设置: " + label + " scale=" + scale);
				((NokiaDesktopActivity) requireActivity()).recreate();
			}));
		}
		NokiaOptionsDialog.show(getParentFragmentManager(), "字体大小", items);
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
				NokiaLog.i("DesktopSettings", "约束ScrollView高度: panelH=" + panelH
						+ " scale=" + scale + " visibleH=" + visibleH
						+ " headH=" + headH + " scrollH=" + scrollH);
			} else {
				NokiaLog.w("DesktopSettings", "scrollH <= 0, skip height constraint: scrollH=" + scrollH);
			}
		});
	}

	/**
	 * 确保焦点行在 ScrollView 可见区域内，方向键导航时自动跟随滚动。
	 */
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
