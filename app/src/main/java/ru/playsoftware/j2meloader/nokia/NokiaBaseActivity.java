package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;

/**
 * 诺基亚风格界面的公共基类。
 * 设计基准为 240x320（参考截图）。每个布局由三个面板组成：
 *   id=topPanel   （高度由内容决定/wrap_content，贴顶、宽度铺满；顶栏组件优先完整显示）
 *   id=midPanel   （高 262dp，贴左右、在顶/底之间垂直居中）
 *   id=bottomPanel（高 22dp，贴底、宽度铺满）
 * 外层 FrameLayout 铺满全屏并承载壁纸背景。基类按"宽度优先"计算缩放比
 *   scale = 屏幕宽度(dp) / 240
 * （若按此比例整体高度会超出屏幕，则退化为 contain 以避免裁切），再用
 * setScaleX/Y 等比放大面板内容、用 setX/setY 把顶栏贴顶、底栏贴底、中间居中。
 * 这样无论何种分辨率，顶栏/底栏/左右都铺满屏幕，多出的空位由壁纸背景自然填充，
 * 呈现怀旧的全屏效果。
 */
public abstract class NokiaBaseActivity extends AppCompatActivity {
	/**
	 * 部分低分辨率设备（如 320x480 且系统 density 非标准，例如 136 DPI → density=0.85）
	 * 会让所有 dp 尺寸落在亚像素位置，被抗锯齿虚化成灰边，导致图标发虚。
	 * 这里把 density 吸附到标准的 1.0（mdpi），物理布局完全不变（240dp 设计仍铺满屏幕），
	 * 但所有尺寸对齐到整数像素，彻底消除亚像素模糊。高 DPI 设备（density 已是整数倍）不受影响。
	 */
	@Override
	protected void attachBaseContext(Context newBase) {
		Configuration config = newBase.getResources().getConfiguration();
		// 用户字体缩放（桌面设置 → 字体大小）：统一应用内文字大小，
		// 同时剥离系统字体缩放，避免系统设置干扰应用内一致性。
		float userFontScale = 1f;
		try {
			userFontScale = NokiaSettingsStorage.getFontScale(newBase);
		} catch (Exception ignored) {
			userFontScale = 1f;
		}
		if (userFontScale <= 0f) {
			userFontScale = 1f;
		}
		NokiaDimens.sUserFontScale = userFontScale;
		int dpi = config.densityDpi;
		int fixed = dpi;
		int[] standards = {120, 160, 213, 240, 320, 480, 640};
		boolean standard = false;
		for (int s : standards) {
			if (s == dpi) {
				standard = true;
				break;
			}
		}
		if (dpi < 160) {
			// ldpi 及以下（如 120 DPI → density 0.75）向上吸附到 mdpi(160)，
			// 让 240x320 等小屏得到整数 scale=1，彻底消除 setScaleX/Y 的亚像素插值模糊。
			fixed = 160;
		} else if (!standard) {
			// 高分辨率但非标准密度（如 420 → 480）：吸附到最近的标准密度，
			// 保留其高像素密度，否则会被压成 160 使图标在小屏上显得极小。
			int nearest = standards[0];
			int minDiff = Math.abs(dpi - nearest);
			for (int s : standards) {
				int diff = Math.abs(dpi - s);
				if (diff < minDiff) {
					minDiff = diff;
					nearest = s;
				}
			}
			fixed = nearest;
		}
	if (fixed != dpi) {
		Configuration newConfig = new Configuration(config);
		newConfig.densityDpi = fixed;
		newConfig.fontScale = userFontScale;   // 应用用户字体缩放，忽略系统字体设置
		super.attachBaseContext(newBase.createConfigurationContext(newConfig));
	} else {
		// 即便 density 已是标准值，也要显式设置 fontScale（系统字体缩放可能非 1，
		// 否则 sp 字号会跟随系统字体被放大，导致顶栏溢出、底栏标题截断）。
		Configuration cfg = new Configuration(config);
		cfg.fontScale = userFontScale;
		super.attachBaseContext(newBase.createConfigurationContext(cfg));
	}
}

	private TextView tvTime;
	private final Handler clockHandler = new Handler();
	private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
	private final Runnable clockTick = new Runnable() {
		@Override
		public void run() {
			if (tvTime != null) {
				tvTime.setText(fmt.format(new Date()));
			}
			clockHandler.postDelayed(this, 1000);
		}
	};

	/** 设计基准尺寸（单位 dp）。 */
	private static final float BASE_W = 240f;
	private static final float TOP_H = 36f;  // 加了运营商行（原 22dp + 14dp 运营商行）
	private static final float BOT_H = 22f;
	private static final float MID_H = 262f; // 320(设计总高) - 36(顶栏) - 22(底栏)

	/** 响应式原生 DP 模式：所有组件按真实物理像素点对点渲染，scale 恒为 1.0f。 */
	public float getScale() {
		return 1.0f;
	}

	/** 计算缩放比：宽度优先，高度溢出时退化为 contain，接近整数时吸附。 */
	private static float computeScale(float widthDp, float heightDp) {
		float scale = widthDp / BASE_W;
		if (BASE_W > 0 && 320f * scale > heightDp) {
			scale = heightDp / 320f;
		}
		if (Math.abs(scale - Math.round(scale)) < 0.04f) {
			scale = Math.round(scale);
		}
		return scale;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
	}

	/** 在 setContentView() 之后调用：初始化时钟并应用分辨率缩放。 */
	protected void setupNokiaUi() {
		tvTime = findViewById(R.id.tvTime);
		applyScale();
	}

	@Override
	protected void onResume() {
		super.onResume();
		clockHandler.post(clockTick);
	}

	@Override
	protected void onPause() {
		super.onPause();
		clockHandler.removeCallbacks(clockTick);
	}

	private void applyScale() {
		View topPanel = findViewById(R.id.topPanel);
		if (topPanel != null) {
			ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) topPanel.getLayoutParams();
			lp.topMargin = 0;
			topPanel.setLayoutParams(lp);
			topPanel.setVisibility(View.VISIBLE);
		}

		View bottomPanel = findViewById(R.id.bottomPanel);
		if (bottomPanel != null) {
			bottomPanel.setVisibility(View.VISIBLE);
		}
	}

	/**
	 * 设置底部栏三按钮文字。文字为空时用 INVISIBLE 隐藏对应 TextView：
	 * INVISIBLE 保留占位（三栏 weight 布局宽度不变），保证中间标题始终居中，
	 * 且 INVISIBLE 的 View 不接收触摸，避免空按钮误触。
	 * 中间标题按字符数自动缩字号，长名称（如「桌面组件设置」）也能完整显示。
	 * 各碎片切到前台时都应调用一次，保证显示状态同步。
	 */
	protected void setBottomBar(String left, String center, String right) {
		applyBottomText(findViewById(R.id.bottomLeft), left, false);
		applyBottomText(findViewById(R.id.bottomCenter), center, true);
		applyBottomText(findViewById(R.id.bottomRight), right, false);
	}

	private void applyBottomText(TextView tv, String text, boolean isCenter) {
		if (tv == null) return;
		if (text == null || text.isEmpty()) {
			tv.setVisibility(View.INVISIBLE);
		} else {
			tv.setText(text);
			if (isCenter) {
				// 长界面名动态缩字号（dp 单位，不跟随系统字体）：≤4 字 12dp，5-6 字 11dp，≥7 字 10dp
				int len = text.length();
				float size;
				if (len <= 4) {
					size = 12f;
				} else if (len <= 6) {
					size = 11f;
				} else {
					size = 10f;
				}
				NokiaDimens.textSize(tv, size);
				fitCenterTextToWidth(tv);
			}
			tv.setVisibility(View.VISIBLE);
		}
	}

	/**
	 * 根据实际可用宽度动态缩小底部中间标题字号（dp 单位），
	 * 保证长标题完整显示、不出现省略号截断（如「桌面组件设置」）。
	 * 在布局完成后执行：若文字测量宽度超出 TextView 宽度，逐步降字号直至放得下。
	 */
	private void fitCenterTextToWidth(final TextView tv) {
		tv.post(new Runnable() {
			@Override
			public void run() {
				if (tv.getVisibility() != View.VISIBLE || tv.getWidth() <= 0) {
					return;
				}
				final float density = getResources().getDisplayMetrics().density;
				final android.graphics.Paint paint = tv.getPaint();
				while (tv.getTextSize() > 6f * density) {
					if (paint.measureText(tv.getText().toString()) <= tv.getWidth()) {
						break;
					}
					tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP,
							Math.max(6f, tv.getTextSize() / density - 0.5f));
				}
			}
		});
	}

	/**
	 * 设置底部栏中间按钮的文字。文字为空时用 INVISIBLE 隐藏（保留占位保证居中）。
	 * 各碎片切到前台时都应调用一次，保证显示状态同步。
	 */
	protected void setBottomCenterText(String text) {
		TextView bc = findViewById(R.id.bottomCenter);
		if (bc == null) return;
		if (text == null || text.isEmpty()) {
			bc.setVisibility(View.INVISIBLE);
		} else {
			bc.setText(text);
			bc.setVisibility(View.VISIBLE);
			fitCenterTextToWidth(bc);
		}
	}

	/**
	 * 响应式原生渲染：确保 1:1 原生物理像素绘制，无 GPU 缩放插值模糊。
	 *
	 * @param content  碎片根视图（其父必须是中间容器 midPanel）
	 * @param topAlign true=贴容器顶部；false=垂直居中
	 */
	protected void scaleMidContent(View content, boolean topAlign) {
		if (content == null) return;
		content.setScaleX(1f);
		content.setScaleY(1f);
		content.setTranslationX(0f);
		content.setTranslationY(0f);
		ViewParent parent = content.getParent();
		if (parent instanceof View) {
			((View) parent).setVisibility(View.VISIBLE);
		}
	}

	/**
	 * 在响应式原生 DP 模式下为安全空实现，保留以兼容现有调用。
	 */
	public void fixMidContentHeight(final View content, final boolean topAlign) {
		// 响应式原生 DP 模式下，根视图 match_parent 自适应容器高度，无需二次强改
	}

	/** 获取系统状态栏高度；如无法取得则返回 0。 */
	private int getStatusBarHeight() {
		int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
		return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
	}

	/**
	 * 响应式原生渲染：不再对面板内容进行 GPU setScaleX/Y，保证矢量清晰度。
	 */
	private void scalePanelContent(View panel, float scale, float baseH, float density,
								   boolean centerPivot, boolean setHeight) {
		if (panel == null) {
			return;
		}
		if (panel instanceof ViewGroup && ((ViewGroup) panel).getChildCount() > 0) {
			View content = ((ViewGroup) panel).getChildAt(0);
			content.setScaleX(1f);
			content.setScaleY(1f);
		}
		panel.setVisibility(View.VISIBLE);
	}
}
