package ru.playsoftware.j2meloader.nokia;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面与系统壁纸设置页（继承 NokiaListPageFragment，方向键循环导航）。
 * 提供经典深蓝、曜石黑、青海蓝、翡翠绿、酒红紫、琥珀金等诺基亚经典风格壁纸主题。
 */
public class NokiaWallpaperSettingsFragment extends NokiaListPageFragment {

	private static class WallpaperItem {
		final String id;
		final String name;
		final int drawableRes;
		final int primaryColor;

		WallpaperItem(String id, String name, int drawableRes, int primaryColor) {
			this.id = id;
			this.name = name;
			this.drawableRes = drawableRes;
			this.primaryColor = primaryColor;
		}
	}

	private final List<WallpaperItem> items = new ArrayList<>();
	private NokiaSettingsStorage storage;
	private String currentWallpaperId;
	private LinearLayout listContainer;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_wallpaper_settings;
	}

	@Override
	public String getPageTitle() {
		return "壁纸设置";
	}

	@Override
	public String getSoftLeftText() {
		return "应用";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	protected void onPageCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
		storage = new NokiaSettingsStorage(requireContext());
		currentWallpaperId = storage.getWallpaper();
		listScroll = root.findViewById(R.id.scroll_wallpaper);
		listContainer = root.findViewById(R.id.ll_wallpaper_list);

		initWallpaperData();
		rebuildList();
	}

	private void initWallpaperData() {
		items.clear();
		items.add(new WallpaperItem("default", "经典深蓝 (默认)", R.drawable.bg_nokia_classic_blue, 0xFF1A3A6B));
		items.add(new WallpaperItem("obsidian_black", "曜石沉黑", R.drawable.bg_nokia_obsidian_black, 0xFF1A1A1A));
		items.add(new WallpaperItem("cyan_sea", "青海深邃", R.drawable.bg_nokia_cyan_sea, 0xFF0B3D4F));
		items.add(new WallpaperItem("emerald_green", "翡翠幽绿", R.drawable.bg_nokia_emerald_green, 0xFF144324));
		items.add(new WallpaperItem("wine_purple", "酒红雅致", R.drawable.bg_nokia_wine_purple, 0xFF4A153B));
		items.add(new WallpaperItem("amber_gold", "琥珀暖金", R.drawable.bg_nokia_amber_gold, 0xFF4A2D14));
	}

	private void rebuildList() {
		listContainer.removeAllViews();
		itemViews = new View[items.size()];

		int initialFocus = 0;
		for (int i = 0; i < items.size(); i++) {
			final int index = i;
			final WallpaperItem item = items.get(i);
			boolean isCurrent = item.id.equals(currentWallpaperId);
			if (isCurrent) initialFocus = i;

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			int pVer = NokiaDimens.dp(getResources(), 8);
			int pHor = NokiaDimens.dp(getResources(), 10);
			row.setPadding(pHor, pVer, pHor, pVer);

			// 1. 色彩预览小圆点/色块
			View colorDot = new View(requireContext());
			int dotSize = NokiaDimens.dp(getResources(), 16);
			LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
			dotLp.rightMargin = NokiaDimens.dp(getResources(), 8);
			colorDot.setLayoutParams(dotLp);
			GradientDrawable shape = new GradientDrawable();
			shape.setShape(GradientDrawable.OVAL);
			shape.setColor(item.primaryColor);
			shape.setStroke(NokiaDimens.dp(getResources(), 1), 0xFFFFFFFF);
			colorDot.setBackground(shape);
			row.addView(colorDot);

			// 2. 主题名称
			TextView tvName = new TextView(requireContext());
			LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
			tvName.setLayoutParams(nameLp);
			tvName.setText(item.name);
			tvName.setTextColor(Color.WHITE);
			tvName.setTextSize(14);
			row.addView(tvName);

			// 3. 当前选中对勾图标
			if (isCurrent) {
				ImageView ivCheck = new ImageView(requireContext());
				int checkSize = NokiaDimens.dp(getResources(), 18);
				ivCheck.setLayoutParams(new LinearLayout.LayoutParams(checkSize, checkSize));
				ivCheck.setImageDrawable(NokiaIcons.get(requireContext(), NokiaIcons.ICON_CHECK, 0xFF60A5FA, 18));
				row.addView(ivCheck);
			}

			row.setOnClickListener(v -> {
				setFocusIndex(index);
				applyWallpaper(item);
			});

			listContainer.addView(row);
			itemViews[i] = row;
		}

		setFocusIndex(initialFocus);
	}

	private void applyWallpaper(WallpaperItem item) {
		currentWallpaperId = item.id;
		storage.setWallpaper(item.id);

		// 即时更新当前 Activity 的背景
		View wall = requireActivity().findViewById(R.id.wallpaper);
		if (wall != null) {
			wall.setBackgroundResource(item.drawableRes);
		}

		rebuildList();
		Toast.makeText(requireContext(), "已应用壁纸: " + item.name, Toast.LENGTH_SHORT).show();
	}

	@Override
	public boolean onSoftLeft() {
		if (focusIndex >= 0 && focusIndex < items.size()) {
			applyWallpaper(items.get(focusIndex));
			return true;
		}
		return false;
	}

	@Override
	public boolean onSelect() {
		return onSoftLeft();
	}

	@Override
	public boolean onSoftRight() {
		requireActivity().getSupportFragmentManager().popBackStack();
		return true;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}
}
