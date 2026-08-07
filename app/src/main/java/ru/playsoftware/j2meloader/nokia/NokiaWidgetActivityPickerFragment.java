package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面组件设置 → 添加Activity快捷 → 步骤2：选择 Activity。
 * 纵向列表展示指定应用的所有 exported Activity（排除 launcher Activity），
 * 按 label 字母排序，支持翻页。
 * <p>
 * 接收 Bundle 参数：mode (ADD/EDIT), packageName, appLabel, editIndex(EDIT模式)。
 * 确认键选中 Activity → 进入步骤3（名称输入）。
 */
public class NokiaWidgetActivityPickerFragment extends NokiaPageFragment {

	private static final String TAG = "WidgetActivityPicker";

	private static final String EXTRA_MODE = "mode";
	private static final String EXTRA_PACKAGE_NAME = "packageName";
	private static final String EXTRA_APP_LABEL = "appLabel";
	private static final String EXTRA_EDIT_INDEX = "editIndex";

	public static final String MODE_ADD = "ADD";
	public static final String MODE_EDIT = "EDIT";

	private static final float ROW_H_DP = 24f;    // 行高（fallback）
	private static final float TITLE_H_DP = 20f;  // 标题栏 + 页码 预留高度

	private String mode = MODE_ADD;
	private String packageName;
	private String appLabel;
	private int editIndex = -1;

	private LinearLayout listLayout;
	private ScrollView scroll;
	private TextView tvTitle;
	private TextView tvPage;
	private TextView tvEmpty;
	private Toast toast;

	private final List<ActivityEntry> activities = new ArrayList<>();
	private View[] itemViews;
	private int focusIndex = -1;
	private View selectedView = null;

	private int rowsPerPage = 8;
	private int pageIndex = 0;
	private int totalPages = 1;

	/** Activity 条目：label + className + icon */
	private static class ActivityEntry {
		final String label;
		final String className;
		final Drawable icon;
		ActivityEntry(String label, String className, Drawable icon) {
			this.label = label;
			this.className = className;
			this.icon = icon;
		}
	}

	// ---- 创建入口 ----

	public static NokiaWidgetActivityPickerFragment newAddMode(String packageName, String appLabel) {
		NokiaWidgetActivityPickerFragment f = new NokiaWidgetActivityPickerFragment();
		Bundle b = new Bundle();
		b.putString(EXTRA_MODE, MODE_ADD);
		b.putString(EXTRA_PACKAGE_NAME, packageName);
		b.putString(EXTRA_APP_LABEL, appLabel);
		f.setArguments(b);
		return f;
	}

	public static NokiaWidgetActivityPickerFragment newEditMode(String packageName, String appLabel, int editIndex) {
		NokiaWidgetActivityPickerFragment f = new NokiaWidgetActivityPickerFragment();
		Bundle b = new Bundle();
		b.putString(EXTRA_MODE, MODE_EDIT);
		b.putString(EXTRA_PACKAGE_NAME, packageName);
		b.putString(EXTRA_APP_LABEL, appLabel);
		b.putInt(EXTRA_EDIT_INDEX, editIndex);
		f.setArguments(b);
		return f;
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_widget_activity_picker;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		if (args != null) {
			mode = args.getString(EXTRA_MODE, MODE_ADD);
			packageName = args.getString(EXTRA_PACKAGE_NAME);
			appLabel = args.getString(EXTRA_APP_LABEL);
			editIndex = args.getInt(EXTRA_EDIT_INDEX, -1);
		}
		NokiaLog.i(TAG, "初始化 mode=" + mode + " pkg=" + packageName
				+ " appLabel=" + appLabel + " editIndex=" + editIndex);

		tvTitle = view.findViewById(R.id.tvActivityPickerTitle);
		tvPage = view.findViewById(R.id.tvActivityPage);
		tvEmpty = view.findViewById(R.id.tvActivityEmpty);
		listLayout = view.findViewById(R.id.activityListLayout);
		scroll = view.findViewById(R.id.activityScroll);

		updateTitle();

		// 延迟到 midPanel 布局完成后计算行数并加载 Activity
		view.post(() -> {
			if (!isAdded()) return;
			computeRowsPerPage();
			loadActivities();
			buildPage();
			setInitialFocus();
			NokiaLog.i(TAG, "Activity选择页初始化完成：共 " + activities.size()
					+ " 项，" + totalPages + " 页，每页 " + rowsPerPage + " 行");
		});
	}

	private void updateTitle() {
		if (tvTitle != null && appLabel != null) {
			tvTitle.setText(appLabel + " - 选择Activity");
		}
	}

	// ---- 行数计算 ----

	private void computeRowsPerPage() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		int panelH = host.getMidPanelHeight();
		float density = getResources().getDisplayMetrics().density;
		float scale = host.getScale();
		float availDesign = panelH > 0 ? (panelH / density / scale) : 262f;
		int rows = (int) ((availDesign - TITLE_H_DP) / ROW_H_DP);
		rowsPerPage = Math.max(5, Math.min(12, rows));
		NokiaLog.i(TAG, "computeRowsPerPage: rowsPerPage=" + rowsPerPage
				+ " panelH=" + panelH + " scale=" + scale);
	}

	// ---- 数据加载 ----

	private void loadActivities() {
		if (packageName == null || packageName.isEmpty()) {
			NokiaLog.w(TAG, "packageName 为空，无法加载 Activity");
			return;
		}

		PackageManager pm = requireActivity().getPackageManager();
		try {
			PackageInfo pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
			if (pkgInfo.activities == null || pkgInfo.activities.length == 0) {
				NokiaLog.i(TAG, "应用 " + packageName + " 没有任何 Activity");
				return;
			}

			// 获取该包的 launcher Activity 类名集合（排除）
			Set<String> launcherNames = getLauncherActivityNames(pm);
			// 去重用：同名 Activity 可能因多个 intent-filter 被重复注册
			Set<String> seenClassNames = new HashSet<>();

			// 获取 Application label，用于判断 Activity label 是否回退到了应用名
			String appLabelStr = getApplicationLabel(pm);

			for (ActivityInfo ai : pkgInfo.activities) {
				if (ai == null || ai.name == null) continue;
				// 去重：同名类只保留第一个
				if (seenClassNames.contains(ai.name)) {
					NokiaLog.d(TAG, "去重跳过: " + ai.name);
					continue;
				}
				seenClassNames.add(ai.name);

				// 过滤条件
				if (!ai.exported) {
					NokiaLog.d(TAG, "排除非exported Activity: " + ai.name);
					continue;
				}
				if (launcherNames.contains(ai.name)) {
					NokiaLog.d(TAG, "排除Launcher Activity: " + ai.name);
					continue;
				}

				// 构造显示名：优先用 Activity 自身 label，如果等于应用名就用类名简称
				CharSequence labelCs = ai.loadLabel(pm);
				String label;
				if (labelCs != null && labelCs.length() > 0) {
					String rawLabel = labelCs.toString();
					// 如果 label 等于应用名（说明 Activity 没单独设 label，回退到了 Application label），
					// 则用类名简称来区分
					if (appLabelStr != null && appLabelStr.equals(rawLabel)) {
						label = shortenClassName(ai.name);
						NokiaLog.d(TAG, "Activity label 回退到应用名 \"" + rawLabel
								+ "\"，改用类名简称: " + label);
					} else {
						label = rawLabel;
					}
				} else {
					label = shortenClassName(ai.name);
				}

				Drawable icon = null;
				try {
					icon = pm.getApplicationIcon(packageName);
				} catch (Exception e) {
					NokiaLog.w(TAG, "加载应用图标失败: " + packageName);
				}

				activities.add(new ActivityEntry(label, ai.name, icon));
			}

			// 按 label 字母排序
			Collections.sort(activities, new Comparator<ActivityEntry>() {
				@Override
				public int compare(ActivityEntry a, ActivityEntry b) {
					return a.label.compareToIgnoreCase(b.label);
				}
			});

			NokiaLog.i(TAG, "加载 Activity: " + activities.size() + " 个（已过滤exported/launcher/重复）");
		} catch (PackageManager.NameNotFoundException e) {
			NokiaLog.e(TAG, "应用未找到: " + packageName, e);
		}
	}

	/** 获取应用的显示名（Application label）。 */
	private String getApplicationLabel(PackageManager pm) {
		try {
			android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
			CharSequence label = appInfo.loadLabel(pm);
			return label != null ? label.toString() : null;
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
	}

	/** 取类名最后一段（去掉包名前缀），如 "com.tencent.mm.ui.xxx" → "xxx"。 */
	private String shortenClassName(String fullName) {
		if (fullName == null) return "";
		int lastDot = fullName.lastIndexOf('.');
		return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
	}

	/** 获取该包所有 launcher Activity 类名集合。 */
	private Set<String> getLauncherActivityNames(PackageManager pm) {
		Set<String> names = new HashSet<>();
		Intent main = new Intent(Intent.ACTION_MAIN);
		main.addCategory(Intent.CATEGORY_LAUNCHER);
		main.setPackage(packageName);
		List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
		for (ResolveInfo ri : list) {
			if (ri.activityInfo != null && ri.activityInfo.name != null) {
				names.add(ri.activityInfo.name);
			}
		}
		return names;
	}

	// ---- 页面构建 ----

	private void buildPage() {
		if (listLayout == null) return;
		listLayout.removeAllViews();

		totalPages = Math.max(1, (int) Math.ceil((double) activities.size() / rowsPerPage));
		if (pageIndex >= totalPages) pageIndex = totalPages - 1;
		if (pageIndex < 0) pageIndex = 0;

		if (activities.isEmpty()) {
			tvEmpty.setVisibility(View.VISIBLE);
			tvPage.setText("1/1");
			itemViews = new View[0];
			NokiaLog.i(TAG, "无可用 Activity，显示空状态");
			return;
		}
		tvEmpty.setVisibility(View.GONE);

		int start = pageIndex * rowsPerPage;
		int count = Math.min(rowsPerPage, activities.size() - start);
		NokiaLog.d(TAG, "buildPage 页=" + (pageIndex + 1) + "/" + totalPages
				+ " start=" + start + " count=" + count);

		itemViews = new View[count];
		for (int i = 0; i < count; i++) {
			ActivityEntry entry = activities.get(start + i);

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					NokiaDimens.dp(getResources(), ROW_H_DP)));
			row.setPadding(NokiaDimens.dp(getResources(), 6), 0,
					NokiaDimens.dp(getResources(), 6), 0);
			row.setClickable(true);

			// 图标：应用图标缩放到 14×14dp
			ImageView iv = new ImageView(requireContext());
			iv.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 14), NokiaDimens.dp(getResources(), 14)));
			if (entry.icon != null) {
				iv.setImageDrawable(entry.icon);
			} else {
				try {
					iv.setImageDrawable(ContextCompat.getDrawable(requireContext(),
							R.mipmap.ic_launcher));
				} catch (Exception ignored) {}
			}
			row.addView(iv);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 5), 1));

			// Activity label
			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(entry.label);
			tv.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tv, 11);
			tv.setSingleLine(true);
			tv.setEllipsize(TextUtils.TruncateAt.END);
			row.addView(tv);

			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		tvPage.setText((pageIndex + 1) + "/" + totalPages);
		if (focusIndex >= count) focusIndex = count - 1;
		if (focusIndex < 0 && count > 0) focusIndex = 0;
		applyListHighlight();
	}

	// ---- 焦点 ----

	private void setInitialFocus() {
		focusIndex = (itemViews != null && itemViews.length > 0) ? 0 : -1;
		applyListHighlight();
	}

	private void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
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

	private void applyListHighlight() {
		clearListHighlight();
		if (itemViews == null || focusIndex < 0 || focusIndex >= itemViews.length) return;
		itemViews[focusIndex].setBackgroundResource(R.drawable.bg_nokia_selected_dark);
		selectedView = itemViews[focusIndex];
	}

	private void clearListHighlight() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	// ---- 翻页 ----

	private void pageNext() {
		if (!isAdded() || getView() == null) return;
		if (pageIndex < totalPages - 1) {
			pageIndex++;
			buildPage();
			focusIndex = 0;
			applyListHighlight();
			NokiaLog.d(TAG, "翻页(下) -> " + (pageIndex + 1) + "/" + totalPages);
		}
	}

	private void pagePrev() {
		if (!isAdded() || getView() == null) return;
		if (pageIndex > 0) {
			pageIndex--;
			buildPage();
			focusIndex = itemViews.length - 1;
			applyListHighlight();
			NokiaLog.d(TAG, "翻页(上) -> " + (pageIndex + 1) + "/" + totalPages);
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		if (activities.isEmpty()) return true;
		int count = itemViews != null ? itemViews.length : 0;
		if (count == 0) return true;

		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > 0) {
					setFocusIndex(focusIndex - 1);
				} else if (pageIndex > 0) {
					pagePrev();
				}
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < count - 1) {
					setFocusIndex(focusIndex + 1);
				} else if (pageIndex < totalPages - 1) {
					pageNext();
				}
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
				return true;
			default:
				return true;
		}
	}

	@Override
	public boolean onSelect() {
		if (activities.isEmpty()) return true;
		int global = pageIndex * rowsPerPage + focusIndex;
		if (global < 0 || global >= activities.size()) {
			NokiaLog.w(TAG, "onSelect 越界 global=" + global);
			return false;
		}
		ActivityEntry entry = activities.get(global);
		NokiaLog.i(TAG, "选中 Activity label=" + entry.label + " className=" + entry.className);

		// 进入步骤3：名称输入
		NokiaWidgetActivityNameFragment nameFrag;
		if (MODE_EDIT.equals(mode)) {
			nameFrag = NokiaWidgetActivityNameFragment.newEditMode(
					packageName, entry.className, entry.label, editIndex);
		} else {
			nameFrag = NokiaWidgetActivityNameFragment.newAddMode(
					packageName, entry.className, entry.label);
		}
		((NokiaDesktopActivity) requireActivity()).openFragment(nameFrag);
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		return false; // 左软键（空）
	}

	@Override
	public boolean onSoftRight() {
		NokiaLog.i(TAG, "右软键：返回步骤1（选择应用）");
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage 接口 ----

	@Override
	public String getPageTitle() {
		return appLabel != null ? appLabel + " - 选择Activity" : "选择Activity";
	}

	@Override
	public String getSoftLeftText() {
		return null;
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	// ---- 工具 ----

	private void showToast(String msg) {
		if (toast != null) toast.cancel();
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
