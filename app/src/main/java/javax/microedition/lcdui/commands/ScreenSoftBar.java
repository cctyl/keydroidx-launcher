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

import android.view.View;
import android.widget.Button;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Screen;
import javax.microedition.lcdui.TextBox;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.databinding.SoftButtonBarBinding;

public class ScreenSoftBar extends AbstractSoftKeysBar {
	private static final Object TAG_CLEAR = new Object();

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

	private void onClick(View button) {
		Object tag = button.getTag();
		if (tag == TAG_CLEAR) {
			if (target instanceof TextBox) {
				((TextBox) target).deletePreviousChar();
			}
		} else if (tag == null) {
			showOptionsMenu();
		} else {
			target.fireCommandAction((Command) tag);
		}
	}

	@Override
	protected void onCommandsChanged() {
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
		binding.rootLayout.setVisibility(View.VISIBLE);

		if (isTextBoxWithText) {
			// 有字状态：右软键永远是「清除」；左软键呼出全量菜单
			binding.rightButton.setVisibility(View.VISIBLE);
			binding.rightButton.setText(R.string.cmd_clear);
			binding.rightButton.setTag(TAG_CLEAR);

			if (middle != null) {
				setCommand(binding.middleButton, middle);
			}

			if (size > 0 || right != null) {
				binding.leftButton.setVisibility(View.VISIBLE);
				binding.leftButton.setText(R.string.cmd_menu);
				binding.leftButton.setTag(null);
			}
		} else {
			// 无字状态：右软键恢复为「返回」；中间键为主操作直达（如「发送」）
			if (size - menuStartIndex > 1) {
				binding.leftButton.setVisibility(View.VISIBLE);
				binding.leftButton.setText(R.string.cmd_menu);
				binding.leftButton.setTag(null);
			} else if (menuStartIndex < size) {
				setCommand(binding.leftButton, commands.get(menuStartIndex));
			}

			if (right != null) {
				setCommand(binding.rightButton, right);
				if (middle != null) {
					setCommand(binding.middleButton, middle);
				}
			} else {
				if (middle != null) {
					setCommand(binding.rightButton, middle);
				}
			}
		}
	}

	private static void setCommand(Button button, Command command) {
		button.setText(command.getAndroidLabel());
		button.setVisibility(View.VISIBLE);
		button.setTag(command);
	}
}
