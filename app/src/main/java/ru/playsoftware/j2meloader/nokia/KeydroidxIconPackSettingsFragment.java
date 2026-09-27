package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;
import io.github.cctyl.nokia.common.ui.KeydroidxIcons;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPack;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconPackManager;
import ru.playsoftware.j2meloader.nokia.iconpack.KeydroidxIconResolver;

/**
 * 图标包选择页（桌面设置 → 外观与显示 → 图标包；功能表 → 选项 → 更换图标）。
 *
 * <p>两种模式共用一个页面：</p>
 * <ul>
 *   <li><b>全局模式</b>（无 {@link #ARG_TARGET_PKG}）：选择全局图标包，可选内置 S60、
 *       设备已装图标包、「不使用图标包（全部使用应用原图标）」；</li>
 *   <li><b>单应用模式</b>（带 {@link #ARG_TARGET_PKG}）：为某个应用挑图标，先在这里选图标包，
 *       再进入 {@link KeydroidxIconPickerFragment} 挑选具体图标；
 *       已存在覆盖时额外提供「恢复默认图标」。</li>
 * </ul>
 */
public class KeydroidxIconPackSettingsFragment extends KeydroidxListPageFragment {

	private static final String TAG = "IconPackSettings";

	public static final String ARG_TARGET_PKG = "target_pkg";
	public static final String ARG_TARGET_LABEL = "target_label";

	/** 「恢复默认图标」伪 ID（仅单应用模式使用） */
	private static final String ROW_RESET = "__reset__";
	/** 「重置全部图标」伪 ID（仅全局模式使用） */
	private static final String ROW_RESET_ALL = "__reset_all__";

	private static class Row {
		final String packId;
		final String title;
		final boolean current;
		final KeydroidxIconPack pack;

		Row(String packId, String title, boolean current, KeydroidxIconPack pack) {
			this.packId = packId;
			this.title = title;
			this.current = current;
			this.pack = pack;
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private LinearLayout container;
	/** 单应用模式：目标应用包名；全局模式为 null */
	private String targetPkg;
	private String targetLabel;

	/** 全局模式：桌面设置 → 外观与显示 → 图标包 */
	public static KeydroidxIconPackSettingsFragment newInstance() {
		return new KeydroidxIconPackSettingsFragment();
	}

	/** 单应用模式：功能表 → 选项 → 更换图标 */
	public static KeydroidxIconPackSettingsFragment newInstanceForApp(String pkg, String label) {
		KeydroidxIconPackSettingsFragment f = new KeydroidxIconPackSettingsFragment();
		Bundle args = new Bundle();
		args.putString(ARG_TARGET_PKG, pkg);
		args.putString(ARG_TARGET_LABEL, label);
		f.setArguments(args);
		return f;
	}

	private boolean isPerAppMode() {
		return targetPkg != null && !targetPkg.isEmpty();
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_settings_group;
	}

	@Override
	public String getPageTitle() {
		return isPerAppMode() ? "选择图标包" : "图标包";
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
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		if (args != null) {
			targetPkg = args.getString(ARG_TARGET_PKG);
			targetLabel = args.getString(ARG_TARGET_LABEL);
		}

		TextView title = view.findViewById(R.id.settingsTitle);
		if (title != null) {
			title.setText(isPerAppMode()
					? "为「" + (targetLabel != null ? targetLabel : targetPkg) + "」选择图标包"
					: getPageTitle());
		}
		listScroll = view.findViewById(R.id.settingsScroll);
		container = view.findViewById(R.id.settingsList);
		constrainScrollHeight(view, listScroll);

		rebuildList();

		// 当前图标包预热（未就绪时先只显示名称，就绪后补上预览图）
		KeydroidxIconResolver.warmUpAsync(requireContext(), new Runnable() {
			@Override
			public void run() {
				if (isAdded()) rebuildList();
			}
		});
		// 已装图标包枚举（后台，单飞）；完成后补齐清单与预览图
		KeydroidxIconPackManager.get().refreshInstalledAsync(requireContext(), new Runnable() {
			@Override
			public void run() {
				if (!isAdded()) return;
				rebuildList();
				warmUpPreviewIconsAsync();
			}
		});
		warmUpPreviewIconsAsync();
		KeydroidxLog.i(TAG, "图标包页初始化完成，模式=" + (isPerAppMode() ? "单应用 " + targetPkg : "全局"));
	}

	/**
	 * 后台解析各个已装图标包的映射表：只为列表里的预览图标出图（解析本身有单飞保护，
	 * 重复调用无副作用；单个包失败不影响其它行）。
	 */
	private void warmUpPreviewIconsAsync() {
		final java.util.List<KeydroidxIconPack> snapshot = KeydroidxIconPackManager.get().getPacks();
		final Context appCtx = requireContext().getApplicationContext();
		new Thread(new Runnable() {
			@Override
			public void run() {
				for (KeydroidxIconPack pack : snapshot) {
					try {
						pack.ensureLoaded(appCtx);
					} catch (Throwable t) {
						KeydroidxLog.w(TAG, "解析图标包失败(忽略): " + pack.getId() + " " + t);
					}
				}
				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						if (isAdded()) rebuildList();
					}
				});
			}
		}, "icon-pack-preview").start();
	}

	/** 依据当前选择状态与已装清单重建列表（保持焦点索引） */
	private void rebuildList() {
		if (container == null || !isAdded()) return;
		int keepFocus = focusIndex;
		Context ctx = requireContext();

		String currentPackId = isPerAppMode()
				? KeydroidxSettingsStorage.getIconOverridePack(ctx, targetPkg)
				: KeydroidxSettingsStorage.getIconPackId(ctx);

		rows.clear();
		if (isPerAppMode() && KeydroidxSettingsStorage.hasIconOverride(ctx, targetPkg)) {
			rows.add(new Row(ROW_RESET, "恢复默认图标（按全局图标包）", false, null));
		}
		for (KeydroidxIconPack pack : KeydroidxIconPackManager.get().getPacks()) {
			rows.add(new Row(pack.getId(), pack.getDisplayName(),
					pack.getId().equals(currentPackId), pack));
		}
		if (!isPerAppMode()) {
			rows.add(new Row(KeydroidxIconPackManager.PACK_NONE,
					"不使用图标包（使用原图标）",
					KeydroidxIconPackManager.PACK_NONE.equals(currentPackId), null));
			rows.add(new Row(ROW_RESET_ALL, "重置全部图标（回到应用原图标）", false, null));
		}

		container.removeAllViews();
		itemViews = new View[rows.size()];
		int rowHeight = KeydroidxDimens.dp(getResources(), 38);
		int iconSize = KeydroidxDimens.dp(getResources(), 22);
		int gap = KeydroidxDimens.dp(getResources(), 8);
		int margin = KeydroidxDimens.dp(getResources(), 8);

		for (int i = 0; i < rows.size(); i++) {
			final Row row = rows.get(i);
			LinearLayout line = new LinearLayout(ctx);
			line.setOrientation(LinearLayout.HORIZONTAL);
			line.setGravity(Gravity.CENTER_VERTICAL);
			// 高度 WRAP_CONTENT + minHeight：大字号/点阵字体行盒放大后固定 38dp 会裁掉文字
			line.setLayoutParams(new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			line.setMinimumHeight(rowHeight);
			line.setPadding(margin, 0, margin, 0);
			line.setClickable(true);

			// 预览图标：取该图标包的第一个图标；未就绪/无清单时用应用默认图标占位
			ImageView iv = new ImageView(ctx);
			LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(iconSize, iconSize);
			ivLp.rightMargin = gap;
			iv.setLayoutParams(ivLp);
			iv.setImageDrawable(previewIcon(ctx, row));
			line.addView(iv);

			TextView tv = new TextView(ctx);
			tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(row.current ? row.title + "（当前）" : row.title);
			tv.setTextColor(0xFFFFFFFF);
			tv.setSingleLine(true);
			KeydroidxFontManager.textSize(tv, 12);
			line.addView(tv);

			if (row.current) {
				ImageView check = new ImageView(ctx);
				check.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
				check.setImageDrawable(KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_CHECK, 0xFFFFFFFF, 18));
				line.addView(check);
			}

			final int index = i;
			line.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});

			container.addView(line);
			itemViews[i] = line;
		}

		if (rows.isEmpty()) {
			setFocusIndex(-1);
			return;
		}
		setFocusIndex(keepFocus >= 0 && keepFocus < rows.size() ? keepFocus : 0);
	}

	/** 行预览图标：重置类行用「恢复」矢量图 → 图标包首个图标 → 应用默认图标占位 */
	private Drawable previewIcon(Context ctx, Row row) {
		if (ROW_RESET.equals(row.packId) || ROW_RESET_ALL.equals(row.packId)) {
			return KeydroidxIcons.get(ctx, KeydroidxIcons.ICON_RESTORE, 0xFFFFFFFF, 20);
		}
		if (row.pack != null) {
			List<String> names = row.pack.listIconNames();
			if (!names.isEmpty()) {
				Drawable d = row.pack.getIconByName(ctx, names.get(0));
				if (d != null) return d;
			}
		}
		try {
			return ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher);
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= rows.size()) return false;
		Row row = rows.get(focusIndex);
		if (ROW_RESET.equals(row.packId)) {
			resetOverride();
			return true;
		}
		if (ROW_RESET_ALL.equals(row.packId)) {
			resetAllIcons();
			return true;
		}
		if (isPerAppMode()) {
			if (row.pack == null) return false;
			((KeydroidxDesktopActivity) requireActivity()).openFragment(
					KeydroidxIconPickerFragment.newInstance(row.pack.getId(), targetPkg, targetLabel));
			return true;
		}
		applyGlobalPack(row);
		return true;
	}

	/** 全局模式：保存图标包并即时生效（清空图标缓存，返回后功能表/快捷栏重建） */
	private void applyGlobalPack(Row row) {
		Context ctx = requireContext();
		String current = KeydroidxSettingsStorage.getIconPackId(ctx);
		if (current.equals(row.packId)) {
			Toast.makeText(ctx, "已是当前图标包", Toast.LENGTH_SHORT).show();
			return;
		}
		KeydroidxSettingsStorage.setIconPackId(ctx, row.packId);
		// 清空「应用 → 图标」缓存；各图标包的映射表保持不动，列表里的预览图因此不会闪烁
		KeydroidxIconResolver.invalidateAll(ctx);
		rebuildList();
		// 兜底：本次会话还没解析过的包（例如刚装上）此时才第一次出预览图
		warmUpPreviewIconsAsync();
		KeydroidxIconPackManager.get().refreshInstalledAsync(ctx, new Runnable() {
			@Override
			public void run() {
				if (!isAdded()) return;
				rebuildList();
				warmUpPreviewIconsAsync();
			}
		});
		Toast.makeText(ctx, "已切换图标包：" + row.title, Toast.LENGTH_SHORT).show();
		KeydroidxLog.i(TAG, "切换全局图标包 → " + row.packId);
	}

	/**
	 * 全局模式：重置全部图标 —— 清空所有单应用覆盖，并把全局图标包设为「不使用图标包」，
	 * 使桌面内所有图标都回到应用原图标（列表里「不使用图标包」随即变成当前项）。
	 * <p>与单应用「恢复默认图标」的区别：那个只清单个应用的覆盖并回到全局图标包，
	 * 这里是把覆盖与图标包一起清掉，效果等价于「刚装好桌面」的初始图标状态。</p>
	 */
	private void resetAllIcons() {
		Context ctx = requireContext();
		int cleared = KeydroidxSettingsStorage.clearAllIconOverrides(ctx);
		KeydroidxSettingsStorage.setIconPackId(ctx, KeydroidxIconPackManager.PACK_NONE);
		KeydroidxIconResolver.invalidateAll(ctx);
		rebuildList();
		warmUpPreviewIconsAsync();
		Toast.makeText(ctx, cleared > 0
						? "已重置全部图标（清除 " + cleared + " 个自定义图标）"
						: "已重置全部图标",
				Toast.LENGTH_SHORT).show();
		KeydroidxLog.i(TAG, "重置全部图标：清除覆盖 " + cleared + " 条，全局图标包 → "
				+ KeydroidxIconPackManager.PACK_NONE);
	}

	/** 单应用模式：清除覆盖，恢复为按全局图标包解析 */
	private void resetOverride() {
		Context ctx = requireContext();
		KeydroidxSettingsStorage.clearIconOverride(ctx, targetPkg);
		KeydroidxIconResolver.invalidatePackage(ctx, targetPkg);
		Toast.makeText(ctx, "已恢复默认图标", Toast.LENGTH_SHORT).show();
		KeydroidxLog.i(TAG, "清除图标覆盖: " + targetPkg);
		requireActivity().getSupportFragmentManager().popBackStack();
	}
}
