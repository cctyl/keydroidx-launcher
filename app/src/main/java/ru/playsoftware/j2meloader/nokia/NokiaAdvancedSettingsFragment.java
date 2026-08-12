package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * 高级设置。集中存放依赖 mini_shizuku 服务的高级功能入口：
 * <ul>
 *     <li>mini_shizuku：进入服务激活页（adb / root）；</li>
 *     <li>电源键拦截：行内自绘开关，点击或确认键直接切换。</li>
 * </ul>
 * 顶部提示：本页面的功能均需要 mini_shizuku 支持。
 */
public class NokiaAdvancedSettingsFragment extends NokiaPageFragment {

	private static final int[] ITEM_ICONS = {
			R.drawable.ic_nokia_settings,  // mini_shizuku
			R.drawable.ic_nokia_lock,      // 电源键拦截
	};

	private static final String[] ITEM_NAMES = {
			"mini_shizuku",
			"电源键拦截",
	};

	private View[] itemViews;
	private TextView[] tvNames;
	private ScrollView settingsScroll;
	private int focusIndex = -1;
	private View selectedView = null;

	// 电源键拦截开关（行内自绘，index 1 专属）
	private NokiaSwitchView interceptorSwitch;
	private boolean interceptorEnabled = false;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_advanced_settings;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		settingsScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view);

		// 电源键拦截持久化状态（服务离线时仅展示，不强行激活）
		interceptorEnabled = NokiaSettingsStorage.isPowerInterceptorEnabled(requireContext());

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

			// 名称
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setText(ITEM_NAMES[i]);
			tvName.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tvName, 12);
			tvNames[i] = tvName;
			row.addView(tvName);

			// 行尾控件：mini_shizuku 用箭头；电源键拦截用自绘开关
			if (i == 0) {
				TextView tvArrow = new TextView(requireContext());
				tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
				tvArrow.setText(">");
				tvArrow.setTextColor(0xFFAAAAAA);
				NokiaDimens.textSize(tvArrow, 14);
				row.addView(tvArrow);
			} else {
				interceptorSwitch = new NokiaSwitchView(requireContext(), interceptorEnabled);
				row.addView(interceptorSwitch);
			}

			final int index = i;
			row.setOnClickListener(v -> {
				setFocusIndex(index);
				onSelect();
			});

			listLayout.addView(row);
			itemViews[i] = row;
		}

		// 默认选中第一项
		setFocusIndex(0);

		// 异步刷新 mini_shizuku 在线状态，更新第 1 项（index 0）文案
		refreshShizukuStatus();

		NokiaLog.i("AdvancedSettings", "高级设置初始化完成");
	}

	/** 构建行内自绘开关（诺基亚风格：跑道形轨道 + 圆形滑块）。 */
	private static class NokiaSwitchView extends View {
		private static final int COLOR_TRACK_OFF = 0xFF445566;
		private static final int COLOR_TRACK_ON = 0xFF64B5F6;
		private static final int COLOR_THUMB = 0xFFFFFFFF;

		private boolean checked;
		private final int trackW;
		private final int trackH;
		private final int thumbS;
		private final int margin;

		private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

		NokiaSwitchView(Context context, boolean checked) {
			super(context);
			this.checked = checked;
			trackW = NokiaDimens.dp(context.getResources(), 34);
			trackH = NokiaDimens.dp(context.getResources(), 16);
			thumbS = trackH - NokiaDimens.dp(context.getResources(), 4);
			margin = NokiaDimens.dp(context.getResources(), 2);
			trackPaint.setStyle(Paint.Style.FILL);
			thumbPaint.setStyle(Paint.Style.FILL);
		}

		/** 切换开关状态并重绘。 */
		void setChecked(boolean checked) {
			this.checked = checked;
			invalidate();
		}

		boolean isChecked() {
			return checked;
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			// 固定 34×16dp，所有分辨率下尺寸一致（视觉缩放由 Activity 整体 scale 接管）
			setMeasuredDimension(trackW, trackH);
		}

		@Override
		protected void onDraw(Canvas canvas) {
			// 跑道形轨道：圆角半径 = height/2 形成左右两端半圆
			trackPaint.setColor(checked ? COLOR_TRACK_ON : COLOR_TRACK_OFF);
			canvas.drawRoundRect(0, 0, trackW, trackH, trackH / 2f, trackH / 2f, trackPaint);
			// 圆形滑块：开启靠右、关闭靠左
			thumbPaint.setColor(COLOR_THUMB);
			float cx = checked ? (trackW - margin - thumbS / 2f) : (margin + thumbS / 2f);
			float cy = trackH / 2f;
			canvas.drawCircle(cx, cy, thumbS / 2f, thumbPaint);
		}
	}

	/** 后台检测 mini_shizuku 服务是否在线，回主线程刷新第 1 项文案。 */
	private void refreshShizukuStatus() {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final boolean running = Shizuku.isRunning();
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded() || tvNames == null || tvNames.length < 1 || tvNames[0] == null) {
							return;
						}
						tvNames[0].setText("mini_shizuku：" + (running ? "在线" : "离线"));
						NokiaLog.i("AdvancedSettings", "mini_shizuku 状态: " + (running ? "在线" : "离线"));
					}
				});
			}
		}, "shizuku-status-check").start();
	}

	/**
	 * 切换电源键拦截开关。要求 mini_shizuku 服务在线；离线时提示先激活。
	 * TCP 探测/发送为网络操作，必须在后台线程执行；结果回主线程更新开关。
	 */
	private void togglePowerInterceptor() {
		final Handler mainHandler = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				if (!Shizuku.isRunning()) {
					mainHandler.post(new Runnable() {
						@Override
						public void run() {
							if (!isAdded()) return;
							Toast.makeText(requireContext(), "服务未在线，请先激活", Toast.LENGTH_SHORT).show();
						}
					});
					return;
				}
				final boolean enable = !interceptorEnabled;
				final boolean ok = Shizuku.enablePowerInterceptor(enable);
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						if (!isAdded()) return;
						if (ok) {
							interceptorEnabled = enable;
							NokiaSettingsStorage.setPowerInterceptorEnabled(requireContext(), enable);
							if (interceptorSwitch != null) interceptorSwitch.setChecked(enable);
							NokiaLog.i("AdvancedSettings", "电源键拦截已" + (enable ? "开启" : "关闭"));
							Toast.makeText(requireContext(), enable ? "电源键拦截已开启" : "电源键拦截已关闭",
									Toast.LENGTH_SHORT).show();
						} else {
							Toast.makeText(requireContext(), "发送命令失败", Toast.LENGTH_SHORT).show();
						}
					}
				});
			}
		}, "shizuku-interceptor-toggle").start();
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
		if (focusIndex < 0) return false;
		switch (focusIndex) {
			case 0:
				NokiaLog.i("AdvancedSettings", "进入 mini_shizuku 服务");
				((NokiaDesktopActivity) requireActivity()).openFragment(new ShizukuFragment());
				return true;
			case 1:
				NokiaLog.i("AdvancedSettings", "电源键拦截开关");
				togglePowerInterceptor();
				return true;
			default:
				return false;
		}
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
		return "高级设置";
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
