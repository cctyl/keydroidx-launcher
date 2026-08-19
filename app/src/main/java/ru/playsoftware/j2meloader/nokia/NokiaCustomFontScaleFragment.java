package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面设置 → 外观与显示 → 自定义字体缩放输入页。
 * 允许高分辨率用户直接输入任意比例值（0.5 ~ 3.0）。
 */
public class NokiaCustomFontScaleFragment extends NokiaPageFragment {
	private EditText etCustomScale;
	private TextView tvPreviewText;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_custom_font_scale;
	}

	@Override
	public String getPageTitle() {
		return "自定义字号";
	}

	@Override
	public String getSoftLeftText() {
		return "保存";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	public boolean onDirection(int action) {
		return false;
	}

	@Override
	public boolean onSoftLeft() {
		return saveAndExit();
	}

	@Override
	public boolean onSoftRight() {
		hideIme();
		requireActivity().getSupportFragmentManager().popBackStack();
		return true;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}

	@Override
	public boolean onSelect() {
		return saveAndExit();
	}

	@Override
	protected void onPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		etCustomScale = view.findViewById(R.id.etCustomScale);
		tvPreviewText = view.findViewById(R.id.tvPreviewText);

		float currentScale = NokiaSettingsStorage.getFontScale(requireContext());
		etCustomScale.setText(String.format(Locale.US, "%.2f", currentScale));
		etCustomScale.setSelection(etCustomScale.getText().length());

		updatePreview(currentScale);

		etCustomScale.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				try {
					String str = s.toString().trim();
					if (!TextUtils.isEmpty(str)) {
						float val = Float.parseFloat(str);
						if (val >= 0.3f && val <= 5.0f) {
							updatePreview(val);
						}
					}
				} catch (Exception ignored) {}
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		// 默认请求焦点并唤起软键盘
		etCustomScale.post(() -> {
			if (etCustomScale != null && isAdded()) {
				etCustomScale.requestFocus();
				InputMethodManager imm = (InputMethodManager) requireContext()
						.getSystemService(Context.INPUT_METHOD_SERVICE);
				if (imm != null) {
					imm.showSoftInput(etCustomScale, InputMethodManager.SHOW_IMPLICIT);
				}
			}
		});
	}

	private void updatePreview(float scale) {
		if (tvPreviewText != null) {
			tvPreviewText.setTextSize(13 * scale);
		}
	}

	private boolean saveAndExit() {
		if (etCustomScale == null) return false;
		String input = etCustomScale.getText().toString().trim();
		if (TextUtils.isEmpty(input)) {
			Toast.makeText(requireContext(), "请输入字体倍率", Toast.LENGTH_SHORT).show();
			return false;
		}

		try {
			float scale = Float.parseFloat(input);
			if (scale < 0.5f || scale > 3.5f) {
				Toast.makeText(requireContext(), "倍率请在 0.5 ~ 3.5 之间", Toast.LENGTH_SHORT).show();
				return false;
			}

			NokiaSettingsStorage.setFontScale(requireContext(), scale);
			hideIme();

			Toast.makeText(requireContext(), "字体大小已设置为 " + String.format(Locale.US, "%.2f", scale) + "x", Toast.LENGTH_SHORT).show();
			((NokiaDesktopActivity) requireActivity()).recreate();
			return true;
		} catch (Exception e) {
			Toast.makeText(requireContext(), "格式不正确，请输入数字（如 1.5）", Toast.LENGTH_SHORT).show();
			return false;
		}
	}

	private void hideIme() {
		if (etCustomScale != null) {
			InputMethodManager imm = (InputMethodManager) requireContext()
					.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				imm.hideSoftInputFromWindow(etCustomScale.getWindowToken(), 0);
			}
			etCustomScale.clearFocus();
		}
	}
}
