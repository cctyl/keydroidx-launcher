package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.ui.NokiaIcons;

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

import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.util.List;

import ru.playsoftware.j2meloader.R;

public class NokiaThemeSettingsFragment extends NokiaListPageFragment {

	private NokiaSettingsStorage storage;
	private String currentThemeId;
	private List<NokiaTheme.ThemeDef> themes;
	private LinearLayout container;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_wallpaper_settings;
	}

	@Override
	protected int getWallpaperRes() {
		return 0; // 遵循全局主题背景
	}

	@Override
	public String getPageTitle() {
		return "主题设置";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
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

	@Override
	public boolean onSelect() {
		if (focusIndex >= 0 && focusIndex < themes.size()) {
			NokiaTheme.ThemeDef chosen = themes.get(focusIndex);
			storage.setThemeId(chosen.id);
			currentThemeId = chosen.id;

			// 刷新全局主题外观
			NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
			host.applyCurrentTheme();
			host.refreshPageBar();

			int savedFocus = focusIndex;
			rebuildList();
			setFocusIndex(savedFocus);
			Toast.makeText(requireContext(), "已切换主题：" + chosen.name, Toast.LENGTH_SHORT).show();
			return true;
		}
		return false;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		storage = new NokiaSettingsStorage(requireContext());
		currentThemeId = storage.getThemeId();
		themes = NokiaTheme.getThemes();
		listScroll = view.findViewById(R.id.scroll_wallpaper);
		container = view.findViewById(R.id.ll_wallpaper_list);

		rebuildList();

		// 默认高亮当前选中的主题
		int initialIndex = 0;
		for (int i = 0; i < themes.size(); i++) {
			if (themes.get(i).id.equals(currentThemeId)) {
				initialIndex = i;
				break;
			}
		}
		setFocusIndex(initialIndex);
	}

	private void rebuildList() {
		container.removeAllViews();
		int count = themes.size();
		itemViews = new View[count];
		int rowHeight = NokiaDimens.dp(getResources(), 44);
		int previewSize = NokiaDimens.dp(getResources(), 24);
		int margin = NokiaDimens.dp(getResources(), 10);

		for (int i = 0; i < count; i++) {
			final NokiaTheme.ThemeDef item = themes.get(i);
			final int itemIndex = i;

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
			));
			row.setPadding(margin, 0, margin, 0);

			// 1. 主题预览色块（圆形渐变）
			ImageView ivPreview = new ImageView(requireContext());
			LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(previewSize, previewSize);
			ivLp.rightMargin = margin;
			ivPreview.setLayoutParams(ivLp);

			GradientDrawable previewBg = new GradientDrawable(
					GradientDrawable.Orientation.TOP_BOTTOM,
					new int[]{item.bgStartColor, item.bgCenterColor, item.bgEndColor}
			);
			previewBg.setCornerRadius(previewSize / 2f);
			previewBg.setStroke(2, 0x88FFFFFF);
			ivPreview.setBackground(previewBg);
			row.addView(ivPreview);

			// 2. 主题名称
			TextView tv = new TextView(requireContext());
			tv.setText(item.name);
			tv.setTextColor(Color.WHITE);
			tv.setTextSize(14);
			LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
			tv.setLayoutParams(tvLp);
			row.addView(tv);

			// 3. 当前选中勾选标记
			if (item.id.equals(currentThemeId)) {
				ImageView ivCheck = new ImageView(requireContext());
				ivCheck.setLayoutParams(new LinearLayout.LayoutParams(previewSize, previewSize));
				ivCheck.setImageDrawable(NokiaIcons.get(requireContext(), NokiaIcons.ICON_CHECK, item.accentColor, 18));
				row.addView(ivCheck);
			}

			row.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					setFocusIndex(itemIndex);
					onSelect();
				}
			});

			container.addView(row);
			itemViews[i] = row;
		}
	}
}
