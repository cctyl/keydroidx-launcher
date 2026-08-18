/*
 *  Copyright 2022 Yury Kharchenko
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

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.nokia.NokiaKeyBinding;
import ru.playsoftware.j2meloader.nokia.NokiaOptionsDialog;

public abstract class AbstractSoftKeysBar {
	protected final Displayable target;
	protected final List<Command> commands = new ArrayList<>();
	protected boolean middleSoft;
	protected Command middle;
	protected Command right;
	protected int menuStartIndex;

	protected AbstractSoftKeysBar(Displayable target, boolean middleSoft) {
		this.target = target;
		this.middleSoft = middleSoft;
	}

	public void notifyChanged() {
		ViewHandler.postEvent(this::onCommandsChanged);
	}

	/** 呼出诺基亚统一标准选项弹窗（NokiaOptionsDialog）。 */
	public void showOptionsMenu() {
		Context context = ContextHolder.getActivity();
		if (!(context instanceof FragmentActivity)) return;
		FragmentActivity activity = (FragmentActivity) context;
		FragmentManager fm = activity.getSupportFragmentManager();

		// 呼出前确保隐藏软键盘
		View currentFocus = activity.getCurrentFocus();
		if (currentFocus != null) {
			InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
			}
		}

		List<NokiaOptionsDialog.OptionItem> items = new ArrayList<>();
		for (Command cmd : commands) {
			items.add(new NokiaOptionsDialog.OptionItem(
					0,
					cmd.getAndroidLabel(),
					true,
					false,
					() -> target.fireCommandAction(cmd)
			));
		}

		// 保底措施：如果是 TextBox 且有输入内容，在左菜单加入「清空输入」选项
		if (target instanceof TextBox && ((TextBox) target).size() > 0) {
			items.add(new NokiaOptionsDialog.OptionItem(
					0,
					"清空输入",
					true,
					false,
					() -> ((TextBox) target).setString("")
			));
		}

		if (items.isEmpty()) return;

		int[] keyCodes = NokiaKeyBinding.loadKeyCodes(context);
		String title = context.getString(R.string.cmd_menu);
		NokiaOptionsDialog.show(fm, title, items, keyCodes);
	}

	protected void onCommandsChanged() {
		commands.clear();
		Command[] arr = target.getCommands();
		Arrays.sort(arr);
		commands.addAll(Arrays.asList(arr));
		middle = null;
		right = null;

		// 1. 优先提取右软键（返回/退出/取消/停止）
		for (Command cmd : arr) {
			int type = cmd.getCommandType();
			if (type == Command.BACK || type == Command.EXIT || type == Command.CANCEL || type == Command.STOP) {
				if (right == null) {
					right = cmd;
					commands.remove(cmd);
					break;
				}
			}
		}

		// 2. 提取中间确认键（OK / 主操作直达，例如「发送」）
		if (middleSoft) {
			// 先找显式 Command.OK
			for (Command cmd : commands) {
				if (cmd.getCommandType() == Command.OK) {
					middle = cmd;
					break;
				}
			}
			// 若无 Command.OK，则将第一个主要命令作为 middle（主操作直达！）
			if (middle == null && !commands.isEmpty()) {
				middle = commands.get(0);
			}
			if (middle != null) {
				commands.remove(middle);
			}
		}

		int i = 0;
		if (middle != null) {
			// 将 middle 加回列表头部供菜单查看
			commands.add(0, middle);
			i++;
		}
		if (right != null) {
			commands.add(0, right);
			i++;
		}
		menuStartIndex = i;
	}

	public boolean isMenuShowing() {
		Context context = ContextHolder.getActivity();
		if (context instanceof FragmentActivity) {
			FragmentManager fm = ((FragmentActivity) context).getSupportFragmentManager();
			return fm.findFragmentByTag("NokiaOptions") != null;
		}
		return false;
	}

	public void closeMenu() {
		Context context = ContextHolder.getActivity();
		if (context instanceof FragmentActivity) {
			FragmentManager fm = ((FragmentActivity) context).getSupportFragmentManager();
			DialogFragment df = (DialogFragment) fm.findFragmentByTag("NokiaOptions");
			if (df != null) {
				df.dismiss();
			}
		}
	}
}
