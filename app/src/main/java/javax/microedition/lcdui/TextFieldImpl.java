/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2019 Nikita Shakarun
 * Copyright 2023 Arman Jussupgaliyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatEditText;

import ru.playsoftware.j2meloader.nokia.NokiaDimens;

import javax.microedition.lcdui.event.SimpleEvent;

class TextFieldImpl {
	private EditText textview;
	private LinearLayout screenContainer;
	private TextView counterTextView;
	private TextBox ownerTextBox;

	private String text;
	private int maxSize;
	private int constraints;

	private final SimpleEvent msgSetText = new SimpleEvent() {
		@Override
		public void process() {
			textview.setText(text);
		}
	};

	void setString(String text) {
		if (text != null && text.length() > maxSize) {
			throw new IllegalArgumentException("text length exceeds max size");
		}

		if (text != null) {
			this.text = text;
		} else {
			this.text = "";
		}

		if (textview != null) {
			ViewHandler.postEvent(msgSetText);
		}
	}

	void insert(String src, int pos) {
		String tmp = new StringBuilder(text).insert(pos, src).toString();
		setString(tmp);
	}

	String getString() {
		return text;
	}

	int size() {
		return text.length();
	}

	int setMaxSize(int maxSize) {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("max size must be > 0");
		}

		this.maxSize = maxSize;

		if (textview != null) {
			textview.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxSize)});
		}

		return maxSize;
	}

	int getMaxSize() {
		return maxSize;
	}

	void setConstraints(int constraints) {
		this.constraints = constraints;

		if (textview != null) {
			int inputType;

			switch (constraints & TextField.CONSTRAINT_MASK) {
				default:
				case TextField.ANY:
					inputType = InputType.TYPE_CLASS_TEXT;
					break;
				case TextField.EMAILADDR:
					inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
					break;
				case TextField.NUMERIC:
					inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
					break;
				case TextField.PHONENUMBER:
					inputType = InputType.TYPE_CLASS_PHONE;
					break;
				case TextField.URL:
					inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
					break;
				case TextField.DECIMAL:
					inputType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED |
							InputType.TYPE_NUMBER_FLAG_DECIMAL;
					break;
			}


			if ((constraints & TextField.NON_PREDICTIVE) != 0 ||
					(constraints & TextField.SENSITIVE) != 0 ||
					(constraints & TextField.PASSWORD) != 0) {
				inputType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
			}

			if ((constraints & TextField.INITIAL_CAPS_WORD) != 0) {
				inputType |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
			}

			if ((constraints & TextField.INITIAL_CAPS_SENTENCE) != 0) {
				inputType |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
			}

			textview.setInputType(inputType);
			textview.setSingleLine(true);
			textview.setMaxLines(50);
			textview.setHorizontallyScrolling(false);
			textview.setEnabled((constraints & TextField.UNEDITABLE) == 0);

			if ((constraints & TextField.PASSWORD) != 0) {
				textview.setTransformationMethod(new PasswordTransformationMethod());
			}
		}
	}

	int getConstraints() {
		return constraints;
	}

	int getChars(char[] data) {
		text.getChars(0, text.length(), data, 0);
		return text.length();
	}

	void setOwnerTextBox(TextBox ownerTextBox) {
		this.ownerTextBox = ownerTextBox;
	}

	int getCaretPosition() {
		if (textview != null) {
			return textview.getSelectionEnd();
		} else {
			return 0;
		}
	}

	void delete(int offset, int length) {
		if (text == null || offset < 0 || offset + length > text.length()) return;
		String tmp = new StringBuilder(text).delete(offset, offset + length).toString();
		this.text = tmp;
		if (textview != null) {
			textview.setText(tmp);
			int newPos = Math.max(0, Math.min(offset, tmp.length()));
			textview.setSelection(newPos);
		}
		if (ownerTextBox != null) {
			ownerTextBox.updateSoftBarText();
		}
	}

	EditText getView(Context context, Item item) {
		if (textview == null) {
			textview = new AppCompatEditText(context);

			setMaxSize(maxSize);
			setConstraints(constraints);
			setString(text);

			textview.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
				}

				@Override
				public void afterTextChanged(Editable s) {
					text = s.toString();
					updateCounter();
					if (item != null) item.notifyStateChanged();
					if (ownerTextBox != null) ownerTextBox.updateSoftBarText();
				}
			});

			Resources res = context.getResources();
			if (item != null) {
				// Form 内部 TextField：卡片样式
				GradientDrawable bg = new GradientDrawable();
				bg.setColor(Color.WHITE);
				bg.setStroke(NokiaDimens.dp(res, 1), 0xFFCCD2DB);
				bg.setCornerRadius(NokiaDimens.dpF(res, 3));
				textview.setBackground(bg);
				int pad = NokiaDimens.dp(res, 6);
				textview.setPadding(pad, pad, pad, pad);
				textview.setTextColor(0xFF1F2937);
				textview.setHintTextColor(0xFF8A95A5);
				NokiaDimens.textSize(textview, 13);
				textview.setOnFocusChangeListener((v, hasFocus) -> {
					if (!hasFocus) item.notifyStateChanged();
				});
			} else {
				// 独立 TextBox 样式在 getScreenContainer 中统一装配
				textview.setGravity(Gravity.TOP);
			}
		}
		return textview;
	}

	private void updateCounter() {
		if (counterTextView != null) {
			int current = (text != null) ? text.length() : 0;
			counterTextView.setText(current + "/" + maxSize);
		}
	}

	/** 构造诺基亚桌面同款深色壁纸卡片编辑界面（供 TextBox 使用）。 */
	View getScreenContainer(Context context) {
		if (screenContainer == null) {
			Resources res = context.getResources();
			screenContainer = new LinearLayout(context);
			screenContainer.setOrientation(LinearLayout.VERTICAL);
			screenContainer.setBackgroundColor(0x00000000); // 透明底色，直接透出桌面深海蓝壁纸

			// 1. 顶部标题与字数栏
			String title = (ownerTextBox != null) ? ownerTextBox.getTitle() : null;
			LinearLayout header = new LinearLayout(context);
			header.setOrientation(LinearLayout.HORIZONTAL);
			header.setGravity(Gravity.CENTER_VERTICAL);
			header.setBackgroundColor(0x800D1B3E); // 半透明深蓝黑顶栏
			int headerH = NokiaDimens.dp(res, 30);
			int headerPadH = NokiaDimens.dp(res, 10);
			header.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerH));
			header.setPadding(headerPadH, 0, headerPadH, 0);

			TextView titleTv = new TextView(context);
			titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
			titleTv.setSingleLine(true);
			titleTv.setEllipsize(TextUtils.TruncateAt.END);
			titleTv.setTextColor(0xFFFFFFFF); // 纯白标题字
			titleTv.setTypeface(Typeface.DEFAULT_BOLD);
			NokiaDimens.textSize(titleTv, 14);
			if (title != null && !title.trim().isEmpty()) {
				titleTv.setText(title);
			}
			header.addView(titleTv);

			counterTextView = new TextView(context);
			counterTextView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			counterTextView.setTextColor(0xFF93C5FD); // 浅天蓝字数统计
			counterTextView.setTypeface(Typeface.DEFAULT_BOLD);
			NokiaDimens.textSize(counterTextView, 13);
			updateCounter();
			header.addView(counterTextView);

			screenContainer.addView(header);

			// 顶部栏下方的微细半透明分隔线
			View divider = new View(context);
			divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
			divider.setBackgroundColor(0x30FFFFFF);
			screenContainer.addView(divider);

			// 2. 中间卡片输入区
			EditText et = getView(context, null);
			GradientDrawable cardBg = new GradientDrawable();
			cardBg.setColor(Color.WHITE);
			cardBg.setStroke(NokiaDimens.dp(res, 1), 0xFF60A5FA); // 细腻天蓝边框
			cardBg.setCornerRadius(NokiaDimens.dpF(res, 4));
			et.setBackground(cardBg);

			int pad = NokiaDimens.dp(res, 10);
			et.setPadding(pad, pad, pad, pad);
			et.setTextColor(0xFF1F2937);
			et.setHintTextColor(0xFF9CA3AF);
			NokiaDimens.textSize(et, 14);
			et.setLineSpacing(NokiaDimens.dpF(res, 3), 1.0f);

			LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
			int margin = NokiaDimens.dp(res, 8);
			etParams.setMargins(margin, margin, margin, margin);
			et.setLayoutParams(etParams);

			screenContainer.addView(et);
		}
		return screenContainer;
	}

	void requestTextFocus() {
		if (textview != null) {
			textview.requestFocus();
		}
	}

	void clearScreenView() {
		textview = null;
		screenContainer = null;
		counterTextView = null;
	}
}
