package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面组件设置 → 添加/编辑网址组件页。
 * 简洁三态：名称字段 / 网址字段 / 保存按钮。
 * 网址由用户直接输入完整 URL，不做前缀拼接。
 */
public class KeydroidxWidgetUrlEditFragment extends KeydroidxPageFragment {

	private static final String TAG = "WidgetUrlEdit";
	private static final String EXTRA_MODE = "mode";
	private static final String EXTRA_EDIT_INDEX = "editIndex";
	private static final int MODE_ADD = 0;
	private static final int MODE_EDIT = 1;

	private static final int FOCUS_NAME = 0;
	private static final int FOCUS_URL = 1;

	private int mode = MODE_ADD;
	private int editIndex = -1;

	private TextView tvTitle;
	private EditText etName;
	private EditText etUrl;
	private Toast toast;

	private KeydroidxWidgetStorage storage;

	private int focusIndex = FOCUS_NAME;
	private boolean editing = false;
	private int editingField = -1;

	public static KeydroidxWidgetUrlEditFragment newAddMode() {
		KeydroidxWidgetUrlEditFragment f = new KeydroidxWidgetUrlEditFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_ADD);
		f.setArguments(b);
		return f;
	}

	public static KeydroidxWidgetUrlEditFragment newEditMode(int editIndex) {
		KeydroidxWidgetUrlEditFragment f = new KeydroidxWidgetUrlEditFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_EDIT);
		b.putInt(EXTRA_EDIT_INDEX, editIndex);
		f.setArguments(b);
		return f;
	}

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_widget_url_edit;
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		Bundle args = getArguments();
		if (args != null) {
			mode = args.getInt(EXTRA_MODE, MODE_ADD);
			editIndex = args.getInt(EXTRA_EDIT_INDEX, -1);
		}
		KeydroidxLog.i(TAG, "初始化 mode=" + (mode == MODE_EDIT ? "EDIT" : "ADD")
				+ " editIndex=" + editIndex);

		storage = new KeydroidxWidgetStorage(requireContext());
		tvTitle = view.findViewById(R.id.tvUrlTitle);
		etName = view.findViewById(R.id.etName);
		etUrl = view.findViewById(R.id.etUrl);
		updateTitle();

		prefillIfEditMode();

		etName.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_NEXT) {
				KeydroidxLog.d(TAG, "名称 IME next → 激活网址编辑态");
				exitEditing();
				activateEditing(FOCUS_URL);
				return true;
			}
			return false;
		});
		etUrl.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				KeydroidxLog.d(TAG, "网址 IME done → 关闭软键盘");
				exitEditing();
				applyFocus();
				return true;
			}
			return false;
		});
		etName.setOnClickListener(v -> activateEditing(FOCUS_NAME));
		etUrl.setOnClickListener(v -> activateEditing(FOCUS_URL));

		view.post(() -> {
			if (!isAdded()) return;
			focusIndex = FOCUS_NAME;
			applyFocus();
			KeydroidxLog.i(TAG, "初始化完成 focusIndex=" + focusIndex);
		});
	}

	private void updateTitle() {
		if (tvTitle != null) {
			tvTitle.setText(mode == MODE_EDIT ? "编辑网址" : "添加网址");
		}
	}

	private void prefillIfEditMode() {
		if (mode != MODE_EDIT) return;
		List<KeydroidxWidgetItem> widgets = storage.getWidgets();
		if (editIndex >= 0 && editIndex < widgets.size()) {
			KeydroidxWidgetItem item = widgets.get(editIndex);
			etName.setText(item.label == null ? "" : item.label);
			etUrl.setText(item.value == null ? "" : item.value);
			KeydroidxLog.i(TAG, "EDIT 预填 label=" + item.label + " value=" + item.value);
		} else {
			KeydroidxLog.w(TAG, "editIndex 越界，降级为添加模式");
			mode = MODE_ADD;
			updateTitle();
		}
	}

	// ---- 编辑态 ----

	private void activateEditing(int fieldIndex) {
		if (fieldIndex != FOCUS_NAME && fieldIndex != FOCUS_URL) return;
		editing = true;
		editingField = fieldIndex;
		focusIndex = fieldIndex;
		EditText target = fieldIndex == FOCUS_NAME ? etName : etUrl;
		target.setFocusable(true);
		target.setFocusableInTouchMode(true);
		target.requestFocus();
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
		}
		applyFocus();
	}

	private void exitEditing() {
		if (!editing) return;
		editing = false;
		editingField = -1;
		etName.clearFocus();
		etName.setFocusable(false);
		etUrl.clearFocus();
		etUrl.setFocusable(false);
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.hideSoftInputFromWindow(etName.getWindowToken(), 0);
		}
		KeydroidxLog.d(TAG, "退出编辑态");
	}

	// ---- 焦点 ----

	private void setFocusIndex(int index) {
		if (index < FOCUS_NAME || index > FOCUS_URL) return;
		KeydroidxLog.d(TAG, "setFocusIndex " + focusIndex + " -> " + index);
		focusIndex = index;
		applyFocus();
	}

	private void applyFocus() {
		if (etName == null || etUrl == null) return;
		etName.setBackgroundResource(
				(focusIndex == FOCUS_NAME && !editing) || editingField == FOCUS_NAME
						? 0 : R.drawable.bg_keydroidx_searchbox);
		etUrl.setBackgroundResource(
				(focusIndex == FOCUS_URL && !editing) || editingField == FOCUS_URL
						? 0 : R.drawable.bg_keydroidx_searchbox);
	}

	// ---- 导航 ----

	@Override
	public boolean onDirection(int direction) {
		if (editing) {
			// 编辑态：上下键切换输入框，左右键交给 EditText 移动光标
			if (direction == KeydroidxKeyBinding.ACTION_UP && editingField == FOCUS_URL) {
				switchEditingField(FOCUS_NAME);
				return true;
			}
			if (direction == KeydroidxKeyBinding.ACTION_DOWN && editingField == FOCUS_NAME) {
				switchEditingField(FOCUS_URL);
				return true;
			}
			return false;
		}
		// 焦点态
		switch (direction) {
			case KeydroidxKeyBinding.ACTION_UP:
				if (focusIndex > FOCUS_NAME) setFocusIndex(focusIndex - 1);
				return true;
			case KeydroidxKeyBinding.ACTION_DOWN:
				if (focusIndex < FOCUS_URL) setFocusIndex(focusIndex + 1);
				return true;
			case KeydroidxKeyBinding.ACTION_LEFT:
			case KeydroidxKeyBinding.ACTION_RIGHT:
				return true;
			default:
				return false;
		}
	}

	private void switchEditingField(int newField) {
		if (newField == editingField) return;
		editingField = newField;
		focusIndex = newField;
		EditText target = newField == FOCUS_NAME ? etName : etUrl;
		target.setFocusable(true);
		target.setFocusableInTouchMode(true);
		target.requestFocus();
		InputMethodManager imm = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null) {
			imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
		}
		applyFocus();
	}

	@Override
	public boolean onSelect() {
		if (editing) return false;
		switch (focusIndex) {
			case FOCUS_NAME:
				activateEditing(FOCUS_NAME);
				return true;
			case FOCUS_URL:
				activateEditing(FOCUS_URL);
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean onSoftLeft() {
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
		((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		// 编辑态下返回键：不做特殊处理，让系统正常处理（可能是删除文字）
		// 只在焦点态下退出页面
		if (!editing) {
			((KeydroidxDesktopActivity) requireActivity()).exitCurrent();
		}
		// 编辑态返回 false，让系统/输入法处理
		return !editing;
	}

	// ---- 保存 ----

	private void validateAndSave() {
		String name = etName.getText() == null ? "" : etName.getText().toString().trim();
		String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
		KeydroidxLog.i(TAG, "保存 name=\"" + name + "\" url=\"" + url + "\"");

		if (name.isEmpty()) {
			showToast("请输入显示名称");
			return;
		}
		if (url.isEmpty()) {
			showToast("请输入网址");
			return;
		}

		KeydroidxWidgetItem item = new KeydroidxWidgetItem(KeydroidxWidgetItem.TYPE_URL, name, url);
		KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
		if (mode == MODE_EDIT) {
			storage.updateWidget(editIndex, item);
			showToast("已保存 " + name);
			host.exitCurrent();
		} else {
			if (storage.isFull()) {
				showToast("组件已达上限");
				return;
			}
			storage.addWidget(item);
			showToast("已添加 " + name);
			FragmentManager fm = host.getSupportFragmentManager();
			int entries = fm.getBackStackEntryCount();
			if (entries > 1) {
				fm.popBackStackImmediate();
				fm.popBackStackImmediate();
				KeydroidxLog.i(TAG, "ADD 保存后出栈 2 层");
			} else {
				fm.popBackStackImmediate();
				KeydroidxLog.i(TAG, "ADD 保存后出栈 1 层");
			}
		}
	}

	// ---- KeydroidxPage ----

	@Override
	public String getPageTitle() {
		return mode == MODE_EDIT ? "编辑网址" : "添加网址";
	}

	@Override
	public String getSoftLeftText() {
		return "保存";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	private void showToast(String msg) {
		if (toast != null) toast.cancel();
		toast = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT);
		toast.show();
		KeydroidxLog.i(TAG, "Toast: " + msg);
	}
}
