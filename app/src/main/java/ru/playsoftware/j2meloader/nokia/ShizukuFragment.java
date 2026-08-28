package ru.playsoftware.j2meloader.nokia;

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

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.util.NokiaDimens;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * mini_shizuku 服务页面。
 * <p>
 * 所有 Android 版本统一使用 mini_shizuku 权限通道。服务端由用户在电脑上通过 adb 执行
 * 发布包附带的 {@code mini_shizuku.sh} 脚本拉起（app_process 以 shell 身份运行）。
 * <p>
 * 页面结构：
 * <ul>
 *     <li>顶部状态行：显示当前通道与服务在线状态；</li>
 *     <li>主体为两条可导航菜单（adb 激活 / root 激活），方向键选中、确认键进入对应子页；</li>
 *     <li>左软键「刷新」：重新检测服务在线状态；右软键「返回」：返回上一层。</li>
 * </ul>
 * <p>
 * 电源键拦截开关已移至「高级设置」（{@link NokiaAdvancedSettingsFragment}）。
 */
public class ShizukuFragment extends NokiaListPageFragment {

	private TextView statusText;
	private LinearLayout actionList;

	private static final String[] ACTION_NAMES = {
			"adb 激活",
			"root 激活",
	};

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

		// 异步刷新状态，避免 TCP 探测阻塞主线程
		refreshStatus();

		setFocusIndex(0);
	}

	/** 构建底部可导航操作列表（方向键 + 确认键触发）。 */
	private void buildActionList() {
		actionList.removeAllViews();
		itemViews = new View[ACTION_NAMES.length];
		for (int i = 0; i < ACTION_NAMES.length; i++) {
			LinearLayout row = new LinearLayout(requireContext());
			row.setOrientation(LinearLayout.HORIZONTAL);
			row.setGravity(Gravity.CENTER_VERTICAL);
			row.setLayoutParams(new LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT, NokiaDimens.dp(getResources(), 36)));
			row.setPadding(NokiaDimens.dp(getResources(), 12), 0, NokiaDimens.dp(getResources(), 12), 0);
			row.setClickable(true);

			TextView tv = new TextView(requireContext());
			tv.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tv.setText(ACTION_NAMES[i]);
			tv.setTextColor(0xFFFFFFFF);
			NokiaFontManager.textSize(tv, 12);
			row.addView(tv);

			TextView arrow = new TextView(requireContext());
			arrow.setText(">");
			arrow.setTextColor(0xFFAAAAAA);
			NokiaFontManager.textSize(arrow, 14);
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

	/** 后台检测服务在线状态，回主线程刷新状态行文案。 */
	private void refreshStatus() {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean running = Shizuku.isRunning();
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						updateStatusText(running);
					}
				});
			}
		}, "shizuku-status-check").start();
	}

	private void updateStatusText(boolean running) {
		if (statusText == null) return;
		statusText.setText("当前通道：mini_shizuku\n服务状态：" + (running ? "在线" : "离线"));
		statusText.setTextColor(running ? 0xFF64B5F6 : 0xFFFF8A80);
	}

	private void onAction(int index) {
		if (index < 0 || index >= ACTION_NAMES.length) return;
		switch (index) {
			case 0:
				NokiaLog.i("Shizuku", "进入 adb 激活说明页");
				((NokiaDesktopActivity) requireActivity()).openFragment(new ShizukuAdbFragment());
				break;
			case 1:
				NokiaLog.i("Shizuku", "进入 root 激活页（占位）");
				((NokiaDesktopActivity) requireActivity()).openFragment(new ShizukuRootFragment());
				break;
			default:
				break;
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onSelect() {
		if (focusIndex >= 0 && focusIndex < ACTION_NAMES.length) {
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
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	// ---- NokiaPage ----

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



