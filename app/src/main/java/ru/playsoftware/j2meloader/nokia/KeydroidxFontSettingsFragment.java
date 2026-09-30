package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxIcons;

import android.app.Activity;
import android.content.Context;
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

import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 字体设置页面：支持内置方舟像素体（12px/16px）、系统默认字体以及从本地导入自定义 TTF/OTF 字体文件。
 */
public class KeydroidxFontSettingsFragment extends KeydroidxListPageFragment {

	private static final int REQUEST_CODE_PICK_FONT = 1001;

	private static final String TAG = "KeydroidxFontSettingsFragment";

	/** 字体文件 MIME 白名单（部分文件管理器按 MIME 过滤，缺一档就选不中 .ttf）。 */
	private static final String[] FONT_MIME_TYPES = {
			"font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"
	};

	private KeydroidxSettingsStorage storage;
	private String currentFontId;
	private List<KeydroidxFontManager.FontItem> fontList = new ArrayList<>();
	private ScrollView listScroll;
	private LinearLayout container;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_wallpaper_settings;
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
		pickFontFile();
		return true;
	}

	/**
	 * 调起字体文件选择器。
	 * <p>
	 * 优先 {@code ACTION_OPEN_DOCUMENT}（SAF，API 19+ 起契约明确：授予可持久化读权限），
	 * 没有可用 Activity 时降级 {@code ACTION_GET_CONTENT}。两种路径都在原始 Intent 与
	 * Chooser Intent 上显式声明 grant flag——{@code Intent.createChooser()} 不会继承原始
	 * Intent 的 flags，漏了就会在读取时抛 {@code SecurityException}。
	 * <p>
	 * 为什么优先 SAF：{@code ACTION_GET_CONTENT} 会把第三方文件管理器（如 ES 文件浏览器）
	 * 列进候选，它们可能返回自家未 exported 的 Provider Uri，任何授权都读不到流
	 * （见 2026-09-28 上报：{@code com.estrongs.files} → Permission Denial）。
	 */
	private void pickFontFile() {
		try {
			Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			intent.setType("*/*");
			intent.putExtra(Intent.EXTRA_MIME_TYPES, FONT_MIME_TYPES);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			addGrantFlags(intent);

			if (intent.resolveActivity(requireActivity().getPackageManager()) == null) {
				KeydroidxLog.w(TAG, "无可用 SAF 文档选择器，降级 ACTION_GET_CONTENT");
				intent = new Intent(Intent.ACTION_GET_CONTENT);
				intent.setType("*/*");
				intent.putExtra(Intent.EXTRA_MIME_TYPES, FONT_MIME_TYPES);
				intent.addCategory(Intent.CATEGORY_OPENABLE);
				addGrantFlags(intent);
			}

			Intent chooser = Intent.createChooser(intent, "选择字体文件 (.ttf / .otf)");
			addGrantFlags(chooser);
			startActivityForResult(chooser, REQUEST_CODE_PICK_FONT);
		} catch (Exception e) {
			// 设备没有可用文件选择器属外部环境问题：按规范用 w，不触发自动上报
			KeydroidxLog.w(TAG, "打开文件选择器失败: " + e.getMessage(), e);
			Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show();
		}
	}

	/** 声明对返回 Uri 的读权限（含可持久化），避免读取时抛 SecurityException。 */
	private static void addGrantFlags(Intent intent) {
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_CODE_PICK_FONT || resultCode != Activity.RESULT_OK || data == null) {
			return;
		}
		Uri uri = data.getData();
		if (uri == null) {
			return;
		}

		// 1) 尽量把读权限固化下来（仅 SAF 契约有效，GET_CONTENT 返回的 Uri 会抛异常，忽略即可）
		try {
			requireActivity().getContentResolver()
					.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "持久化字体 Uri 读权限失败（本次仍走临时授权）: " + e.getMessage());
		}

		// 2) 关键：Uri 的临时读权限只在 onActivityResult 期间可靠，
		//    importFontFromUriAsync 会在此同步打开输入流，再把拷贝/解析交给后台线程，
		//    避免大字体文件（数 MB）阻塞主线程。
		final Context appCtx = requireContext().getApplicationContext();
		Toast.makeText(requireContext(), "正在导入字体...", Toast.LENGTH_SHORT).show();
		KeydroidxFontManager.importFontFromUriAsync(appCtx, uri, fontId -> {
			if (!isAdded() || getView() == null) {
				return;
			}
			if (fontId == null) {
				// 读取失败绝大多数是第三方文件管理器给出的 Uri 未授权（或文件不是合法字体）：
				// 外部环境问题，日志已由 importFontFromUriAsync 以 w 记录，这里只给可执行的提示
				Toast.makeText(requireContext(),
						"字体导入失败：请改用系统「文件」选择器，或先把字体复制到本机存储再导入",
						Toast.LENGTH_LONG).show();
				return;
			}
			storage.setFontId(fontId);
			currentFontId = fontId;
			KeydroidxFontManager.invalidate();
			rebuildList();
			Toast.makeText(requireContext(), "字体导入成功并已应用！", Toast.LENGTH_SHORT).show();
			if (getActivity() instanceof KeydroidxBaseActivity) {
				((KeydroidxBaseActivity) getActivity()).recreate();
			}
		});
	}

	@Override
	public boolean onSelect() {
		if (focusIndex >= 0 && focusIndex < fontList.size()) {
			KeydroidxFontManager.FontItem chosen = fontList.get(focusIndex);
			currentFontId = chosen.id;
			storage.setFontId(currentFontId);
			KeydroidxFontManager.invalidate();

			Toast.makeText(requireContext(), "已选用字体：" + chosen.name, Toast.LENGTH_SHORT).show();

			// 刷新界面呈现
			if (getActivity() instanceof KeydroidxBaseActivity) {
				((KeydroidxBaseActivity) getActivity()).recreate();
			} else {
				rebuildList();
			}
			return true;
		}
		return false;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		storage = new KeydroidxSettingsStorage(requireContext());
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
		fontList = KeydroidxFontManager.getAvailableFonts(requireContext());
		container.removeAllViews();
		int count = fontList.size();
		itemViews = new View[count];
		// 行高 WRAP_CONTENT + minHeight：大字号/点阵字体行盒放大后固定 52dp 会裁掉两行文字
		int margin = KeydroidxDimens.dp(getResources(), 10);
		KeydroidxTheme.ThemeDef currentTheme = KeydroidxTheme.getTheme(storage.getThemeId());

		for (int i = 0; i < count; i++) {
			final KeydroidxFontManager.FontItem item = fontList.get(i);
			final int itemIndex = i;

			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
			));
			row.setMinimumHeight(KeydroidxDimens.dp(getResources(), 52));
			row.setPadding(margin, KeydroidxDimens.dp(getResources(), 4), margin, KeydroidxDimens.dp(getResources(), 4));

			// 1. 字体图标/文字预览
			TextView tvIcon = new TextView(requireContext());
			tvIcon.setText("Aa");
			KeydroidxFontManager.textSize(tvIcon, 13);
			tvIcon.setTextColor(currentTheme.accentColor);
			Typeface sampleTf = KeydroidxFontManager.loadTypeface(requireContext(), item.id);
			if (sampleTf != null) {
				tvIcon.setTypeface(sampleTf);
			}
			LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
					KeydroidxDimens.dp(getResources(), 28), ViewGroup.LayoutParams.WRAP_CONTENT
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
			KeydroidxFontManager.textSize(tvName, 12);
			tvName.setSingleLine(true);
			tvName.setEllipsize(TextUtils.TruncateAt.END);
			if (sampleTf != null) {
				tvName.setTypeface(sampleTf);
			}
			infoLayout.addView(tvName);

			TextView tvDesc = new TextView(requireContext());
			tvDesc.setText(item.description);
			tvDesc.setTextColor(0xAAFFFFFF);
			KeydroidxFontManager.textSize(tvDesc, 9);
			tvDesc.setSingleLine(true);
			tvDesc.setEllipsize(TextUtils.TruncateAt.END);
			infoLayout.addView(tvDesc);

			row.addView(infoLayout);

			// 3. 勾选图标
			if (item.id.equals(currentFontId)) {
				ImageView ivCheck = new ImageView(requireContext());
				int checkSize = KeydroidxDimens.dp(getResources(), 20);
				ivCheck.setLayoutParams(new LinearLayout.LayoutParams(checkSize, checkSize));
				ivCheck.setImageDrawable(KeydroidxIcons.get(requireContext(), KeydroidxIcons.ICON_CHECK, currentTheme.accentColor, 18));
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
