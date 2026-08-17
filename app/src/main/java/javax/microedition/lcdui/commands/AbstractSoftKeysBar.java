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
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.nokia.NokiaDimens;

public abstract class AbstractSoftKeysBar {
	protected final Displayable target;
	protected final List<Command> commands = new ArrayList<>();
	private PopupWindow popup;
	private NokiaMenuAdapter adapter;
	private ListView menuListView;
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

	/** 供外部或软键触发当前菜单选中项的执行。 */
	public void performCurrentMenuSelection() {
		if (popup != null && popup.isShowing() && adapter != null) {
			int pos = adapter.getSelectedPosition();
			if (pos < 0 || pos >= adapter.getCount()) {
				pos = 0;
			}
			if (pos < adapter.getCount()) {
				Command cmd = adapter.getItem(pos);
				target.fireCommandAction(cmd);
				closeMenu();
			}
		}
	}

	protected PopupWindow prepareMenu(int skip) {
		Context context = ContextHolder.getActivity();
		Resources res = context.getResources();

		if (popup == null) {
			popup = new PopupWindow(context);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				popup.setExitTransition(null);
				popup.setEnterTransition(null);
			}
			// 严格禁止菜单唤起输入法
			popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
			popup.setOutsideTouchable(true);
			popup.setFocusable(true);

			// 诺基亚 S40 风格菜单底板：纯白背景 + 1dp 诺基亚深蓝边框 + 2dp 轻微圆角
			GradientDrawable panelBg = new GradientDrawable();
			panelBg.setColor(Color.WHITE);
			panelBg.setStroke(NokiaDimens.dp(res, 1), 0xFF2A5288);
			panelBg.setCornerRadius(NokiaDimens.dpF(res, 2));
			popup.setBackgroundDrawable(panelBg);

			menuListView = new ListView(context);
			menuListView.setDivider(new ColorDrawable(0xFFE2E8F0));
			menuListView.setDividerHeight(NokiaDimens.dp(res, 1));
			menuListView.setSelector(createItemSelectorDrawable(res));
			menuListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
			menuListView.setPadding(0, NokiaDimens.dp(res, 2), 0, NokiaDimens.dp(res, 2));
			menuListView.setClipToPadding(false);

			adapter = new NokiaMenuAdapter(context);
			menuListView.setAdapter(adapter);
			menuListView.setOnItemClickListener(this::onMenuItemClick);

			// D-Pad 方向键循环滚动与物理按键支持
			menuListView.setOnKeyListener((v, keyCode, event) -> {
				if (event.getAction() == KeyEvent.ACTION_DOWN) {
					int count = adapter.getCount();
					int currentPos = adapter.getSelectedPosition();
					if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
						int nextPos = (currentPos <= 0) ? (count - 1) : (currentPos - 1);
						adapter.setSelectedPosition(nextPos);
						menuListView.setSelection(nextPos);
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
						int nextPos = (currentPos >= count - 1) ? 0 : (currentPos + 1);
						adapter.setSelectedPosition(nextPos);
						menuListView.setSelection(nextPos);
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_BACK) {
						closeMenu();
						return true;
					} else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MENU) {
						performCurrentMenuSelection();
						return true;
					}
				}
				return false;
			});

			menuListView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
				@Override
				public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
					adapter.setSelectedPosition(position);
				}

				@Override
				public void onNothingSelected(AdapterView<?> parent) {
				}
			});

			popup.setContentView(menuListView);
			popup.setOnDismissListener(() -> {
				adapter.clear();
				onMenuDismissed();
			});
		}

		// 隐藏虚拟软键盘，切断与输入法的活跃连接
		hideIme(context);

		adapter.clear();
		List<Command> sub = skip == 0 ? new ArrayList<>(commands) : new ArrayList<>(commands.subList(skip, commands.size()));
		// 在 TextBox 编辑界面中，菜单中保留返回/退出选项供随时退出
		if (target instanceof TextBox && right != null && !sub.contains(right)) {
			sub.add(right);
		}
		adapter.addAll(sub);

		// 计算菜单宽度：屏幕宽度的自适应或固定基准 180dp
		int screenW = res.getDisplayMetrics().widthPixels;
		int menuW = Math.min(screenW - NokiaDimens.dp(res, 32), NokiaDimens.dp(res, 200));
		if (menuW < NokiaDimens.dp(res, 140)) menuW = NokiaDimens.dp(res, 140);
		popup.setWidth(menuW);
		popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);

		// 默认高亮第一项
		adapter.setSelectedPosition(0);
		menuListView.post(() -> {
			menuListView.requestFocus();
			menuListView.setSelection(0);
		});

		onMenuShown();
		return popup;
	}

	/** 菜单展示时触发，供子类联动软键栏切换为「选择/空/返回」。 */
	protected void onMenuShown() {
	}

	/** 菜单关闭时触发，供子类恢复软键栏。 */
	protected void onMenuDismissed() {
		notifyChanged();
	}

	private void hideIme(Context context) {
		try {
			InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
			View currentFocus = ContextHolder.getActivity().getCurrentFocus();
			if (imm != null && currentFocus != null) {
				imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
			}
		} catch (Throwable ignored) {
		}
	}

	/** 诺基亚经典蓝底白字高亮条选择器 */
	private static StateListDrawable createItemSelectorDrawable(Resources res) {
		StateListDrawable sld = new StateListDrawable();
		// 获得焦点 / 选中 / 按下：经典诺基亚蓝（#0055AA）
		GradientDrawable focused = new GradientDrawable();
		focused.setColor(0xFF0055AA);
		focused.setCornerRadius(NokiaDimens.dpF(res, 2));

		sld.addState(new int[]{android.R.attr.state_focused}, focused);
		sld.addState(new int[]{android.R.attr.state_selected}, focused);
		sld.addState(new int[]{android.R.attr.state_pressed}, focused);
		sld.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
		return sld;
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
		return popup != null && popup.isShowing();
	}

	public void closeMenu() {
		if (popup != null && popup.isShowing()) {
			popup.dismiss();
		}
	}

	private void onMenuItemClick(AdapterView<?> parent, View view, int position, long id) {
		Command cmd = (Command) parent.getItemAtPosition(position);
		target.fireCommandAction(cmd);
		closeMenu();
	}

	/** 诺基亚经典单行紧凑菜单 Adapter */
	private static class NokiaMenuAdapter extends ArrayAdapter<Command> {
		private int selectedPosition = 0;
		private final GradientDrawable focusedBg;

		public NokiaMenuAdapter(Context context) {
			super(context, 0, new ArrayList<>());
			Resources res = context.getResources();
			focusedBg = new GradientDrawable();
			focusedBg.setColor(0xFF0055AA);
			focusedBg.setCornerRadius(NokiaDimens.dpF(res, 2));
		}

		public void setSelectedPosition(int position) {
			this.selectedPosition = position;
			notifyDataSetChanged();
		}

		public int getSelectedPosition() {
			return selectedPosition;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			TextView tv;
			if (convertView instanceof TextView) {
				tv = (TextView) convertView;
			} else {
				tv = new TextView(getContext());
				Resources res = getContext().getResources();
				int h = NokiaDimens.dp(res, 32);
				int padH = NokiaDimens.dp(res, 10);
				tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
				tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
				tv.setPadding(padH, 0, padH, 0);
				tv.setSingleLine(true);
				tv.setEllipsize(TextUtils.TruncateAt.END);
				NokiaDimens.textSize(tv, 13);
			}

			boolean isSelected = (position == selectedPosition);
			if (isSelected) {
				tv.setBackground(focusedBg);
				tv.setTextColor(0xFFFFFFFF); // 选中的条目：蓝底白字
				tv.setTypeface(Typeface.DEFAULT_BOLD);
			} else {
				tv.setBackgroundColor(0x00000000);
				tv.setTextColor(0xFF1F2937); // 未选中的条目：深黑灰字，极其清晰！
				tv.setTypeface(Typeface.DEFAULT);
			}

			Command cmd = getItem(position);
			if (cmd != null) {
				tv.setText(cmd.getAndroidLabel());
			}
			return tv;
		}
	}
}
