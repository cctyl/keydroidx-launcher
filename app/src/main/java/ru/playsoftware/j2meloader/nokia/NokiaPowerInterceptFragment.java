package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.content.ContextCompat;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 电源键拦截设置页（高级设置 → 电源键拦截设置）。
 * <p>
 * 展示 4 个可选方案供用户选择：
 * <ul>
 *     <li>关闭：不拦截挂机键（发送 INTERCEPTOR_STOP）；</li>
 *     <li>方案1：evdev grab + uinput 回放 + 决策状态机（安卓13 目标方案，实现中，本次仅记录选择）；</li>
 *     <li>方案2：evdev grab 纯消费（安卓4.4 有效，发送 INTERCEPTOR_START）；</li>
 *     <li>方案3：root（预留扩展点，本次仅记录选择）。</li>
 * </ul>
 * 选择结果持久化到 {@link NokiaSettingsStorage#setPowerInterceptorMode}，进入页面时回显当前方案。
 * 所有选择与底层调用均打详细日志（sub: PowerIntercept），便于真机排障。
 */
public class NokiaPowerInterceptFragment extends NokiaPageFragment {

	private static final int[] ITEM_ICONS = {
			R.drawable.ic_nokia_home,      // 关闭
			R.drawable.ic_nokia_settings,  // 方案1
			R.drawable.ic_nokia_settings,  // 方案2
			R.drawable.ic_nokia_lock,      // 方案3
	};

	private static final int[] ITEM_MODES = {
			NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_OFF,
			NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_1,
			NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_2,
			NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_3,
	};

	private static final String[] ITEM_NAMES = {
			"关闭拦截",
			"方案1：grab+回放",
			"方案2：纯消费",
			"方案3：root",
	};

	private View[] itemViews;
	private TextView[] tvNames;
	private ScrollView settingsScroll;
	private int focusIndex = -1;
	private View selectedView = null;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_settings_group;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		TextView title = view.findViewById(R.id.settingsTitle);
		if (title != null) {
			title.setText("电源键拦截设置");
		}

		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		settingsScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view);

		int curMode = NokiaSettingsStorage.getPowerInterceptorMode(requireContext());
		NokiaLog.i("PowerIntercept", "进入电源键拦截设置页，当前模式=" + curMode
				+ " (" + NokiaSettingsStorage.getPowerInterceptorModeName(curMode) + ")");

		itemViews = new View[ITEM_NAMES.length];
		tvNames = new TextView[ITEM_NAMES.length];
		for (int i = 0; i < ITEM_NAMES.length; i++) {
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
					NokiaDimens.dp(getResources(), 22), NokiaDimens.dp(getResources(), 22)));
			try {
				ivIcon.setImageDrawable(ContextCompat.getDrawable(requireContext(), ITEM_ICONS[i]));
			} catch (Exception ignored) {}
			row.addView(ivIcon);

			// 间距
			row.addView(spaceView(NokiaDimens.dp(getResources(), 8), 1));

			// 名称（当前方案追加「（当前）」）
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tvName, 12);
			tvNames[i] = tvName;
			row.addView(tvName);

			final int index = i;
			row.setOnClickListener(v -> {
				NokiaLog.d("PowerIntercept", "点击列表行 index=" + index);
				setFocusIndex(index);
				onSelect();
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		refreshListNames();
		setFocusIndex(0);

		// 异步探测服务在线状态（仅打日志，帮助排障）
		refreshServiceStatus();
	}

	/** 刷新列表名称：当前选中方案追加「（当前）」。 */
	private void refreshListNames() {
		int cur = NokiaSettingsStorage.getPowerInterceptorMode(requireContext());
		if (tvNames == null) return;
		for (int i = 0; i < tvNames.length; i++) {
			if (tvNames[i] == null) continue;
			String text = ITEM_NAMES[i];
			if (ITEM_MODES[i] == cur) {
				text += "（当前）";
			}
			tvNames[i].setText(text);
		}
	}

	/** 后台探测 mini_shizuku 服务是否在线，打日志（不依赖 UI）。 */
	private void refreshServiceStatus() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean running = Shizuku.isRunning();
				NokiaLog.i("PowerIntercept", "mini_shizuku 服务探测结果: "
						+ (running ? "在线" : "离线"));
			}
		}, "shizuku-status-check").start();
	}

	/** 应用用户选择的方案：保存模式 + 打日志 + 按方案执行底层动作。 */
	private void applyMode(int mode) {
		NokiaSettingsStorage.setPowerInterceptorMode(requireContext(), mode);
		refreshListNames();
		NokiaLog.i("PowerIntercept", "用户选择电源键拦截方案: " + mode
				+ " (" + NokiaSettingsStorage.getPowerInterceptorModeName(mode) + ")");
		switch (mode) {
			case NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_OFF:
				applyOff();
				break;
			case NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_1:
				applyMode1();
				break;
			case NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_2:
				applyMode2();
				break;
			case NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_3:
				applyMode3();
				break;
			default:
				NokiaLog.w("PowerIntercept", "未知拦截模式: " + mode);
				break;
		}
	}

	/** 关闭：发送 INTERCEPTOR_STOP（需要服务在线）。 */
	private void applyOff() {
		NokiaLog.i("PowerIntercept", "执行关闭拦截");
		execWithService(false);
	}

	/** 方案2：发送 INTERCEPTOR_START（当前纯消费实现，安卓4.4 有效）。 */
	private void applyMode2() {
		NokiaLog.i("PowerIntercept", "执行方案2（grab 纯消费）启动拦截");
		execWithService(true);
	}

	/** 方案1：实现未完成（决策状态机未接入），仅记录选择。 */
	private void applyMode1() {
		NokiaLog.i("PowerIntercept", "方案1 已选择，但实现未完成（evdev grab + uinput 回放 + "
				+ "决策状态机未接入），本次仅记录选择，不启动拦截");
		if (isAdded()) {
			Toast.makeText(requireContext(), "方案1 实现中，暂未生效", Toast.LENGTH_SHORT).show();
		}
	}

	/** 方案3：root 通道未支持，仅记录选择。 */
	private void applyMode3() {
		NokiaLog.i("PowerIntercept", "方案3（root）已选择，但当前未支持 root 通道，本次仅记录选择");
		if (isAdded()) {
			Toast.makeText(requireContext(), "方案3 需 root，暂未支持", Toast.LENGTH_SHORT).show();
		}
	}

	/**
	 * 依赖服务的底层动作（启动/停止拦截）：TCP 探测/发送为网络操作，必须后台执行；
	 * 服务离线时提示先激活，不静默失败。
	 */
	private void execWithService(final boolean enable) {
		final String cmdName = enable ? "INTERCEPTOR_START" : "INTERCEPTOR_STOP";
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (!Shizuku.isRunning()) {
					NokiaLog.w("PowerIntercept", "发送 " + cmdName + " 失败：mini_shizuku 服务未在线");
					mainHandler.post(new Runnable() {
						@Override
						public void run() {
							if (!isAdded()) return;
							Toast.makeText(requireContext(), "服务未在线，请先激活 mini_shizuku",
									Toast.LENGTH_SHORT).show();
						}
					});
					return;
				}
				final boolean ok = Shizuku.enablePowerInterceptor(enable);
				NokiaLog.i("PowerIntercept", "发送 " + cmdName + " 结果: ok=" + ok);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						Toast.makeText(requireContext(),
								enable ? "拦截已启动（方案2）" : "拦截已关闭",
								Toast.LENGTH_SHORT).show();
					}
				});
			}
		}, "power-interceptor-apply").start();
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		int count = itemViews != null ? itemViews.length : 0;
		if (count == 0) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		int oldIndex = focusIndex;
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > 0) setFocusIndex(focusIndex - 1);
				NokiaLog.d("PowerIntercept", "onDirection 上：old=" + oldIndex + " new=" + focusIndex);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < count - 1) setFocusIndex(focusIndex + 1);
				NokiaLog.d("PowerIntercept", "onDirection 下：old=" + oldIndex + " new=" + focusIndex);
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
				return true; // 纵向列表，左右无效果但消费
			default:
				return false;
		}
	}

	@Override
	public boolean onSelect() {
		if (focusIndex < 0 || focusIndex >= ITEM_MODES.length) return false;
		int mode = ITEM_MODES[focusIndex];
		NokiaLog.d("PowerIntercept", "onSelect 选中 index=" + focusIndex + " mode=" + mode
				+ " (" + NokiaSettingsStorage.getPowerInterceptorModeName(mode) + ")");
		applyMode(mode);
		return true;
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
		return "电源键拦截设置";
	}

	@Override
	public String getSoftLeftText() {
		return "选择";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	// ---- 焦点管理 ----

	private void setFocusIndex(int index) {
		if (itemViews == null || index < 0 || index >= itemViews.length) return;
		clearFocusBackground();
		focusIndex = index;
		applyFocusBackground();
		scrollToVisible(index);
	}

	/** 约束 ScrollView 高度，使列表底部正好落在可视区底边（项目多时可滚动）。 */
	private void constrainScrollHeight(View root) {
		if (settingsScroll == null) return;
		root.post(() -> {
			View parent = (View) root.getParent();
			if (!(parent instanceof View)) return;
			int panelH = ((View) parent).getHeight();
			float scale = root.getScaleX();
			if (scale <= 0) scale = 1;
			int visibleH = (int) (panelH / scale);
			int headH = settingsScroll.getTop();
			int scrollH = visibleH - headH;
			if (scrollH > 0) {
				ViewGroup.LayoutParams lp = settingsScroll.getLayoutParams();
				lp.height = scrollH;
				settingsScroll.setLayoutParams(lp);
			}
		});
	}

	/** 确保焦点行在 ScrollView 可见区域内，方向键导航时自动跟随滚动。 */
	private void scrollToVisible(int index) {
		if (settingsScroll == null || itemViews == null
				|| index < 0 || index >= itemViews.length) return;
		final View item = itemViews[index];
		if (item == null) return;
		settingsScroll.post(() -> {
			int scrollY = settingsScroll.getScrollY();
			int itemTop = item.getTop();
			int itemBottom = item.getBottom();
			int svHeight = settingsScroll.getHeight();
			if (svHeight <= 0) return;
			if (itemTop < scrollY) {
				settingsScroll.smoothScrollTo(0, itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				settingsScroll.smoothScrollTo(0, itemBottom - svHeight);
			}
		});
	}

	private void clearFocusBackground() {
		if (selectedView != null) {
			selectedView.setBackgroundResource(0);
			selectedView = null;
		}
	}

	private void applyFocusBackground() {
		if (focusIndex >= 0 && focusIndex < itemViews.length && itemViews[focusIndex] != null) {
			itemViews[focusIndex].setBackgroundResource(R.drawable.bg_nokia_selected_dark);
			selectedView = itemViews[focusIndex];
		}
	}

	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
