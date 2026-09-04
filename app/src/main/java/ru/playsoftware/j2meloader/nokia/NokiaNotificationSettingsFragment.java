package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import ru.playsoftware.j2meloader.R;

/**
 * 通知中心设置页（桌面设置 → 系统与权限 → 通知中心）。
 * <ul>
 * <li>通知使用权：显示当前授权状态，未授权时确认键跳系统授权页；</li>
 * <li>桌面显示通知条：开关，控制桌面快捷栏下方的通知条；</li>
 * <li>显示常驻通知：开关，控制列表是否展示 FLAG_ONGOING_EVENT 类通知（默认关）。</li>
 * </ul>
 * 复用 {@link NokiaListPageFragment} 的循环导航/焦点三件套与设置分组页布局。
 */
public class NokiaNotificationSettingsFragment extends NokiaListPageFragment {

	/** 行名称（与行 View 一一对应）。 */
	private static final String[] NAMES = {
			"通知使用权",
			"桌面显示通知条",
			"显示常驻通知",
	};

	private TextView[] tvNames;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_settings_group;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		TextView title = view.findViewById(R.id.settingsTitle);
		if (title != null) {
			title.setText("通知中心");
		}

		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;
		listScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view, listScroll);

		itemViews = new View[NAMES.length];
		tvNames = new TextView[NAMES.length];
		for (int i = 0; i < NAMES.length; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4),
					NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4));
			row.setClickable(true);

			ImageView ivIcon = new ImageView(requireContext());
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20)));
			ivIcon.setImageDrawable(NokiaIcons.get(requireContext(),
					NokiaIcons.ICON_NOTIFICATIONS, 0xFFFFFFFF, 20));
			row.addView(ivIcon);
			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tvName, 12);
			tvNames[i] = tvName;
			row.addView(tvName);

			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		refreshNames();
		setFocusIndex(0);
	}

	/** 行名称动态化：授权状态与两个开关的当前值。 */
	private void refreshNames() {
		if (tvNames == null) return;
		tvNames[0].setText(NokiaMusicSessionReader.isNotificationListenerEnabled(requireContext())
				? "通知使用权：已授权" : "通知使用权：未授权（确认键去授权）");
		tvNames[1].setText(NokiaSettingsStorage.isNotificationBarEnabled(requireContext())
				? "桌面显示通知条：开" : "桌面显示通知条：关");
		tvNames[2].setText(NokiaSettingsStorage.isNotificationShowOngoing(requireContext())
				? "显示常驻通知：开" : "显示常驻通知：关");
	}

	/** 行内水平间距占位（与其它设置页一致的实现）。 */
	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}

	@Override
	public String getPageTitle() {
		return "通知中心";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
	}

	@Override
	public void onResume() {
		super.onResume();
		// 从系统「通知使用权」设置页授权返回后，重新查询状态并刷新文案
		refreshNames();
	}

	@Override
	public String getSoftRightText() {
		return "返回";
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

	@Override
	public boolean onSelect() {
		if (focusIndex < 0) return false;
		switch (focusIndex) {
			case 0:
				// 跳系统「通知使用权」设置页；授权后系统会重绑服务并触发 onListenerConnected
				if (!NokiaMusicSessionReader.openNotificationListenerSettings(requireContext())) {
					NokiaLog.w("NotifSettings", "当前系统版本不支持通知使用权设置页");
					Toast.makeText(requireContext(), "当前系统不支持通知使用权，请到设置→安全→通知访问手动开启",
							Toast.LENGTH_LONG).show();
				}
				return true;
			case 1:
				boolean bar = !NokiaSettingsStorage.isNotificationBarEnabled(requireContext());
				NokiaSettingsStorage.setNotificationBarEnabled(requireContext(), bar);
				refreshNames();
				return true;
			case 2:
				boolean ongoing = !NokiaSettingsStorage.isNotificationShowOngoing(requireContext());
				NokiaSettingsStorage.setNotificationShowOngoing(requireContext(), ongoing);
				// 过滤规则变了，让仓储按新规则重刷快照
				NokiaNotificationRepository.get().refreshFromService();
				refreshNames();
				return true;
			default:
				return false;
		}
	}
}
