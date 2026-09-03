package ru.playsoftware.j2meloader.nokia;

import android.app.PendingIntent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaIcons;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import ru.playsoftware.j2meloader.R;

/**
 * 通知中心列表页（screenId: notifications.center）。
 * <p>
 * 展示桌面读取到的系统通知，支持方向键逐条聚焦、确认键打开、选项弹窗清除/全部清除。
 * 软键约定：左软键「选项」、右软键「返回」，破坏性操作（全部清除）先二次确认且默认
 * 焦点在「取消」（规范 §15/§16/§41/§42）。
 * <p>
 * 复用 {@link NokiaListPageFragment} 的循环导航/焦点/滚动跟随三件套，本类只负责：
 * <ul>
 * <li>订阅 {@link NokiaNotificationRepository} 并重建列表（保持焦点位置）；</li>
 * <li>行视图构建（图标 + 应用名/时间 + 标题 + 摘要 + 未读点）；</li>
 * <li>打开通知（contentIntent.send，失败不崩溃）与清除动作。</li>
 * </ul>
 */
public class NokiaNotificationCenterFragment extends NokiaListPageFragment
		implements NokiaNotificationRepository.Listener {

	private LinearLayout listLayout;
	private TextView tvHint;
	private View emptyView;
	private TextView emptyIcon;
	private TextView emptyTitle;
	private TextView emptyText;

	/** 当前展示的条目，与 itemViews 一一对应。 */
	private List<NokiaNotificationItem> current = new ArrayList<>();

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_notification_center;
	}

	@Override
	public void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		listLayout = view.findViewById(R.id.notifCenterList);
		tvHint = view.findViewById(R.id.notifCenterHint);
		emptyView = view.findViewById(R.id.notifCenterEmpty);
		emptyIcon = view.findViewById(R.id.notifCenterEmptyIcon);
		emptyTitle = view.findViewById(R.id.notifCenterEmptyTitle);
		emptyText = view.findViewById(R.id.notifCenterEmptyText);
		emptyIcon.setText(NokiaIcons.NOTIFICATIONS);

		listScroll = view.findViewById(R.id.notifCenterScroll);
		constrainScrollHeight(view, listScroll);

		NokiaNotificationRepository.get().addListener(this);
		rebuildList(null);
	}

	@Override
	public void onResume() {
		super.onResume();
		// 进入页面即视为已读
		NokiaNotificationRepository.get().markAllRead();
	}

	@Override
	public void onDestroyView() {
		NokiaNotificationRepository.get().removeListener(this);
		current = new ArrayList<>();
		super.onDestroyView();
	}

	// ---- 数据驱动 ----

	@Override
	public void onNotificationsChanged() {
		if (!isAdded() || getView() == null) return;
		// 刷新时保持焦点所在通知（按 key 匹配），匹配不到则收敛到最近一条
		String focusedKey = focusIndex >= 0 && focusIndex < current.size()
				? current.get(focusIndex).key : null;
		rebuildList(focusedKey);
		// 页面可见期间新到的通知同样视为已读（用户正在看），回桌面后通知条计数才一致。
		// markAllRead 无变化时不再触发回调，不会形成循环。
		NokiaNotificationRepository.get().markAllRead();
	}

	/**
	 * 按仓储快照重建列表。
	 *
	 * @param keepFocusKey 重建后要恢复焦点的通知 key；null 则聚焦第一条
	 */
	private void rebuildList(@Nullable String keepFocusKey) {
		if (listLayout == null) return;
		current = NokiaNotificationRepository.get().getItems();
		boolean granted = NokiaMusicSessionReader.isNotificationListenerEnabled(requireContext());
		boolean connected = NokiaNotificationRepository.get().isConnected();

		// 状态提示条：未授权 > 连接中 > 隐藏
		if (!granted) {
			showHint("未获得通知使用权，无法读取通知。请到 系统设置 → 通知使用权 中授权本应用。");
		} else if (!connected) {
			showHint("正在连接通知服务…");
		} else {
			showHint(null);
		}

		// 空状态
		if (current.isEmpty()) {
			listLayout.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
			if (granted && !connected) {
				emptyTitle.setText("正在读取");
				emptyText.setText("通知服务连接中…");
			} else if (!granted) {
				emptyTitle.setText("无法读取通知");
				emptyText.setText("请先授予通知使用权");
			} else {
				emptyTitle.setText("没有通知");
				emptyText.setText("新通知会显示在这里");
			}
			itemViews = new View[0];
			focusIndex = -1;
			((NokiaDesktopActivity) requireActivity()).refreshPageBar();
			return;
		}
		emptyView.setVisibility(View.GONE);
		listLayout.setVisibility(View.VISIBLE);

		listLayout.removeAllViews();
		itemViews = new View[current.size()];
		for (int i = 0; i < current.size(); i++) {
			NokiaNotificationItem item = current.get(i);
			View row = buildRow(item, i);
			listLayout.addView(row);
			itemViews[i] = row;
		}

		// 恢复焦点
		int target = 0;
		if (keepFocusKey != null) {
			for (int i = 0; i < current.size(); i++) {
				if (current.get(i).key.equals(keepFocusKey)) {
					target = i;
					break;
				}
			}
		}
		setFocusIndex(target);
	}

	private void showHint(@Nullable String text) {
		if (tvHint == null) return;
		if (text == null || text.isEmpty()) {
			tvHint.setVisibility(View.GONE);
		} else {
			tvHint.setText(text);
			tvHint.setVisibility(View.VISIBLE);
		}
	}

	/** 构建单行通知条目：应用图标 + (应用名/时间 + 标题 + 摘要) + 未读点。 */
	private View buildRow(final NokiaNotificationItem item, final int index) {
		int dp4 = NokiaDimens.dp(getResources(), 4);
		LinearLayout row = new LinearLayout(requireContext());
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		row.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 46)));
		row.setPadding(dp4 * 2, dp4, dp4 * 2, dp4);
		row.setClickable(true);

		// 应用图标（20dp）
		android.widget.ImageView ivIcon = new android.widget.ImageView(requireContext());
		LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
				NokiaDimens.dp(getResources(), 20), NokiaDimens.dp(getResources(), 20));
		ivIcon.setLayoutParams(iconLp);
		if (item.icon != null) {
			ivIcon.setImageDrawable(item.icon);
		} else {
			ivIcon.setImageDrawable(NokiaIcons.get(requireContext(),
					NokiaIcons.ICON_NOTIFICATIONS, 0xFF9DB4E0, 16));
		}
		row.addView(ivIcon);

		// 右侧两行文本区
		LinearLayout body = new LinearLayout(requireContext());
		body.setOrientation(LinearLayout.VERTICAL);
		body.setLayoutParams(new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

		// 行1：应用名 …… 时间
		LinearLayout line1 = new LinearLayout(requireContext());
		line1.setOrientation(LinearLayout.HORIZONTAL);
		TextView tvApp = new TextView(requireContext());
		tvApp.setLayoutParams(new LinearLayout.LayoutParams(
				0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
		tvApp.setText(item.appName);
		tvApp.setTextColor(0xFF9DB4E0);
		tvApp.setSingleLine(true);
		tvApp.setEllipsize(android.text.TextUtils.TruncateAt.END);
		NokiaFontManager.textSize(tvApp, 10);
		line1.addView(tvApp);

		TextView tvTime = new TextView(requireContext());
		tvTime.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tvTime.setText(formatTime(item.postTime));
		tvTime.setTextColor(0xFF7F96C4);
		NokiaFontManager.textSize(tvTime, 9);
		line1.addView(tvTime);
		body.addView(line1);

		// 行2：标题（主文本，每行必有）
		TextView tvTitle = new TextView(requireContext());
		tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
		tvTitle.setText(item.displayTitle());
		tvTitle.setTextColor(0xFFFFFFFF);
		tvTitle.setSingleLine(true);
		tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
		NokiaFontManager.textSize(tvTitle, 12);
		body.addView(tvTitle);

		// 行3：正文摘要（次文本，可无）
		if (item.text != null && item.text.trim().length() > 0) {
			TextView tvText = new TextView(requireContext());
			tvText.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			tvText.setText(item.text);
			tvText.setTextColor(0xFFB8C8EA);
			tvText.setSingleLine(true);
			tvText.setEllipsize(android.text.TextUtils.TruncateAt.END);
			NokiaFontManager.textSize(tvText, 10);
			body.addView(tvText);
		}
		row.addView(body);

		row.setOnClickListener(v -> {
			setFocusIndex(index);
			onSelect();
		});
		return row;
	}

	/** 时间格式：今天 HH:mm / 昨天 / M月d日。 */
	private String formatTime(long postTime) {
		Calendar now = Calendar.getInstance();
		Calendar t = Calendar.getInstance();
		t.setTimeInMillis(postTime);
		if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
				&& now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
			return String.format(Locale.getDefault(), "%02d:%02d",
					t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
		}
		now.add(Calendar.DAY_OF_YEAR, -1);
		if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
				&& now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
			return "昨天";
		}
		return String.format(Locale.getDefault(), "%d月%d日",
				t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
	}

	// ---- 软键与选择 ----

	@Override
	public String getPageTitle() {
		return "通知中心";
	}

	@Override
	public String getSoftLeftText() {
		return "选项";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= current.size()) return false;
		final NokiaNotificationItem item = current.get(focusIndex);
		if (item.contentIntent == null) {
			Toast.makeText(requireContext(), "该通知无法打开", Toast.LENGTH_SHORT).show();
			return true;
		}
		try {
			item.contentIntent.send();
			NokiaLog.i("NotifCenter", "打开通知: " + item.pkg + " " + item.displayTitle());
		} catch (PendingIntent.CanceledException e) {
			NokiaLog.w("NotifCenter", "打开通知失败: " + e.getMessage());
			Toast.makeText(requireContext(), "无法打开该通知", Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			NokiaLog.e("NotifCenter", "打开通知异常", e);
			Toast.makeText(requireContext(), "无法打开该通知", Toast.LENGTH_SHORT).show();
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		showOptionsMenu();
		return true;
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

	/** 左软键选项弹窗（规范 §15）。未授权时提供授权引导；不可清除的条目置灰。 */
	private void showOptionsMenu() {
		// 弹窗是独立窗口，show 是异步的：连按左软键可能叠出两个弹窗，第二个会把第一个
		// 刚执行过的动作再执行一遍。已显示时直接忽略。
		if (getParentFragmentManager().findFragmentByTag("NokiaOptions") != null) {
			return;
		}
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		boolean granted = NokiaMusicSessionReader.isNotificationListenerEnabled(requireContext());

		if (!granted) {
			items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_SETTINGS, "去授权", true, false,
					() -> {
						if (!NokiaMusicSessionReader.openNotificationListenerSettings(requireContext())) {
							Toast.makeText(requireContext(), "当前系统版本不支持通知使用权",
									Toast.LENGTH_SHORT).show();
						}
					}));
		} else if (focusIndex >= 0 && focusIndex < current.size()) {
			final NokiaNotificationItem item = current.get(focusIndex);
			items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_DELETE, "清除", item.clearable,
					false, () -> clearOne(item)));
		}
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_CLEAR_ALL, "全部清除",
				granted && !current.isEmpty(), false, this::confirmClearAll));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_SETTINGS, "通知设置", true, false,
				() -> ((NokiaDesktopActivity) requireActivity()).openFragment(
						new NokiaNotificationSettingsFragment())));

		NokiaOptionsDialog.show(getParentFragmentManager(), "通知中心", items);
	}

	/** 单条清除：走服务 cancel；随后强制按系统实际通知列表刷新，不依赖移除回调是否送达。 */
	private void clearOne(NokiaNotificationItem item) {
		NokiaNotificationListenerService service = NokiaNotificationListenerService.getInstance();
		boolean ok = service != null
				&& service.cancelByKey(item.key, item.pkg, item.tag, item.id);
		if (ok) {
			NokiaLog.i("NotifCenter", "已清除通知: " + item.pkg + " " + item.displayTitle());
			// 系统实际列表是唯一事实来源：个别通知的 onNotificationRemoved 可能延迟或丢失，
			// 不主动同步会让幽灵行留在列表里，干扰后续清除操作
			NokiaNotificationRepository.get().refreshFromService();
		} else {
			Toast.makeText(requireContext(), "清除失败：通知服务未连接", Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * 全部清除二次确认（规范 §41/§42）：默认焦点在「取消」，动作文案用明确动词。
	 * NokiaOptionsDialog 默认聚焦第一项，因此把「取消」放第一位。
	 */
	private void confirmClearAll() {
		int count = current.size();
		if (count == 0) return;
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_CLOSE, "取消", true, false, null));
		items.add(new NokiaOptionsDialog.OptionItem(NokiaIcons.ICON_DELETE, "清除", true, false,
				() -> {
					NokiaNotificationListenerService service =
							NokiaNotificationListenerService.getInstance();
					boolean ok = service != null && service.cancelAll();
					if (ok) {
						NokiaLog.i("NotifCenter", "已清除全部通知，共 " + count + " 条");
						NokiaNotificationRepository.get().refreshFromService();
					} else {
						Toast.makeText(requireContext(), "清除失败：通知服务未连接",
								Toast.LENGTH_SHORT).show();
					}
				}));
		NokiaOptionsDialog.show(getParentFragmentManager(), "全部清除？（" + count + " 条，无法撤销）", items);
	}
}
