package ru.playsoftware.j2meloader.nokia;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面设置二级分组页。同一套纵向列表结构，按传入的分组 ID 展示不同细项：
 * <ul>
 *     <li>{@link #GROUP_APPEARANCE} 外观与显示：字体大小、界面字体、主题设置、壁纸设置；</li>
 *     <li>{@link #GROUP_KEYS} 按键与操作：按键绑定、应用向导；</li>
 *     <li>{@link #GROUP_CONTENT} 桌面内容：顶部快捷栏设置、桌面组件设置；</li>
 *     <li>{@link #GROUP_SYSTEM} 系统与权限：日志记录、默认桌面设置。</li>
 * </ul>
 * 由 {@link NokiaDesktopSettingsFragment} 通过 {@link #newInstance(int)} 打开。
 */
public class NokiaSettingsGroupFragment extends NokiaListPageFragment {

	public static final String ARG_GROUP = "group";

	public static final int GROUP_APPEARANCE = 0;
	public static final int GROUP_KEYS = 1;
	public static final int GROUP_CONTENT = 2;
	public static final int GROUP_SYSTEM = 3;

	/** 分组名（页面标题 + 顶部小标题）。 */
	public static String getGroupTitle(int group) {
		switch (group) {
			case GROUP_APPEARANCE: return "外观与显示";
			case GROUP_KEYS: return "按键与操作";
			case GROUP_CONTENT: return "桌面内容";
			case GROUP_SYSTEM: return "系统与权限";
			default: return "设置";
		}
	}

	private static String[] iconsOf(int group) {
		switch (group) {
			case GROUP_APPEARANCE:
				return new String[]{
						NokiaIcons.ICON_FONT,         // 字体大小
						NokiaIcons.ICON_FONT,         // 界面字体（像素字体/自定义字体）
						NokiaIcons.ICON_PALETTE,      // 主题设置
						NokiaIcons.ICON_WALLPAPER,    // 壁纸设置
				};
			case GROUP_KEYS:
				return new String[]{
						NokiaIcons.ICON_KEYPAD,       // 按键绑定
						NokiaIcons.ICON_ACTIVITY,     // 应用向导
				};
			case GROUP_CONTENT:
				return new String[]{
						NokiaIcons.ICON_SHORTCUTS,    // 顶部快捷栏设置
						NokiaIcons.ICON_WIDGETS,      // 桌面组件设置
						NokiaIcons.ICON_TOGGLES,      // 快捷开关
				};
			case GROUP_SYSTEM:
				return new String[]{
						NokiaIcons.ICON_LOG,          // 日志记录
						NokiaIcons.ICON_HOME,         // 默认桌面设置
				};
			default:
				return new String[0];
		}
	}

	private static String[] namesOf(int group) {
		switch (group) {
			case GROUP_APPEARANCE:
				return new String[]{"字体大小", "字体选择", "主题设置", "壁纸设置"};
			case GROUP_KEYS:
				return new String[]{"按键绑定", "应用向导"};
			case GROUP_CONTENT:
				return new String[]{"顶部快捷栏设置", "桌面组件设置", "快捷开关"};
			case GROUP_SYSTEM:
				return new String[]{"日志记录", "默认桌面设置"};
			default:
				return new String[0];
		}
	}

	/** 字体大小档位（分组页 → 字体大小），作用于全部应用内文字。 */
	private static final float[] FONT_SCALES = {0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f};
	private static final String[] FONT_LABELS = {"较小 (0.85x)", "标准 (1.0x)", "较大 (1.15x)", "特大 (1.3x)", "超大 (1.5x)", "巨大 (1.8x)", "极巨 (2.0x)"};

	private int group;
	private String[] itemIcons;
	private String[] itemNames;
	private TextView[] tvNames;

	public static NokiaSettingsGroupFragment newInstance(int group) {
		NokiaSettingsGroupFragment f = new NokiaSettingsGroupFragment();
		Bundle args = new Bundle();
		args.putInt(ARG_GROUP, group);
		f.setArguments(args);
		return f;
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_settings_group;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		group = args != null ? args.getInt(ARG_GROUP, GROUP_APPEARANCE) : GROUP_APPEARANCE;
		itemIcons = iconsOf(group);
		itemNames = namesOf(group);

		// 顶部小标题 = 分组名
		TextView title = view.findViewById(R.id.settingsTitle);
		if (title != null) {
			title.setText(getGroupTitle(group));
		}

		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		listScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view, listScroll);

		itemViews = new View[itemNames.length];
		tvNames = new TextView[itemNames.length];
		for (int i = 0; i < itemNames.length; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4),
					NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4));
			row.setClickable(true);

			// 图标（Material Icons 矢量字符）
			ImageView ivIcon = new ImageView(requireContext());
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20)));
			ivIcon.setImageDrawable(NokiaIcons.get(requireContext(), itemIcons[i], 0xFFFFFFFF, 20));
			row.addView(ivIcon);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			// 名称（动态：字体大小档位 / 日志开关状态 / 默认桌面状态）
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setText(getItemDisplayName(i));
			tvName.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tvName, 12);
			tvNames[i] = tvName;
			row.addView(tvName);

			// 箭头
			TextView tvArrow = new TextView(requireContext());
			tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvArrow.setText(">");
			tvArrow.setTextColor(0xFFAAAAAA);
			NokiaFontManager.textSize(tvArrow, 14);
			row.addView(tvArrow);

			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		setFocusIndex(0);

		NokiaLog.i("SettingsGroup", "分组页初始化完成: " + getGroupTitle(group));
	}

	/** 取列表项名称：字体大小显示当前档位；日志记录显示开关状态；默认桌面根据状态动态展示。 */
	private String getItemDisplayName(int index) {
		if (group == GROUP_APPEARANCE && index == 0) {
			float cur = NokiaSettingsStorage.getFontScale(requireContext());
			String label = null;
			for (int i = 0; i < FONT_SCALES.length; i++) {
				if (Math.abs(FONT_SCALES[i] - cur) < 0.001f) {
					label = FONT_LABELS[i];
					break;
				}
			}
			if (label == null) {
				label = String.format(java.util.Locale.US, "自定义 (%.2fx)", cur);
			}
			return "字体大小：" + label;
		}
		if (group == GROUP_SYSTEM && index == 0) {
			return NokiaSettingsStorage.isFileLogEnabled(requireContext())
					? "日志记录：开启" : "日志记录：关闭";
		}
		if (group == GROUP_SYSTEM && index == 1) {
			boolean isDefault = ((NokiaDesktopActivity) requireActivity()).isDefaultLauncher();
			return isDefault ? "默认桌面：已设置" : "默认桌面设置";
		}
		return itemNames[index];
	}

	@Override
	public boolean onSelect() {
		NokiaLog.d("SettingsGroup", "onSelect 当前 focusIndex=" + focusIndex);
		if (focusIndex < 0) return false;
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		switch (group) {
			case GROUP_APPEARANCE:
				return onSelectAppearance(host);
			case GROUP_KEYS:
				return onSelectKeys(host);
			case GROUP_CONTENT:
				return onSelectContent(host);
			case GROUP_SYSTEM:
				return onSelectSystem(host);
			default:
				return false;
		}
	}

	private boolean onSelectAppearance(NokiaDesktopActivity host) {
		switch (focusIndex) {
			case 0:
				NokiaLog.i("SettingsGroup", "字体大小设置");
				showFontScaleDialog();
				return true;
			case 1:
				NokiaLog.i("SettingsGroup", "进入字体选择");
				host.openFragment(new NokiaFontSettingsFragment());
				return true;
			case 2:
				NokiaLog.i("SettingsGroup", "进入主题设置");
				host.openFragment(new NokiaThemeSettingsFragment());
				return true;
			case 3:
				NokiaLog.i("SettingsGroup", "进入壁纸设置");
				host.openFragment(new NokiaWallpaperSettingsFragment());
				return true;
			default:
				return false;
		}
	}

	private boolean onSelectKeys(NokiaDesktopActivity host) {
		switch (focusIndex) {
			case 0:
				NokiaLog.i("SettingsGroup", "按键绑定");
				host.openFragment(new NokiaKeyBindFragment());
				return true;
			case 1:
				NokiaLog.i("SettingsGroup", "进入应用向导");
				host.getSupportFragmentManager().beginTransaction()
						.replace(R.id.midPanel, new NokiaKeyBindWizardFragment())
						.addToBackStack(null)
						.commit();
				return true;
			default:
				return false;
		}
	}

	private boolean onSelectContent(NokiaDesktopActivity host) {
		switch (focusIndex) {
			case 0:
				NokiaLog.i("SettingsGroup", "进入快捷栏设置");
				host.openFragment(new NokiaShortcutSettingsFragment());
				return true;
			case 1:
				NokiaLog.i("SettingsGroup", "进入桌面组件设置");
				host.openFragment(new NokiaWidgetSettingsFragment());
				return true;
			case 2:
				NokiaLog.i("SettingsGroup", "进入快捷开关设置");
				host.openFragment(new NokiaQuickToggleSettingsFragment());
				return true;
			default:
				return false;
		}
	}

	private boolean onSelectSystem(NokiaDesktopActivity host) {
		switch (focusIndex) {
			case 0:
				NokiaLog.i("SettingsGroup", "日志记录开关");
				toggleLogEnabled();
				return true;
			case 1:
				NokiaLog.i("SettingsGroup", "默认桌面设置：引导设为默认桌面");
				host.requestSetDefaultLauncher();
				return true;
			default:
				return false;
		}
	}

	/**
	 * 切换日志记录开关并实时生效：
	 * 开启=输出详细日志（全级别）；关闭=仅输出 ERROR 及以上。
	 * 默认值：debug 版开启，release 版关闭。
	 */
	private void toggleLogEnabled() {
		boolean next = !NokiaSettingsStorage.isFileLogEnabled(requireContext());
		NokiaSettingsStorage.setFileLogEnabled(requireContext(), next);
		NokiaLog.i("SettingsGroup", "日志记录切换为: " + (next ? "开启(详细)" : "关闭(仅错误)"));
		if (tvNames != null && tvNames.length > 0 && tvNames[0] != null) {
			tvNames[0].setText(getItemDisplayName(0));
		}
	}

	/** 弹出字体大小选择弹窗（复用通用选项弹窗），选择后保存并重建 Activity 立即生效。 */
	private void showFontScaleDialog() {
		float cur = NokiaSettingsStorage.getFontScale(requireContext());
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		for (int i = 0; i < FONT_LABELS.length; i++) {
			final float scale = FONT_SCALES[i];
			final String label = FONT_LABELS[i];
			String itemLabel = Math.abs(scale - cur) < 0.001f
					? label + "（当前）" : label;
			items.add(new NokiaOptionsDialog.OptionItem(0, itemLabel, true, false, () -> {
				NokiaSettingsStorage.setFontScale(requireContext(), scale);
				NokiaLog.i("SettingsGroup", "字体大小已设置: " + label + " scale=" + scale);
				((NokiaDesktopActivity) requireActivity()).recreate();
			}));
		}
		// 增加自定义倍率选项（支持手动输入 0.5 ~ 3.5x）
		items.add(new NokiaOptionsDialog.OptionItem(0, "自定义倍率...", true, false, () -> {
			NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
			host.openFragment(new NokiaCustomFontScaleFragment());
		}));
		NokiaOptionsDialog.show(getParentFragmentManager(), "字体大小", items);
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
	}

	@Override
	public boolean onSoftRight() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return getGroupTitle(group);
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}



	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
