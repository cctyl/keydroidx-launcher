package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.graphics.Color;
import android.os.Bundle;
import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.drawable.NokiaDashedLineDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 快捷开关设置页面。
 * 支持：
 * 1. 普通模式：按确认键快速勾选/取消勾选展示开关；按左软键弹出选项菜单（调整排序、全选/取消全选、恢复默认）。
 * 2. 排序模式（参考桌面组件排序）：按确认键拎起/放下开关，按上下方向键实时调整开关位置，按左软键完成。
 */
public class NokiaQuickToggleSettingsFragment extends NokiaPageFragment {

	private static final String TAG = "QuickToggleSettings";

	public static final int MODE_NORMAL = 0;
	public static final int MODE_SORT = 1;

	private int mode = MODE_NORMAL;
	private int focusIndex = 0;

	// 排序状态
	private boolean lifted = false;
	private int liftedIndex = -1;

	private NokiaQuickToggleStorage storage;
	private List<NokiaQuickToggleItem> toggles = new ArrayList<>();

	private TextView tvStatus;
	private ScrollView toggleScroll;
	private LinearLayout toggleListLayout;
	private View[] itemViews;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_quick_toggle_settings;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		tvStatus = view.findViewById(R.id.tvToggleStatus);
		toggleScroll = view.findViewById(R.id.toggleScroll);
		toggleListLayout = view.findViewById(R.id.toggleListLayout);

		storage = new NokiaQuickToggleStorage(requireContext());

		// 运行时约束 ScrollView 高度
		view.post(new Runnable() {
			@Override
			public void run() {
				if (toggleScroll == null) return;
				View parent = (View) view.getParent();
				if (parent == null) return;
				int panelH = parent.getHeight();
				float scale = view.getScaleX();
				if (scale <= 0) scale = 1;
				int visibleH = (int) (panelH / scale);
				int headH = toggleScroll.getTop();
				int scrollH = visibleH - headH;
				if (scrollH > 0) {
					ViewGroup.LayoutParams lp = toggleScroll.getLayoutParams();
					lp.height = scrollH;
					toggleScroll.setLayoutParams(lp);
				}
			}
		});

		loadData();
		rebuildList();
		updateStatusText();
	}

	@Override
	public void onResume() {
		super.onResume();
		loadData();
		rebuildList();
		updateStatusText();
		updateBottomBar();
	}

	private void updateBottomBar() {
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).refreshPageBar();
		}
	}

	private void loadData() {
		toggles = storage.getToggles();
		if (focusIndex >= toggles.size()) {
			focusIndex = Math.max(0, toggles.size() - 1);
		}
	}

	private void updateStatusText() {
		if (tvStatus == null) return;
		if (mode == MODE_SORT) {
			if (lifted) {
				tvStatus.setText("按上下键移动，按确认键放下");
				tvStatus.setTextColor(0xFF00E5FF);
			} else {
				tvStatus.setText("按确认键拎起开关进行排序");
				tvStatus.setTextColor(0xFF88D8C0);
			}
		} else {
			int enabledCount = 0;
			for (int i = 0; i < toggles.size(); i++) {
				if (toggles.get(i).enabled) enabledCount++;
			}
			tvStatus.setText("展示 " + enabledCount + " / " + toggles.size() + " 项 (确认键切换)");
			tvStatus.setTextColor(0xFF888888);
		}
	}

	private void rebuildList() {
		if (toggleListLayout == null || getContext() == null) return;
		toggleListLayout.removeAllViews();
		itemViews = new View[toggles.size()];

		int padH = NokiaDimens.dp(getResources(), 8);
		int padV = NokiaDimens.dp(getResources(), 6);
		int iconSize = NokiaDimens.dp(getResources(), 18);
		int iconMargin = NokiaDimens.dp(getResources(), 8);

		for (int i = 0; i < toggles.size(); i++) {
			final int index = i;
			final NokiaQuickToggleItem item = toggles.get(i);

			LinearLayout row = new LinearLayout(getContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setPadding(padH, padV, padH, padV);

			// 1. 复选框标记 [✓] 或 [ ] (普通模式) / 排序序号 (排序模式)
			// 与「快捷栏设置」页保持一致：复选框统一在行首，字号走 NokiaFontManager（dp 单位 + 全局像素字体）
			TextView checkTv = new TextView(getContext());
			NokiaFontManager.textSize(checkTv, 12);
			if (mode == MODE_SORT) {
				checkTv.setText((i + 1) + ".");
				checkTv.setTextColor(0xFF888888);
				checkTv.setMinWidth(NokiaDimens.dp(getResources(), 24));
			} else {
				checkTv.setText(item.enabled ? "[✓] " : "[ ] ");
				checkTv.setTextColor(item.enabled ? 0xFF00FF66 : 0xFF888888);
			}
			row.addView(checkTv);

			// 2. 开关图标（Material Icons 矢量字体）
			ImageView iv = new ImageView(getContext());
			LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(iconSize, iconSize);
			ivLp.rightMargin = iconMargin;
			iv.setLayoutParams(ivLp);
			int iconColor = (item.enabled || mode == MODE_SORT) ? 0xFFFFFFFF : 0xFF666666;
			// 传 Context：亮度图标会按当前档位（低/中/高/自动）显示不同图标
			iv.setImageDrawable(NokiaIcons.get(getContext(), item.getIconUnicode(getContext()), iconColor, 18));
			row.addView(iv);

			// 3. 开关名称
			TextView tvName = new TextView(getContext());
			tvName.setText(item.name);
			tvName.setTextSize(13);
			tvName.setTextColor(0xFFFFFFFF);
			LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
			tvName.setLayoutParams(nameLp);
			row.addView(tvName);

			// 4. 排序模式下的拎起状态提示
			if (mode == MODE_SORT && lifted && liftedIndex == i) {
				TextView tvLifted = new TextView(getContext());
				tvLifted.setText("已拎起");
				tvLifted.setTextSize(11);
				tvLifted.setTextColor(0xFF00E5FF);
				row.addView(tvLifted);
			}

			row.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					focusIndex = index;
					onSelect();
				}
			});

			itemViews[i] = row;
			toggleListLayout.addView(row);

			// 添加点线分隔
			if (i < toggles.size() - 1) {
				View line = new View(getContext());
				LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 1));
				line.setLayoutParams(lineLp);
				line.setBackground(new NokiaDashedLineDrawable(getResources(), 0x40FFFFFF, 2, 2));
				toggleListLayout.addView(line);
			}
		}

		updateFocusHighlight();
	}

	private void updateFocusHighlight() {
		if (itemViews == null) return;
		for (int i = 0; i < itemViews.length; i++) {
			View row = itemViews[i];
			if (row == null) continue;
			if (mode == MODE_SORT && lifted && liftedIndex == i) {
				// 拎起高亮：浅青蓝色半透明背景
				row.setBackgroundResource(R.drawable.bg_nokia_lifted);
			} else if (i == focusIndex) {
				// 正常焦点高亮
				row.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			} else {
				row.setBackgroundColor(Color.TRANSPARENT);
			}
		}
	}

	private void scrollToFocus() {
		if (toggleScroll == null || itemViews == null || focusIndex < 0 || focusIndex >= itemViews.length) return;
		final View target = itemViews[focusIndex];
		if (target == null) return;
		toggleScroll.post(new Runnable() {
			@Override
			public void run() {
				if (toggleScroll != null && target != null) {
					int scrollY = target.getTop() - NokiaDimens.dp(getResources(), 20);
					toggleScroll.smoothScrollTo(0, Math.max(0, scrollY));
				}
			}
		});
	}

	// ---- 按键处理 ----

	@Override
	public boolean onDirection(int direction) {
		if (toggles.isEmpty()) return false;
		if (mode == MODE_SORT && lifted) {
			// 排序拎起状态：上下键移动位置
			if (direction == NokiaKeyBinding.ACTION_UP) {
				if (liftedIndex > 0) {
					Collections.swap(toggles, liftedIndex, liftedIndex - 1);
					liftedIndex--;
					focusIndex = liftedIndex;
					storage.setToggles(toggles);
					rebuildList();
					scrollToFocus();
					return true;
				}
			} else if (direction == NokiaKeyBinding.ACTION_DOWN) {
				if (liftedIndex < toggles.size() - 1) {
					Collections.swap(toggles, liftedIndex, liftedIndex + 1);
					liftedIndex++;
					focusIndex = liftedIndex;
					storage.setToggles(toggles);
					rebuildList();
					scrollToFocus();
					return true;
				}
			}
			return true;
		}

		// 常规上下循环移动
		if (direction == NokiaKeyBinding.ACTION_UP) {
			focusIndex = (focusIndex - 1 + toggles.size()) % toggles.size();
			updateFocusHighlight();
			scrollToFocus();
			return true;
		} else if (direction == NokiaKeyBinding.ACTION_DOWN) {
			focusIndex = (focusIndex + 1) % toggles.size();
			updateFocusHighlight();
			scrollToFocus();
			return true;
		}
		return false;
	}

	@Override
	public boolean onSelect() {
		if (toggles.isEmpty()) return false;
		if (mode == MODE_SORT) {
			if (!lifted) {
				lifted = true;
				liftedIndex = focusIndex;
			} else {
				lifted = false;
				liftedIndex = -1;
				storage.setToggles(toggles);
			}
			updateStatusText();
			rebuildList();
			return true;
		}

		// 普通模式：快速切换勾选状态
		NokiaQuickToggleItem item = toggles.get(focusIndex);
		item.enabled = !item.enabled;
		storage.setToggles(toggles);
		rebuildList();
		updateStatusText();
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		if (mode == MODE_SORT) {
			// 排序模式下左软键为「完成」
			mode = MODE_NORMAL;
			lifted = false;
			liftedIndex = -1;
			storage.setToggles(toggles);
			rebuildList();
			updateStatusText();
			updateBottomBar();
			return true;
		}

		// 普通模式下弹出选项
		showOptionsMenu();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		if (mode == MODE_SORT) {
			// 排序模式下右软键也是退出排序
			mode = MODE_NORMAL;
			lifted = false;
			liftedIndex = -1;
			storage.setToggles(toggles);
			rebuildList();
			updateStatusText();
			updateBottomBar();
			return true;
		}
		return onBack();
	}

	@Override
	public boolean onBack() {
		if (mode == MODE_SORT) {
			mode = MODE_NORMAL;
			lifted = false;
			liftedIndex = -1;
			storage.setToggles(toggles);
			rebuildList();
			updateStatusText();
			updateBottomBar();
			return true;
		}
		return false;
	}

	private void showOptionsMenu() {
		final NokiaOptionsDialog[] dialogHolder = new NokiaOptionsDialog[1];
		List<NokiaOptionsDialog.OptionItem> items = buildOptionsItems(dialogHolder);
		dialogHolder[0] = NokiaOptionsDialog.show(getParentFragmentManager(), "选项", items);
	}

	private List<NokiaOptionsDialog.OptionItem> buildOptionsItems(final NokiaOptionsDialog[] dialogHolder) {
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();

		// 1. 调整排序
		items.add(new NokiaOptionsDialog.OptionItem(
				NokiaIcons.ICON_SORT,
				"调整排序",
				true,
				false,
				new Runnable() {
					@Override
					public void run() {
						enterSortMode();
					}
				}
		));

		// 2. 全选 / 取消全选
		final boolean all = isAllEnabled();
		items.add(new NokiaOptionsDialog.OptionItem(
				all ? NokiaIcons.ICON_CHECK_BOX_OUTLINE_BLANK : NokiaIcons.ICON_CHECK_BOX,
				all ? "取消全选" : "全选",
				true,
				true,
				new Runnable() {
					@Override
					public void run() {
						toggleSelectAll(!all);
						if (dialogHolder[0] != null) {
							dialogHolder[0].setItems(buildOptionsItems(dialogHolder));
						}
					}
				}
		));

		// 3. 恢复默认
		items.add(new NokiaOptionsDialog.OptionItem(
				NokiaIcons.ICON_RESTORE,
				"恢复默认",
				true,
				false,
				new Runnable() {
					@Override
					public void run() {
						storage.resetToDefaults();
						loadData();
						rebuildList();
						updateStatusText();
					}
				}
		));

		return items;
	}

	private boolean isAllEnabled() {
		if (toggles.isEmpty()) return false;
		for (int i = 0; i < toggles.size(); i++) {
			if (!toggles.get(i).enabled) return false;
		}
		return true;
	}

	private void toggleSelectAll(boolean selectAll) {
		for (int i = 0; i < toggles.size(); i++) {
			toggles.get(i).enabled = selectAll;
		}
		storage.setToggles(toggles);
		rebuildList();
		updateStatusText();
	}

	private void enterSortMode() {
		mode = MODE_SORT;
		lifted = false;
		liftedIndex = -1;
		rebuildList();
		updateStatusText();
		updateBottomBar();
	}

	// ---- NokiaPage 契约 ----

	@Override
	public String getPageTitle() {
		return mode == MODE_SORT ? "开关排序" : "快捷开关";
	}

	@Override
	public String getSoftLeftText() {
		return mode == MODE_SORT ? "完成" : "选项";
	}

	@Override
	public String getSoftRightText() {
		return mode == MODE_SORT ? "完成" : "返回";
	}
}
