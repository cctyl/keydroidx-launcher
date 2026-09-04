package ru.playsoftware.j2meloader.nokia;

import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;

import java.util.ArrayList;
import java.util.List;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.permission.NokiaPermissionManager;
import io.github.cctyl.nokia.common.ui.NokiaIcons;
import io.github.cctyl.nokia.common.util.NokiaDimens;

import ru.playsoftware.j2meloader.R;

/**
 * 系统权限自检页面（持久可见入口）。
 * <p>以纵向列表页逐项展示桌面核心权限的授权状态，方向键浏览、确认键修复：
 * <ul>
 *   <li>应用列表（GET_INSTALLED_APPS + 展锐 CTA）—— 功能表/解冻/启动；</li>
 *   <li>电话状态/信号（READ_PHONE_STATE）—— 顶栏信号/双卡；</li>
 *   <li>允许通知（POST_NOTIFICATIONS，Android 13+）—— 保活通知可见；</li>
 *   <li>允许读取通知（NotificationListenerService）—— 通知中心/音乐组件；</li>
 *   <li>一键修复全部缺失项—— 批量申请全集。</li>
 * </ul>
 * 解决「启动/向导自动弹窗错过时机即无入口、release 详细日志关闭不可见」的问题：
 * 用户随时进设置→系统与权限→系统权限自检查看状态并修复。
 * <p>复用 {@link NokiaListPageFragment} 的循环导航/焦点/滚动，复用
 * {@code fragment_nokia_settings_group} 布局。
 */
public class NokiaPermissionCheckFragment extends NokiaListPageFragment {

	private static final String TAG = "PermCheck";

	/** 行 id（用于 onSelect 分发），非列表索引。 */
	private static final int ID_APP_LIST = 0;
	private static final int ID_PHONE = 1;
	private static final int ID_NOTIF = 2;
	private static final int ID_LISTENER = 3;
	private static final int ID_FIX_ALL = 99;

	/** 每行结构：图标 unicode、名称、id。 */
	private static class Row {
		final String icon;
		final String name;
		final int id;
		Row(String icon, String name, int id) {
			this.icon = icon; this.name = name; this.id = id;
		}
	}

	private List<Row> rows = new ArrayList<>();
	private TextView[] tvStatus;   // 每行的状态文案（已授权/未授权 / 一键修复）

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_settings_group;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		// 构建行（允许通知仅 Android 13+ 显示）
		rows.add(new Row(NokiaIcons.ICON_APP, "应用列表", ID_APP_LIST));
		rows.add(new Row(NokiaIcons.ICON_KEYPAD, "电话状态/信号", ID_PHONE));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			rows.add(new Row(NokiaIcons.ICON_NOTIFICATIONS, "允许通知", ID_NOTIF));
		}
		rows.add(new Row(NokiaIcons.ICON_NOTIFICATIONS_OFF, "允许读取通知", ID_LISTENER));
		rows.add(new Row(NokiaIcons.ICON_REFRESH, "一键修复全部缺失项", ID_FIX_ALL));

		// 顶部小标题
		TextView title = view.findViewById(R.id.settingsTitle);
		if (title != null) title.setText("系统权限自检");

		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;
		listScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view, listScroll);

		itemViews = new View[rows.size()];
		tvStatus = new TextView[rows.size()];
		for (int i = 0; i < rows.size(); i++) {
			final int index = i;
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4),
					NokiaDimens.dp(getResources(), 10), NokiaDimens.dp(getResources(), 4));
			row.setClickable(true);

			// 图标
			ImageView ivIcon = new ImageView(requireContext());
			ivIcon.setLayoutParams(new LinearLayout.LayoutParams(
					NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20)));
			ivIcon.setImageDrawable(NokiaIcons.get(requireContext(), rows.get(i).icon, 0xFFFFFFFF, 20));
			row.addView(ivIcon);

			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			// 权限名 + 状态文案（两段：名称固定、状态动态刷新）
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setText(rows.get(i).name);
			tvName.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tvName, 12);
			row.addView(tvName);

			// 状态文案
			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tv.setTextColor(0xFFCCCCCC);
			NokiaFontManager.textSize(tv, 12);
			tvStatus[i] = tv;
			row.addView(tv);

			row.addView(spaceView(NokiaDimens.dp(getResources(), 6), 1));

			// 箭头
			TextView tvArrow = new TextView(requireContext());
			tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvArrow.setText(">");
			tvArrow.setTextColor(0xFFAAAAAA);
			NokiaFontManager.textSize(tvArrow, 14);
			row.addView(tvArrow);

			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});
			listLayout.addView(row);
			itemViews[i] = row;
		}

		refreshStatuses();
		setFocusIndex(0);
		NokiaLog.i(TAG, "系统权限自检页初始化完成，共 " + rows.size() + " 项");
	}

	/** 刷新各行状态文案（授权后回调调用）。 */
	private void refreshStatuses() {
		boolean appList = NokiaPermissionManager.hasAppListPermission(requireContext());
		boolean phone = NokiaPermissionManager.isGranted(requireContext(), Permission.READ_PHONE_STATE);
		boolean notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
				|| NokiaPermissionManager.isGranted(requireContext(), Permission.POST_NOTIFICATIONS);
		boolean listener = NokiaPermissionManager.isGranted(requireContext(), Permission.BIND_NOTIFICATION_LISTENER_SERVICE);
		boolean allOk = appList && phone && notif && listener;

		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			String status;
			switch (r.id) {
				case ID_APP_LIST:   status = appList ? "已授权" : "未授权"; break;
				case ID_PHONE:      status = phone ? "已授权" : "未授权"; break;
				case ID_NOTIF:      status = notif ? "已授权" : "未授权"; break;
				case ID_LISTENER:   status = listener ? "已授权" : "未授权"; break;
				case ID_FIX_ALL:    status = allOk ? "全部就绪" : "去修复"; break;
				default:            status = "";
			}
			if (tvStatus[i] != null) tvStatus[i].setText(status);
		}
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0) return false;
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		Row r = rows.get(focusIndex);
		NokiaLog.i(TAG, "onSelect id=" + r.id + " name=" + r.name);
		switch (r.id) {
			case ID_APP_LIST:
				if (NokiaPermissionManager.hasAppListPermission(requireContext())) {
					toastShort("该项已授权");
				} else {
					NokiaPermissionManager.requestAppListPermission(host,
							"需要应用列表权限以展示与启动应用", makeCallback(host));
				}
				return true;
			case ID_PHONE:
				if (NokiaPermissionManager.isGranted(requireContext(), Permission.READ_PHONE_STATE)) {
					toastShort("该项已授权");
				} else {
					NokiaPermissionManager.request(host, makeCallback(host), Permission.READ_PHONE_STATE);
				}
				return true;
			case ID_NOTIF:
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
						&& !NokiaPermissionManager.isGranted(requireContext(), Permission.POST_NOTIFICATIONS)) {
					NokiaPermissionManager.request(host, makeCallback(host), Permission.POST_NOTIFICATIONS);
				} else {
					toastShort("该项已授权");
				}
				return true;
			case ID_LISTENER:
				if (NokiaPermissionManager.isGranted(requireContext(), Permission.BIND_NOTIFICATION_LISTENER_SERVICE)) {
					toastShort("该项已授权");
				} else {
					NokiaPermissionManager.request(host, makeCallback(host), Permission.BIND_NOTIFICATION_LISTENER_SERVICE);
				}
				return true;
			case ID_FIX_ALL:
				if (NokiaPermissionManager.isCorePermissionsGranted(requireContext())) {
					toastShort("全部权限已就绪");
				} else {
					NokiaPermissionManager.requestCorePermissions(host,
							"一键修复所有缺失的系统权限", makeCallback(host));
				}
				return true;
			default:
				return false;
		}
	}

	/** 权限申请回调：Toast 反馈并刷新状态文案。 */
	private OnPermissionCallback makeCallback(NokiaDesktopActivity host) {
		return new OnPermissionCallback() {
			@Override
			public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
				host.runOnUiThread(() -> {
					toastShort(allGranted ? "权限已就绪" : "部分权限未授予");
					refreshStatuses();
				});
			}
			@Override
			public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
				host.runOnUiThread(() -> {
					toastShort(doNotAskAgain ? "已勾选不再询问，请去系统设置开启" : "权限被拒");
					refreshStatuses();
				});
			}
		};
	}

	private void toastShort(String msg) {
		android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show();
	}

	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}

	// ---- NokiaPage 接口（底部菜单栏）----

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

	@Override
	public String getPageTitle() {
		return "系统权限自检";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}
}
