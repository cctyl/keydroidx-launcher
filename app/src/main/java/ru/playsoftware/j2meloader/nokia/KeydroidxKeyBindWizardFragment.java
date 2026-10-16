package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxFocusHost;
import java.util.ArrayList;
import java.util.List;

import io.github.cctyl.nokia.common.permission.KeydroidxPermissionManager;
import ru.playsoftware.j2meloader.R;

/**
 * 首次启动按键绑定向导。
 * <p>
 * 状态机：INTRO（弹窗询问是否绑定）→ RECORDING（按 上/下/左/右/确认/左软键/右软键/锁屏/拨号键
 * 顺序逐个提示并捕获一次物理键）→ DONE（标记完成并返回桌面）。
 * <p>
 * 「后台管理」不进向导：拨号键在桌面语境即打开后台管理（见
 * {@code KeydroidxDesktopActivity} 的 HANGUP→RECENT_APPS 改写），无需独立按键。
 * <p>
 * 每个录制步骤支持"跳过"：录制态下按返回键即跳过当前动作（保留默认值）并前进，
 * 避免设备缺键时卡死。
 * <p>
 * 仅首次启动弹出（由 KeydroidxKeyBinding.isWizardDone 控制，清数据后重置）。
 * 整个流程完全由物理按键驱动，复用 KeydroidxKeyRecorder 的录制捕获机制。
 */
public class KeydroidxKeyBindWizardFragment extends KeydroidxPageFragment implements KeydroidxKeyRecorder {

	private static final String TAG = "KeyWizard";

	private static final int STATE_INTRO = 0;
	private static final int STATE_RECORDING = 1;
	private static final int STATE_DONE = 2;

	/**
	 * 录入冷却时长：每录完一项（或刚进入录制）后必须停顿该时长，期间物理键一律吞掉不录入。
	 * 防两类事故：① 用户手快，上一键的第二次敲击被当成下一动作的键录入；
	 * ② 设备连键（一次按压抖出两个不同键码），第二个键码被录进下一动作，
	 * 造成「一个物理键同时承担两个功能」——绝对不允许出现。
	 */
	private static final long RECORD_COOLDOWN_MS = 1000L;

	/** INTRO 进入防抖：刚进入向导的确认/软键连键抖出的第二发，不得立刻触发「绑定/触屏模式」。 */
	private static final long ENTRY_DEBOUNCE_MS = 800L;

	/** 向导引导绑定的动作全集：桌面动作去掉「后台管理」（其复用拨号键，不单独引导）。 */
	private static final int[] WIZARD_ACTIONS = {
			KeydroidxKeyBinding.ACTION_UP,
			KeydroidxKeyBinding.ACTION_DOWN,
			KeydroidxKeyBinding.ACTION_LEFT,
			KeydroidxKeyBinding.ACTION_RIGHT,
			KeydroidxKeyBinding.ACTION_SELECT,
			KeydroidxKeyBinding.ACTION_SOFT_LEFT,
			KeydroidxKeyBinding.ACTION_SOFT_RIGHT,
			KeydroidxKeyBinding.ACTION_LOCK_SCREEN,
			KeydroidxKeyBinding.ACTION_HANGUP,
	};

	private KeydroidxKeyBinding keyBinding;
	private int state = STATE_INTRO;
	private int introChoice = 0;      // 0=绑定, 1=跳过
	private int recordingStep = -1;   // -1=非录制态；0..ACTION_COUNT-1=正在录制第 N 个动作

	/** 录入冷却中：true 期间 onKeyRecorded 吞掉一切按键（防连键/连击误录下一动作）。 */
	private boolean coolingDown;
	/** 刚进入 INTRO 的时刻（uptimeMillis），用于进入防抖。 */
	private long enteredAt;

	private final Handler cooldownHandler = new Handler(Looper.getMainLooper());
	private final Runnable cooldownEndRunnable = this::endRecordCooldown;

	private View introCard;
	private View recordingLayout;
	private View doneLayout;
	private View introBind;
	private View introSkip;
	private TextView recordPrompt;
	private TextView recordDesc;
	private TextView recordProgress;
	private TextView stepBadge;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_key_bind_wizard;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		keyBinding = new KeydroidxKeyBinding(requireContext());

		introCard = view.findViewById(R.id.introCard);
		recordingLayout = view.findViewById(R.id.recordingLayout);
		doneLayout = view.findViewById(R.id.doneLayout);
		introBind = view.findViewById(R.id.introBind);
		introSkip = view.findViewById(R.id.introSkip);
		recordPrompt = view.findViewById(R.id.recordPrompt);
		recordDesc = view.findViewById(R.id.recordDesc);
		recordProgress = view.findViewById(R.id.recordProgress);
		stepBadge = view.findViewById(R.id.stepBadge);

		// 触摸跳过按钮：仅通过触摸触发跳过当前项（录制态下返回键已被忽略，不再用于跳过）
		View recordSkip = view.findViewById(R.id.recordSkip);
		if (recordSkip != null) {
			recordSkip.setOnClickListener(v -> {
				KeydroidxLog.i(TAG, "触摸点击 跳过当前项");
				onSkipCurrent();
			});
		}

		// 触摸支持（不影响按键路径）
		introBind.setOnClickListener(v -> {
			introChoice = 0;
			updateIntroHighlight();
			startRecording();
		});
		introSkip.setOnClickListener(v -> {
			introChoice = 1;
			updateIntroHighlight();
			chooseTouchMode();
		});

		View doneBtn = view.findViewById(R.id.doneBtn);
		if (doneBtn != null) {
			doneBtn.setOnClickListener(v -> exitToDesktop());
		}

		showIntro();
	}

	@Override
	public void onDestroyView() {
		// 清掉冷却回调，防止视图销毁后回调触碰已释放的 UI 或跨页面误刷新
		cooldownHandler.removeCallbacksAndMessages(null);
		coolingDown = false;
		super.onDestroyView();
	}

	// ---- 状态切换 ----

	private void showIntro() {
		state = STATE_INTRO;
		recordingStep = -1;
		coolingDown = false;
		// 进入防抖：从上一页面按键进入向导时，该键的连键/长按残留事件不得立刻误触 INTRO 选择
		enteredAt = android.os.SystemClock.uptimeMillis();
		introCard.setVisibility(View.VISIBLE);
		recordingLayout.setVisibility(View.GONE);
		doneLayout.setVisibility(View.GONE);
		introChoice = 0;
		updateIntroHighlight();
		if (stepBadge != null) stepBadge.setText("");
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		host.refreshPageBar();
		KeydroidxLog.i(TAG, "进入 INTRO 弹窗（绑定/跳过）");
	}

	private void updateIntroHighlight() {
		introBind.setBackground(introChoice == 0 ? KeydroidxTheme.createSelectionDrawable(requireContext(), 4) : null);
		introSkip.setBackground(introChoice == 1 ? KeydroidxTheme.createSelectionDrawable(requireContext(), 4) : null);
	}

	private void startRecording() {
		state = STATE_RECORDING;
		recordingStep = 0;
		introCard.setVisibility(View.GONE);
		recordingLayout.setVisibility(View.VISIBLE);
		doneLayout.setVisibility(View.GONE);
		// 录制态下任意键都会被捕获为当前动作的绑定键，底部栏隐藏
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		host.refreshPageBar();
		// 刚进入录制的确认/软键可能连键抖出第二发，先进入 1 秒冷却再接受录入
		beginRecordCooldown("请稍候，正在准备录制…");
		KeydroidxLog.i(TAG, "开始录制，第 1 项="
				+ KeydroidxKeyBinding.getWizardPromptName(0)
				+ "（冷却 1 秒后接受录入）");
	}

	/**
	 * 进入录入冷却：冷却期内 onKeyRecorded 吞掉一切物理键，结束后刷新录制提示。
	 * 必须在「录完一项」「跳过一项」「刚进入录制」三个时机调用，
	 * 保证相邻两项录入之间有 1 秒硬间隔，连键/连击绝不落到下一动作上。
	 */
	private void beginRecordCooldown(String transitionHint) {
		coolingDown = true;
		cooldownHandler.removeCallbacks(cooldownEndRunnable);
		if (recordPrompt != null && transitionHint != null) {
			recordPrompt.setText(transitionHint);
		}
		cooldownHandler.postDelayed(cooldownEndRunnable, RECORD_COOLDOWN_MS);
	}

	private void endRecordCooldown() {
		coolingDown = false;
		if (state == STATE_RECORDING) {
			updateRecordingPrompt();
			KeydroidxLog.i(TAG, "录入冷却结束，开始接受第 " + (recordingStep + 1) + " 项录入");
		}
	}

	/** 是否仍在刚进入 INTRO 的防抖窗口内（窗口内触发状态转换的按键一律忽略）。 */
	private boolean inEntryDebounce() {
		return android.os.SystemClock.uptimeMillis() - enteredAt < ENTRY_DEBOUNCE_MS;
	}

	private void updateRecordingPrompt() {
		int action = WIZARD_ACTIONS[recordingStep];
		recordPrompt.setText("请按下『" + KeydroidxKeyBinding.getWizardPromptName(action) + "』键");
		recordProgress.setText("第 " + (recordingStep + 1) + " / " + WIZARD_ACTIONS.length + " 项");
		if (stepBadge != null) {
			stepBadge.setText((recordingStep + 1) + "/" + WIZARD_ACTIONS.length);
		}
		updateStepDescription(action);
	}

	/** 步骤用途说明：目前只有拨号键需要特别说明（一键两用），其余步骤不显示。 */
	private void updateStepDescription(int action) {
		if (recordDesc == null) return;
		if (action == KeydroidxKeyBinding.ACTION_HANGUP) {
			recordDesc.setText("拨号键一键两用：桌面时按下打开后台管理；"
					+ "jar 应用内按下弹出菜单（继续 / 退出 / 后台运行）");
			recordDesc.setVisibility(View.VISIBLE);
		} else {
			recordDesc.setText("");
			recordDesc.setVisibility(View.GONE);
		}
	}

	// ---- KeydroidxKeyRecorder（录制态捕获物理键）----

	@Override
	public boolean isRecording() {
		return state == STATE_RECORDING;
	}

	@Override
	public void onKeyRecorded(int keycode) {
		if (state != STATE_RECORDING) return;
		if (coolingDown) {
			// 冷却期：吞掉连键抖出的第二发 / 用户手快的连击，绝不录入（防同一物理键占两个动作）
			KeydroidxLog.w(TAG, "录入冷却中，吞掉按键 " + KeydroidxKeyBinding.keyName(keycode));
			return;
		}
		int action = WIZARD_ACTIONS[recordingStep];

		keyBinding.setKeyCode(action, keycode);
		// 同步到全局 JAR 设置
		KeydroidxGlobalProfile.syncKeyBindings(requireContext());
		KeydroidxLog.i(TAG, "第 " + (recordingStep + 1) + " 项 绑定成功 "
				+ KeydroidxKeyBinding.keyName(keycode));

		int next = recordingStep + 1;
		if (next >= WIZARD_ACTIONS.length) {
			finishWizard(true);
		} else {
			recordingStep = next;
			// 强制 1 秒硬间隔：等用户松手、连键窗口过去后再接受下一项录入
			beginRecordCooldown("已记录，请松开按键并稍候…");
			KeydroidxLog.i(TAG, "进入第 " + (next + 1) + " 项="
					+ KeydroidxKeyBinding.getWizardPromptName(WIZARD_ACTIONS[next])
					+ "（冷却 1 秒后接受录入）");
		}
	}

	/** 录制态下按返回键：跳过当前动作的绑定（保留默认值），前进到下一项或结束。 */
	@Override
	public void onSkipCurrent() {
		if (state != STATE_RECORDING) return;
		int action = WIZARD_ACTIONS[recordingStep];
		KeydroidxLog.i(TAG, "第 " + (recordingStep + 1) + " 项 跳过（保留默认 "
				+ KeydroidxKeyBinding.keyName(keyBinding.getKeyCode(action)) + "）");

		int next = recordingStep + 1;
		if (next >= WIZARD_ACTIONS.length) {
			finishWizard(true);
		} else {
			recordingStep = next;
			// 跳过同样进入 1 秒冷却，防止触摸跳过后残留按键误录下一项
			beginRecordCooldown("已跳过，请稍候…");
			KeydroidxLog.i(TAG, "跳过进入第 " + (next + 1) + " 项="
					+ KeydroidxKeyBinding.getWizardPromptName(WIZARD_ACTIONS[next])
					+ "（冷却 1 秒后接受录入）");
		}
	}

	private void finishWizard(boolean bound) {
		state = STATE_DONE;
		recordingStep = -1;
		// 最后一项录入后仍保持 1 秒冷却：连键第二发不得立刻触发「完成→返回桌面」
		cooldownHandler.removeCallbacks(cooldownEndRunnable);
		cooldownHandler.postDelayed(cooldownEndRunnable, RECORD_COOLDOWN_MS);
		introCard.setVisibility(View.GONE);
		recordingLayout.setVisibility(View.GONE);
		doneLayout.setVisibility(View.VISIBLE);
		if (stepBadge != null) stepBadge.setText("");
		// 标记向导已完成，下次启动不再弹出
		keyBinding.markWizardDone();
		// 让 Activity 的内存绑定立即生效
		if (getActivity() instanceof KeydroidxDesktopActivity) {
			((KeydroidxDesktopActivity) getActivity()).reloadKeyBindings();
			((KeydroidxDesktopActivity) getActivity()).refreshPageBar();
		}
		KeydroidxLog.i(TAG, "向导结束 bound=" + bound + "，进入完成提示页（冷却 1 秒防连键误触）");
	}

	/** 退出向导并返回桌面待机屏 */
	private void exitToDesktop() {
		if (!isAdded() || getActivity() == null) return;
		FragmentManager fm = requireActivity().getSupportFragmentManager();
		// 先清空返回栈再 replace：进入向导的记录链（设置分组页/按键绑定页）还压在栈里，
		// 直接 replace 会让这些旧记录残留——之后任意一次 goHome 的整栈回放会把旧页面
		// 连同被替换掉的旧桌面碎片一起复活，造成多个碎片叠加在 midPanel 上（快捷栏残影）。
		if (fm.getBackStackEntryCount() > 0) {
			fm.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
		}
		fm.beginTransaction()
				.replace(R.id.midPanel, new KeydroidxDesktopFragment())
				.commitAllowingStateLoss();
		KeydroidxLog.i(TAG, "返回桌面待机屏");
		// 向导结束后：若尚未是默认桌面，则询问是否设为默认桌面；之后检查并批量请求核心权限
		askSetDefaultLauncher();
	}

	/** 向导完成后（返回桌面时）询问用户是否将本应用设为系统默认桌面。 */
	private void askSetDefaultLauncher() {
		if (!isAdded() || !(getActivity() instanceof KeydroidxDesktopActivity)) return;
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) getActivity();
		List<KeydroidxOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new KeydroidxOptionsDialog.OptionItem(R.drawable.ic_keydroidx_home, "设置默认桌面", true, false,
				() -> {
					KeydroidxLog.i(TAG, "用户选择设置默认桌面");
					host.requestSetDefaultLauncher();
					navigateToPermissionCheckIfNeeded(host);
				}));
		items.add(new KeydroidxOptionsDialog.OptionItem(0, "稍后再说", true, false,
				() -> {
					KeydroidxLog.i(TAG, "用户选择稍后再说");
					navigateToPermissionCheckIfNeeded(host);
				}));
		KeydroidxOptionsDialog.show(host.getSupportFragmentManager(), "设为默认桌面？", items)
				// 用户按返回键/右软键直接关掉弹窗时也要接续后续流程，
				// 否则向导后的权限自检被整条跳过（实测 release 上按返回键即丢失该步骤）。
				.setOnDismissAction(() -> {
					KeydroidxLog.i(TAG, "默认桌面询问窗未选择即关闭");
					navigateToPermissionCheckIfNeeded(host);
				});
		KeydroidxLog.i(TAG, "弹出默认桌面询问窗");
	}

	/** 向导完成后检查核心运行时权限，若有缺失则跳转至「系统权限自检」页面引导授权 */
	private void navigateToPermissionCheckIfNeeded(KeydroidxDesktopActivity host) {
		if (host == null || host.isFinishing()) return;
		if (KeydroidxPermissionManager.isCorePermissionsGranted(host)) {
			KeydroidxLog.i(TAG, "核心权限已全部授予，跳过自检");
			return;
		}
		KeydroidxLog.i(TAG, "核心权限存在缺失，打开系统权限自检页");
		host.openFragment(new KeydroidxPermissionCheckFragment());
	}

	private void chooseTouchMode() {
		KeydroidxLog.i(TAG, "选择开启触屏模式");
		if (getActivity() instanceof KeydroidxDesktopActivity) {
			((KeydroidxDesktopActivity) getActivity()).setTouchModeEnabled(true);
		} else {
			KeydroidxSettingsStorage.setTouchMode(requireContext(), true);
		}
		finishWizard(false);
	}

	// ---- KeydroidxFocusHost（INTRO / DONE 状态使用）----

	@Override
	public boolean onDirection(int direction) {
		if (state == STATE_INTRO) {
			introChoice = (introChoice == 0) ? 1 : 0;
			updateIntroHighlight();
			KeydroidxLog.d(TAG, "INTRO 切换选择 -> " + (introChoice == 0 ? "绑定" : "触屏模式"));
			return true;
		}
		return true;
	}

	@Override
	public boolean onSelect() {
		if (state == STATE_INTRO) {
			if (inEntryDebounce()) {
				KeydroidxLog.w(TAG, "INTRO 进入防抖中，忽略确认键（防连键误触）");
				return true;
			}
			if (introChoice == 0) {
				KeydroidxLog.i(TAG, "INTRO 选择 绑定");
				startRecording();
			} else {
				KeydroidxLog.i(TAG, "INTRO 选择 触屏模式");
				chooseTouchMode();
			}
			return true;
		}
		if (state == STATE_DONE) {
			if (coolingDown) {
				KeydroidxLog.w(TAG, "DONE 冷却中，忽略确认键（防末项连键立刻返回桌面）");
				return true;
			}
			KeydroidxLog.i(TAG, "DONE 确认 -> 进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		if (state == STATE_INTRO) {
			if (inEntryDebounce()) {
				KeydroidxLog.w(TAG, "INTRO 进入防抖中，忽略左软键（防连键误触）");
				return true;
			}
			KeydroidxLog.i(TAG, "左软键 -> 绑定");
			startRecording();
			return true;
		}
		if (state == STATE_DONE) {
			if (coolingDown) {
				KeydroidxLog.w(TAG, "DONE 冷却中，忽略左软键（防末项连键立刻返回桌面）");
				return true;
			}
			KeydroidxLog.i(TAG, "DONE 左软键 -> 完成/进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	@Override
	public boolean onSoftRight() {
		if (state == STATE_INTRO) {
			if (inEntryDebounce()) {
				KeydroidxLog.w(TAG, "INTRO 进入防抖中，忽略右软键（防连键误触）");
				return true;
			}
			KeydroidxLog.i(TAG, "右软键 -> 触屏模式");
			chooseTouchMode();
			return true;
		}
		if (state == STATE_DONE) {
			if (coolingDown) {
				KeydroidxLog.w(TAG, "DONE 冷却中，忽略右软键（防末项连键立刻返回桌面）");
				return true;
			}
			KeydroidxLog.i(TAG, "DONE 右软键 -> 完成/进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	@Override
	public boolean onBack() {
		// 录制态的 BACK 由 onKeyRecorded 处理（将其绑定为当前动作）；此处仅 INTRO / DONE 生效
		if (state == STATE_INTRO) {
			if (inEntryDebounce()) {
				KeydroidxLog.w(TAG, "INTRO 进入防抖中，忽略返回键（防连键误触）");
				return true;
			}
			KeydroidxLog.i(TAG, "返回键 -> 触屏模式");
			chooseTouchMode();
			return true;
		}
		if (state == STATE_DONE) {
			if (coolingDown) {
				KeydroidxLog.w(TAG, "DONE 冷却中，忽略返回键（防末项连键立刻返回桌面）");
				return true;
			}
			KeydroidxLog.i(TAG, "DONE 返回键 -> 进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	// ---- KeydroidxPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

	@Override
	public String getPageTitle() {
		return "应用向导";
	}

	@Override
	public String getSoftLeftText() {
		if (state == STATE_INTRO) return "绑定";
		if (state == STATE_DONE) return "完成";
		return null;
	}

	@Override
	public String getSoftRightText() {
		if (state == STATE_INTRO) return "触屏模式";
		return null;
	}
}
