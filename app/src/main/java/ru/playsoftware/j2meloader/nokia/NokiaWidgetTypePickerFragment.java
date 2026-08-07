package ru.playsoftware.j2meloader.nokia;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面组件设置 → 添加组件 → 组件类型选择页（S6）。
 * 列出全部 8 种组件类型，方向键导航，确认键选择类型后跳转：
 * 应用/网址/Activity快捷 → 各自编辑页（后续文档，暂未实现）；
 * 日历/内存/存储/使用时长/锁屏 → 直接添加组件并返回 S1。
 * <p>
 * 「使用时长」依赖 UsageStatsManager（API 21+），Android 4.4 及以下显示为灰色、
 * 光标跳过、确认键无响应。
 */
public class NokiaWidgetTypePickerFragment extends Fragment implements NokiaPage {

	private static final String TAG = "WidgetTypePicker";

	private static final int[] TYPE_IDS = {
			NokiaWidgetItem.TYPE_APP,
			NokiaWidgetItem.TYPE_URL,
			NokiaWidgetItem.TYPE_CALENDAR,
			NokiaWidgetItem.TYPE_ACTIVITY,
			NokiaWidgetItem.TYPE_MEMORY,
			NokiaWidgetItem.TYPE_STORAGE,
			NokiaWidgetItem.TYPE_USAGE,
			NokiaWidgetItem.TYPE_LOCK_SCREEN,
			NokiaWidgetItem.TYPE_BG_MANAGER,
	};

	private LinearLayout listLayout;
	private ScrollView scroll;
	private final List<Boolean> enabledList = new ArrayList<>();
	private View[] itemViews;
	private int focusIndex = -1;
	private View selectedView = null;

	private NokiaWidgetStorage storage;
	private Toast toast;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_nokia_widget_type_picker, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.scaleMidContent(view, true);
		// match_parent 根布局 + topAlign=true 的二次缩放陷阱：补动态高度调整
		host.fixMidContentHeight(view, true);

		View wall = host.findViewById(R.id.wallpaper);
		if (wall != null) {
			wall.setBackgroundResource(R.drawable.bg_nokia_menu);
		}
		// 底部菜单栏由 NokiaPage 声明 + host.refreshPageBar() 自动装配
		host.refreshPageBar();

		storage = new NokiaWidgetStorage(requireContext());
		listLayout = view.findViewById(R.id.typeListLayout);
		scroll = view.findViewById(R.id.typeScroll);

		// 约束 ScrollView 高度，使列表底部正好落在可视区底边
		view.post(() -> {
			if (scroll == null) return;
			View parent = (View) view.getParent();
			if (!(parent instanceof View)) {
				NokiaLog.w(TAG, "parent is not a View, skip height constraint");
				return;
			}
			int panelH = ((View) parent).getHeight();
			float scale = view.getScaleX();
			if (scale <= 0) scale = 1;
			int visibleH = (int) (panelH / scale);
			int headH = scroll.getTop();
			int scrollH = visibleH - headH;
			if (scrollH > 0) {
				ViewGroup.LayoutParams lp = scroll.getLayoutParams();
				lp.height = scrollH;
				scroll.setLayoutParams(lp);
				NokiaLog.i(TAG, "约束ScrollView高度: panelH=" + panelH
						+ " scale=" + scale + " visibleH=" + visibleH
						+ " headH=" + headH + " scrollH=" + scrollH);
			} else {
				NokiaLog.w(TAG, "scrollH <= 0, skip height constraint: scrollH=" + scrollH);
			}
		});

		buildTypeList();

		NokiaLog.i(TAG, "类型选择页初始化完成（使用时长可用=" + usageStatsAvailable() + "）");
	}

	private boolean usageStatsAvailable() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
	}

	private void buildTypeList() {
		if (listLayout == null) return;
		listLayout.removeAllViews();
		enabledList.clear();
		itemViews = new View[TYPE_IDS.length];

		for (int i = 0; i < TYPE_IDS.length; i++) {
			int type = TYPE_IDS[i];
			boolean enabled = !(type == NokiaWidgetItem.TYPE_USAGE && !usageStatsAvailable());
			enabledList.add(enabled);

			String label = NokiaWidgetItem.getTypeName(type);
			if (!enabled) {
				label += " (需Android 5.0+)";
			}

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 34)));
			row.setPadding(NokiaDimens.dp(getResources(), 6), NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 6), NokiaDimens.dp(getResources(), 2));
			if (enabled) {
				row.setClickable(true);
			}

			// 图标
			ImageView iv = new ImageView(requireContext());
			iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 22)));
			try {
				iv.setImageDrawable(ContextCompat.getDrawable(requireContext(),
						NokiaWidgetItem.getTypeIcon(type)));
			} catch (Exception ignored) {
				NokiaLog.w(TAG, "加载类型图标失败 type=" + type);
			}
			if (!enabled) {
				iv.setAlpha(0.5f);
			}
			row.addView(iv);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			// 名称
			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(label);
			NokiaDimens.textSize(tv, 12);
			tv.setSingleLine(true);
			tv.setEllipsize(TextUtils.TruncateAt.END);
			tv.setTextColor(enabled ? 0xFFFFFFFF : 0xFF666666);
			row.addView(tv);

			if (enabled) {
				final int index = i;
				row.setOnClickListener(v -> {
					setFocusIndex(index);
					onSelect();
				});
			}

			listLayout.addView(row);
			itemViews[i] = row;
		}

		// 默认聚焦第一个可选类型
		focusIndex = -1;
		int first = firstEnabledIndex();
		if (first >= 0) {
			setFocusIndex(first);
		} else {
			applyListHighlight();
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		if (direction == NokiaKeyBinding.ACTION_LEFT || direction == NokiaKeyBinding.ACTION_RIGHT) {
			return true; // 类型列表不响应左右
		}
		if (direction == NokiaKeyBinding.ACTION_UP) {
			int next = focusIndex - 1;
			while (next >= 0 && !enabledList.get(next)) next--;
			if (next >= 0) setFocusIndex(next);
			return true;
		}
		if (direction == NokiaKeyBinding.ACTION_DOWN) {
			int next = focusIndex + 1;
			while (next < TYPE_IDS.length && !enabledList.get(next)) next++;
			if (next < TYPE_IDS.length) setFocusIndex(next);
			return true;
		}
		return true;
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= TYPE_IDS.length) return false;
		if (!enabledList.get(focusIndex)) {
			NokiaLog.d(TAG, "确认键：当前类型不可选（跳过）");
			return true;
		}
		onTypeSelected(TYPE_IDS[focusIndex]);
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		return false; // 左软键（空）
	}

	@Override
	public boolean onSoftRight() {
		NokiaLog.i(TAG, "右软键：返回桌面组件设置");
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
		return "选择组件类型";
	}

	@Override
	public String getSoftLeftText() {
		return null;
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	// ---- 类型选择后的跳转 ----

	private void onTypeSelected(int type) {
		NokiaLog.i(TAG, "选择类型: " + NokiaWidgetItem.getTypeName(type));
		switch (type) {
			case NokiaWidgetItem.TYPE_APP:
				// 应用选择页（添加模式），见 docs/2-应用类组件添加界面设计.md
				((NokiaDesktopActivity) requireActivity())
						.openFragment(NokiaWidgetAppPickerFragment.newAddMode());
				break;
			case NokiaWidgetItem.TYPE_URL:
				// 网址编辑页（添加模式），见 docs/3-网址类组件添加编辑界面设计.md
				((NokiaDesktopActivity) requireActivity())
						.openFragment(NokiaWidgetUrlEditFragment.newAddMode());
				break;
			case NokiaWidgetItem.TYPE_ACTIVITY:
				// Activity 快捷：进入步骤1（选择应用），详见 docs/6-Activity快捷组件添加编辑界面设计.md
				((NokiaDesktopActivity) requireActivity())
						.openFragment(NokiaWidgetAppPickerFragment.newActivityAddMode());
				break;
			default:
				// 日历/内存/存储/使用时长：直接添加并返回 S1
				if (storage.isFull()) {
					NokiaLog.w(TAG, "组件已达上限 " + NokiaWidgetItem.MAX_COUNT + "，拒绝添加");
					showToast("组件已达上限");
					return;
				}
				NokiaWidgetItem item = new NokiaWidgetItem(type, NokiaWidgetItem.getDefaultLabel(type));
				storage.addWidget(item);
				NokiaLog.i(TAG, "已添加组件: " + item.label + " type=" + type);
				((NokiaDesktopActivity) requireActivity()).exitCurrent();
				break;
		}
	}

	// ---- 焦点管理 ----

	private int firstEnabledIndex() {
		for (int i = 0; i < enabledList.size(); i++) {
			if (enabledList.get(i)) return i;
		}
		return -1;
	}

	private void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
		if (!enabledList.get(index)) return;
		clearListHighlight();
		focusIndex = index;
		applyListHighlight();
		scrollToVisible(index);
	}

	private void scrollToVisible(int index) {
		if (scroll == null || itemViews == null || index < 0 || index >= itemViews.length) return;
		View item = itemViews[index];
		if (item == null) return;
		scroll.post(() -> {
			int scrollY = scroll.getScrollY();
			int itemTop = item.getTop();
			int itemBottom = item.getBottom();
			int svHeight = scroll.getHeight();
			if (svHeight <= 0) return;
			if (itemTop < scrollY) {
				scroll.smoothScrollTo(0, itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				scroll.smoothScrollTo(0, itemBottom - svHeight);
			}
		});
	}

	private void clearListHighlight() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyListHighlight() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
		if (itemViews == null || focusIndex < 0 || focusIndex >= itemViews.length) return;
		if (!enabledList.get(focusIndex)) return;
		itemViews[focusIndex].setBackgroundResource(R.drawable.bg_nokia_selected_dark);
		selectedView = itemViews[focusIndex];
	}

	private void showToast(String msg) {
		if (toast != null) {
			toast.cancel();
		}
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		NokiaLog.i(TAG, "Toast: " + msg);
	}


	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
