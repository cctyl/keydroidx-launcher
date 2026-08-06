package ru.playsoftware.j2meloader.nokia;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * 桌面组件设置主界面。承载设计文档中的全部状态：
 * S1 正常浏览 / S2 左软键弹出选项菜单 / S3 删除模式 / S4 排序光标态 / S5 排序拎起态。
 * S6（类型选择）由 {@link NokiaWidgetTypePickerFragment} 实现。
 * <p>
 * S2 选项菜单与 S3 删除子菜单均为底部弹出的 DialogFragment
 * （样式与「功能表→应用程序→选中JAR的选项菜单」一致），弹窗内已接入 NokiaKeyBinding。
 */
public class NokiaWidgetSettingsFragment extends Fragment implements NokiaPage {

	private static final String TAG = "WidgetSettings";

	private static final int MODE_NORMAL = 0;   // S1
	private static final int MODE_DELETE = 1;   // S3
	private static final int MODE_SORT = 2;     // S4 / S5

	private LinearLayout listLayout;
	private ScrollView scroll;
	private TextView tvStatus;

	private NokiaWidgetStorage storage;
	private final List<NokiaWidgetItem> widgets = new ArrayList<>();
	private View[] itemViews;
	private int focusIndex = -1;
	private View selectedView = null;

	private int mode = MODE_NORMAL;

	// 排序拎起态（S5）
	private boolean lifted = false;
	private int liftedIndex = -1;

	// 删除模式勾选（与 widgets 下标一一对应）
	private final List<Boolean> checked = new ArrayList<>();

	private Toast toast;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_nokia_widget_settings, container, false);
	}

	@Override
	public void onResume() {
		super.onResume();
		if (storage == null) return;
		widgets.clear();
		widgets.addAll(storage.getWidgets());
		NokiaLog.i(TAG, "onResume 重载组件列表，数量=" + widgets.size());
		rebuildList();
		updateBottomBar();
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

		storage = new NokiaWidgetStorage(requireContext());
		listLayout = view.findViewById(R.id.widgetListLayout);
		scroll = view.findViewById(R.id.widgetScroll);
		tvStatus = view.findViewById(R.id.tvWidgetStatus);

		// 运行时约束 ScrollView 高度，使列表底部正好落在可视区底边（同快捷栏设置）
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

		widgets.clear();
		widgets.addAll(storage.getWidgets());
		NokiaLog.i(TAG, "桌面组件设置初始化完成，组件数=" + widgets.size());

		mode = MODE_NORMAL;
		rebuildList();
		updateBottomBar();
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		dismissToastIfShown();
		switch (mode) {
			case MODE_NORMAL:
			case MODE_DELETE:
				return onListDirection(direction);
			case MODE_SORT:
				return lifted ? onLiftedDirection(direction) : onListDirection(direction);
			default:
				return false;
		}
	}

	@Override
	public boolean onSelect() {
		dismissToastIfShown();
		switch (mode) {
			case MODE_NORMAL:
				onConfirmWidget();
				return true;
			case MODE_DELETE:
				if (focusIndex >= 0 && focusIndex < widgets.size()) {
					toggleChecked(focusIndex);
				}
				return true;
			case MODE_SORT:
				if (lifted) {
					dropLifted();
				} else {
					liftCurrent();
				}
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean onSoftLeft() {
		dismissToastIfShown();
		switch (mode) {
			case MODE_NORMAL:
				showOptionsDialog();
				return true;
			case MODE_DELETE:
				showDeleteDialog();
				return true;
			case MODE_SORT:
				finishSort();
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean onSoftRight() {
		dismissToastIfShown();
		switch (mode) {
			case MODE_NORMAL:
				exitCurrent();
				return true;
			case MODE_DELETE:
				backToNormal();
				return true;
			case MODE_SORT:
				return true; // 排序模式右软键无按钮（完成在左）
			default:
				return false;
		}
	}

	@Override
	public boolean onBack() {
		dismissToastIfShown();
		switch (mode) {
			case MODE_NORMAL:
				exitCurrent();
				return true;
			case MODE_DELETE:
				backToNormal();
				return true;
			case MODE_SORT:
				finishSort();
				return true;
			default:
				return false;
		}
	}

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return "桌面组件设置";
	}

	@Override
	public String getSoftLeftText() {
		switch (mode) {
			case MODE_DELETE:
				return "选项";
			case MODE_SORT:
				return "完成";
			default:
				return "选项";
		}
	}

	@Override
	public String getSoftRightText() {
		switch (mode) {
			case MODE_DELETE:
				return "取消";
			case MODE_SORT:
				return null; // 排序模式右软键无按钮
			default:
				return "返回";
		}
	}

	// ---- S1 确认键行为 ----

	private void onConfirmWidget() {
		if (widgets.isEmpty()) return;
		if (focusIndex < 0 || focusIndex >= widgets.size()) return;
		NokiaWidgetItem item = widgets.get(focusIndex);
		if (item.isEditable()) {
			if (item.type == NokiaWidgetItem.TYPE_APP) {
				// 应用类组件：进入应用选择页（编辑模式），换绑应用
				NokiaLog.i(TAG, "确认键：应用类组件进入编辑（换绑） label=" + item.label);
				((NokiaDesktopActivity) requireActivity())
						.openFragment(NokiaWidgetAppPickerFragment.newEditMode(focusIndex));
			} else if (item.type == NokiaWidgetItem.TYPE_URL) {
				// 网址类组件：进入网址编辑页（编辑模式）
				NokiaLog.i(TAG, "确认键：网址类组件进入编辑 label=" + item.label);
				((NokiaDesktopActivity) requireActivity())
						.openFragment(NokiaWidgetUrlEditFragment.newEditMode(focusIndex));
			} else if (item.type == NokiaWidgetItem.TYPE_ACTIVITY) {
				// Activity快捷组件：进入步骤1应用选择页（编辑模式），从头开始选
				// 详见 docs/6-Activity快捷组件添加编辑界面设计.md
				NokiaLog.i(TAG, "确认键：Activity快捷组件进入编辑（从步骤1开始） label=" + item.label);
				((NokiaDesktopActivity) requireActivity())
						.openFragment(NokiaWidgetAppPickerFragment.newActivityEditMode(focusIndex));
			}
		} else {
			NokiaLog.i(TAG, "确认键：该组件不可编辑 label=" + item.label);
			showToast("该组件不可编辑");
		}
	}

	// ---- S2 选项菜单（底部弹出弹窗） ----

	private void showOptionsDialog() {
		boolean canAdd = widgets.size() < NokiaWidgetItem.MAX_COUNT;
		boolean canDelete = !widgets.isEmpty();
		boolean canSort = widgets.size() > 1;
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_add,
				"添加组件", canAdd, false, this::openTypePicker));
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_delete,
				"删除组件", canDelete, false, this::enterDeleteMode));
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_sort_by_size,
				"组件排序", canSort, false, this::enterSortMode));
		NokiaOptionsDialog.show(getParentFragmentManager(), "选项", items);
		NokiaLog.i(TAG, "弹出选项菜单弹窗: canAdd=" + canAdd + " canDelete=" + canDelete + " canSort=" + canSort);
	}

	private void openTypePicker() {
		NokiaLog.i(TAG, "打开组件类型选择页（S6）");
		((NokiaDesktopActivity) requireActivity()).openFragment(new NokiaWidgetTypePickerFragment());
	}

	// ---- S3 删除模式 ----

	private void enterDeleteMode() {
		mode = MODE_DELETE;
		checked.clear();
		for (int i = 0; i < widgets.size(); i++) {
			checked.add(false);
		}
		rebuildList();
		updateBottomBar();
		NokiaLog.i(TAG, "进入删除模式，共 " + widgets.size() + " 项");
	}

	private void showDeleteDialog() {
		// 全选/取消全选项 keepOpen=true，点击后不关闭并刷新文案（删除已选按钮计数随之更新）
		final NokiaOptionsDialog[] holder = new NokiaOptionsDialog[1];
		List<NokiaOptionsDialog.OptionItem> items = buildDeleteDialogItems(holder);
		holder[0] = NokiaOptionsDialog.show(getParentFragmentManager(), "删除", items);
		NokiaLog.i(TAG, "弹出删除子菜单弹窗");
	}

	private List<NokiaOptionsDialog.OptionItem> buildDeleteDialogItems(final NokiaOptionsDialog[] holder) {
		boolean all = allChecked();
		int count = checkedCount();
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(0,
				all ? "取消全选" : "全选",
				true, true, () -> {
			toggleSelectAll();
			// 保持弹窗打开，动态刷新全选文案与删除计数
			if (holder[0] != null) {
				holder[0].setItems(buildDeleteDialogItems(holder));
			}
		}));
		items.add(new NokiaOptionsDialog.OptionItem(android.R.drawable.ic_menu_delete,
				"删除已选(" + count + ")", count > 0, false, this::deleteSelected));
		return items;
	}

	private void toggleChecked(int index) {
		if (index < 0 || index >= checked.size()) return;
		checked.set(index, !checked.get(index));
		if (itemViews != null && index < itemViews.length && itemViews[index] != null) {
			View row = itemViews[index];
			TextView check = row.findViewWithTag("check_" + index);
			if (check != null) {
				boolean isChecked = checked.get(index);
				check.setText(isChecked ? "[✓]" : "[ ]");
				check.setTextColor(isChecked ? 0xFF4CAF50 : 0xFF888888);
			}
		}
		NokiaLog.d(TAG, "切换勾选 index=" + index + " -> " + checked.get(index));
	}

	private boolean allChecked() {
		if (checked.isEmpty()) return false;
		for (Boolean b : checked) {
			if (!b) return false;
		}
		return true;
	}

	private int checkedCount() {
		int count = 0;
		for (Boolean b : checked) {
			if (b) count++;
		}
		return count;
	}

	private void toggleSelectAll() {
		boolean all = allChecked();
		for (int i = 0; i < checked.size(); i++) {
			checked.set(i, !all);
		}
		rebuildList();
		NokiaLog.i(TAG, "全选/取消全选: 当前勾选 " + checkedCount() + " 项");
	}

	private void deleteSelected() {
		List<NokiaWidgetItem> toRemove = new ArrayList<>();
		for (int i = 0; i < checked.size(); i++) {
			if (checked.get(i)) {
				toRemove.add(widgets.get(i));
			}
		}
		if (toRemove.isEmpty()) {
			NokiaLog.w(TAG, "删除已选：无勾选项");
			return;
		}
		NokiaLog.i(TAG, "删除已选组件 " + toRemove.size() + " 个");
		storage.removeWidgets(toRemove);
		widgets.removeAll(toRemove);
		NokiaLog.i(TAG, "删除后剩余组件 " + widgets.size() + " 个");
		backToNormal();
	}

	private void backToNormal() {
		mode = MODE_NORMAL;
		lifted = false;
		liftedIndex = -1;
		checked.clear();
		rebuildList();
		updateBottomBar();
		NokiaLog.i(TAG, "回到 S1 正常浏览");
	}

	// ---- S4 / S5 排序模式 ----

	private void enterSortMode() {
		mode = MODE_SORT;
		lifted = false;
		liftedIndex = -1;
		rebuildList();
		updateBottomBar();
		showToast("按下确认键选中组件进行排序");
		NokiaLog.i(TAG, "进入排序模式（光标态）");
	}

	private void liftCurrent() {
		if (focusIndex < 0 || focusIndex >= widgets.size()) return;
		lifted = true;
		liftedIndex = focusIndex;
		applyListHighlight();
		NokiaLog.i(TAG, "拎起行 index=" + liftedIndex);
	}

	private void dropLifted() {
		if (!lifted) return;
		focusIndex = liftedIndex;
		lifted = false;
		liftedIndex = -1;
		applyListHighlight();
		NokiaLog.i(TAG, "放下行 -> 光标落回 index=" + focusIndex);
	}

	private void swapWidgets(int a, int b) {
		NokiaWidgetItem tmp = widgets.get(a);
		widgets.set(a, widgets.get(b));
		widgets.set(b, tmp);
		NokiaLog.d(TAG, "交换行 " + a + " <-> " + b);
	}

	private void finishSort() {
		lifted = false;
		liftedIndex = -1;
		storage.setWidgets(widgets);
		NokiaLog.i(TAG, "保存组件排序并退出排序模式");
		backToNormal();
	}

	// ---- 方向键 ----

	private boolean onListDirection(int direction) {
		int count = itemViews != null ? itemViews.length : 0;
		if (count == 0) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > 0) setFocusIndex(focusIndex - 1);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < count - 1) setFocusIndex(focusIndex + 1);
				return true;
			default:
				return true;
		}
	}

	private boolean onLiftedDirection(int direction) {
		if (liftedIndex < 0) return true;
		if (direction == NokiaKeyBinding.ACTION_UP && liftedIndex > 0) {
			swapWidgets(liftedIndex, liftedIndex - 1);
			liftedIndex--;
			rebuildList();
			applyListHighlight();
			return true;
		}
		if (direction == NokiaKeyBinding.ACTION_DOWN && liftedIndex < widgets.size() - 1) {
			swapWidgets(liftedIndex, liftedIndex + 1);
			liftedIndex++;
			rebuildList();
			applyListHighlight();
			return true;
		}
		return true; // 边界不环绕
	}

	// ---- 构建列表 ----

	private void rebuildList() {
		if (listLayout == null) return;
		listLayout.removeAllViews();

		if (widgets.isEmpty()) {
			TextView empty = new TextView(requireContext());
			empty.setText("暂无组件，按左软键添加");
			empty.setTextColor(0xFFAAAAAA);
			NokiaDimens.textSize(empty, 12);
			empty.setGravity(Gravity.CENTER);
			empty.setPadding(0, NokiaDimens.dp(getResources(), 20), 0, 0);
			listLayout.addView(empty);
			itemViews = new View[0];
			focusIndex = -1;
			selectedView = null;
			updateStatusText();
			return;
		}

		itemViews = new View[widgets.size()];
		for (int i = 0; i < widgets.size(); i++) {
			NokiaWidgetItem item = widgets.get(i);

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 34)));
			row.setPadding(NokiaDimens.dp(getResources(), 6), NokiaDimens.dp(getResources(), 2), NokiaDimens.dp(getResources(), 6), NokiaDimens.dp(getResources(), 2));
			row.setClickable(true);

			// 删除模式：行首勾选标记
			if (mode == MODE_DELETE) {
				TextView tvCheck = new TextView(requireContext());
				tvCheck.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 24), NokiaDimens.dp(getResources(), 24)));
				tvCheck.setGravity(Gravity.CENTER);
				NokiaDimens.textSize(tvCheck, 13);
				boolean isChecked = i < checked.size() && checked.get(i);
				tvCheck.setText(isChecked ? "[✓]" : "[ ]");
				tvCheck.setTextColor(isChecked ? 0xFF4CAF50 : 0xFF888888);
				tvCheck.setTag("check_" + i);
				row.addView(tvCheck);
			}

		// 图标
		ImageView iv = new ImageView(requireContext());
		iv.setLayoutParams(new LinearLayout.LayoutParams(NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20)));
		if (item.type == NokiaWidgetItem.TYPE_APP) {
			// 应用组件：加载真实应用图标（S60 → 系统图标 → 占位），未命中则后台异步刷新
			loadAppIcon(item, iv);
		} else {
			try {
				iv.setImageDrawable(ContextCompat.getDrawable(requireContext(),
						NokiaWidgetItem.getTypeIcon(item.type)));
			} catch (Exception ignored) {
				NokiaLog.w(TAG, "加载组件图标失败 type=" + item.type);
			}
		}
		row.addView(iv);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 6), 1));

			// 名称
			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(item.label);
			tv.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tv, 12);
			tv.setSingleLine(true);
			tv.setEllipsize(TextUtils.TruncateAt.END);
			row.addView(tv);

			// 类型标签（灰色小字）
			TextView tvTag = new TextView(requireContext());
			tvTag.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvTag.setText(item.getTypeTag());
			tvTag.setTextColor(0xFF999999);
			NokiaDimens.textSize(tvTag, 9);
			row.addView(tvTag);

			final int index = i;
			row.setOnClickListener(v -> {
				if (mode == MODE_DELETE) {
					setFocusIndex(index);
					toggleChecked(index);
				} else if (mode == MODE_SORT && lifted) {
					// 拎起态点击任意行视同放下（回到光标态）
					dropLifted();
				} else {
					setFocusIndex(index);
					onSelect();
				}
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		if (focusIndex < 0 || focusIndex >= widgets.size()) {
			focusIndex = 0;
		}
		updateStatusText();
		applyListHighlight();
	}

	// ---- 应用组件图标加载 ----

	/**
	 * 为 TYPE_APP 组件加载图标。优先级：S60 风格图标缓存（毫秒级，主线程可安全调用）
	 * → 系统真实图标（IPC，后台线程）。首帧先用 S60 缓存/占位图标渲染，未命中真实图标时
	 * 启动后台线程加载系统图标，完成后回主线程更新 ImageView。
	 */
	private void loadAppIcon(NokiaWidgetItem item, final ImageView iv) {
		ComponentName cn = parseAppValue(item.value);
		if (cn == null) {
			// value 无法解析（如 J2ME 应用）→ 直接用类型占位图标
			try {
				iv.setImageDrawable(ContextCompat.getDrawable(requireContext(),
						NokiaWidgetItem.getTypeIcon(NokiaWidgetItem.TYPE_APP)));
			} catch (Exception ignored) {
				NokiaLog.w(TAG, "加载应用组件占位图标失败");
			}
			return;
		}

		String pkg = cn.getPackageName();
		// 第 1 优先级：S60 风格图标（读内存缓存，毫秒级，主线程安全；传入 label 以启用应用名匹配）
		int s60Res = NokiaS60IconMap.getIcon(pkg, item.label);
		if (s60Res != 0) {
			try {
				Drawable s60Icon = ContextCompat.getDrawable(requireContext(), s60Res);
				if (s60Icon != null) {
					NokiaLog.d(TAG, "应用组件 " + item.label + " 使用 S60 图标");
					iv.setImageDrawable(s60Icon);
					return;
				}
			} catch (Exception e) {
				NokiaLog.w(TAG, "加载 S60 图标失败: " + item.label);
			}
		}

		// 第 2 优先级（后台）：系统真实图标。先放占位，避免主线程 IPC 卡顿。
		try {
			iv.setImageDrawable(ContextCompat.getDrawable(requireContext(),
					NokiaWidgetItem.getTypeIcon(NokiaWidgetItem.TYPE_APP)));
		} catch (Exception ignored) {
			NokiaLog.w(TAG, "加载应用组件占位图标失败");
		}
		loadAppIconAsync(item, cn, iv);
	}

	/** 解析组件 value 为 ComponentName；J2ME / 非法 value 返回 null。 */
	private ComponentName parseAppValue(String value) {
		if (value == null || value.isEmpty()) return null;
		// J2ME 应用 value 形如 "j2me:label:path"，跳过
		if (value.startsWith("j2me:")) return null;
		try {
			return ComponentName.unflattenFromString(value);
		} catch (Exception e) {
			NokiaLog.w(TAG, "解析应用组件 value 失败: " + value);
			return null;
		}
	}

	/** 后台线程加载系统真实图标，完成后回主线程更新 ImageView。 */
	private void loadAppIconAsync(final NokiaWidgetItem item, final ComponentName cn, final ImageView iv) {
		final String pkg = cn.getPackageName();
		final Context appContext = requireContext().getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				Drawable icon = null;
				try {
					PackageManager pm = appContext.getPackageManager();
					icon = pm.getActivityIcon(cn);
				} catch (Exception e) {
					NokiaLog.w(TAG, "后台加载系统图标失败: " + item.label + " " + e.getMessage());
				}
				if (icon == null) return;
				final Drawable result = icon;
				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || getView() == null) return;
						iv.setImageDrawable(result);
						NokiaLog.d(TAG, "应用组件 " + item.label + " 使用系统真实图标");
					}
				});
			}
		}, "widget-icon-" + pkg).start();
	}

	private void updateStatusText() {
		if (tvStatus == null) return;
		switch (mode) {
			case MODE_DELETE:
				tvStatus.setText("删除模式");
				break;
			case MODE_SORT:
				tvStatus.setText("排序模式");
				break;
			default:
				tvStatus.setText("已选 " + widgets.size() + " / " + NokiaWidgetItem.MAX_COUNT + " 项");
				break;
		}
	}

	private void updateBottomBar() {
		// 底部菜单栏由 NokiaPage 声明 + host.refreshPageBar() 自动装配（按 mode 动态取值）
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.refreshPageBar();
	}

	// ---- 焦点管理 ----

	private void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
		clearListHighlight();
		focusIndex = index;
		applyListHighlight();
		scrollToVisible(index);
	}

	/**
	 * 确保焦点行在 ScrollView 可见区域内，方向键导航时自动跟随滚动。
	 */
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
				NokiaLog.d(TAG, "↑ 滚动至 item " + index + " top=" + itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				scroll.smoothScrollTo(0, itemBottom - svHeight);
				NokiaLog.d(TAG, "↓ 滚动至 item " + index + " bottom=" + itemBottom + " svH=" + svHeight);
			}
		});
	}

	private void applyListHighlight() {
		clearListHighlight();
		if (itemViews == null) return;
		if (mode == MODE_SORT && lifted && liftedIndex >= 0 && liftedIndex < itemViews.length) {
			// 拎起行：独立视觉样式（亮蓝底 + 亮青边框 + 增高，模拟"抓起/抬起"）
			View liftedRow = itemViews[liftedIndex];
			liftedRow.setBackgroundResource(R.drawable.bg_nokia_lifted);
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) liftedRow.getLayoutParams();
			if (lp != null) {
				lp.height = NokiaDimens.dp(getResources(), 40);
				liftedRow.setLayoutParams(lp);
			}
			selectedView = liftedRow;
		} else if (focusIndex >= 0 && focusIndex < itemViews.length) {
			View focusRow = itemViews[focusIndex];
			focusRow.setBackgroundResource(R.drawable.bg_nokia_selected_dark);
			LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) focusRow.getLayoutParams();
			if (lp != null && lp.height != NokiaDimens.dp(getResources(), 34)) {
				lp.height = NokiaDimens.dp(getResources(), 34);
				focusRow.setLayoutParams(lp);
			}
			selectedView = focusRow;
		}
	}

	private void clearListHighlight() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	// ---- Toast（不可编辑提示） ----

	private void showToast(String msg) {
		if (toast != null) {
			toast.cancel();
		}
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		NokiaLog.i(TAG, "Toast: " + msg);
	}

	private void dismissToastIfShown() {
		if (toast != null) {
			toast.cancel();
			toast = null;
		}
	}

	private void exitCurrent() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
	}


	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
