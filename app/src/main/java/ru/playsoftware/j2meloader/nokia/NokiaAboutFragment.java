package ru.playsoftware.j2meloader.nokia;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ru.playsoftware.j2meloader.R;

public class NokiaAboutFragment extends NokiaScrollPageFragment {

	private View btnGithub;
	private View btnBilibili;
	private int focusIndex = 0; // 0: btnGithub, 1: btnBilibili, 2: 底部纯文本浏览状态

	@Override
	protected int getLayoutRes() {
		return R.layout.fragment_nokia_about;
	}

	@Override
	protected int getWallpaperRes() {
		return 0;
	}

	@Override
	public String getPageTitle() {
		return "关于";
	}

	@Override
	public String getSoftLeftText() {
		return focusIndex >= 0 && focusIndex <= 1 ? "打开" : "";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	protected void onScrollPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		btnGithub = view.findViewById(R.id.btnGithub);
		btnBilibili = view.findViewById(R.id.btnBilibili);

		if (btnGithub != null) {
			btnGithub.setOnClickListener(v -> {
				focusIndex = 0;
				updateFocus();
				openUrl("https://github.com/keydroidx/keydroidx-launcher");
			});
		}

		if (btnBilibili != null) {
			btnBilibili.setOnClickListener(v -> {
				focusIndex = 1;
				updateFocus();
				openUrl("https://www.bilibili.com/video/BV1WxMX6yEHX");
			});
		}

		updateFocus();
	}

	private void updateFocus() {
		int normalBg = 0x1AFFFFFF;
		if (btnGithub != null) {
			if (focusIndex == 0) {
				btnGithub.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			} else {
				btnGithub.setBackgroundColor(normalBg);
			}
		}
		if (btnBilibili != null) {
			if (focusIndex == 1) {
				btnBilibili.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			} else {
				btnBilibili.setBackgroundColor(normalBg);
			}
		}
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).refreshPageBar();
		}
	}

	@Override
	public boolean onDirection(int action) {
		if (action == NokiaKeyBinding.ACTION_DOWN) {
			if (focusIndex == 0) {
				focusIndex = 1;
				updateFocus();
				return true;
			} else {
				// focusIndex >= 1，向下滚动
				if (focusIndex == 1) {
					focusIndex = 2;
					updateFocus();
				}
				scrollDown();
				return true;
			}
		} else if (action == NokiaKeyBinding.ACTION_UP) {
			if (focusIndex == 2) {
				if (pageScrollView != null && pageScrollView.getScrollY() > 10) {
					scrollUp();
				} else {
					focusIndex = 1;
					updateFocus();
				}
				return true;
			} else if (focusIndex == 1) {
				focusIndex = 0;
				updateFocus();
				if (pageScrollView != null) {
					pageScrollView.smoothScrollTo(0, 0);
				}
				return true;
			} else if (focusIndex == 0) {
				if (pageScrollView != null) {
					pageScrollView.smoothScrollTo(0, 0);
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean onSelect() {
		if (focusIndex == 0) {
			openUrl("https://github.com/cctyl/keydroidx-launcher");
			return true;
		} else if (focusIndex == 1) {
			openUrl("https://www.bilibili.com/video/BV1WxMX6yEHX");
			return true;
		}
		return true;
	}

	@Override
	public boolean onSoftLeft() {
		return onSelect();
	}

	@Override
	public boolean onSoftRight() {
		if (getActivity() instanceof NokiaDesktopActivity) {
			((NokiaDesktopActivity) getActivity()).exitCurrent();
			return true;
		}
		return false;
	}

	@Override
	public boolean onBack() {
		return onSoftRight();
	}

	private void openUrl(String url) {
		try {
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			startActivity(intent);
		} catch (Exception e) {
			Toast.makeText(requireContext(), "无法打开链接: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}
}
