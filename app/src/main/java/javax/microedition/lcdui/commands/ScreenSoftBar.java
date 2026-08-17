/*
 *  Copyright 2019-2022 Yury Kharchenko
 *  Copyright 2023 Arman Jussupgaliyev
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package javax.microedition.lcdui.commands;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.PopupWindow;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Screen;
import javax.microedition.lcdui.TextBox;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.databinding.SoftButtonBarBinding;

public class ScreenSoftBar extends AbstractSoftKeysBar {
	private static final Object TAG_CLEAR = new Object();
	private static final Object TAG_SELECT_MENU_ITEM = new Object();
	private static final Object TAG_CLOSE_MENU = new Object();

	private final SoftButtonBarBinding binding;

	public ScreenSoftBar(Screen target, SoftButtonBarBinding binding) {
		super(target, true);
		this.binding = binding;
		this.binding.leftButton.setOnClickListener(this::onClick);
		this.binding.middleButton.setOnClickListener(this::onClick);
		this.binding.rightButton.setOnClickListener(this::onClick);

		// 规范：底部软键栏按钮不获得 D-Pad 光标焦点，由硬件软键直接触发
		this.binding.leftButton.setFocusable(false);
		this.binding.middleButton.setFocusable(false);
		this.binding.rightButton.setFocusable(false);
		this.binding.leftButton.setFocusableInTouchMode(false);
		this.binding.middleButton.setFocusableInTouchMode(false);
		this.binding.rightButton.setFocusableInTouchMode(false);

		notifyChanged();
	}

	public Button getLeftButton() {
		return binding.leftButton;
	}

	public Button getMiddleButton() {
		return binding.middleButton;
	}

	public Button getRightButton() {
		return binding.rightButton;
	}

	@Override
	protected void onMenuShown() {
		// 菜单弹出态：左「选择」，中「空」（INVISIBLE），右「返回」
		binding.leftButton.setText(R.string.cmd_select);
		binding.leftButton.setVisibility(View.VISIBLE);
		binding.leftButton.setTag(TAG_SELECT_MENU_ITEM);

		binding.middleButton.setVisibility(View.INVISIBLE);
		binding.middleButton.setTag(null);

		binding.rightButton.setText(R.string.cmd_back);
		binding.rightButton.setVisibility(View.VISIBLE);
		binding.rightButton.setTag(TAG_CLOSE_MENU);
	}

	@Override
	protected void onMenuDismissed() {
		notifyChanged();
	}

	private void onClick(View button) {
		Object tag = button.getTag();
		if (tag == TAG_CLEAR) {
			if (target instanceof TextBox) {
				((TextBox) target).deletePreviousChar();
			}
		} else if (tag == TAG_SELECT_MENU_ITEM) {
			performCurrentMenuSelection();
		} else if (tag == TAG_CLOSE_MENU) {
			closeMenu();
		} else if (tag == null) {
			PopupWindow popup = prepareMenu(menuStartIndex);
			int y = binding.rootLayout.getHeight();
			View rootView = binding.rootLayout.getRootView();
			popup.showAtLocation(rootView, Gravity.START | Gravity.BOTTOM, 0, y);
		} else {
			target.fireCommandAction((Command) tag);
		}
	}

	@Override
	protected void onCommandsChanged() {
		if (isMenuShowing()) {
			return;
		}

		binding.leftButton.setTag(null);
		binding.middleButton.setTag(null);
		binding.rightButton.setTag(null);
		binding.leftButton.setText("");
		binding.middleButton.setText("");
		binding.rightButton.setText("");
		binding.leftButton.setVisibility(View.INVISIBLE);
		binding.middleButton.setVisibility(View.INVISIBLE);
		binding.rightButton.setVisibility(View.INVISIBLE);

		super.onCommandsChanged();
		int size = commands.size();

		boolean isTextBoxWithText = (target instanceof TextBox && ((TextBox) target).size() > 0);

		if (size == 0 && !isTextBoxWithText) {
			binding.rootLayout.setVisibility(View.GONE);
			return;
		}

		if (isTextBoxWithText) {
			// 诺基亚 S40 规范：有字时右软键动态显示为「清除」，点击删字
			binding.rightButton.setVisibility(View.VISIBLE);
			binding.rightButton.setText(R.string.cmd_clear);
			binding.rightButton.setTag(TAG_CLEAR);

			// 中间确认键：直达主命令（例如「发送」）
			if (middle != null) {
				setCommand(binding.middleButton, middle);
			}

			// 左软键：显示「菜单」呼出选项列表
			if (size - menuStartIndex > 0 || right != null || middle != null) {
				binding.leftButton.setVisibility(View.VISIBLE);
				binding.leftButton.setText(R.string.cmd_menu);
				binding.leftButton.setTag(null);
			}
		} else {
			// 无字状态
			if (size - menuStartIndex > 1) {
				binding.leftButton.setVisibility(View.VISIBLE);
				binding.leftButton.setText(R.string.cmd_menu);
				binding.leftButton.setTag(null);
			} else if (menuStartIndex < size) {
				Command left = commands.get(menuStartIndex);
				setCommand(binding.leftButton, left);
			}

			if (middle != null) {
				setCommand(binding.middleButton, middle);
			}

			if (right != null) {
				setCommand(binding.rightButton, right);
			}
		}
		binding.rootLayout.setVisibility(View.VISIBLE);
	}

	private void setCommand(Button btn, Command c) {
		btn.setVisibility(View.VISIBLE);
		btn.setText(c.getAndroidLabel());
		btn.setTag(c);
	}
}
