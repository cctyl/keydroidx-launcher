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

import java.util.List;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面组件设置 → 添加/编辑网址组件页。
 * 简洁三态：名称字段 / 网址字段 / 保存按钮。
 * 网址由用户直接输入完整 URL，不做前缀拼接。
 */
public class NokiaWidgetUrlEditFragment extends Fragment implements NokiaPage {

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

	private NokiaWidgetStorage storage;

	private int focusIndex = FOCUS_NAME;
	private boolean editing = false;
	private int editingField = -1;

	public static NokiaWidgetUrlEditFragment newAddMode() {
		NokiaWidgetUrlEditFragment f = new NokiaWidgetUrlEditFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_ADD);
		f.setArguments(b);
		return f;
	}

	public static NokiaWidgetUrlEditFragment newEditMode(int editIndex) {
		NokiaWidgetUrlEditFragment f = new NokiaWidgetUrlEditFragment();
		Bundle b = new Bundle();
		b.putInt(EXTRA_MODE, MODE_EDIT);
		b.putInt(EXTRA_EDIT_INDEX, editIndex);
		f.setArguments(b);
		return f;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_nokia_widget_url_edit, container, false);
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
			mode = args.getInt(EXTRA_MODE, MODE_ADD);
			editIndex = args.getInt(EXTRA_EDIT_INDEX, -1);
		}
		NokiaLog.i(TAG, "初始化 mode=" + (mode == MODE_EDIT ? "EDIT" : "ADD")
				+ " editIndex=" + editIndex);

		storage = new NokiaWidgetStorage(requireContext());
		tvTitle = view.findViewById(R.id.tvUrlTitle);
		etName = view.findViewById(R.id.etName);
		etUrl = view.findViewById(R.id.etUrl);
		updateTitle();

		prefillIfEditMode();

		etName.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_NEXT) {
				NokiaLog.d(TAG, "名称 IME next → 激活网址编辑态");
				exitEditing();
				activateEditing(FOCUS_URL);
				return true;
			}
			return false;
		});
		etUrl.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				NokiaLog.d(TAG, "网址 IME done → 关闭软键盘");
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
			NokiaLog.i(TAG, "初始化完成 focusIndex=" + focusIndex);
		});
	}

	private void updateTitle() {
		if (tvTitle != null) {
			tvTitle.setText(mode == MODE_EDIT ? "编辑网址" : "添加网址");
		}
	}

	private void prefillIfEditMode() {
		if (mode != MODE_EDIT) return;
		List<NokiaWidgetItem> widgets = storage.getWidgets();
		if (editIndex >= 0 && editIndex < widgets.size()) {
			NokiaWidgetItem item = widgets.get(editIndex);
			etName.setText(item.label == null ? "" : item.label);
			etUrl.setText(item.value == null ? "" : item.value);
			NokiaLog.i(TAG, "EDIT 预填 label=" + item.label + " value=" + item.value);
		} else {
			NokiaLog.w(TAG, "editIndex 越界，降级为添加模式");
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
		NokiaLog.d(TAG, "退出编辑态");
	}

	// ---- 焦点 ----

	private void setFocusIndex(int index) {
		if (index < FOCUS_NAME || index > FOCUS_URL) return;
		NokiaLog.d(TAG, "setFocusIndex " + focusIndex + " -> " + index);
		focusIndex = index;
		applyFocus();
	}

	private void applyFocus() {
		if (etName == null || etUrl == null) return;
		etName.setBackgroundResource(
				(focusIndex == FOCUS_NAME && !editing) || editingField == FOCUS_NAME
						? R.drawable.bg_nokia_selected_dark : R.drawable.bg_nokia_searchbox);
		etUrl.setBackgroundResource(
				(focusIndex == FOCUS_URL && !editing) || editingField == FOCUS_URL
						? R.drawable.bg_nokia_selected_dark : R.drawable.bg_nokia_searchbox);
	}

	// ---- 导航 ----

	@Override
	public boolean onDirection(int direction) {
		if (editing) {
			// 编辑态：上下键切换输入框，左右键交给 EditText 移动光标
			if (direction == NokiaKeyBinding.ACTION_UP && editingField == FOCUS_URL) {
				switchEditingField(FOCUS_NAME);
				return true;
			}
			if (direction == NokiaKeyBinding.ACTION_DOWN && editingField == FOCUS_NAME) {
				switchEditingField(FOCUS_URL);
				return true;
			}
			return false;
		}
		// 焦点态
		switch (direction) {
			case NokiaKeyBinding.ACTION_UP:
				if (focusIndex > FOCUS_NAME) setFocusIndex(focusIndex - 1);
				return true;
			case NokiaKeyBinding.ACTION_DOWN:
				if (focusIndex < FOCUS_URL) setFocusIndex(focusIndex + 1);
				return true;
			case NokiaKeyBinding.ACTION_LEFT:
			case NokiaKeyBinding.ACTION_RIGHT:
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
		((NokiaDesktopActivity) requireActivity()).exitCurrent();
		return true;
	}

	@Override
	public boolean onBack() {
		// 编辑态下返回键：不做特殊处理，让系统正常处理（可能是删除文字）
		// 只在焦点态下退出页面
		if (!editing) {
			((NokiaDesktopActivity) requireActivity()).exitCurrent();
		}
		// 编辑态返回 false，让系统/输入法处理
		return !editing;
	}

	// ---- 保存 ----

	private void validateAndSave() {
		String name = etName.getText() == null ? "" : etName.getText().toString().trim();
		String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
		NokiaLog.i(TAG, "保存 name=\"" + name + "\" url=\"" + url + "\"");

		if (name.isEmpty()) {
			showToast("请输入显示名称");
			return;
		}
		if (url.isEmpty()) {
			showToast("请输入网址");
			return;
		}

		NokiaWidgetItem item = new NokiaWidgetItem(NokiaWidgetItem.TYPE_URL, name, url);
		NokiaDesktopActivity host = (NokiaDesktopActivity) requireActivity();
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
				NokiaLog.i(TAG, "ADD 保存后出栈 2 层");
			} else {
				fm.popBackStackImmediate();
				NokiaLog.i(TAG, "ADD 保存后出栈 1 层");
			}
		}
	}

	// ---- NokiaPage ----

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
		NokiaLog.i(TAG, "Toast: " + msg);
	}
}
