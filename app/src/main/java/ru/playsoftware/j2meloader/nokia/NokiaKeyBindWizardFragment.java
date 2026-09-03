package ru.playsoftware.j2meloader.nokia;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import io.github.cctyl.nokia.common.log.NokiaLog;
import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.ui.focus.NokiaFocusHost;
import java.util.ArrayList;
import java.util.List;

import io.github.cctyl.nokia.common.permission.NokiaPermissionManager;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import ru.playsoftware.j2meloader.R;

/**
 * 首次启动按键绑定向导。
 * <p>
 * 状态机：INTRO（弹窗询问是否绑定）→ RECORDING（按 上/下/左/右/确认/左软键/右软键/锁屏
 * 顺序逐个提示并捕获一次物理键）→ DONE（标记完成并返回桌面）。
 * <p>
 * 每个录制步骤支持"跳过"：录制态下按返回键即跳过当前动作（保留默认值）并前进，
 * 避免设备缺键时卡死。
 * <p>
 * 仅首次启动弹出（由 NokiaKeyBinding.isWizardDone 控制，清数据后重置）。
 * 整个流程完全由物理按键驱动，复用 NokiaKeyRecorder 的录制捕获机制。
 */
public class NokiaKeyBindWizardFragment extends NokiaPageFragment implements NokiaKeyRecorder {

	private static final int STATE_INTRO = 0;
	private static final int STATE_RECORDING = 1;
	private static final int STATE_DONE = 2;

	private NokiaKeyBinding keyBinding;
	private int state = STATE_INTRO;
	private int introChoice = 0;      // 0=绑定, 1=跳过
	private int recordingStep = -1;   // -1=非录制态；0..7=正在录制第 N 个动作

	private View introCard;
	private View recordingLayout;
	private View doneLayout;
	private View introBind;
	private View introSkip;
	private TextView recordPrompt;
	private TextView recordProgress;
	private TextView stepBadge;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_key_bind_wizard;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		keyBinding = new NokiaKeyBinding(requireContext());

		introCard = view.findViewById(R.id.introCard);
		recordingLayout = view.findViewById(R.id.recordingLayout);
		doneLayout = view.findViewById(R.id.doneLayout);
		introBind = view.findViewById(R.id.introBind);
		introSkip = view.findViewById(R.id.introSkip);
		recordPrompt = view.findViewById(R.id.recordPrompt);
		recordProgress = view.findViewById(R.id.recordProgress);
		stepBadge = view.findViewById(R.id.stepBadge);

		// 触摸跳过按钮：仅通过触摸触发跳过当前项（录制态下返回键已被忽略，不再用于跳过）
		View recordSkip = view.findViewById(R.id.recordSkip);
		if (recordSkip != null) {
			recordSkip.setOnClickListener(v -> {
				NokiaLog.i("KeyWizard", "触摸点击 跳过当前项");
				onSkipCurrent();
			});
		}

		// 触摸支持（不影响按键路径）
		introBind.setOnClickListener(v -> {
			introChoice = 0;
			updateIntroHighlight();
			startRecording();
		});
		introSkip.setOnClickListener(v -> finishWizard(false));

		View doneBtn = view.findViewById(R.id.doneBtn);
		if (doneBtn != null) {
			doneBtn.setOnClickListener(v -> exitToDesktop());
		}

		showIntro();
	}

	// ---- 状态切换 ----

	private void showIntro() {
		state = STATE_INTRO;
		recordingStep = -1;
		introCard.setVisibility(View.VISIBLE);
		recordingLayout.setVisibility(View.GONE);
		doneLayout.setVisibility(View.GONE);
		introChoice = 0;
		updateIntroHighlight();
		if (stepBadge != null) stepBadge.setText("");
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.refreshPageBar();
		NokiaLog.i("KeyWizard", "进入 INTRO 弹窗（绑定/跳过）");
	}

	private void updateIntroHighlight() {
		introBind.setBackground(introChoice == 0 ? NokiaTheme.createSelectionDrawable(requireContext(), 4) : null);
		introSkip.setBackground(introChoice == 1 ? NokiaTheme.createSelectionDrawable(requireContext(), 4) : null);
	}

	private void startRecording() {
		state = STATE_RECORDING;
		recordingStep = 0;
		introCard.setVisibility(View.GONE);
		recordingLayout.setVisibility(View.VISIBLE);
		doneLayout.setVisibility(View.GONE);
		// 录制态下任意键都会被捕获为当前动作的绑定键，底部栏隐藏
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.refreshPageBar();
		updateRecordingPrompt();
		NokiaLog.i("KeyWizard", "开始录制，第 1 项="
				+ NokiaKeyBinding.getWizardPromptName(0));
	}

	private void updateRecordingPrompt() {
		recordPrompt.setText("请按下『" + NokiaKeyBinding.getWizardPromptName(recordingStep) + "』键");
		recordProgress.setText("第 " + (recordingStep + 1) + " / " + NokiaKeyBinding.ACTION_COUNT + " 项");
		if (stepBadge != null) {
			stepBadge.setText((recordingStep + 1) + "/" + NokiaKeyBinding.ACTION_COUNT);
		}
	}

	// ---- NokiaKeyRecorder（录制态捕获物理键）----

	@Override
	public boolean isRecording() {
		return state == STATE_RECORDING;
	}

	@Override
	public void onKeyRecorded(int keycode) {
		if (state != STATE_RECORDING) return;
		int action = recordingStep;

		keyBinding.setKeyCode(action, keycode);
		// 同步到全局 JAR 设置
		NokiaGlobalProfile.syncKeyBindings(requireContext());
		NokiaLog.i("KeyWizard", "第 " + (action + 1) + " 项 绑定成功 "
				+ NokiaKeyBinding.keyName(keycode));

		int next = action + 1;
		if (next >= NokiaKeyBinding.ACTION_COUNT) {
			finishWizard(true);
		} else {
			recordingStep = next;
			updateRecordingPrompt();
			NokiaLog.i("KeyWizard", "进入第 " + (next + 1) + " 项="
					+ NokiaKeyBinding.getWizardPromptName(next));
		}
	}

	/** 录制态下按返回键：跳过当前动作的绑定（保留默认值），前进到下一项或结束。 */
	@Override
	public void onSkipCurrent() {
		if (state != STATE_RECORDING) return;
		int action = recordingStep;
		NokiaLog.i("KeyWizard", "第 " + (action + 1) + " 项 跳过（保留默认 "
				+ NokiaKeyBinding.keyName(keyBinding.getKeyCode(action)) + "）");

		int next = action + 1;
		if (next >= NokiaKeyBinding.ACTION_COUNT) {
			finishWizard(true);
		} else {
			recordingStep = next;
			updateRecordingPrompt();
			NokiaLog.i("KeyWizard", "跳过进入第 " + (next + 1) + " 项="
					+ NokiaKeyBinding.getWizardPromptName(next));
		}
	}

	private void finishWizard(boolean bound) {
		state = STATE_DONE;
		recordingStep = -1;
		introCard.setVisibility(View.GONE);
		recordingLayout.setVisibility(View.GONE);
		doneLayout.setVisibility(View.VISIBLE);
		if (stepBadge != null) stepBadge.setText("");
		// 标记向导已完成，下次启动不再弹出
		keyBinding.markWizardDone();
		// 让 Activity 的内存绑定立即生效
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).reloadKeyBindings();
			((NokiaDesktopActivity) getActivity()).refreshPageBar();
		}
		NokiaLog.i("KeyWizard", "向导结束 bound=" + bound + "，进入完成提示页");
	}

	/** 退出向导并返回桌面待机屏 */
	private void exitToDesktop() {
		if (!isAdded() || getActivity() == null) return;
		FragmentManager fm = requireActivity().getSupportFragmentManager();
		fm.beginTransaction()
				.replace(R.id.midPanel, new NokiaDesktopFragment())
				.commitAllowingStateLoss();
		NokiaLog.i("KeyWizard", "返回桌面待机屏");
		// 向导结束后：若尚未是默认桌面，则询问是否设为默认桌面；之后检查并批量请求核心权限
		askSetDefaultLauncher();
	}

	/** 向导完成后（返回桌面时）询问用户是否将本应用设为系统默认桌面。 */
	private void askSetDefaultLauncher() {
		if (!isAdded() || !(getActivity() instanceof NokiaDesktopActivity)) return;
		NokiaDesktopActivity host = (NokiaDesktopActivity) getActivity();
		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		items.add(new NokiaOptionsDialog.OptionItem(R.drawable.ic_nokia_home, "设置默认桌面", true, false,
				() -> {
					NokiaLog.i("KeyWizard", "用户选择设置默认桌面");
					host.requestSetDefaultLauncher();
					checkAndRequestPermissions(host);
				}));
		items.add(new NokiaOptionsDialog.OptionItem(0, "稍后再说", true, false,
				() -> {
					NokiaLog.i("KeyWizard", "用户选择稍后再说");
					checkAndRequestPermissions(host);
				}));
		NokiaOptionsDialog.show(host.getSupportFragmentManager(), "设为默认桌面？", items);
		NokiaLog.i("KeyWizard", "弹出默认桌面询问窗");
	}

	/** 向导完成后检查核心运行时权限，若有缺失则弹出复古诺基亚对话框批量引导授权 */
	private void checkAndRequestPermissions(NokiaDesktopActivity host) {
		if (host == null || host.isFinishing()) return;
		// 收集未授权的核心权限（电话状态用于顶栏信号/卡信息，应用列表用于功能表和解冻）
		List<String> needed = new ArrayList<>();
		if (!NokiaPermissionManager.isGranted(host, Permission.READ_PHONE_STATE)) {
			needed.add(Permission.READ_PHONE_STATE);
		}
		if (!NokiaPermissionManager.isGranted(host, Permission.GET_INSTALLED_APPS)) {
			needed.add(Permission.GET_INSTALLED_APPS);
		}

		if (needed.isEmpty()) {
			NokiaLog.i("KeyWizard", "核心权限已全部就绪，无需额外申请");
			return;
		}

		NokiaLog.i("KeyWizard", "向导结束批量申请权限: " + needed);
		NokiaPermissionManager.requestWithNokiaDialog(
				host,
				"系统权限申请",
				"桌面需要电话状态与应用列表权限，以显示信号状态及展示/启动应用。",
				needed,
				new OnPermissionCallback() {
					@Override
					public void onGranted(List<String> permissions, boolean allGranted) {
						NokiaLog.i("KeyWizard", "向导批量权限全部授予成功");
					}

					@Override
					public void onDenied(List<String> permissions, boolean doNotAskAgain) {
						NokiaLog.w("KeyWizard", "向导批量权限部分被拒: " + permissions + ", doNotAskAgain=" + doNotAskAgain);
					}
				}
		);
	}

	// ---- NokiaFocusHost（INTRO / DONE 状态使用）----

	@Override
	public boolean onDirection(int direction) {
		if (state == STATE_INTRO) {
			introChoice = (introChoice == 0) ? 1 : 0;
			updateIntroHighlight();
			NokiaLog.d("KeyWizard", "INTRO 切换选择 -> " + (introChoice == 0 ? "绑定" : "跳过"));
			return true;
		}
		return true;
	}

	@Override
	public boolean onSelect() {
		if (state == STATE_INTRO) {
			if (introChoice == 0) {
				NokiaLog.i("KeyWizard", "INTRO 选择 绑定");
				startRecording();
			} else {
				NokiaLog.i("KeyWizard", "INTRO 选择 跳过");
				finishWizard(false);
			}
			return true;
		}
		if (state == STATE_DONE) {
			NokiaLog.i("KeyWizard", "DONE 确认 -> 进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		if (state == STATE_INTRO) {
			NokiaLog.i("KeyWizard", "左软键 -> 绑定");
			startRecording();
			return true;
		}
		if (state == STATE_DONE) {
			NokiaLog.i("KeyWizard", "DONE 左软键 -> 完成/进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	@Override
	public boolean onSoftRight() {
		if (state == STATE_INTRO) {
			NokiaLog.i("KeyWizard", "右软键 -> 跳过");
			finishWizard(false);
			return true;
		}
		if (state == STATE_DONE) {
			NokiaLog.i("KeyWizard", "DONE 右软键 -> 完成/进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	@Override
	public boolean onBack() {
		// 录制态的 BACK 由 onKeyRecorded 处理（将其绑定为当前动作）；此处仅 INTRO / DONE 生效
		if (state == STATE_INTRO) {
			NokiaLog.i("KeyWizard", "返回键 -> 跳过");
			finishWizard(false);
			return true;
		}
		if (state == STATE_DONE) {
			NokiaLog.i("KeyWizard", "DONE 返回键 -> 进入桌面");
			exitToDesktop();
			return true;
		}
		return true;
	}

	// ---- NokiaPage 接口（底部菜单栏声明，由 host.refreshPageBar() 装配） ----

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
		if (state == STATE_INTRO) return "跳过";
		return null;
	}
}
