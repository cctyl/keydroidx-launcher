package ru.playsoftware.j2meloader.nokia;

import android.app.Dialog;
import android.os.Bundle;
import io.github.cctyl.nokia.common.ui.focus.NokiaDialogFocus;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import ru.playsoftware.j2meloader.R;

/**
 * 诺基亚风格卸载确认弹窗。
 * 提供"取消"与"卸载"两个选项，可用方向键（左右/软键）切换高亮，
 * 按确认键（DPAD_CENTER / ENTER）或对应软键触发当前高亮项；返回键等效"取消"。
 * 实际删除逻辑由宿主通过 {@link ConfirmListener} 回调执行（避免传递非 Parcelable 的 AppItem）。
 */
public class NokiaUninstallDialog extends DialogFragment {
	private static final String TAG = "UninstallDialog";
	private static final String ARG_NAME = "app_name";

	private TextView softLeft;
	private TextView softRight;
	private ConfirmListener confirmListener;

	public interface ConfirmListener {
		void onConfirm();
	}

	public static NokiaUninstallDialog newInstance(String appName) {
		NokiaUninstallDialog dialog = new NokiaUninstallDialog();
		Bundle args = new Bundle();
		args.putString(ARG_NAME, appName);
		dialog.setArguments(args);
		return dialog;
	}

	public void setConfirmListener(ConfirmListener listener) {
		this.confirmListener = listener;
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		NokiaLog.i(TAG, "onCreateDialog: 创建卸载确认弹窗");
		Dialog dialog = new Dialog(requireActivity());
		dialog.setContentView(R.layout.dialog_nokia_uninstall);
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		if (dialog.getWindow() != null) {
			dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			dialog.getWindow().setGravity(Gravity.BOTTOM);
			dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
		}

		String appName = requireArguments() != null ? requireArguments().getString(ARG_NAME, "") : "";
		TextView content = dialog.findViewById(R.id.uninstall_content);
		softLeft = dialog.findViewById(R.id.softLeft);
		softRight = dialog.findViewById(R.id.softRight);

		NokiaTheme.ThemeDef theme = NokiaTheme.getSelectedTheme(requireContext());
		View titleBar = dialog.findViewById(R.id.uninstall_title_bar);
		if (titleBar != null) titleBar.setBackground(NokiaTheme.createSoftKeyDrawable(theme));
		View body = dialog.findViewById(R.id.uninstall_body);
		if (body != null) body.setBackground(NokiaTheme.createDialogBodyDrawable(theme));
		View bottomBar = dialog.findViewById(R.id.uninstall_bottom_bar);
		if (bottomBar != null) bottomBar.setBackground(NokiaTheme.createSoftKeyDrawable(theme));
		if (content != null) {
			content.setText("是否卸载「" + appName + "」？");
		}

		// 触摸支持：点击软键 = 直接触发该项
		if (softLeft != null) {
			softLeft.setOnClickListener(v -> trigger(0));
		}
		if (softRight != null) {
			softRight.setOnClickListener(v -> trigger(1));
		}

		// 接入用户自定义按键映射，与桌面行为 100% 一致（禁止写死 keyCode）
		final NokiaKeyBinding keyBinding =
				((NokiaDesktopActivity) requireActivity()).getKeyBinding();
		dialog.setOnKeyListener((d, keyCode, event) -> {
			if (event.getAction() != KeyEvent.ACTION_DOWN) {
				// 消费抬起事件，避免重复触发
				return true;
			}
			NokiaLog.d(TAG, "onKey keyCode=" + keyCode);
			// 返回键由弹窗自己处理（NokiaKeyBinding 不管 BACK）
			if (keyCode == KeyEvent.KEYCODE_BACK) {
				NokiaLog.i(TAG, "返回键：取消卸载");
				trigger(1);
				return true;
			}
			int action = keyBinding.resolveAction(event);
			switch (action) {
				case NokiaKeyBinding.ACTION_SOFT_LEFT:
					NokiaLog.i(TAG, "左软键：确认卸载");
					trigger(0);
					return true;
				case NokiaKeyBinding.ACTION_SOFT_RIGHT:
					NokiaLog.i(TAG, "右软键：取消卸载");
					trigger(1);
					return true;
				case NokiaKeyBinding.ACTION_SELECT:
					// 内容区无可选中项，确认键只消费，绝不触发左右软键
					return true;
				case NokiaKeyBinding.ACTION_LEFT:
				case NokiaKeyBinding.ACTION_RIGHT:
					// 软键没有"焦点"概念，方向键左/右直接忽略（消费）
					return true;
				default:
					return false;
			}
		});

		// Android 12+：Dialog 窗口首个导航键会被触摸模式吞掉，show 后强制退出该状态
		dialog.setOnShowListener(d -> NokiaDialogFocus.forceNonTouchMode(dialog));

		return dialog;
	}

	private void trigger(int index) {
		if (index == 0) {
			NokiaLog.i(TAG, "确认卸载");
			if (confirmListener != null) {
				confirmListener.onConfirm();
			}
		} else {
			NokiaLog.i(TAG, "取消卸载");
		}
		dismiss();
	}
}
