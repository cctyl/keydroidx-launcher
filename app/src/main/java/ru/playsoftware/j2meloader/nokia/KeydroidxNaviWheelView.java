package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

/**
 * 诺基亚经典一体化四瓣无缝导航环 (Nokia Navi-Wheel)
 * <p>
 * 工业级设计特点：
 * 1. 100% 空间利用率：对角线对齐几何切割，彻底消除四个角落的死区凹槽，盲按永不点空。
 * 2. 纯正工业金属美学：四瓣加厚微缝分割，中心独立高光 OK 键，立体按压霓虹高亮。
 * 3. API 19 全版本兼容，纯原生 Canvas 绘制，0 崩溃风险。
 */
public class KeydroidxNaviWheelView extends View {

	public static final int KEY_NONE = -1;
	public static final int KEY_UP = 0;
	public static final int KEY_DOWN = 1;
	public static final int KEY_LEFT = 2;
	public static final int KEY_RIGHT = 3;
	public static final int KEY_CENTER = 4;

	private KeydroidxVirtualKeypadView.OnVirtualKeyEventListener listener;

	private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint seamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	private final RectF bounds = new RectF();
	private final RectF centerBounds = new RectF();
	private final Path pathOuter = new Path();
	private final Path pathUp = new Path();
	private final Path pathDown = new Path();
	private final Path pathLeft = new Path();
	private final Path pathRight = new Path();
	private final Path pathCenter = new Path();

	private float density;
	private int activeKey = KEY_NONE;

	public KeydroidxNaviWheelView(Context context) {
		super(context);
		init();
	}

	public KeydroidxNaviWheelView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public KeydroidxNaviWheelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		density = getContext().getResources().getDisplayMetrics().density;
		setFocusable(false);
		setClickable(true);
	}

	public void setOnVirtualKeyEventListener(KeydroidxVirtualKeypadView.OnVirtualKeyEventListener listener) {
		this.listener = listener;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		computePaths(w, h);
	}

	private void computePaths(int w, int h) {
		bounds.set(1.5f * density, 1.5f * density, w - 1.5f * density, h - 1.5f * density);
		float cornerRadius = 20f * density;

		pathOuter.reset();
		pathOuter.addRoundRect(bounds, cornerRadius, cornerRadius, Path.Direction.CW);

		// 中心 OK 键尺寸 (宽约 68dp, 高约 46dp)
		float okW = Math.min(w * 0.36f, 70f * density);
		float okH = Math.min(h * 0.38f, 48f * density);
		float cx = w / 2f;
		float cy = h / 2f;
		centerBounds.set(cx - okW / 2f, cy - okH / 2f, cx + okW / 2f, cy + okH / 2f);

		pathCenter.reset();
		pathCenter.addRoundRect(centerBounds, 14f * density, 14f * density, Path.Direction.CW);

		// UP 梯形区域：顶部全宽 + 两侧对角线 + OK 键上沿
		pathUp.reset();
		pathUp.moveTo(bounds.left, bounds.top);
		pathUp.lineTo(bounds.right, bounds.top);
		pathUp.lineTo(centerBounds.right, centerBounds.top);
		pathUp.lineTo(centerBounds.left, centerBounds.top);
		pathUp.close();

		// DOWN 梯形区域：底部全宽 + 两侧对角线 + OK 键下沿
		pathDown.reset();
		pathDown.moveTo(bounds.left, bounds.bottom);
		pathDown.lineTo(bounds.right, bounds.bottom);
		pathDown.lineTo(centerBounds.right, centerBounds.bottom);
		pathDown.lineTo(centerBounds.left, centerBounds.bottom);
		pathDown.close();

		// LEFT 梯形区域：左侧全高 + 上下对角线 + OK 键左沿
		pathLeft.reset();
		pathLeft.moveTo(bounds.left, bounds.top);
		pathLeft.lineTo(centerBounds.left, centerBounds.top);
		pathLeft.lineTo(centerBounds.left, centerBounds.bottom);
		pathLeft.lineTo(bounds.left, bounds.bottom);
		pathLeft.close();

		// RIGHT 梯形区域：右侧全高 + 上下对角线 + OK 键右沿
		pathRight.reset();
		pathRight.moveTo(bounds.right, bounds.top);
		pathRight.lineTo(centerBounds.right, centerBounds.top);
		pathRight.lineTo(centerBounds.right, centerBounds.bottom);
		pathRight.lineTo(bounds.right, bounds.bottom);
		pathRight.close();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		int w = getWidth();
		int h = getHeight();
		if (w <= 0 || h <= 0) return;

		float cx = w / 2f;
		float cy = h / 2f;

		// 1. 绘制底层底座剪裁区
		canvas.save();
		canvas.clipPath(pathOuter);

		// 背景底盘
		bgPaint.setStyle(Paint.Style.FILL);
		bgPaint.setColor(0xFF131720);
		canvas.drawRect(0, 0, w, h, bgPaint);

		// 2. 分别绘制四向梯形瓣
		drawSector(canvas, pathUp, KEY_UP, 0, 0, 0, h * 0.45f);
		drawSector(canvas, pathDown, KEY_DOWN, 0, h * 0.55f, 0, h);
		drawSector(canvas, pathLeft, KEY_LEFT, 0, 0, w * 0.45f, 0);
		drawSector(canvas, pathRight, KEY_RIGHT, w * 0.55f, 0, w, 0);

		// 3. 绘制 4 条对角线精密分割缝隙
		seamPaint.setStyle(Paint.Style.STROKE);
		seamPaint.setStrokeWidth(2f * density);
		seamPaint.setColor(0xFF0e1218);
		canvas.drawLine(bounds.left, bounds.top, centerBounds.left, centerBounds.top, seamPaint);
		canvas.drawLine(bounds.right, bounds.top, centerBounds.right, centerBounds.top, seamPaint);
		canvas.drawLine(bounds.left, bounds.bottom, centerBounds.left, centerBounds.bottom, seamPaint);
		canvas.drawLine(bounds.right, bounds.bottom, centerBounds.right, centerBounds.bottom, seamPaint);

		// 4. 绘制四向指示箭头
		drawDirectionLabels(canvas, cx, cy);

		// 5. 绘制外框高光描边
		strokePaint.setStyle(Paint.Style.STROKE);
		strokePaint.setStrokeWidth(1.5f * density);
		strokePaint.setColor(0xFF475569);
		canvas.drawPath(pathOuter, strokePaint);

		canvas.restore();

		// 6. 绘制中心核心 OK 确认键
		drawCenterOk(canvas, cx, cy);
	}

	private void drawSector(Canvas canvas, Path path, int keyType, float x0, float y0, float x1, float y1) {
		boolean isPressed = (activeKey == keyType);
		fillPaint.setStyle(Paint.Style.FILL);

		if (isPressed) {
			fillPaint.setShader(null);
			fillPaint.setColor(0xFF0f172a);
		} else {
			fillPaint.setShader(new LinearGradient(x0, y0, x1, y1,
					0xFF374151, 0xFF1f2937, Shader.TileMode.CLAMP));
		}
		canvas.drawPath(path, fillPaint);
		fillPaint.setShader(null);

		if (isPressed) {
			strokePaint.setStyle(Paint.Style.STROKE);
			strokePaint.setStrokeWidth(2f * density);
			strokePaint.setColor(0xFF38bdf8);
			canvas.drawPath(path, strokePaint);
		}
	}

	private void drawDirectionLabels(Canvas canvas, float cx, float cy) {
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTextSize(14f * density);
		textPaint.setFakeBoldText(true);

		// UP
		textPaint.setColor(activeKey == KEY_UP ? 0xFF38bdf8 : 0xFFF1F5F9);
		canvas.drawText("▲", cx, (bounds.top + centerBounds.top) / 2f + 5f * density, textPaint);

		// DOWN
		textPaint.setColor(activeKey == KEY_DOWN ? 0xFF38bdf8 : 0xFFF1F5F9);
		canvas.drawText("▼", cx, (bounds.bottom + centerBounds.bottom) / 2f + 6f * density, textPaint);

		// LEFT
		textPaint.setColor(activeKey == KEY_LEFT ? 0xFF38bdf8 : 0xFFF1F5F9);
		Paint.FontMetrics fm = textPaint.getFontMetrics();
		float textBaseY = cy - (fm.top + fm.bottom) / 2f;
		canvas.drawText("◀", (bounds.left + centerBounds.left) / 2f, textBaseY, textPaint);

		// RIGHT
		textPaint.setColor(activeKey == KEY_RIGHT ? 0xFF38bdf8 : 0xFFF1F5F9);
		canvas.drawText("▶", (bounds.right + centerBounds.right) / 2f, textBaseY, textPaint);
	}

	private void drawCenterOk(Canvas canvas, float cx, float cy) {
		boolean isPressed = (activeKey == KEY_CENTER);

		// 填充
		fillPaint.setStyle(Paint.Style.FILL);
		if (isPressed) {
			fillPaint.setShader(null);
			fillPaint.setColor(0xFF0f172a);
		} else {
			fillPaint.setShader(new RadialGradient(cx, cy, centerBounds.width() * 0.6f,
					0xFF3b4657, 0xFF1f2733, Shader.TileMode.CLAMP));
		}
		canvas.drawPath(pathCenter, fillPaint);
		fillPaint.setShader(null);

		// 边框
		strokePaint.setStyle(Paint.Style.STROKE);
		strokePaint.setStrokeWidth(isPressed ? 2.2f * density : 1.8f * density);
		strokePaint.setColor(isPressed ? 0xFF38bdf8 : 0xFF94a3b8);
		canvas.drawPath(pathCenter, strokePaint);

		// OK 文字
		textPaint.setTextAlign(Paint.Align.CENTER);
		textPaint.setTextSize(14f * density);
		textPaint.setFakeBoldText(true);
		textPaint.setColor(isPressed ? 0xFF38bdf8 : 0xFFFFFFFF);

		Paint.FontMetrics fm = textPaint.getFontMetrics();
		float textY = cy - (fm.top + fm.bottom) / 2f;
		canvas.drawText("OK", cx, textY, textPaint);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		float x = event.getX();
		float y = event.getY();

		switch (action) {
			case MotionEvent.ACTION_DOWN: {
				int targetKey = resolveHitKey(x, y);
				if (targetKey != KEY_NONE) {
					dispatchKeyDown(targetKey);
					return true;
				}
				break;
			}
			case MotionEvent.ACTION_MOVE: {
				int targetKey = resolveHitKey(x, y);
				if (targetKey != activeKey) {
					if (activeKey != KEY_NONE) {
						dispatchKeyUp(activeKey);
					}
					if (targetKey != KEY_NONE) {
						dispatchKeyDown(targetKey);
					}
				}
				return true;
			}
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL: {
				if (activeKey != KEY_NONE) {
					dispatchKeyUp(activeKey);
				}
				return true;
			}
		}
		return super.onTouchEvent(event);
	}

	/**
	 * 100% 空间利用率触控命中判定算法
	 * 彻底消除四个角落的死角盲区
	 */
	private int resolveHitKey(float x, float y) {
		int w = getWidth();
		int h = getHeight();
		if (x < 0 || x > w || y < 0 || y > h) {
			return KEY_NONE;
		}

		// 1. 优先判定中心 OK 矩形
		if (centerBounds.contains(x, y)) {
			return KEY_CENTER;
		}

		// 2. 根据相对中心点的斜率对角线严格四分整个矩形盘面
		float cx = w / 2f;
		float cy = h / 2f;
		float dx = x - cx;
		float dy = y - cy;

		float normX = Math.abs(dx) / (w / 2f);
		float normY = Math.abs(dy) / (h / 2f);

		if (normX < normY) {
			// 垂直主导区（UP 或 DOWN）
			return (dy < 0) ? KEY_UP : KEY_DOWN;
		} else {
			// 水平主导区（LEFT 或 RIGHT）
			return (dx < 0) ? KEY_LEFT : KEY_RIGHT;
		}
	}

	private void dispatchKeyDown(int keyType) {
		activeKey = keyType;
		invalidate();
		playSoundEffect(SoundEffectConstants.CLICK);
		performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

		if (listener != null) {
			int semanticAction = getSemanticAction(keyType);
			int defaultKeyCode = getDefaultKeyCode(keyType);
			listener.onVirtualKeyDown(semanticAction, defaultKeyCode);
		}
	}

	private void dispatchKeyUp(int keyType) {
		int prev = activeKey;
		activeKey = KEY_NONE;
		invalidate();

		if (listener != null && prev != KEY_NONE) {
			int semanticAction = getSemanticAction(prev);
			int defaultKeyCode = getDefaultKeyCode(prev);
			listener.onVirtualKeyUp(semanticAction, defaultKeyCode);
		}
	}

	private int getSemanticAction(int keyType) {
		switch (keyType) {
			case KEY_UP: return KeydroidxKeyBinding.ACTION_UP;
			case KEY_DOWN: return KeydroidxKeyBinding.ACTION_DOWN;
			case KEY_LEFT: return KeydroidxKeyBinding.ACTION_LEFT;
			case KEY_RIGHT: return KeydroidxKeyBinding.ACTION_RIGHT;
			case KEY_CENTER: return KeydroidxKeyBinding.ACTION_SELECT;
			default: return -1;
		}
	}

	private int getDefaultKeyCode(int keyType) {
		switch (keyType) {
			case KEY_UP: return KeyEvent.KEYCODE_DPAD_UP;
			case KEY_DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
			case KEY_LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
			case KEY_RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
			case KEY_CENTER: return KeyEvent.KEYCODE_DPAD_CENTER;
			default: return KeyEvent.KEYCODE_UNKNOWN;
		}
	}
}
