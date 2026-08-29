package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 壁纸设置页（桌面设置 → 外观与显示 → 壁纸设置）。
 * <p>
 * 功能：
 * <ul>
 *   <li>「选择图片」：调起系统文件选择器（{@code ACTION_GET_CONTENT}），
 *       选中的图片会被复制到应用内部存储并设为全屏背景；
 *   <li>「缩放模式」：居中裁剪 / 拉伸铺满 / 适应屏幕 三选一（复用 {@link NokiaOptionsDialog}）；
 *   <li>「恢复默认」：删除自定义壁纸，回退到当前主题的渐变背景。
 * </ul>
 * 背景机制见 {@link NokiaWallpaper}：全屏壁纸由 Activity 统一承载，
 * 因此设置后<b>桌面、功能表、百宝箱、设置等所有页面同步生效</b>。
 * <p>
 * 注意：系统文件选择器只能用触屏操作（与「字体设置 → 导入字体」一致），
 * 方向键在本页仅用于切换列表项。
 */
public class NokiaWallpaperSettingsFragment extends NokiaListPageFragment {

	private static final String TAG = "WallpaperSettings";
	private static final int REQUEST_PICK_IMAGE = 1002;

	/** 缩放模式文案（顺序与 {@link NokiaSettingsStorage#WALLPAPER_SCALE_CROP} 等常量一致）。 */
	private static final String[] SCALE_LABELS = {"居中裁剪", "拉伸铺满", "适应屏幕"};

	private LinearLayout container;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private Toast toast;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_wallpaper_settings;
	}

	@Override
	protected int getWallpaperRes() {
		return 0; // 遵循全局主题背景（与主题/字体设置页一致）
	}

	@Override
	public String getPageTitle() {
		return "壁纸设置";
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
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		listScroll = view.findViewById(R.id.scroll_wallpaper);
		container = view.findViewById(R.id.ll_wallpaper_list);
		rebuildList();
		setFocusIndex(0);
	}

	@Override
	public boolean onSelect() {
		switch (focusIndex) {
			case 0:
				pickImage();
				return true;
			case 1:
				showScaleDialog();
				return true;
			case 2:
				restoreDefault();
				return true;
			default:
				return false;
		}
	}

	// ---- 列表构建 ----

	private void rebuildList() {
		if (container == null) {
			return;
		}
		container.removeAllViews();
		final boolean hasCustom = NokiaWallpaper.hasCustomWallpaper(requireContext());
		final int scaleMode = NokiaSettingsStorage.getWallpaperScale(requireContext());

		// 状态说明行（不可聚焦，仅作为列表头部信息）
		TextView tvStatus = new TextView(requireContext());
		tvStatus.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		tvStatus.setText(hasCustom
				? "当前：自定义图片（桌面/功能表等全部页面生效）"
				: "当前：主题背景（未设置自定义壁纸）");
		tvStatus.setTextColor(0xFFB0BEC5);
		tvStatus.setPadding(dp(10), dp(6), dp(10), dp(10));
		NokiaFontManager.textSize(tvStatus, 10);
		container.addView(tvStatus);

		String[] icons = {
				NokiaIcons.ICON_WALLPAPER,  // 选择图片
				NokiaIcons.ICON_DISPLAY,    // 缩放模式
				NokiaIcons.ICON_RESTORE,    // 恢复默认
		};
		String[] names = {
				"选择图片",
				"缩放模式：" + scaleLabel(scaleMode),
				"恢复默认",
		};

		itemViews = new View[names.length];
		int rowHeight = dp(44);
		for (int i = 0; i < names.length; i++) {
			LinearLayout row = buildRow(icons[i], names[i], rowHeight);
			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});
			container.addView(row);
			itemViews[i] = row;
		}
	}

	private LinearLayout buildRow(String iconUnicode, String name, int rowHeight) {
		LinearLayout row = new LinearLayout(requireContext());
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
		row.setPadding(dp(10), 0, dp(10), 0);
		row.setClickable(true);

		ImageView ivIcon = new ImageView(requireContext());
		int iconSize = dp(20);
		ivIcon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
		ivIcon.setImageDrawable(NokiaIcons.get(requireContext(), iconUnicode, 0xFFFFFFFF, 20));
		row.addView(ivIcon);

		TextView tvName = new TextView(requireContext());
		LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
				0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		nameLp.leftMargin = dp(8);
		tvName.setLayoutParams(nameLp);
		tvName.setText(name);
		tvName.setTextColor(0xFFFFFFFF);
		tvName.setSingleLine(true);
		tvName.setEllipsize(TextUtils.TruncateAt.END);
		NokiaFontManager.textSize(tvName, 12);
		row.addView(tvName);

		TextView tvArrow = new TextView(requireContext());
		tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		tvArrow.setText(">");
		tvArrow.setTextColor(0xFFAAAAAA);
		NokiaFontManager.textSize(tvArrow, 14);
		row.addView(tvArrow);

		return row;
	}

	// ---- 动作 ----

	/**
	 * 调起系统文件选择器挑选图片。
	 * <p>
	 * 优先 {@code ACTION_OPEN_DOCUMENT}（SAF，API 19+ 起契约明确：授予可持久化的读权限），
	 * 没有可用 Activity 时降级 {@code ACTION_GET_CONTENT}。
	 * 两种路径都在原始 Intent 与 Chooser Intent 上显式声明 grant flag——
	 * {@code Intent.createChooser()} 不会继承原始 Intent 的 flags，漏了就会在读取时抛
	 * {@code SecurityException}。
	 */
	private void pickImage() {
		try {
			Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
			intent.setType("image/*");
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			addGrantFlags(intent);

			if (intent.resolveActivity(requireActivity().getPackageManager()) == null) {
				NokiaLog.w(TAG, "无可用 SAF 文档选择器，降级 ACTION_GET_CONTENT");
				intent = new Intent(Intent.ACTION_GET_CONTENT);
				intent.setType("image/*");
				intent.addCategory(Intent.CATEGORY_OPENABLE);
				addGrantFlags(intent);
			}

			Intent chooser = Intent.createChooser(intent, "选择壁纸图片");
			addGrantFlags(chooser);
			startActivityForResult(chooser, REQUEST_PICK_IMAGE);
		} catch (Exception e) {
			NokiaLog.e(TAG, "打开图片选择器失败", e);
			showToast("无法打开文件选择器");
		}
	}

	/** 声明对返回 Uri 的读权限（含可持久化），避免读取时抛出 SecurityException。 */
	private static void addGrantFlags(Intent intent) {
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQUEST_PICK_IMAGE || resultCode != Activity.RESULT_OK || data == null) {
			return;
		}
		Uri uri = data.getData();
		if (uri == null) {
			return;
		}
		final Context appCtx = requireContext().getApplicationContext();

		// 1) 尽量把读权限固化下来（仅 SAF 契约有效，GET_CONTENT 会抛异常，忽略即可）
		try {
			requireActivity().getContentResolver().takePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION);
		} catch (Exception ignored) {
			// 非 SAF 返回的 Uri 不支持持久化授权，退化为本次临时授权
		}

		// 2) 关键：Uri 的临时读权限只在 onActivityResult 期间可靠，
		//    必须在这里同步完成 IO，再交给后台线程做解码（详见 NokiaWallpaper.copyToCacheSync）。
		showToast("正在导入壁纸...");
		final File cached = NokiaWallpaper.copyToCacheSync(appCtx, uri);
		if (cached == null) {
			showToast("读取所选图片失败，请换一张试试");
			return;
		}

		// 3) 解码 + 压缩 + 落盘，CPU 密集，放后台线程
		new Thread(() -> {
			final boolean ok = NokiaWallpaper.finalizeImport(appCtx, cached);
			mainHandler.post(() -> {
				if (!isAdded() || getView() == null) {
					return;
				}
				if (ok) {
					applyAndRefresh();
					showToast("壁纸已设置");
				} else {
					showToast("壁纸导入失败，请换一张图片试试");
				}
			});
		}, "wallpaper-import").start();
	}

	/** 弹出缩放模式选择弹窗（复用通用选项弹窗）。 */
	private void showScaleDialog() {
		final int cur = NokiaSettingsStorage.getWallpaperScale(requireContext());
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		for (int i = 0; i < SCALE_LABELS.length; i++) {
			final int mode = i;
			String label = (i == cur) ? SCALE_LABELS[i] + "（当前）" : SCALE_LABELS[i];
			items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_WALLPAPER, label,
					true, false, () -> {
						NokiaSettingsStorage.setWallpaperScale(requireContext(), mode);
						NokiaLog.i(TAG, "壁纸缩放模式切换为: " + SCALE_LABELS[mode]);
						applyAndRefresh();
						showToast("已切换：" + SCALE_LABELS[mode]);
					}));
		}
		NokiaOptionsDialog.show(getParentFragmentManager(), "缩放模式", items);
	}

	/** 清除自定义壁纸，回退主题渐变背景。 */
	private void restoreDefault() {
		if (!NokiaWallpaper.hasCustomWallpaper(requireContext())) {
			showToast("当前已是主题背景");
			return;
		}
		NokiaWallpaper.clear(requireContext());
		applyAndRefresh();
		showToast("已恢复主题背景");
	}

	/** 让壁纸改动立即生效并刷新本页文案（背景由 Activity 统一承载）。 */
	private void applyAndRefresh() {
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.applyCurrentTheme();
		int saved = focusIndex;
		rebuildList();
		if (itemViews != null && itemViews.length > 0) {
			setFocusIndex(Math.min(Math.max(saved, 0), itemViews.length - 1));
		}
		host.refreshPageBar();
	}

	private static String scaleLabel(int mode) {
		if (mode >= 0 && mode < SCALE_LABELS.length) {
			return SCALE_LABELS[mode];
		}
		return SCALE_LABELS[NokiaSettingsStorage.WALLPAPER_SCALE_CROP];
	}

	private int dp(float value) {
		return NokiaDimens.dp(getResources(), value);
	}

	private void showToast(String msg) {
		if (toast != null) {
			toast.cancel();
		}
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		NokiaLog.i(TAG, "Toast: " + msg);
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		mainHandler.removeCallbacksAndMessages(null);
		container = null;
	}
}
