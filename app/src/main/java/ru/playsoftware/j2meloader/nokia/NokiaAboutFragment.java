package ru.playsoftware.j2meloader.nokia;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

import io.github.cctyl.nokia.common.ui.NokiaTheme;
import io.github.cctyl.nokia.common.update.NokiaUpdateConfig;
import io.github.cctyl.nokia.common.update.NokiaUpdateDialog;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.R;

public class NokiaAboutFragment extends NokiaScrollPageFragment {

	private static final String REPO_URL = "https://github.com/cctyl/keydroidx-launcher";

	private View btnCheckUpdate;
	private View btnGithub;
	private View btnBilibili;
	// 0: 检查更新, 1: GitHub, 2: Bilibili, 3: 底部纯文本浏览状态
	private int focusIndex = 0;

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
		if (focusIndex == 0) {
			return "检查";
		} else if (focusIndex == 1 || focusIndex == 2) {
			return "打开";
		}
		return "";
	}

	@Override
	public String getSoftRightText() {
		return "返回";
	}

	@Override
	protected void onScrollPageCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		btnCheckUpdate = view.findViewById(R.id.btnCheckUpdate);
		btnGithub = view.findViewById(R.id.btnGithub);
		btnBilibili = view.findViewById(R.id.btnBilibili);

		if (btnCheckUpdate != null) {
			btnCheckUpdate.setOnClickListener(v -> {
				focusIndex = 0;
				updateFocus();
				checkUpdate();
			});
		}

		if (btnGithub != null) {
			btnGithub.setOnClickListener(v -> {
				focusIndex = 1;
				updateFocus();
				openUrl(REPO_URL);
			});
		}

		if (btnBilibili != null) {
			btnBilibili.setOnClickListener(v -> {
				focusIndex = 2;
				updateFocus();
				openUrl("https://www.bilibili.com/video/BV1WxMX6yEHX");
			});
		}

		updateFocus();
	}

	private void updateFocus() {
		int normalBg = 0x1AFFFFFF;
		if (btnCheckUpdate != null) {
			if (focusIndex == 0) {
				btnCheckUpdate.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			} else {
				btnCheckUpdate.setBackgroundColor(normalBg);
			}
		}
		if (btnGithub != null) {
			if (focusIndex == 1) {
				btnGithub.setBackground(NokiaTheme.createSelectionDrawable(requireContext(), 4));
			} else {
				btnGithub.setBackgroundColor(normalBg);
			}
		}
		if (btnBilibili != null) {
			if (focusIndex == 2) {
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
			if (focusIndex < 2) {
				focusIndex++;
				updateFocus();
				return true;
			}
			// focusIndex >= 2：先切到纯文本浏览态，再向下滚动
			if (focusIndex == 2) {
				focusIndex = 3;
				updateFocus();
			}
			scrollDown();
			return true;
		} else if (action == NokiaKeyBinding.ACTION_UP) {
			if (focusIndex == 3) {
				if (pageScrollView != null && pageScrollView.getScrollY() > 10) {
					scrollUp();
				} else {
					focusIndex = 2;
					updateFocus();
				}
				return true;
			} else if (focusIndex == 2) {
				focusIndex = 1;
				updateFocus();
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
			checkUpdate();
			return true;
		} else if (focusIndex == 1) {
			openUrl(REPO_URL);
			return true;
		} else if (focusIndex == 2) {
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

	private void checkUpdate() {
		if (getActivity() == null) {
			return;
		}
		Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show();
		// 剥掉 flavor 渠道后缀（-open/-play/-dev-NNNNN）再比较：
		// 渠道后缀不是 pre-release 标记，但 semver 会把它当成修饰段误判为更小。
		NokiaUpdateConfig config = new NokiaUpdateConfig(REPO_URL)
				.setCurrentVersion(stripFlavorSuffix(BuildConfig.VERSION_NAME));
		NokiaUpdateDialog.checkAndShow(getActivity(), config);
	}

	/**
	 * 剥离 flavor 渠道后缀，只保留逻辑版本号。
	 *
	 * <p>AGP 构建时会把 productFlavors 里的 {@code versionNameSuffix}
	 * （如 "-open"、"-play"、"-dev-12345"）追加到 defaultConfig 的 versionName 上，
	 * 写进最终 manifest。运行时 {@code BuildConfig.VERSION_NAME} 读到的就是带后缀的串
	 * （如 "1.3.1-open"）。但渠道后缀并非 pre-release 标记，semver 比较时却会把它
	 * 当成修饰段判为更小，导致与 GitHub 裸 tag（如 "1.3.1"）误判为「有新版本」。
	 * 这里按 {@link BuildConfig#FLAVOR} 把尾部对应后缀剥掉。</p>
	 */
	private static String stripFlavorSuffix(String versionName) {
		if (versionName == null || versionName.isEmpty()) {
			return versionName;
		}
		String flavor = BuildConfig.FLAVOR;
		if (flavor == null || flavor.isEmpty()) {
			return versionName;
		}
		// 匹配尾部 "-<flavor>" 或 "-<flavor>-<数字>"（dev 的 versionNameSuffix = "-dev-" + digits）
		String pattern = "-" + Pattern.quote(flavor) + "(-\\d+)?$";
		return versionName.replaceFirst(pattern, "");
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
