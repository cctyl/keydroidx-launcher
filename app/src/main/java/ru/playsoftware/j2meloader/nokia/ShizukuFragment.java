package ru.playsoftware.j2meloader.nokia;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;
import io.github.cctyl.nokia.common.util.KeydroidxDimens;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.ServerIdentity;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * mini_shizuku 服务页面（授权模式双轨制入口）。
 * <p>
 * 两种互斥授权模式（用户单选，持久化于 {@link KeydroidxSettingsStorage}）：
 * <ul>
 *     <li><b>root 模式</b>：服务端以 root 身份运行（桌面内 root 激活，su -cn 切 shell 域
 *     + 启动早期补 inet 组），功能最全；</li>
 *     <li><b>mini_shizuku 模式</b>：服务端以 shell 身份运行（电脑 adb 激活），兼容最广，
 *     shell 做不了的操作（如 4.4 冻结）直接失败并提示。</li>
 * </ul>
 * 模式是「期望的服务端身份」，实际以 WHOAMI 探测到的 uid 为准，不一致时状态行
 * 明确提示重新激活（设计文档 §3.4）。
 * <p>
 * 页面结构：
 * <ul>
 *     <li>顶部状态行：当前模式 / 服务状态 / 服务端身份一致性；</li>
 *     <li>可导航菜单：授权模式切换 / root 激活 / adb 激活；</li>
 *     <li>左软键「刷新」；右软键「返回」。</li>
 * </ul>
 * <p>
 * 电源键拦截开关已移至「高级设置」（{@link KeydroidxAdvancedSettingsFragment}）。
 */
public class ShizukuFragment extends KeydroidxListPageFragment {

	private TextView statusText;
	private LinearLayout actionList;

	/** 菜单项：root 模式（确认 = 切换模式并直接 root 激活，无子页）。 */
	private static final int ACTION_ROOT = 0;
	/** 菜单项：adb 模式（确认 = 切换模式；激活需电脑 adb，进说明页）。 */
	private static final int ACTION_ADB = 1;
	private static final int ACTION_COUNT = 2;

	/** 动态菜单标签。 */
	private String actionLabel(int index) {
		switch (index) {
			case ACTION_ROOT:
				return "root 模式";
			case ACTION_ADB:
				return "adb 模式";
			default:
				return "";
		}
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_shizuku;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		statusText = view.findViewById(R.id.shizukuStatus);
		listScroll = view.findViewById(R.id.shizukuScroll);
		actionList = view.findViewById(R.id.shizukuActions);

		buildActionList();

		// 顶部状态行的「当前模式」即切换入口（点击 = 在 root ↔ mini_shizuku 间切换），
		// 不再单设「授权模式」菜单行——避免与状态行信息重复。
		statusText.setOnClickListener(v -> toggleMode());
		statusText.setClickable(true);

		// 异步刷新状态，避免 TCP 探测阻塞主线程
		refreshStatus();

		setFocusIndex(0);
	}

	/** 构建底部可导航操作列表（方向键 + 确认键触发）。可重复调用以刷新动态标签。 */
	private void buildActionList() {
		actionList.removeAllViews();
		itemViews = new View[ACTION_COUNT];
		for (int i = 0; i < ACTION_COUNT; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			// 高度 WRAP_CONTENT + minHeight：大字号/点阵字体行盒放大后固定行高会裁掉文字
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
			row.setMinimumHeight(KeydroidxDimens.dp(getResources(), 36));
			row.setPadding(KeydroidxDimens.dp(getResources(), 12), 0, KeydroidxDimens.dp(getResources(), 12), 0);
			row.setClickable(true);

			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(actionLabel(i));
			tv.setTextColor(0xFFFFFFFF);
			KeydroidxFontManager.textSize(tv, 12);
			row.addView(tv);

			TextView arrow = new TextView(requireContext());
			arrow.setText(">");
			arrow.setTextColor(0xFFAAAAAA);
			KeydroidxFontManager.textSize(arrow, 13);
			row.addView(arrow);

			final int idx = i;
			row.setOnClickListener(v -> {
				setFocusIndex(idx);
				onSelect();
			});

			actionList.addView(row);
			itemViews[i] = row;
		}
	}

	/** 后台检测服务在线状态与服务端身份（WHOAMI），回主线程刷新状态行。 */
	private void refreshStatus() {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean running = Shizuku.isRunning();
				final int uid = running ? Shizuku.serverUid() : ServerIdentity.UID_UNKNOWN;
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						updateStatusText(running, uid);
					}
				});
			}
		}, "shizuku-status-check").start();
	}

	/**
	 * 刷新状态行：当前模式 / 服务状态 / 服务端真实身份与所选模式的一致性。
	 * 身份取 WHOAMI 探测结果（uid），与模式不符时明确提示重新激活。
	 */
	private void updateStatusText(boolean running, int serverUid) {
		if (statusText == null) return;
		int mode = KeydroidxSettingsStorage.getAuthMode(requireContext());
		StringBuilder sb = new StringBuilder();
		sb.append("当前模式：").append(KeydroidxSettingsStorage.getAuthModeName(mode))
				.append("（点击切换）").append('\n');
		sb.append("服务状态：").append(running ? "在线" : "离线").append('\n');
		int color;
		if (!running) {
			sb.append("服务端身份：—（服务离线，请激活）");
			color = 0xFFFF8A80;
		} else if (serverUid == ServerIdentity.UID_UNKNOWN) {
			sb.append("服务端身份：无法确认（旧版服务端不含 WHOAMI）");
			color = 0xFF64B5F6;
		} else {
			boolean isRoot = serverUid == 0;
			boolean match = (mode == KeydroidxSettingsStorage.AUTH_MODE_ROOT) == isRoot;
			sb.append("服务端身份：uid=").append(serverUid)
					.append(isRoot ? "（root）" : "（shell）")
					.append(match ? "  ✓ 与模式一致" : "  ✗ 与模式不符，请重新激活");
			color = match ? 0xFF81C784 : 0xFFFF8A80;
		}
		statusText.setText(sb.toString());
		statusText.setTextColor(color);
	}

	/**
	 * 校验已在线服务端身份与新模式的一致性（不一致则提醒重新激活）。
	 * <p>
	 * {@link Shizuku#isRunning()} / {@link Shizuku#serverUid()} 内部是 TCP 连接，
	 * <b>严禁在主线程调用</b>——4.4 上会抛 {@link android.os.NetworkOnMainThreadException}
	 * 直接崩溃（2026-09 真机实测：点击「授权模式」即闪退，即此原因）。
	 */
	private void checkServerIdentityMatch(final int newMode) {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (!Shizuku.isRunning()) return;
				final int uid = Shizuku.serverUid();
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						boolean match = (newMode == KeydroidxSettingsStorage.AUTH_MODE_ROOT) == (uid == 0);
						if (uid != ServerIdentity.UID_UNKNOWN && !match) {
							Toast.makeText(requireContext(),
									"当前服务端身份与新模式不符，请重新激活", Toast.LENGTH_SHORT).show();
						}
					}
				});
			}
		}, "shizuku-mode-identity").start();
	}

	private void onAction(int index) {
		if (index < 0 || index >= ACTION_COUNT) return;
		switch (index) {
			case ACTION_ROOT:
				// 确认即生效：切 root 模式 + 直接 root 激活（无子页）。
				// 模式切换在 activateRootInline 内做（失败时需按原模式回滚）
				activateRootInline();
				break;
			case ACTION_ADB:
				// 确认即切换 adb 模式；激活需电脑 adb，进说明页（不是子菜单，是必要指引）
				KeydroidxSettingsStorage.setAuthMode(requireContext(),
						KeydroidxSettingsStorage.AUTH_MODE_SHIZUKU);
				refreshStatus();
				checkServerIdentityMatch(KeydroidxSettingsStorage.AUTH_MODE_SHIZUKU);
				((KeydroidxDesktopActivity) requireActivity()).openFragment(new ShizukuAdbFragment());
				break;
			default:
				break;
		}
	}

	/** 后台执行 root 激活并 toast 结果（{@link KeydroidxShizukuActivator} 阻塞流程）。 */
	private void activateRootInline() {
		final Context appCtx = requireContext().getApplicationContext();
		// 记住原模式：无 root 设备上激活必然失败，须回滚，
		// 否则留下「模式=root 但永远激活不了」的死状态（2026-09 无 root 真机实测）。
		final int prevMode = KeydroidxSettingsStorage.getAuthMode(appCtx);
		KeydroidxSettingsStorage.setAuthMode(appCtx, KeydroidxSettingsStorage.AUTH_MODE_ROOT);
		refreshStatus();
		Toast.makeText(requireContext(), "正在通过 root 激活...", Toast.LENGTH_SHORT).show();
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final KeydroidxShizukuActivator.Result r =
						KeydroidxShizukuActivator.activateRootServer(appCtx);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						refreshStatus();
						String msg;
						if (r.busy) {
							// 另一次激活（本页或 root 激活详情页）正在进行：它同样会 kill 所有
							// app_process，再起一次只会互相拆台，这里直接放弃并回滚模式
							KeydroidxSettingsStorage.setAuthMode(appCtx, prevMode);
							msg = "正在激活中，请稍候…";
						} else if (!r.execOk) {
							// su 不可用/被拒：回滚模式并明确告知
							KeydroidxSettingsStorage.setAuthMode(appCtx, prevMode);
							msg = "root 激活失败：无 root 或 su 授权被拒，已保持原模式";
						} else if (!r.online) {
							msg = "root 命令已执行，但服务未上线，请查看日志";
						} else if (!r.isFullyOk()) {
							msg = "服务已上线，但身份异常 (uid=" + r.serverUid + " ≠ 0)";
						} else {
							msg = "root 激活成功，已切换 root 模式";
						}
						// LENGTH_LONG：低分屏上 SHORT 约 2 秒即消失，用户来不及看到失败原因
						Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
					}
				});
			}
		}, "shizuku-inline-activate").start();
	}

	/** 切换授权模式（root ↔ mini_shizuku），入口：顶部状态行「当前模式」点击。 */
	private void toggleMode() {
		int cur = KeydroidxSettingsStorage.getAuthMode(requireContext());
		int next = cur == KeydroidxSettingsStorage.AUTH_MODE_ROOT
				? KeydroidxSettingsStorage.AUTH_MODE_SHIZUKU
				: KeydroidxSettingsStorage.AUTH_MODE_ROOT;
		KeydroidxSettingsStorage.setAuthMode(requireContext(), next);
		KeydroidxLog.i("Shizuku", "授权模式切换: " + KeydroidxSettingsStorage.getAuthModeName(cur)
				+ " -> " + KeydroidxSettingsStorage.getAuthModeName(next));
		refreshStatus();
		checkServerIdentityMatch(next);
	}

	@Override
	public boolean onSelect() {
		if (focusIndex >= 0 && focusIndex < ACTION_COUNT) {
			onAction(focusIndex);
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键 = 刷新状态
		refreshStatus();
		Toast.makeText(requireContext(), "状态已刷新", Toast.LENGTH_SHORT).show();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- KeydroidxPage ----

	@Override
	public String getPageTitle() {
		return "mini_shizuku";
	}

	@Override
	public String getSoftLeftText() {
		return "刷新";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}


}



