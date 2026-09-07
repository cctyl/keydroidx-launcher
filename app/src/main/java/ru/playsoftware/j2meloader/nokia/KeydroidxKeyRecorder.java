package ru.playsoftware.j2meloader.nokia;

/**
 * 录制态统一接口。
 * 实现该接口的 Fragment 表示当前正在"录制物理按键"——
 * Activity 的 dispatchKeyEvent 会在 resolveAction 之前，把任意物理按键
 * 直接喂给 {@link #onKeyRecorded(int)}，从而捕获用户按下的键用于绑定。
 * <p>
 * 按键绑定设置界面（KeydroidxKeyBindFragment）与首次启动向导
 * （KeydroidxKeyBindWizardFragment）都实现本接口，复用同一套按键捕获机制。
 */
public interface KeydroidxKeyRecorder {

	/** 当前是否处于录制态（捕获任意物理键）。 */
	boolean isRecording();

	/** 录制态下捕获到一次物理按键时回调。 */
	void onKeyRecorded(int keycode);

	/**
	 * 跳过当前动作的绑定（保留默认值），由实现自行前进到下一项或结束。
	 * 仅通过屏幕上的触摸按钮触发（如向导的"跳过此项"、设置页录制状态栏点击）；
	 * 录制态下物理返回键会被 Activity 忽略，不会调用本方法，避免误绑/误用。
	 */
	void onSkipCurrent();
}
