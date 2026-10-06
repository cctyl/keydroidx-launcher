package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import ru.playsoftware.j2meloader.R;

/**
 * 触屏模式下的虚拟物理键盘面板。
 * <p>基于 {@code FEATURE_PHONE_UI_SPEC.md} 规范设计：包含功能导航岛（五向 D-Pad、左右软键、拨号、挂机）
 * 以及 3×4 九宫格数字键盘。按下按键时提供音效与触觉反馈，并交由宿主分发。</p>
 */
public class KeydroidxVirtualKeypadView extends LinearLayout {

	private static final String TAG = "VirtualKeypad";

	public interface OnVirtualKeyEventListener {
		void onVirtualKeyDown(int action, int keyCode);
		void onVirtualKeyUp(int action, int keyCode);
	}

	private OnVirtualKeyEventListener listener;

	public KeydroidxVirtualKeypadView(Context context) {
		super(context);
	}

	public KeydroidxVirtualKeypadView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public KeydroidxVirtualKeypadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public void setOnVirtualKeyEventListener(OnVirtualKeyEventListener listener) {
		this.listener = listener;
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		setupKeys();
	}

	private void setupKeys() {
		// 1. 语义动作按键（D-Pad、软键、通话、挂机）
		bindKey(R.id.btn_key_up, KeydroidxKeyBinding.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP);
		bindKey(R.id.btn_key_down, KeydroidxKeyBinding.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
		bindKey(R.id.btn_key_left, KeydroidxKeyBinding.ACTION_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
		bindKey(R.id.btn_key_right, KeydroidxKeyBinding.ACTION_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT);
		bindKey(R.id.btn_key_center, KeydroidxKeyBinding.ACTION_SELECT, KeyEvent.KEYCODE_DPAD_CENTER);

		bindKey(R.id.btn_key_lsk, KeydroidxKeyBinding.ACTION_SOFT_LEFT, KeyEvent.KEYCODE_SOFT_LEFT);
		bindKey(R.id.btn_key_rsk, KeydroidxKeyBinding.ACTION_SOFT_RIGHT, KeyEvent.KEYCODE_SOFT_RIGHT);

		bindKey(R.id.btn_key_call, KeydroidxKeyBinding.ACTION_HANGUP, KeyEvent.KEYCODE_CALL);
		bindKey(R.id.btn_key_end, KeydroidxKeyBinding.ACTION_LOCK_SCREEN, KeyEvent.KEYCODE_ENDCALL);

		// 2. 九宫格数字及特殊键（action = -1，直接使用标准 KeyCode）
		bindKey(R.id.btn_key_1, -1, KeyEvent.KEYCODE_1);
		bindKey(R.id.btn_key_2, -1, KeyEvent.KEYCODE_2);
		bindKey(R.id.btn_key_3, -1, KeyEvent.KEYCODE_3);
		bindKey(R.id.btn_key_4, -1, KeyEvent.KEYCODE_4);
		bindKey(R.id.btn_key_5, -1, KeyEvent.KEYCODE_5);
		bindKey(R.id.btn_key_6, -1, KeyEvent.KEYCODE_6);
		bindKey(R.id.btn_key_7, -1, KeyEvent.KEYCODE_7);
		bindKey(R.id.btn_key_8, -1, KeyEvent.KEYCODE_8);
		bindKey(R.id.btn_key_9, -1, KeyEvent.KEYCODE_9);
		bindKey(R.id.btn_key_0, -1, KeyEvent.KEYCODE_0);
		bindKey(R.id.btn_key_star, -1, KeyEvent.KEYCODE_STAR);
		bindKey(R.id.btn_key_pound, -1, KeyEvent.KEYCODE_POUND);
	}

	private void bindKey(int resId, final int action, final int defaultKeyCode) {
		View keyView = findViewById(resId);
		if (keyView == null) return;

		keyView.setOnTouchListener(new OnTouchListener() {
			private boolean isDown = false;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				int masked = event.getActionMasked();
				switch (masked) {
					case MotionEvent.ACTION_DOWN:
						isDown = true;
						v.setPressed(true);
						v.playSoundEffect(SoundEffectConstants.CLICK);
						v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
						if (listener != null) {
							listener.onVirtualKeyDown(action, defaultKeyCode);
						}
						return true;

					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL:
						if (isDown) {
							isDown = false;
							v.setPressed(false);
							if (listener != null) {
								listener.onVirtualKeyUp(action, defaultKeyCode);
							}
						}
						return true;
				}
				return false;
			}
		});
	}
}
