package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面组件设置 → 添加Activity快捷 → 步骤3：输入名称。
 * 单字段表单页：名称输入框 + 保存按钮，复用3号文档的三态模式。
 * <p>
 * 接收 Bundle 参数：mode (ADD/EDIT), packageName, className, activityLabel, editIndex(EDIT模式)。
 * 名称字段预填 Activity label，用户可直接确认或修改后保存。
 */
public class NokiaWidgetActivityNameFragment extends Fragment implements NokiaPage {

	private static final String TAG = "WidgetActivityName";

	private static final String EXTRA_MODE = "mode";
	private static final String EXTRA_PACKAGE_NAME = "packageName";
	private static final String EXTRA_CLASS_NAME = "className";
	private static final String EXTRA_ACTIVITY_LABEL = "activityLabel";
	private static final String EXTRA_EDIT_INDEX = "editIndex";

	public static final String MODE_ADD = "ADD";
	public static final String MODE_EDIT = "EDIT";

	private static final int FOCUS_NAME = 0;
	private static final int FOCUS_SAVE = 1;

	private String mode = MODE_ADD;
	private String packageName;
	private String className;
	private String activityLabel;
	private int editIndex = -1;

	private EditText etName;
	private TextView tvSaveButton;
	private Toast toast;

	private NokiaWidgetStorage storage;

	private int focusIndex = FOCUS_NAME;
	private boolean editing = false;

	// ---- 创建入口 ----

	public static NokiaWidgetActivityNameFragment newAddMode(String packageName,
			String className, String activityLabel) {
		NokiaWidgetActivityNameFragment f = new NokiaWidgetActivityNameFragment();
		Bundle b = new Bundle();
		b.putString(EXTRA_MODE, MODE_ADD);
		b.putString(EXTRA_PACKAGE_NAME, packageName);
		b.putString(EXTRA_CLASS_NAME, className);
		b.putString(EXTRA_ACTIVITY_LABEL, activityLabel);
		f.setArguments(b);
		return f;
	}

	public static NokiaWidgetActivityNameFragment newEditMode(String packageName,
			String className, String activityLabel, int editIndex) {
		NokiaWidgetActivityNameFragment f = new NokiaWidgetActivityNameFragment();
		Bundle b = new Bundle();
		b.putString(EXTRA_MODE, MODE_EDIT);
		b.putString(EXTRA_PACKAGE_NAME, packageName);
		b.putString(EXTRA_CLASS_NAME, className);
		b.putString(EXTRA_ACTIVITY_LABEL, activityLabel);
		b.putInt(EXTRA_EDIT_INDEX, editIndex);
		f.setArguments(b);
		return f;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_nokia_widget_activity_name, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
		host.scaleMidContent(view, true);
		// match_parent 根布局 + topAlign=true 的二次缩放陷阱：补动态高度调整
		host.fixMidContentHeight(view, true);

		View wall = host.findViewById(R.id.wallpaper);
		if (wall != null) {
			wall.setBackgroundResource(R.drawable.bg_nokia_menu);
		}
		host.refreshPageBar();

		Bundle args = getArguments();
		if (args != null) {
			mode = args.getString(EXTRA_MODE, MODE_ADD);
			packageName = args.getString(EXTRA_PACKAGE_NAME);
			className = args.getString(EXTRA_CLASS_NAME);
			activityLabel = args.getString(EXTRA_ACTIVITY_LABEL);
			editIndex = args.getInt(EXTRA_EDIT_INDEX, -1);
		}
		NokiaLog.i(TAG, "初始化 mode=" + mode + " pkg=" + packageName
				+ " cls=" + className + " label=" + activityLabel + " editIndex=" + editIndex);

		storage = new NokiaWidgetStorage(requireContext());
		etName = view.findViewById(R.id.etActivityName);
		tvSaveButton = view.findViewById(R.id.tvSaveButton);

		// 预填 Activity label
		String prefill = activityLabel;
		if (prefill == null || prefill.isEmpty()) {
			// 无 label 时使用类名简称
			if (className != null) {
				int lastDot = className.lastIndexOf('.');
				prefill = lastDot >= 0 ? className.substring(lastDot + 1) : className;
			} else {
				prefill = "";
			}
		}
		etName.setText(prefill);
		NokiaLog.i(TAG, "预填名称: \"" + prefill + "\"");

		// IME done → 关闭软键盘
		etName.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				NokiaLog.d(TAG, "IME done → 关闭软键盘");
				exitEditing();
				applyFocus();
				return true;
			}
			return false;
		});
		etName.setOnClickListener(v -> activateEditing());

		// 保存按钮点击
		tvSaveButton.setOnClickListener(v -> {
			NokiaLog.d(TAG, "保存按钮点击");
			setFocusIndex(FOCUS_SAVE);
			validateAndSave();
		});

		view.post(() -> {
			if (!isAdded()) return;
			focusIndex = FOCUS_NAME;
			applyFocus();
			NokiaLog.i(TAG, "初始化完成 focusIndex=" + focusIndex);
		});
	}

	// ---- 编辑态 ----

	private void activateEditing() {
		editing = true;
		focusIndex = FOCUS_NAME;
		etName.setFocusable(true);
		etName.setFocusableInTouchMode(true);
		etName.requestFocus();
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.showSoftInput(etName, InputMethodManager.SHOW_IMPLICIT);
			NokiaLog.d(TAG, "激活编辑态，软键盘弹出");
		}
		applyFocus();
	}

	private void exitEditing() {
		if (!editing) return;
		editing = false;
		etName.clearFocus();
		etName.setFocusable(false);
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.hideSoftInputFromWindow(etName.getWindowToken(), 0);
		}
		NokiaLog.d(TAG, "退出编辑态");
	}

	// ---- 焦点 ----

	private void setFocusIndex(int index) {
		if (index < FOCUS_NAME || index > FOCUS_SAVE) return;
		NokiaLog.d(TAG, "setFocusIndex " + focusIndex + " -> " + index);
		focusIndex = index;
		applyFocus();
	}

	private void applyFocus() {
		if (etName == null || tvSaveButton == null) return;
		// 名称字段：焦点态蓝色高亮，非焦点态灰色边框
		etName.setBackgroundResource(
				(focusIndex == FOCUS_NAME && !editing) || editing
						? R.drawable.bg_nokia_selected_dark : R.drawable.bg_nokia_searchbox);

		// 保存按钮：焦点态蓝色高亮，非焦点态普通样式
		if (focusIndex == FOCUS_SAVE) {
			tvSaveButton.setTextColor(0xFF2196F3);
			tvSaveButton.setBackgroundResource(R.drawable.bg_nokia_selected_dark);
		} else {
			tvSaveButton.setTextColor(0xFF64B5F6);
			tvSaveButton.setBackgroundResource(0);
		}
	}

	// ---- NokiaFocusHost ----

	@Override
	public boolean onDirection(int direction) {
		if (editing) {
			// 编辑态：方向键不做焦点切换，锁定在 EditText
			return false;
		}
		// 焦点态：上下切换（名称字段 ↔ 保存按钮）
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > FOCUS_NAME) setFocusIndex(focusIndex - 1);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < FOCUS_SAVE) setFocusIndex(focusIndex + 1);
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
				return true;
			default:
				return true;
		}
	}

	@Override
	public boolean onSelect() {
		if (editing) return false;
		switch (focusIndex) {
			case FOCUS_NAME:
				activateEditing();
				return true;
			case FOCUS_SAVE:
				validateAndSave();
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean onSoftLeft() {
		// 左软键「保存」：直接保存（不论焦点在哪）
		NokiaLog.i(TAG, "左软键保存");
		validateAndSave();
		return true;
	}

	@Override
	public boolean onSoftRight() {
		if (editing) {
			exitEditing();
			applyFocus();
			return true;
		}
		// 右软键「返回」：回到步骤2
		NokiaLog.i(TAG, "右软键：返回步骤2（选择Activity）");
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		if (!editing) {
			((NokiaDesktopActivity) requireActivity()).exitCurrent();
		}
		return !editing;
	}

	// ---- 保存 ----

	private void validateAndSave() {
		String name = etName.getText() == null ? "" : etName.getText().toString().trim();
		NokiaLog.i(TAG, "保存 name=\"" + name + "\"");

		if (name.isEmpty()) {
			showToast("请输入显示名称");
			return;
		}

		// payload = packageName + "/" + className
		String payload = packageName + "/" + className;
		// iconPath = packageName（桌面加载应用图标用）
		NokiaWidgetItem item = new NokiaWidgetItem(NokiaWidgetItem.TYPE_ACTIVITY, name, payload, packageName);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();

		if (MODE_EDIT.equals(mode)) {
			storage.updateWidget(editIndex, item);
			showToast("已保存 " + name);
			// EDIT 模式：栈为 S1 → AppPicker → ActivityPicker → NameFragment，
			// 需 pop 三层回到 S1
			FragmentManager fm = host.getSupportFragmentManager();
			int entries = fm.getBackStackEntryCount();
			if (entries >= 3) {
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "EDIT 保存后同步出栈 3 层");
			} else {
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "EDIT 保存后出栈 1 层（返回栈不足3层）");
			}
		} else {
			if (storage.isFull()) {
				showToast("组件已达上限");
				return;
			}
			storage.addWidget(item);
			showToast("已添加 " + name);
			// ADD 模式：栈为 S1 → 类型选择 → AppPicker → ActivityPicker → NameFragment，
			// 需 pop 四层回到 S1
			FragmentManager fm = host.getSupportFragmentManager();
			int entries = fm.getBackStackEntryCount();
			if (entries >= 4) {
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "ADD 保存后同步出栈 4 层");
			} else if (entries >= 3) {
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "ADD 保存后出栈 3 层（返回栈不足4层）");
			} else {
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "ADD 保存后出栈 1 层（返回栈不足3层）");
			}
		}
	}

	// ---- NokiaPage ----

	@Override
	public String getPageTitle() {
		return "输入名称";
	}

	@Override
	public String getSoftLeftText() {
		return "保存";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	// ---- 工具 ----

	private void showToast(String msg) {
		if (toast != null) toast.cancel();
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		NokiaLog.i(TAG, "Toast: " + msg);
	}
}
