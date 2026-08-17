package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.mini_shizuku.Shizuku;

/**
 * 高级设置。集中存放依赖 mini_shizuku 服务的高级功能入口：
 * <ul>
 *     <li>mini_shizuku：进入服务激活页（adb / root）；</li>
 *     <li>电源键拦截设置：行尾只读开关展示当前开/关状态（关闭 或 任一方案），
 *         点击/确认整行进入方案选择页（关闭 / 方案1 / 方案2）；root 激活见 mini_shizuku。</li>
 * </ul>
 * 顶部提示：本页面的功能均需要 mini_shizuku 支持。
 */
public class NokiaAdvancedSettingsFragment extends NokiaListPageFragment {

	private static final int[] ITEM_ICONS = {
			R.drawable.ic_nokia_settings,  // mini_shizuku
			R.drawable.ic_nokia_lock,      // 电源键拦截设置
	};

	private static final String[] ITEM_NAMES = {
			"mini_shizuku",
			"电源键拦截设置",
	};

	private TextView[] tvNames;

	// 电源键拦截开关（行尾只读展示，index 1 专属；点击整行进入方案选择页）
	private NokiaSwitchView interceptorSwitch;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_advanced_settings;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		LinearLayout listLayout = view.findViewById(R.id.settingsList);
		if (listLayout == null) return;

		listScroll = view.findViewById(R.id.settingsScroll);
		constrainScrollHeight(view, listScroll);

		int curMode = NokiaSettingsStorage.getPowerInterceptorMode(requireContext());
		NokiaLog.i("AdvancedSettings", "进入高级设置页，当前电源键拦截方案=" + curMode
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

			// 名称（电源键拦截设置项动态显示当前方案）
			TextView tvName = new TextView(requireContext());
			tvName.setLayoutParams(new LinearLayout.LayoutParams(
					0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
			tvName.setText(getItemDisplayName(i));
			tvName.setTextColor(0xFFFFFFFF);
			NokiaDimens.textSize(tvName, 12);
			tvNames[i] = tvName;
			row.addView(tvName);

			// 行尾控件：mini_shizuku 用箭头；电源键拦截设置用只读开关（开/关状态）
			if (i == 0) {
				TextView tvArrow = new TextView(requireContext());
				tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
				tvArrow.setText(">");
				tvArrow.setTextColor(0xFFAAAAAA);
				NokiaDimens.textSize(tvArrow, 14);
				row.addView(tvArrow);
			} else {
				interceptorSwitch = new NokiaSwitchView(requireContext(), isInterceptorOn());
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

	/** 列表项名称：mini_shizuku 动态显示在线状态；电源键拦截设置固定名称（开/关由行尾开关展示）。 */
	private String getItemDisplayName(int index) {
		if (index == 0) {
			return ITEM_NAMES[0];
		}
		return ITEM_NAMES[index];
	}

	/**
	 * 电源键拦截当前是否开启：仅方案1/2 算开启，关闭算关。
	 * 与 {@link NokiaPowerInterceptFragment} 的实际底层动作保持一致。
	 */
	private boolean isInterceptorOn() {
		int mode = NokiaSettingsStorage.getPowerInterceptorMode(requireContext());
		return mode == NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_1
				|| mode == NokiaSettingsStorage.POWER_INTERCEPTOR_MODE_2;
	}

	/**
	 * 页面重新可见时刷新电源键拦截开关状态：
	 * 从「电源键拦截设置」方案选择页返回本页时，行尾开关要与最新方案同步。
	 */
	@Override
	public void onResume() {
		super.onResume();
		if (interceptorSwitch != null) {
			boolean on = isInterceptorOn();
			interceptorSwitch.setChecked(on);
			NokiaLog.d("AdvancedSettings", "onResume 刷新电源键拦截开关: "
					+ (on ? "开" : "关") + " 方案=" + NokiaSettingsStorage.getPowerInterceptorModeName(
							NokiaSettingsStorage.getPowerInterceptorMode(requireContext())));
		}
	}

	/** 构建行内自绘开关（诺基亚风格：跑道形轨道 + 圆形滑块），仅展示状态、不响应点击。 */
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
		private final RectF trackRect = new RectF();

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

		/** 切换开关状态并重绘（仅展示，不做点击切换）。 */
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
			// 跑道形轨道：圆角半径 = height/2 形成左右两端半圆。
			// 只能用 drawRoundRect(RectF, ...) 的 API 1 重载：7 参数 float 重载是 API 21 才有，
			// Android 4.4（API 19）上会 NoSuchMethodError 闪退。
			trackPaint.setColor(checked ? COLOR_TRACK_ON : COLOR_TRACK_OFF);
			trackRect.set(0, 0, trackW, trackH);
			canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint);
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

	@Override
	public boolean onSelect() {
		if (focusIndex < 0) return false;
		switch (focusIndex) {
			case 0:
				NokiaLog.i("AdvancedSettings", "进入 mini_shizuku 服务");
				((NokiaDesktopActivity) requireActivity()).openFragment(new ShizukuFragment());
				return true;
			case 1:
				NokiaLog.i("AdvancedSettings", "进入电源键拦截设置");
				((NokiaDesktopActivity) requireActivity()).openFragment(new NokiaPowerInterceptFragment());
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



	private View spaceView(int w, int h) {
		View v = new View(requireContext());
		v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
		return v;
	}
}
