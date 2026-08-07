package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import ru.playsoftware.j2meloader.R;

/**
 * mini_shizuku 服务页面。
 * <p>
 * mini_shizuku 是让无法使用官方 Shizuku（Android 7 以下）的设备也能获得系统级
 * （shell/adb）权限的方案：服务端由用户在电脑上通过 adb 执行发布包附带的
 * {@code mini_shizuku.sh} 脚本拉起（app_process 以 shell 身份运行）。
 * <p>
 * 本页只做两件事：展示服务状态，以及告诉普通用户如何激活。左软键无对应操作
 * （返回 null 隐藏），保留「刷新状态」按钮供激活后检测在线状态。
 */
public class ShizukuFragment extends Fragment implements NokiaPage {

	private TextView statusText;
	private ScrollView contentScroll;
	private LinearLayout actionList;
	private View[] actionViews;
	private int focusIndex = -1;

	private static final String[] ACTION_NAMES = {
			"刷新状态",
	};

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_shizuku, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.scaleMidContent(view, true);

		View wall = host.findViewById(R.id.wallpaper);
		if (wall != null) {
			wall.setBackgroundResource(R.drawable.bg_nokia_menu);
		}
		host.refreshPageBar();

		statusText = view.findViewById(R.id.shizukuStatus);
		contentScroll = view.findViewById(R.id.shizukuScroll);
		actionList = view.findViewById(R.id.shizukuActions);

		buildActionList();

		// 异步刷新状态，避免 TCP 探测阻塞主线程
		refreshStatus();

		setFocusIndex(0);
	}

	/** 构建底部可导航操作列表（方向键 + 确认键触发），目前只有「刷新状态」。 */
	private void buildActionList() {
		actionList.removeAllViews();
		actionViews = new View[ACTION_NAMES.length];
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
			NokiaDimens.textSize(tv, 12);
			row.addView(tv);

			TextView arrow = new TextView(requireContext());
			arrow.setText(">");
			arrow.setTextColor(0xFFAAAAAA);
			NokiaDimens.textSize(arrow, 14);
			row.addView(arrow);

			final int idx = i;
			row.setOnClickListener(v -> {
				setFocusIndex(idx);
				onSelect();
			});

			actionList.addView(row);
			actionViews[i] = row;
		}
	}

	/** 后台检测服务在线状态，回主线程刷新状态行文案。 */
	private void refreshStatus() {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean running = ru.playsoftware.mini_shizuku.Shizuku.isRunning();
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
		boolean supported = ru.playsoftware.mini_shizuku.Shizuku.isSupported();
		String supportInfo = supported
				? "适用（Android 7 以下）"
				: "不适用（Android 7+ 请用官方 Shizuku）";
		statusText.setText("当前设备：" + supportInfo + "\n服务状态：" + (running ? "在线" : "离线"));
		statusText.setTextColor(running ? 0xFF64B5F6 : 0xFFFF8A80);
	}

	private void onAction(int index) {
		if (index == 0) {
			refreshStatus();
			Toast.makeText(requireContext(), "状态已刷新", Toast.LENGTH_SHORT).show();
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		int count = actionViews != null ? actionViews.length : 0;
		if (count == 0) return false;
		if (focusIndex < 0) {
			setFocusIndex(0);
			return true;
		}
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > 0) setFocusIndex(focusIndex - 1);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < count - 1) setFocusIndex(focusIndex + 1);
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
		if (focusIndex >= 0 && focusIndex < ACTION_NAMES.length) {
			onAction(focusIndex);
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键无对应操作，静默消费
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
		// 返回 null 隐藏左软键（无对应操作）
		return null;
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	// ---- 焦点管理 ----

	private void setFocusIndex(int index) {
		if (actionViews == null || index < 0 || index >= actionViews.length) return;
		clearFocusBackground();
		focusIndex = index;
		applyFocusBackground();
		scrollToVisible(index);
	}

	private void clearFocusBackground() {
		if (actionViews == null) return;
		for (View v : actionViews) {
			if (v != null) v.setBackgroundResource(0);
		}
	}

	private void applyFocusBackground() {
		if (focusIndex >= 0 && focusIndex < actionViews.length && actionViews[focusIndex] != null) {
			actionViews[focusIndex].setBackgroundResource(R.drawable.bg_nokia_selected_dark);
		}
	}

	private void scrollToVisible(int index) {
		if (contentScroll == null || actionViews == null || index < 0 || index >= actionViews.length) {
			return;
		}
		final View item = actionViews[index];
		if (item == null) return;
		contentScroll.post(() -> {
			int scrollY = contentScroll.getScrollY();
			int itemTop = item.getTop();
			int itemBottom = item.getBottom();
			int svHeight = contentScroll.getHeight();
			if (svHeight <= 0) return;
			if (itemTop < scrollY) {
				contentScroll.smoothScrollTo(0, itemTop);
			} else if (itemBottom > scrollY + svHeight) {
				contentScroll.smoothScrollTo(0, itemBottom - svHeight);
			}
		});
	}
}
