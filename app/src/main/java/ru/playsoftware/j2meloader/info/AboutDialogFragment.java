/*
 * Copyright 2017 Nikita Shakarun
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

package ru.playsoftware.j2meloader.info;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.text.util.Linkify;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.nokia.KeydroidxKeyBinding;

public class AboutDialogFragment extends DialogFragment {
	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		StringBuilder message = new StringBuilder().append(getText(R.string.version))
				.append(BuildConfig.VERSION_NAME)
				.append(getText(R.string.about_email))
				.append(getText(R.string.about_github))
				.append(getText(R.string.about_4pda))
				.append(getText(R.string.about_xda))
				.append(getText(R.string.about_emugen_wiki))
				.append(getText(R.string.about_crowdin))
				.append(getText(R.string.about_copyright));
		TextView tv = new TextView(getActivity());
		tv.setText(Html.fromHtml(message.toString()));
		tv.setTextSize(16);
		tv.setMovementMethod(new ScrollingMovementMethod());
		Linkify.addLinks(tv, Linkify.ALL);
		float density = getResources().getDisplayMetrics().density;
		int paddingHorizontal = (int) (density * 20);
		int paddingVertical = (int) (density * 14);
		tv.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, 0);
		AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
		builder.setTitle(R.string.app_name)
				.setIcon(R.mipmap.ic_launcher)
				.setView(tv)
				.setPositiveButton(R.string.licenses, (dialog, which) -> {
					LicensesDialogFragment licensesDialogFragment = new LicensesDialogFragment();
					licensesDialogFragment.show(getParentFragmentManager(), "licenses");
				})
				.setNeutralButton(R.string.more, (dialog, which) -> {
					InfoDialogFragment infoDialogFragment = new InfoDialogFragment();
					infoDialogFragment.show(getParentFragmentManager(), "more");
				});
		return builder.create();
	}

	@Override
	public void onStart() {
		super.onStart();
		AlertDialog dialog = (AlertDialog) getDialog();
		if (dialog == null) return;
		// positive = "许可"(R.string.licenses，靠右)，neutral = "更多"(R.string.more，靠左)
		Button btnLicenses = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
		Button btnMore = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
		KeydroidxKeyBinding keyBinding = new KeydroidxKeyBinding(requireContext());
		dialog.setOnKeyListener((d, keyCode, event) -> keyBinding.dispatchDialogKey(
				event,
				() -> { if (btnMore != null) btnMore.performClick(); },       // 左软键 -> 更多
				() -> { if (btnLicenses != null) btnLicenses.performClick(); }, // 右软键 -> 许可
				this::dismiss,                                                  // 返回 -> 关闭
				true));
	}
}
