package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 字体设置页面：支持内置方舟像素体（12px/16px）、系统默认字体以及从本地导入自定义 TTF/OTF 字体文件。
 */
public class NokiaFontSettingsFragment extends NokiaListPageFragment {

	private static final int REQUEST_CODE_PICK_FONT = 1001;

	private NokiaSettingsStorage storage;
	private String currentFontId;
	private List<NokiaFontManager.FontItem> fontList = new ArrayList<>();
	private ScrollView listScroll;
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
		return "字体设置";
	}

	@Override
	public String getSoftLeftText() {
		return "导入字体";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
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
	public boolean onSoftLeft() {
		// 调用系统文件选择器导入 .ttf 或 .otf
		try {
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.setType("*/*");
			String[] mimeTypes = {"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"};
			intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			startActivityForResult(Intent.createChooser(intent, "选择字体文件 (.ttf / .otf)"), REQUEST_CODE_PICK_FONT);
		} catch (Exception e) {
			NokiaLog.e("NokiaFontSettingsFragment", "打开文件选择器失败", e);
			Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show();
		}
		return true;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_CODE_PICK_FONT && resultCode == Activity.RESULT_OK && data != null) {
			Uri uri = data.getData();
			if (uri != null) {
				String newFontId = NokiaFontManager.importFontFromUri(requireContext(), uri);
				if (newFontId != null) {
					storage.setFontId(newFontId);
					currentFontId = newFontId;
					NokiaFontManager.invalidate();
					rebuildList();
					Toast.makeText(requireContext(), "字体导入成功并已应用！", Toast.LENGTH_SHORT).show();
					if (getActivity() instanceof NokiaBaseActivity) {
						((NokiaBaseActivity) getActivity()).recreate();
					}
				} else {
					Toast.makeText(requireContext(), "字体导入失败，请检查文件格式是否为合法 .ttf / .otf", Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	@Override
	public boolean onSelect() {
		if (focusIndex >= 0 && focusIndex < fontList.size()) {
			NokiaFontManager.FontItem chosen = fontList.get(focusIndex);
			currentFontId = chosen.id;
			storage.setFontId(currentFontId);
			NokiaFontManager.invalidate();

			Toast.makeText(requireContext(), "已选用字体：" + chosen.name, Toast.LENGTH_SHORT).show();

			// 刷新界面呈现
			if (getActivity() instanceof NokiaBaseActivity) {
				((NokiaBaseActivity) getActivity()).recreate();
			} else {
				rebuildList();
			}
			return true;
		}
		return false;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		storage = new NokiaSettingsStorage(requireContext());
		currentFontId = storage.getFontId();
		listScroll = view.findViewById(R.id.scroll_wallpaper);
		container = view.findViewById(R.id.ll_wallpaper_list);

		rebuildList();

		// 默认高亮选中的字体
		int initialIndex = 0;
		for (int i = 0; i < fontList.size(); i++) {
			if (fontList.get(i).id.equals(currentFontId)) {
				initialIndex = i;
				break;
			}
		}
		setFocusIndex(initialIndex);
	}

	private void rebuildList() {
		fontList = NokiaFontManager.getAvailableFonts(requireContext());
		container.removeAllViews();
		int count = fontList.size();
		itemViews = new View[count];
		int rowHeight = NokiaDimens.dp(getResources(), 52);
		int margin = NokiaDimens.dp(getResources(), 10);
		NokiaTheme.ThemeDef currentTheme = NokiaTheme.getTheme(storage.getThemeId());

		for (int i = 0; i < count; i++) {
			final NokiaFontManager.FontItem item = fontList.get(i);
			final int itemIndex = i;

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
			));
			row.setPadding(margin, NokiaDimens.dp(getResources(), 4), margin, NokiaDimens.dp(getResources(), 4));

			// 1. 字体图标/文字预览
			TextView tvIcon = new TextView(requireContext());
			tvIcon.setText("Aa");
			tvIcon.setTextSize(16);
			tvIcon.setTextColor(currentTheme.accentColor);
			Typeface sampleTf = NokiaFontManager.loadTypeface(requireContext(), item.id);
			if (sampleTf != null) {
				tvIcon.setTypeface(sampleTf);
			}
			LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 32), ViewGroup.LayoutParams.WRAP_CONTENT
			);
			tvIcon.setLayoutParams(iconLp);
			tvIcon.setGravity(Gravity.CENTER);
			row.addView(tvIcon);

			// 2. 字体名称与描述（垂直排版）
			LinearLayout infoLayout = new LinearLayout(requireContext());
			infoLayout.setOrientation(LinearLayout.VERTICAL);
			LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
			infoLp.leftMargin = margin;
			infoLayout.setLayoutParams(infoLp);

			TextView tvName = new TextView(requireContext());
			tvName.setText(item.name);
			tvName.setTextColor(Color.WHITE);
			tvName.setTextSize(14);
			tvName.setSingleLine(true);
			tvName.setEllipsize(TextUtils.TruncateAt.END);
			if (sampleTf != null) {
				tvName.setTypeface(sampleTf);
			}
			infoLayout.addView(tvName);

			TextView tvDesc = new TextView(requireContext());
			tvDesc.setText(item.description);
			tvDesc.setTextColor(0xAAFFFFFF);
			tvDesc.setTextSize(11);
			tvDesc.setSingleLine(true);
			tvDesc.setEllipsize(TextUtils.TruncateAt.END);
			infoLayout.addView(tvDesc);

			row.addView(infoLayout);

			// 3. 勾选图标
			if (item.id.equals(currentFontId)) {
				ImageView ivCheck = new ImageView(requireContext());
				int checkSize = NokiaDimens.dp(getResources(), 20);
				ivCheck.setLayoutParams(new LinearLayout.LayoutParams(checkSize, checkSize));
				ivCheck.setImageDrawable(NokiaIcons.get(requireContext(), NokiaIcons.ICON_CHECK, currentTheme.accentColor, 18));
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
