package ru.playsoftware.j2meloader.nokia;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 诺基亚风格通用「选项」弹窗（完整版）。
 * <p>
 * 收敛了原来的 {@code NokiaAppOptionsDialog}（应用选项）、{@code NokiaWidgetOptionsDialog}
 * （桌面组件选项）、{@code NokiaWidgetDeleteDialog}（删除子菜单）三个弹窗：
 * <ul>
 *   <li>数据模型 {@link OptionItem}：图标 + 文案 + 是否可用 + 是否点击后不关闭 + 点击动作；</li>
 *   <li>静态入口 {@link #show(FragmentManager, String, List)} 打开，返回实例以便调用 {@link #setItems(List)} 动态刷新；</li>
 *   <li>{@code keepOpen=true} 的项点击后不关闭弹窗，由宿主更新数据后调用 {@link #setItems(List)} 重建列表并刷新文案（全选/取消全选场景）；</li>
 *   <li>禁用项（{@code enabled=false}）灰显，方向键自动跳过；</li>
 *   <li>统一窗口配置（底部锚定、透明背景）、行高亮（{@code bg_nokia_selected_dark}）、
 *       {@code forceNonTouchMode} 与按键分发。</li>
 * </ul>
 * <p>
 * 按键规范：弹窗是独立 Window，自行接入 {@link NokiaKeyBinding}（禁止写死 keyCode）；
 * 返回键由弹窗单独处理；底部软键栏只显示文字标签，无高亮/焦点逻辑。
 */
public class NokiaOptionsDialog extends DialogFragment {
	private static final String TAG = "NokiaOptions";
	private static final String ARG_TITLE = "title";

	/**
	 * 弹窗选项数据模型。
	 */
	public static class OptionItem {
		/** 图标资源 id，0 表示无图标。 */
		public final int icon;
		/** 文案（{@link #setItems(List)} 后可整体替换刷新）。 */
		public final String label;
		/** false=灰色不可选，方向键自动跳过。 */
		public final boolean enabled;
		/** true=点击后不关闭弹窗（用于全选/取消全选后刷新文案）。 */
		public final boolean keepOpen;
		/** 点击动作。 */
		public final Runnable action;

		public OptionItem(int icon, String label, boolean enabled, boolean keepOpen, Runnable action) {
			this.icon = icon;
			this.label = label;
			this.enabled = enabled;
			this.keepOpen = keepOpen;
			this.action = action;
		}
	}

	private String title = "";
	private List<OptionItem> items = new ArrayList<>();
	private LinearLayout listContainer;
	private LinearLayout[] optionRows;
	private int focusIndex = -1;

	/**
	 * 静态入口：创建并显示选项弹窗，返回实例以便后续 {@link #setItems(List)} 刷新。
	 */
	public static NokiaOptionsDialog show(@NonNull FragmentManager fm, String title,
										  @NonNull List<OptionItem> items) {
		NokiaOptionsDialog dialog = new NokiaOptionsDialog();
		Bundle args = new Bundle();
		args.putString(ARG_TITLE, title);
		dialog.setArguments(args);
		dialog.setItemsInternal(items);
		dialog.show(fm, "NokiaOptions");
		return dialog;
	}

	/**
	 * 动态刷新整个选项列表（全选/取消全选后更新文案与可用状态）。
	 * 重建列表容器并修正焦点（跳过禁用项），不重新膨胀整个布局。
	 */
	public void setItems(@NonNull List<OptionItem> newItems) {
		setItemsInternal(newItems);
		rebuildList();
	}

	private void setItemsInternal(@NonNull List<OptionItem> newItems) {
		items = new ArrayList<>(newItems);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		if (args != null) {
			title = args.getString(ARG_TITLE, "");
		}
		NokiaLog.i(TAG, "onCreateDialog: 创建通用选项弹窗，title=" + title + " options=" + items.size());

		Dialog dialog = new Dialog(requireActivity());
		dialog.setContentView(R.layout.dialog_nokia_widget_options);
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		if (dialog.getWindow() != null) {
			dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			dialog.getWindow().setGravity(Gravity.BOTTOM);
			dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
		}

		TextView titleView = dialog.findViewById(R.id.widgetOptionsTitle);
		if (titleView != null) {
			titleView.setText(title);
		}

		listContainer = dialog.findViewById(R.id.widgetOptionsList);
		rebuildList();

		// 接入用户自定义按键映射（禁止写死 keyCode）
		final NokiaKeyBinding keyBinding =
				((NokiaDesktopActivity) requireActivity()).getKeyBinding();
		dialog.setOnKeyListener((d, keyCode, event) -> {
			if (event.getAction() != KeyEvent.ACTION_DOWN) {
				return true; // 消费抬起事件
			}
			// 返回键由弹窗自己处理（NokiaKeyBinding 不管 BACK）
			if (keyCode == KeyEvent.KEYCODE_BACK) {
				NokiaLog.i(TAG, "返回键：关闭选项弹窗");
				dismiss();
				return true;
			}
			int action = keyBinding.resolveAction(event);
			switch (action) {
				case NokiaKeyBinding.ACTION_UP:
					moveFocus(-1);
					return true;
				case NokiaKeyBinding.ACTION_DOWN:
					moveFocus(1);
					return true;
				case NokiaKeyBinding.ACTION_SELECT:
				case NokiaKeyBinding.ACTION_SOFT_LEFT:
					// 左软键（选择）等同确认键：执行当前选项
					trigger(focusIndex);
					return true;
				case NokiaKeyBinding.ACTION_SOFT_RIGHT:
					NokiaLog.i(TAG, "右软键：关闭选项弹窗");
					dismiss();
					return true;
				case NokiaKeyBinding.ACTION_LEFT:
				case NokiaKeyBinding.ACTION_RIGHT:
					return true; // 菜单为纵向列表，左右无效果
				default:
					return false;
			}
		});

		// 默认焦点在第一个可用选项
		setFocus(firstEnabledIndex());

		// Android 12+：Dialog 窗口首个导航键会被触摸模式吞掉，show 后强制退出该状态
		dialog.setOnShowListener(d -> NokiaDialogFocus.forceNonTouchMode(dialog));

		return dialog;
	}

	/** 重建列表容器（onCreateDialog 首次构建 / setItems 动态刷新共用）。 */
	private void rebuildList() {
		if (listContainer == null) return;
		listContainer.removeAllViews();
		optionRows = new LinearLayout[items.size()];
		for (int i = 0; i < items.size(); i++) {
			final OptionItem item = items.get(i);
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 40)));
			row.setPadding(NokiaDimens.dp(getResources(), 14), 0, NokiaDimens.dp(getResources(), 14), 0);

			if (item.icon != 0) {
				ImageView iv = new ImageView(requireContext());
				iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 24), NokiaDimens.dp(getResources(), 24)));
				try {
					iv.setImageResource(item.icon);
				} catch (Exception ignored) {}
				if (!item.enabled) {
					iv.setAlpha(0.5f);
				}
				row.addView(iv);
			}

			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			if (item.icon != 0) {
				tv.setPadding(NokiaDimens.dp(getResources(), 10), 0, 0, 0);
			}
			tv.setText(item.label);
			NokiaDimens.textSize(tv, 14);
			tv.setSingleLine(true);
			tv.setTextColor(item.enabled ? 0xFFFFFFFF : 0xFF666666);
			row.addView(tv);

			if (item.enabled) {
				final int idx = i;
				row.setClickable(true);
				row.setOnClickListener(v -> {
					setFocus(idx);
					trigger(idx);
				});
			}
			listContainer.addView(row);
			optionRows[i] = row;
		}
		// 刷新后修正焦点：若当前焦点失效（禁用/越界）则回到第一个可用项
		if (focusIndex < 0 || focusIndex >= items.size()
				|| !items.get(focusIndex).enabled) {
			setFocus(firstEnabledIndex());
		} else {
			// 焦点有效但行对象已重建，重刷高亮
			applyFocus(focusIndex);
		}
	}

	private void moveFocus(int step) {
		int next = focusIndex + step;
		while (next >= 0 && next < items.size() && !items.get(next).enabled) {
			next += step;
		}
		if (next >= 0 && next < items.size()) {
			setFocus(next);
		}
	}

	private int firstEnabledIndex() {
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).enabled) return i;
		}
		return items.isEmpty() ? -1 : 0;
	}

	private void setFocus(int index) {
		focusIndex = index;
		applyFocus(index);
	}

	private void applyFocus(int index) {
		if (optionRows == null) return;
		for (int i = 0; i < optionRows.length; i++) {
			if (optionRows[i] == null) continue;
			boolean selected = (i == index) && items.get(i).enabled;
			optionRows[i].setBackgroundResource(
					selected ? R.drawable.bg_nokia_selected_dark : 0);
		}
	}

	private void trigger(int index) {
		if (index < 0 || index >= items.size()) return;
		OptionItem item = items.get(index);
		if (!item.enabled) return;
		NokiaLog.i(TAG, "执行选项: " + index + " (" + item.label + ") keepOpen=" + item.keepOpen);
		if (item.action != null) {
			item.action.run();
		}
		if (!item.keepOpen) {
			dismiss();
		}
	}

}
