package ru.playsoftware.j2meloader.nokia;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import java.util.Locale;

import ru.playsoftware.j2meloader.R;

/**
 * 桌面设置 → 外观与显示 → 自定义字体缩放输入页。
 * 允许高分辨率用户直接输入任意比例值（0.5 ~ 3.0）。
 */
public class KeydroidxCustomFontScaleFragment extends KeydroidxPageFragment {
	private static final String TAG = "CustomFontScale";

	private EditText etCustomScale;
	private TextView tvPreviewText;
	/** 已调度延迟退栈：防止 250ms 窗口内的重复按键引发双重 popBackStack。 */
	private boolean mExitScheduled;

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_keydroidx_custom_font_scale;
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
		exitPage();
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

		float currentScale = KeydroidxSettingsStorage.getFontScale(requireContext());
		etCustomScale.setText(String.format(Locale.US, "%.2f", currentScale));
		etCustomScale.setSelection(etCustomScale.getText().length());

		// 预览字号必须等基类 onViewCreated 的 applyFontToViewHierarchy 跑完之后再设：
		// 本钩子先于该遍历执行，若此处直接设原始字号，会被遍历当作「设计字号」记入 tag
		// 再乘一次 sFontScale，预览呈现 12×scale×sFontScale 的双重缩放。
		// post 到遍历之后覆盖为真实值 12×scale；此后编辑输入框时的实时预览不受影响。
		tvPreviewText.post(() -> {
			if (isAdded() && tvPreviewText != null) {
				updatePreview(currentScale);
			}
		});

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
				} catch (Exception ignored) {
					KeydroidxLog.w(TAG, "updatePreview failed: " + ignored.getMessage());
				}
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
			tvPreviewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12 * scale);
		}
	}

	private boolean saveAndExit() {
		if (mExitScheduled) return true;
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

			KeydroidxSettingsStorage.setFontScale(requireContext(), scale);

			Toast.makeText(requireContext(), "字体大小已设置为 " + String.format(Locale.US, "%.2f", scale) + "x", Toast.LENGTH_SHORT).show();
			// 就地重应用代替 recreate()：不再重建 Resources/重解码位图，
			// 消除低内存设备上 inflate 阶段 OOM 的放大器（2026-10-17 复现结论）。
			KeydroidxDesktopActivity host = (KeydroidxDesktopActivity) requireActivity();
			host.applyFontChangeInPlace();
			// 保存成功后自动退出本页：返回上一层「外观与显示」，
			// 其视图在出栈重建时自动按新倍率渲染，行文案也随 getItemDisplayName 刷新。
			exitPage();
			return true;
		} catch (Exception e) {
			KeydroidxLog.w(TAG, "makeText failed: " + e.getMessage());
			Toast.makeText(requireContext(), "格式不正确，请输入数字（如 1.5）", Toast.LENGTH_SHORT).show();
			return false;
		}
	}

	/**
	 * 退出本页：先给输入法收尾，再延迟退栈。
	 * <p>
	 * 现场结论（2026-10-17，5990a29a + 国笔 GBIme）：输入框有内容时直接
	 * {@code hideSoftInputFromWindow + 立即 popBackStack}，GBIme 的 IME 窗口不会随
	 * IMS 的 hide 真正销毁（mHasSurface=true、mViewVisibility=VISIBLE，残留在
	 * [0,240][240,320]），此后所有物理返回键被它整体吞掉（logcat 可见 GBIme
	 * "hard key:up::4"），直到其内部状态机自行消化（用户实测连按 5 次才退出）。
	 * 对照路径：先逐字删空输入框再退出（GBIme 自行结束编辑会话并拆窗）从不吞键。
	 * 因此这里模拟同一条干净路径：清空内容 → 清焦点 → 隐藏 IME → 延迟退栈，
	 * 给 GBIme 留出拆窗时间后再销毁本页。
	 */
	private void exitPage() {
		if (mExitScheduled) return;
		mExitScheduled = true;
		if (etCustomScale != null && isAdded()) {
			etCustomScale.setText("");
			etCustomScale.clearFocus();
			InputMethodManager imm = (InputMethodManager) requireContext()
					.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null && etCustomScale.getWindowToken() != null) {
				imm.hideSoftInputFromWindow(etCustomScale.getWindowToken(), 0);
			}
		}
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			if (isAdded()) {
				requireActivity().getSupportFragmentManager().popBackStack();
			}
		}, 250);
	}
}
