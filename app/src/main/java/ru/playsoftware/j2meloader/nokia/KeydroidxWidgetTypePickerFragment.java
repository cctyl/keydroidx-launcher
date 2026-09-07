package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxIcons;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
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

import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;
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
public class KeydroidxWidgetTypePickerFragment extends KeydroidxPageFragment {

	private static final String TAG = "WidgetTypePicker";

	private static final int[] TYPE_IDS = {
			KeydroidxWidgetItem.TYPE_APP,
			KeydroidxWidgetItem.TYPE_URL,
			KeydroidxWidgetItem.TYPE_CALENDAR,
			KeydroidxWidgetItem.TYPE_ACTIVITY,
			KeydroidxWidgetItem.TYPE_MEMORY,
			KeydroidxWidgetItem.TYPE_STORAGE,
			KeydroidxWidgetItem.TYPE_USAGE,
			KeydroidxWidgetItem.TYPE_LOCK_SCREEN,
			KeydroidxWidgetItem.TYPE_BG_MANAGER,
			KeydroidxWidgetItem.TYPE_IP,
			KeydroidxWidgetItem.TYPE_QS_TILE,
			KeydroidxWidgetItem.TYPE_MUSIC_PLAYER,
	};

	private LinearLayout listLayout;
	private ScrollView scroll;
	private final List<Boolean> enabledList = new ArrayList<>();
	private View[] itemViews;
	private int focusIndex = -1;
	private View selectedView = null;

	private KeydroidxWidgetStorage storage;
	private Toast toast;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_widget_type_picker;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		storage = new KeydroidxWidgetStorage(requireContext());
		listLayout = view.findViewById(R.id.typeListLayout);
		scroll = view.findViewById(R.id.typeScroll);

		// 约束 ScrollView 高度，使列表底部正好落在可视区底边
		view.post(() -> {
			if (scroll == null) return;
			View parent = (View) view.getParent();
			if (!(parent instanceof View)) {
				KeydroidxLog.w(TAG, "parent is not a View, skip height constraint");
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
				KeydroidxLog.i(TAG, "约束ScrollView高度: panelH=" + panelH
						+ " scale=" + scale + " visibleH=" + visibleH
						+ " headH=" + headH + " scrollH=" + scrollH);
			} else {
				KeydroidxLog.w(TAG, "scrollH <= 0, skip height constraint: scrollH=" + scrollH);
			}
		});

		buildTypeList();

		KeydroidxLog.i(TAG, "类型选择页初始化完成（使用时长可用=" + usageStatsAvailable() + "）");
	}

	private boolean usageStatsAvailable() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
	}

	private boolean qsTileAvailable() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
	}

	private void buildTypeList() {
		if (listLayout == null) return;
		listLayout.removeAllViews();
		enabledList.clear();
		itemViews = new View[TYPE_IDS.length];

		for (int i = 0; i < TYPE_IDS.length; i++) {
			int type = TYPE_IDS[i];
			boolean enabled = true;
			if (type == KeydroidxWidgetItem.TYPE_USAGE && !usageStatsAvailable()) {
				enabled = false;
			} else if (type == KeydroidxWidgetItem.TYPE_QS_TILE && !qsTileAvailable()) {
				enabled = false;
			}
			enabledList.add(enabled);

			String label = KeydroidxWidgetItem.getTypeName(type);
			if (type == KeydroidxWidgetItem.TYPE_USAGE && !enabled) {
				label += " (需Android 5.0+)";
			} else if (type == KeydroidxWidgetItem.TYPE_QS_TILE && !enabled) {
				label += " (需Android 7.0+)";
			}
			// 后台管理组件：Android 5.0+ 需 mini_shizuku 才能准确枚举/清理；
			// 未激活时仍可选可添加（B 方案），但标注提示，加完在桌面显示“未激活”
			if (type == KeydroidxWidgetItem.TYPE_BG_MANAGER
					&& !KeydroidxBgManagerHelper.isBgManagerAvailable()) {
				label += " (需激活)";
			}

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, KeydroidxDimens.dp(getResources(), 34)));
			row.setPadding(KeydroidxDimens.dp(getResources(), 6), KeydroidxDimens.dp(getResources(), 2), KeydroidxDimens.dp(getResources(), 6), KeydroidxDimens.dp(getResources(), 2));
			if (enabled) {
				row.setClickable(true);
			}

			// 图标（统一使用 Material Icons 矢量字体）
			ImageView iv = new ImageView(requireContext());
			iv.setLayoutParams(new LinearLayout.LayoutParams(KeydroidxDimens.dp(getResources(), 22), KeydroidxDimens.dp(getResources(), 22)));
			String unicode = KeydroidxWidgetItem.getTypeIconUnicode(type);
			int iconColor = enabled ? 0xFFFFFFFF : 0xFF888888;
			iv.setImageDrawable(KeydroidxIcons.get(requireContext(), unicode, iconColor, 20));
			if (!enabled) {
				iv.setAlpha(0.5f);
			}
			row.addView(iv);

			// 间距
			row.addView(spaceView(KeydroidxDimens.dp(getResources(), 8), 1));

			// 名称
			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(label);
			KeydroidxFontManager.textSize(tv, 12);
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

	// ---- KeydroidxFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		if (direction == KeydroidxKeyBinding.ACTION_LEFT || direction == KeydroidxKeyBinding.ACTION_RIGHT) {
			return true; // 类型列表不响应左右
		}
		if (direction == KeydroidxKeyBinding.ACTION_UP) {
			int next = focusIndex - 1;
			while (next >= 0 && !enabledList.get(next)) next--;
			if (next >= 0) setFocusIndex(next);
			return true;
		}
		if (direction == KeydroidxKeyBinding.ACTION_DOWN) {
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
			KeydroidxLog.d(TAG, "确认键：当前类型不可选（跳过）");
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
		KeydroidxLog.i(TAG, "右软键：返回桌面组件设置");
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- KeydroidxPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

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
		KeydroidxLog.i(TAG, "选择类型: " + KeydroidxWidgetItem.getTypeName(type));
		switch (type) {
			case KeydroidxWidgetItem.TYPE_APP:
				// 应用选择页（添加模式），见 docs/2-应用类组件添加界面设计.md
				((KeydroidxDesktopActivity) requireActivity())
						.openFragment(KeydroidxWidgetAppPickerFragment.newAddMode());
				break;
			case KeydroidxWidgetItem.TYPE_URL:
				// 网址编辑页（添加模式），见 docs/3-网址类组件添加编辑界面设计.md
				((KeydroidxDesktopActivity) requireActivity())
						.openFragment(KeydroidxWidgetUrlEditFragment.newAddMode());
				break;
			case KeydroidxWidgetItem.TYPE_ACTIVITY:
				// Activity 快捷：进入步骤1（选择应用），详见 docs/6-Activity快捷组件添加编辑界面设计.md
				((KeydroidxDesktopActivity) requireActivity())
						.openFragment(KeydroidxWidgetAppPickerFragment.newActivityAddMode());
				break;
			case KeydroidxWidgetItem.TYPE_QS_TILE:
				// 快捷开关：选择已安装的第三方/系统 QS Tile
				((KeydroidxDesktopActivity) requireActivity())
						.openFragment(new KeydroidxWidgetTilePickerFragment());
				break;
			default:
				// 日历/内存/存储/使用时长：直接添加并返回 S1
				if (storage.isFull()) {
					KeydroidxLog.w(TAG, "组件已达上限 " + KeydroidxWidgetItem.MAX_COUNT + "，拒绝添加");
					showToast("组件已达上限");
					return;
				}
				KeydroidxWidgetItem item = new KeydroidxWidgetItem(type, KeydroidxWidgetItem.getDefaultLabel(type));
				storage.addWidget(item);
				KeydroidxLog.i(TAG, "已添加组件: " + item.label + " type=" + type);
				((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
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
		itemViews[focusIndex].setBackground(KeydroidxTheme.createSelectionDrawable(requireContext(), 4));
		selectedView = itemViews[focusIndex];
	}

	private void showToast(String msg) {
		if (toast != null) {
			toast.cancel();
		}
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		KeydroidxLog.i(TAG, "Toast: " + msg);
	}


	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
