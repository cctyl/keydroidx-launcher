package ru.woesss.j2me.installer;

import android.app.Dialog;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.appsdb.AppRepository;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity;
import io.github.cctyl.nokia.common.ui.focus.NokiaDialogFocus;
import ru.playsoftware.j2meloader.nokia.NokiaKeyBinding;
import ru.playsoftware.j2meloader.nokia.NokiaTheme;
import io.github.cctyl.nokia.common.log.NokiaLog;

/**
 * 诺基亚风格 JAR 安装弹窗。
 *
 * 设计原则：
 * 1. 只处理最常见的"全新安装"主路径（STATUS_NEW → STATUS_SUCCESS）
 * 2. 复杂分支（版本冲突、不匹配、需选文件）回退到原有 InstallerDialog
 * 3. UI 完全复用诺基亚风格：蓝渐变标题栏、深色内容区、软键栏、方向键导航
 * 4. 不改动 AppInstaller 和 InstallerDialog 的任何逻辑
 *
 * 注意：本类必须置于 ru.woesss.j2me.installer 包内，因为 AppInstaller 的构造器、
 * loadInfo/install/deleteTemp/clearCache 均为包私有，只有同包才能复用，从而做到
 * 零改动 AppInstaller 的前提下套上新的 UI 壳。
 */
public class NokiaInstallerDialog extends DialogFragment {
	private static final String TAG = "NokiaInstaller";
	private static final String ARG_URI = "uri";

	// UI 状态
	private static final int UI_STATE_LOADING = 0;   // 加载信息中
	private static final int UI_STATE_INSTALLING = 1; // 安装中
	private static final int UI_STATE_SUCCESS = 2;    // 安装成功
	private static final int UI_STATE_ERROR = 3;      // 安装失败

	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	private AppRepository appRepository;
	private AppInstaller installer;
	private Uri uri;
	private int uiState = UI_STATE_LOADING;

	// 视图引用
	private TextView tvTitle;
	private ProgressBar progressBar;
	private TextView tvStatus;
	private ImageView ivIcon;
	private TextView tvAppName;
	private TextView tvResult;
	private TextView softLeft;
	private TextView softRight;
	private View contentLoading;
	private View contentResult;

	// 结果状态
	private AppItem installedApp;
	private String errorMessage;

	public static NokiaInstallerDialog newInstance(Uri uri) {
		NokiaInstallerDialog dialog = new NokiaInstallerDialog();
		Bundle args = new Bundle();
		args.putParcelable(ARG_URI, uri);
		dialog.setArguments(args);
		dialog.setCancelable(false);
		return dialog;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// 重建守卫：旋转等导致 Fragment 重建时直接放弃，避免二次触发 loadInfo/install
		if (savedInstanceState != null) {
			NokiaLog.i(TAG, "重建（savedInstanceState!=null），放弃安装避免重复");
			dismissAllowingStateLoss();
			return;
		}
		uri = requireArguments().getParcelable(ARG_URI);

		AppListModel appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Dialog dialog = new Dialog(requireActivity());
		dialog.setContentView(R.layout.dialog_nokia_installer);
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);

		if (dialog.getWindow() != null) {
			dialog.getWindow().setLayout(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			dialog.getWindow().setGravity(Gravity.BOTTOM);
			dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
		}

		initViews(dialog);
		setupKeyListener(dialog);

		NokiaTheme.ThemeDef theme = NokiaTheme.getSelectedTheme(requireContext());
		View titleBar = dialog.findViewById(R.id.install_title_bar);
		if (titleBar != null) titleBar.setBackground(NokiaTheme.createSoftKeyDrawable(theme));
		View body = dialog.findViewById(R.id.install_body);
		if (body != null) body.setBackground(NokiaTheme.createDialogBodyDrawable(theme));
		View bottomBar = dialog.findViewById(R.id.install_bottom_bar);
		if (bottomBar != null) bottomBar.setBackground(NokiaTheme.createSoftKeyDrawable(theme));

		// Android 12+：Dialog 窗口首个导航键会被触摸模式吞掉，show 后强制退出该状态
		dialog.setOnShowListener(d -> NokiaDialogFocus.forceNonTouchMode(dialog));

		return dialog;
	}

	@Override
	public void onStart() {
		super.onStart();
		if (installer == null) {
			startLoadInfo();
		}
	}

	@Override
	public void onDestroy() {
		compositeDisposable.dispose();
		super.onDestroy();
	}

	// ============================
	// 视图初始化
	// ============================

	private void initViews(Dialog dialog) {
		tvTitle = dialog.findViewById(R.id.install_title);
		progressBar = dialog.findViewById(R.id.install_progress);
		tvStatus = dialog.findViewById(R.id.install_status);
		ivIcon = dialog.findViewById(R.id.install_app_icon);
		tvAppName = dialog.findViewById(R.id.install_app_name);
		tvResult = dialog.findViewById(R.id.install_result_text);
		softLeft = dialog.findViewById(R.id.softLeft);
		softRight = dialog.findViewById(R.id.softRight);
		contentLoading = dialog.findViewById(R.id.content_loading);
		contentResult = dialog.findViewById(R.id.content_result);

		// 触摸支持
		if (softLeft != null) {
			softLeft.setOnClickListener(v -> onSoftKey(0));
		}
		if (softRight != null) {
			softRight.setOnClickListener(v -> onSoftKey(1));
		}
	}

	// ============================
	// 按键监听
	// ============================

	private void setupKeyListener(Dialog dialog) {
		// 接入用户自定义按键映射，与桌面行为 100% 一致（禁止写死 keyCode）
		final NokiaKeyBinding keyBinding =
				((NokiaDesktopActivity) requireActivity()).getKeyBinding();
		dialog.setOnKeyListener((d, keyCode, event) -> {
			if (event.getAction() != KeyEvent.ACTION_DOWN) {
				return true; // 消费抬起事件，避免重复触发
			}
			// 返回键由弹窗自己处理（NokiaKeyBinding 不管 BACK）
			if (keyCode == KeyEvent.KEYCODE_BACK) {
				onBackKey();
				return true;
			}
			int action = keyBinding.resolveAction(event);
			switch (action) {
				case NokiaKeyBinding.ACTION_SOFT_LEFT:
					onSoftKey(0);
					return true;
				case NokiaKeyBinding.ACTION_SOFT_RIGHT:
					onSoftKey(1);
					return true;
				case NokiaKeyBinding.ACTION_SELECT:
					// 内容区无可选中项，确认键只消费，绝不触发左右软键
					return true;
				case NokiaKeyBinding.ACTION_LEFT:
				case NokiaKeyBinding.ACTION_RIGHT:
					// 软键没有"焦点"概念，方向键左/右直接忽略（消费）
					return true;
				default:
					return false;
			}
		});
	}

	private void onSoftKey(int index) {
		switch (uiState) {
			case UI_STATE_LOADING:
			case UI_STATE_INSTALLING:
				if (index == 0) { // 取消
					cancelInstall();
				}
				break;
			case UI_STATE_SUCCESS:
				trigger(index);
				break;
			case UI_STATE_ERROR:
				if (index == 1) { // 确定
					dismiss();
				}
				break;
		}
	}

	private void onBackKey() {
		switch (uiState) {
			case UI_STATE_LOADING:
			case UI_STATE_INSTALLING:
				cancelInstall();
				break;
			case UI_STATE_SUCCESS:
				trigger(1); // 等效"完成"
				break;
			case UI_STATE_ERROR:
				dismiss();
				break;
		}
	}

	private void cancelInstall() {
		NokiaLog.i(TAG, "用户取消安装");
		compositeDisposable.dispose();
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
		dismiss();
	}

	// ============================
	// 触发动作（无焦点概念，直接按软键索引触发）
	// ============================

	private void trigger(int index) {
		if (index == 0 && installedApp != null) {
			// 打开
			NokiaLog.i(TAG, "打开应用: " + installedApp.getTitle());
			Config.startApp(requireContext(), installedApp.getTitle(),
					installedApp.getPathExt(), false);
		}
		// index == 1 或打开后都关闭弹窗
		dismiss();
	}

	// ============================
	// 安装流程
	// ============================

	private void startLoadInfo() {
		NokiaLog.i(TAG, "开始加载安装信息: " + uri);
		installer = new AppInstaller(null, uri, requireActivity().getApplication(), appRepository);

		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onLoadInfoResult, this::onError);
		compositeDisposable.add(disposable);
	}

	private void onLoadInfoResult(Integer status) {
		NokiaLog.i(TAG, "loadInfo 返回状态: " + status);

		if (status != null && status == AppInstaller.STATUS_NEW) {
			// 主路径：直接安装
			startInstall();
		} else {
			// 分支路径：回退到原有 InstallerDialog
			NokiaLog.i(TAG, "非主路径状态，回退到 InstallerDialog: " + status);
			fallbackToOriginalDialog();
		}
	}

	private void startInstall() {
		uiState = UI_STATE_INSTALLING;
		updateUi();

		Disposable disposable = Single.create(installer::install)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onInstallResult, this::onError);
		compositeDisposable.add(disposable);
	}

	private void onInstallResult(Integer status) {
		NokiaLog.i(TAG, "install 返回状态: " + status);

		if (status != null && status == AppInstaller.STATUS_SUCCESS) {
			installedApp = installer.getExistsApp();
			uiState = UI_STATE_SUCCESS;
		} else {
			// 理论上 install() 只返回 SUCCESS，其他情况走 onError
			errorMessage = "安装失败";
			uiState = UI_STATE_ERROR;
		}
		updateUi();
	}

	private void onError(Throwable e) {
		NokiaLog.e(TAG, "安装错误", e);
		errorMessage = e.getMessage();
		if (errorMessage == null || errorMessage.isEmpty()) {
			errorMessage = "未知错误";
		}
		uiState = UI_STATE_ERROR;

		// 清理
		if (installer != null) {
			installer.clearCache();
			installer.deleteTemp();
		}

		updateUi();
	}

	// ============================
	// 回退到原有弹窗
	// ============================

	private void fallbackToOriginalDialog() {
		NokiaLog.i(TAG, "回退到原 InstallerDialog");
		// 清理当前 installer
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
		compositeDisposable.dispose();

		// 关闭自身
		dismissAllowingStateLoss();

		// 启动原有 InstallerDialog（使用父 FragmentManager，避免与当前 childFragmentManager 冲突）
		InstallerDialog originalDialog = InstallerDialog.newInstance(uri);
		originalDialog.show(getParentFragmentManager(), "installer");
	}

	// ============================
	// UI 更新
	// ============================

	private void updateUi() {
		if (getDialog() == null || !isAdded()) return;

		switch (uiState) {
			case UI_STATE_LOADING:
				showLoadingUi();
				break;
			case UI_STATE_INSTALLING:
				showInstallingUi();
				break;
			case UI_STATE_SUCCESS:
				showSuccessUi();
				break;
			case UI_STATE_ERROR:
				showErrorUi();
				break;
		}
	}

	private void showLoadingUi() {
		if (tvTitle != null) tvTitle.setText("安装");
		if (contentLoading != null) contentLoading.setVisibility(View.VISIBLE);
		if (contentResult != null) contentResult.setVisibility(View.GONE);
		if (progressBar != null) progressBar.setIndeterminate(true);
		if (tvStatus != null) tvStatus.setText("正在加载...");
		if (softLeft != null) {
			softLeft.setText("取消");
			softLeft.setVisibility(View.VISIBLE);
		}
		if (softRight != null) softRight.setVisibility(View.INVISIBLE);
	}

	private void showInstallingUi() {
		if (tvTitle != null) tvTitle.setText("安装");
		if (progressBar != null) progressBar.setIndeterminate(true);
		if (tvStatus != null) tvStatus.setText("正在安装...");
		if (softLeft != null) {
			softLeft.setText("取消");
			softLeft.setVisibility(View.VISIBLE);
		}
		if (softRight != null) softRight.setVisibility(View.INVISIBLE);
	}

	private void showSuccessUi() {
		if (tvTitle != null) tvTitle.setText("安装完成");
		if (contentLoading != null) contentLoading.setVisibility(View.GONE);
		if (contentResult != null) contentResult.setVisibility(View.VISIBLE);

		if (installedApp != null) {
			if (tvAppName != null) tvAppName.setText(installedApp.getTitle());
			if (ivIcon != null) {
				String iconPath = installedApp.getImagePathExt();
				if (iconPath != null) {
					Drawable drawable = Drawable.createFromPath(iconPath);
					if (drawable != null) {
						ivIcon.setImageDrawable(drawable);
						ivIcon.setVisibility(View.VISIBLE);
					} else {
						ivIcon.setVisibility(View.GONE);
					}
				} else {
					ivIcon.setVisibility(View.GONE);
				}
			}
		}
		if (tvResult != null) tvResult.setText("安装成功");

		if (softLeft != null) {
			softLeft.setText("打开");
			softLeft.setVisibility(View.VISIBLE);
		}
		if (softRight != null) {
			softRight.setText("完成");
			softRight.setVisibility(View.VISIBLE);
		}
	}

	private void showErrorUi() {
		if (tvTitle != null) tvTitle.setText("安装失败");
		if (contentLoading != null) contentLoading.setVisibility(View.GONE);
		if (contentResult != null) contentResult.setVisibility(View.VISIBLE);

		if (tvAppName != null) tvAppName.setText("");
		if (ivIcon != null) ivIcon.setVisibility(View.GONE);
		if (tvResult != null) tvResult.setText("错误：" + errorMessage);

		if (softLeft != null) softLeft.setVisibility(View.INVISIBLE);
		if (softRight != null) {
			softRight.setText("确定");
			softRight.setVisibility(View.VISIBLE);
		}
	}
}
