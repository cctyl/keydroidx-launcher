package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 诺基亚经典风格电池 Drawable（支持动态电量格 + 充电闪电动态指示）。
 * 兼容 Android 4.4+ (API 19)，无需额外图片资源，纯矢量高精度绘制。
 */
public class NokiaBatteryDrawable extends Drawable {

	private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path boltPath = new Path();

	private final RectF bodyRect = new RectF();
	private final RectF capRect = new RectF();
	private final RectF barRect = new RectF();

	private int levelPct = 100;
	private boolean isCharging = false;

	public NokiaBatteryDrawable(Context context) {
		strokePaint.setStyle(Paint.Style.STROKE);
		strokePaint.setColor(0xFFFFFFFF);
		strokePaint.setStrokeWidth(NokiaDimens.dp(context.getResources(), 1.0f));

		fillPaint.setStyle(Paint.Style.FILL);

		boltPaint.setStyle(Paint.Style.FILL_AND_STROKE);
		boltPaint.setColor(0xFFFFEB3B); // 鲜亮明黄闪电
		boltPaint.setStrokeWidth(NokiaDimens.dp(context.getResources(), 0.5f));
		boltPaint.setStrokeJoin(Paint.Join.ROUND);
		boltPaint.setStrokeCap(Paint.Cap.ROUND);
	}

	public void setBatteryState(int pct, boolean charging) {
		if (this.levelPct != pct || this.isCharging != charging) {
			this.levelPct = pct;
			this.isCharging = charging;
			invalidateSelf();
		}
	}

	@Override
	public int getIntrinsicWidth() {
		return 36;
	}

	@Override
	public int getIntrinsicHeight() {
		return 20;
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		Rect bounds = getBounds();
		int w = bounds.width();
		int h = bounds.height();
		if (w <= 0 || h <= 0) return;

		canvas.save();
		canvas.translate(bounds.left, bounds.top);

		// 尺寸比例规划：
		// 电池主体占宽度的 80%，电池正极帽占 10%，留边 10%
		float strokeW = strokePaint.getStrokeWidth();
		float padY = h * 0.15f;
		float bodyW = w * 0.80f;
		float bodyH = h - padY * 2;
		float capW = w * 0.10f;
		float capH = bodyH * 0.48f;

		// 1. 电池外壳
		bodyRect.set(strokeW / 2, padY + strokeW / 2, bodyW - strokeW / 2, padY + bodyH - strokeW / 2);
		float cornerRadius = bodyH * 0.12f;
		canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, strokePaint);

		// 2. 电池正极帽（右侧突出部）
		float capX = bodyW + 1;
		float capY = padY + (bodyH - capH) / 2;
		capRect.set(capX, capY, Math.min(capX + capW, w - 1), capY + capH);
		strokePaint.setStyle(Paint.Style.FILL);
		canvas.drawRoundRect(capRect, cornerRadius * 0.8f, cornerRadius * 0.8f, strokePaint);
		strokePaint.setStyle(Paint.Style.STROKE);

		// 3. 内部电量格 (经典 4 格电池)
		int totalBars = 4;
		int activeBars;
		int barColor;

		if (levelPct <= 10) {
			activeBars = 1;
			barColor = 0xFFF44336; // 红色低电量告警
		} else if (levelPct <= 25) {
			activeBars = 1;
			barColor = 0xFF4CAF50; // 绿色
		} else if (levelPct <= 50) {
			activeBars = 2;
			barColor = 0xFF4CAF50;
		} else if (levelPct <= 75) {
			activeBars = 3;
			barColor = 0xFF4CAF50;
		} else {
			activeBars = 4;
			barColor = 0xFF4CAF50;
		}

		if (isCharging) {
			barColor = 0xFF00E676; // 充电中：充满活力的亮绿
		}

		fillPaint.setColor(barColor);

		float innerMargin = strokeW + 1.5f;
		float innerX = bodyRect.left + innerMargin;
		float innerY = bodyRect.top + innerMargin;
		float innerW = bodyRect.width() - innerMargin * 2;
		float innerH = bodyRect.height() - innerMargin * 2;

		float barGap = innerW * 0.08f;
		float singleBarW = (innerW - barGap * (totalBars - 1)) / totalBars;

		for (int i = 0; i < activeBars; i++) {
			float bx = innerX + i * (singleBarW + barGap);
			barRect.set(bx, innerY, bx + singleBarW, innerY + innerH);
			canvas.drawRect(barRect, fillPaint);
		}

		// 4. 充电中闪电图标 (Charging Lightning Bolt)
		if (isCharging) {
			boltPath.reset();
			float cx = innerX + innerW * 0.5f;
			float cy = innerY + innerH * 0.5f;
			float bw = innerW * 0.55f;
			float bh = innerH * 1.15f;

			// 绘制凌厉的闪电折线图形
			boltPath.moveTo(cx + bw * 0.15f, cy - bh * 0.50f); // 顶起点
			boltPath.lineTo(cx - bw * 0.40f, cy + bh * 0.05f); // 中左拐点
			boltPath.lineTo(cx - bw * 0.05f, cy + bh * 0.05f); // 中凹折线
			boltPath.lineTo(cx - bw * 0.20f, cy + bh * 0.50f); // 底尖端
			boltPath.lineTo(cx + bw * 0.40f, cy - bh * 0.05f); // 中右拐点
			boltPath.lineTo(cx + bw * 0.05f, cy - bh * 0.05f); // 中上折线
			boltPath.close();

			// 闪电外层微暗描边（保证在任何背景和电量格上都清晰锐利）
			boltPaint.setColor(0xCC000000);
			boltPaint.setStyle(Paint.Style.STROKE);
			boltPaint.setStrokeWidth(strokeW * 1.5f);
			canvas.drawPath(boltPath, boltPaint);

			// 闪电金黄主体填充
			boltPaint.setColor(0xFFFFEB3B);
			boltPaint.setStyle(Paint.Style.FILL);
			canvas.drawPath(boltPath, boltPaint);
		}

		canvas.restore();
	}

	@Override
	public void setAlpha(int alpha) {
		strokePaint.setAlpha(alpha);
		fillPaint.setAlpha(alpha);
		boltPaint.setAlpha(alpha);
	}

	@Override
	public void setColorFilter(@Nullable ColorFilter colorFilter) {
		strokePaint.setColorFilter(colorFilter);
		fillPaint.setColorFilter(colorFilter);
		boltPaint.setColorFilter(colorFilter);
	}

	@Override
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}
}
