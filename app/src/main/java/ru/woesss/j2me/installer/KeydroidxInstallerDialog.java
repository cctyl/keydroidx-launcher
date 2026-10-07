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

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;

import io.github.cctyl.nokia.common.log.KeydroidxLog;
import io.github.cctyl.nokia.common.ui.KeydroidxFontManager;
import io.github.cctyl.nokia.common.ui.KeydroidxTheme;
import io.github.cctyl.nokia.common.ui.focus.KeydroidxDialogFocus;
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
import ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity;
import ru.playsoftware.j2meloader.nokia.KeydroidxKeyBinding;
import ru.playsoftware.j2meloader.nokia.KeydroidxTouchWindowController;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

/**
 * 诺基亚风格 JAR 安装弹窗。
 *
 * 设计原则：
 * 1. 覆盖安装的全部状态：全新安装、重新安装、更新、降级、JAR/JAD 不匹配、需要手动选 JAR、网络安装；
 *    不再回退到上游的 {@link InstallerDialog}（那是 Android AlertDialog 风格，与桌面不统一）；
 * 2. UI 完全复用诺基亚风格：蓝渐变标题栏、深色内容区、软键栏，按键走用户自定义映射（禁止写死 keyCode）；
 * 3. 不改动 AppInstaller 的安装逻辑，本类只负责 UI 与状态编排。
 *
 * 注意：本类必须置于 ru.woesss.j2me.installer 包内，因为 AppInstaller 的构造器、
 * loadInfo/install/updateInfo/deleteTemp/clearCache 均为包私有，只有同包才能复用，从而做到
 * 零改动 AppInstaller 的前提下套上新的 UI 壳。
 */
public class KeydroidxInstallerDialog extends DialogFragment {
	private static final String TAG = "KeydroidxInstaller";
	private static final String ARG_URI = "uri";
	private static final String ARG_ID = "id";

	// UI 状态
	private static final int UI_STATE_LOADING = 0;   // 加载信息中
	private static final int UI_STATE_INSTALLING = 1; // 安装中
	private static final int UI_STATE_SUCCESS = 2;    // 安装成功
	private static final int UI_STATE_ERROR = 3;      // 安装失败
	private static final int UI_STATE_CONFIRM = 4;    // 等待用户确认（重装/更新/降级/不匹配...）

	// 确认类型
	private static final int CONFIRM_NONE = 0;
	private static final int CONFIRM_REINSTALL = 1;   // 已安装同版本
	private static final int CONFIRM_UPDATE = 2;      // 新版本覆盖旧版本
	private static final int CONFIRM_DOWNGRADE = 3;   // 旧版本覆盖新版本
	private static final int CONFIRM_UNMATCHED = 4;   // JAR 与 JAD 描述不一致
	private static final int CONFIRM_NEED_JAR = 5;    // 需要用户手动选择 JAR 文件
	private static final int CONFIRM_DOWNLOAD = 6;    // 需要从网络下载 JAR

	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	/** 需要用户手动选 JAR 时使用（与上游 InstallerDialog 同一套文件选择器）。 */
	private final ActivityResultLauncher<String> openFileLauncher = registerForActivityResult(
			FileUtils.getFilePicker(),
			this::onPickFileResult);

	private AppRepository appRepository;
	private AppInstaller installer;
	private Uri uri;
	private int installId = -1;
	private int uiState = UI_STATE_LOADING;
	private int confirmType = CONFIRM_NONE;

	private KeydroidxKeyBinding keyBinding;

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

	public static KeydroidxInstallerDialog newInstance(Uri uri) {
		KeydroidxInstallerDialog dialog = new KeydroidxInstallerDialog();
		Bundle args = new Bundle();
		args.putParcelable(ARG_URI, uri);
		dialog.setArguments(args);
		dialog.setCancelable(false);
		return dialog;
	}

	/** 重新安装已安装的应用（按数据库 id，等价上游 InstallerDialog.newInstance(id)）。 */
	public static KeydroidxInstallerDialog newInstance(int id) {
		KeydroidxInstallerDialog dialog = new KeydroidxInstallerDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_ID, id);
		dialog.setArguments(args);
		dialog.setCancelable(false);
		return dialog;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// 重建守卫：旋转等导致 Fragment 重建时直接放弃，避免二次触发 loadInfo/install
		if (savedInstanceState != null) {
			KeydroidxLog.i(TAG, "重建（savedInstanceState!=null），放弃安装避免重复");
			dismissAllowingStateLoss();
			return;
		}
		Bundle args = requireArguments();
		uri = args.getParcelable(ARG_URI);
		installId = args.getInt(ARG_ID, -1);

		AppListModel appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
		appRepository = appListModel.getAppRepository();
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Dialog dialog = new Dialog(requireActivity());
		dialog.setContentView(R.layout.dialog_keydroidx_installer);
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

		KeydroidxTheme.ThemeDef theme = KeydroidxTheme.getCurrentTheme(requireContext());
		View titleBar = dialog.findViewById(R.id.install_title_bar);
		if (titleBar != null) titleBar.setBackground(KeydroidxTheme.createSoftKeyDrawable(theme));
		View body = dialog.findViewById(R.id.install_body);
		if (body != null) body.setBackground(KeydroidxTheme.createDialogBodyDrawable(theme));
		View bottomBar = dialog.findViewById(R.id.install_bottom_bar);
		if (bottomBar != null) bottomBar.setBackground(KeydroidxTheme.createSoftKeyDrawable(theme));

		// Android 12+：Dialog 窗口首个导航键会被触摸模式吞掉，show 后强制退出该状态
		dialog.setOnShowListener(d -> {
			KeydroidxFontManager.applyToViewTree(dialog.getWindow().getDecorView());
			KeydroidxDialogFocus.forceNonTouchMode(dialog);
		});

		return dialog;
	}

	@Override
	public void onStart() {
		super.onStart();
		if (getDialog() != null) {
			KeydroidxTouchWindowController.applyDialogWindowBounds(getDialog());
		}
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
		// 接入用户自定义按键映射，与桌面行为 100% 一致（禁止写死 keyCode）。
		// 宿主为原键桌面时直接复用其实例（跟随设置热更新）；
		// 其它宿主（外部打开 JAR 的 J2meLoaderActivity、旧应用列表）读同一份 SharedPreferences 构造实例。
		Object host = getActivity();
		if (host instanceof KeydroidxDesktopActivity) {
			keyBinding = ((KeydroidxDesktopActivity) host).getKeyBinding();
		} else {
			keyBinding = new KeydroidxKeyBinding(requireContext());
		}

		dialog.setOnKeyListener((d, keyCode, event) -> {
			if (event.getAction() != KeyEvent.ACTION_DOWN) {
				return true; // 消费抬起事件，避免重复触发
			}
			// 返回键由弹窗自己处理（KeydroidxKeyBinding 不管 BACK）
			if (keyCode == KeyEvent.KEYCODE_BACK) {
				onBackKey();
				return true;
			}
			int action = keyBinding.resolveAction(event);
			switch (action) {
				case KeydroidxKeyBinding.ACTION_SOFT_LEFT:
					onSoftKey(0);
					return true;
				case KeydroidxKeyBinding.ACTION_SOFT_RIGHT:
					onSoftKey(1);
					return true;
				case KeydroidxKeyBinding.ACTION_SELECT:
					// 确认态：确认键等同左软键（主操作）；其余状态内容区无可选中项，只消费
					if (uiState == UI_STATE_CONFIRM) {
						onSoftKey(0);
					}
					return true;
				case KeydroidxKeyBinding.ACTION_LEFT:
				case KeydroidxKeyBinding.ACTION_RIGHT:
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
			case UI_STATE_CONFIRM:
				if (index == 0) {
					onConfirmLeft();
				} else {
					onConfirmRight();
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
			case UI_STATE_CONFIRM:
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
		KeydroidxLog.i(TAG, "用户取消安装（state=" + uiState + "）");
		compositeDisposable.dispose();
		cleanupInstaller();
		dismiss();
	}

	private void cleanupInstaller() {
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
	}

	// ============================
	// 触发动作（无焦点概念，直接按软键索引触发）
	// ============================

	private void trigger(int index) {
		if (index == 0 && installedApp != null) {
			// 打开
			KeydroidxLog.i(TAG, "打开应用: " + installedApp.getTitle());
			Config.startApp(requireContext(), installedApp.getTitle(),
					installedApp.getPathExt(), false);
		}
		// index == 1 或打开后都关闭弹窗
		dismiss();
	}

	// ============================
	// 确认态动作
	// ============================

	private void onConfirmLeft() {
		KeydroidxLog.i(TAG, "确认框-左软键: type=" + confirmType);
		switch (confirmType) {
			case CONFIRM_REINSTALL:
			case CONFIRM_UPDATE:
			case CONFIRM_DOWNGRADE:
			case CONFIRM_DOWNLOAD:
				startInstall();
				break;
			case CONFIRM_UNMATCHED:
				installFromJarFile();
				break;
			case CONFIRM_NEED_JAR:
				KeydroidxLog.i(TAG, "打开文件选择器选择 JAR");
				openFileLauncher.launch(null);
				break;
			default:
				break;
		}
	}

	private void onConfirmRight() {
		if (confirmType == CONFIRM_REINSTALL) {
			// 已装同版本：右软键直接启动，省去"取消后再进百宝箱"的一步
			openInstalledApp();
		} else {
			cancelInstall();
		}
	}

	private void openInstalledApp() {
		AppItem app = installer == null ? null : installer.getExistsApp();
		if (app == null) {
			cancelInstall();
			return;
		}
		KeydroidxLog.i(TAG, "启动已安装应用: " + app.getTitle());
		compositeDisposable.dispose();
		cleanupInstaller();
		Config.startApp(requireContext(), app.getTitle(), app.getPathExt(), false);
		dismiss();
	}

	/**
	 * JAR 与 JAD 描述不一致时，用户选择继续：忽略 JAD，改用 JAR 内的 MANIFEST 重新解析后走常规流程。
	 * <p>注意不能清 cacheDir —— 待安装的 JAR 可能就是从网络下载到 cacheDir 的那个文件。
	 */
	private void installFromJarFile() {
		String jarPath = installer == null ? null : installer.getJar();
		if (jarPath == null) {
			cancelInstall();
			return;
		}
		KeydroidxLog.i(TAG, "忽略 JAD，按 JAR 重新解析: " + jarPath);
		installer.deleteTemp();
		File jarFile = new File(jarPath);
		installer = new AppInstaller(null, Uri.fromFile(jarFile),
				requireActivity().getApplication(), appRepository);
		uiState = UI_STATE_LOADING;
		updateUi();
		runLoadInfo();
	}

	// ============================
	// 安装流程
	// ============================

	private void startLoadInfo() {
		KeydroidxLog.i(TAG, "开始加载安装信息: " + (installId != -1 ? ("id=" + installId) : uri));
		try {
			if (installId != -1) {
				installer = new AppInstaller(installId, requireActivity().getApplication(), appRepository);
			} else {
				installer = new AppInstaller(null, uri, requireActivity().getApplication(), appRepository);
			}
		} catch (Exception e) {
			onError(e);
			return;
		}
		uiState = UI_STATE_LOADING;
		updateUi();
		runLoadInfo();
	}

	private void runLoadInfo() {
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onLoadInfoResult, this::onError);
		compositeDisposable.add(disposable);
	}

	private void onLoadInfoResult(Integer status) {
		KeydroidxLog.i(TAG, "loadInfo 返回状态: " + status);

		if (status == null) {
			showError("安装失败");
			return;
		}
		switch (status) {
			case AppInstaller.STATUS_NEW:
				if (installer.getJar() != null) {
					// 本地 JAR 已就绪，直接安装
					startInstall();
				} else {
					// 只有 JAD：需要联网下载 JAR，先向用户说明
					showConfirm(CONFIRM_DOWNLOAD);
				}
				break;
			case AppInstaller.STATUS_EQUAL:
				showConfirm(CONFIRM_REINSTALL);
				break;
			case AppInstaller.STATUS_NEWEST:
				showConfirm(CONFIRM_UPDATE);
				break;
			case AppInstaller.STATUS_OLDEST:
				showConfirm(CONFIRM_DOWNGRADE);
				break;
			case AppInstaller.STATUS_UNMATCHED:
				showConfirm(CONFIRM_UNMATCHED);
				break;
			case AppInstaller.STATUS_NEED_JAD:
				showConfirm(CONFIRM_NEED_JAR);
				break;
			default:
				showError("无法识别的安装状态：" + status);
				break;
		}
	}

	private void onPickFileResult(Uri picked) {
		if (picked == null || installer == null) {
			return;
		}
		KeydroidxLog.i(TAG, "用户选择的 JAR: " + picked);
		uiState = UI_STATE_LOADING;
		updateUi();
		Disposable disposable = installer.updateInfo(picked)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onLoadInfoResult, this::onError);
		compositeDisposable.add(disposable);
	}

	private void showConfirm(int type) {
		confirmType = type;
		uiState = UI_STATE_CONFIRM;
		updateUi();
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
		KeydroidxLog.i(TAG, "install 返回状态: " + status);

		if (status != null && status == AppInstaller.STATUS_SUCCESS) {
			installedApp = installer.getExistsApp();
			uiState = UI_STATE_SUCCESS;
			updateUi();
		} else {
			showError("安装失败");
		}
	}

	private void onError(Throwable e) {
		KeydroidxLog.e(TAG, "安装错误", e);
		cleanupInstaller();
		showError(e.getMessage());
	}

	private void showError(String message) {
		errorMessage = (message == null || message.isEmpty()) ? "未知错误" : message;
		uiState = UI_STATE_ERROR;
		updateUi();
	}

	// ============================
	// UI 更新
	// ============================

	private void updateUi() {
		if (!isAdded() || getDialog() == null) return;

		switch (uiState) {
			case UI_STATE_LOADING:
				showLoadingUi();
				break;
			case UI_STATE_INSTALLING:
				showInstallingUi();
				break;
			case UI_STATE_CONFIRM:
				showConfirmUi();
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
		// 从确认态进入安装态时必须切回进度视图，否则界面停在"结果视图"上看不到进度条
		if (contentLoading != null) contentLoading.setVisibility(View.VISIBLE);
		if (contentResult != null) contentResult.setVisibility(View.GONE);
		if (progressBar != null) progressBar.setIndeterminate(true);
		if (tvStatus != null) tvStatus.setText("正在安装...");
		if (softLeft != null) {
			softLeft.setText("取消");
			softLeft.setVisibility(View.VISIBLE);
		}
		if (softRight != null) softRight.setVisibility(View.INVISIBLE);
	}

	/**
	 * 确认态：图标 + 应用名 + 提示文案，左软键=主操作，右软键=取消/启动。
	 * 文案与软键语义按 {@link #confirmType} 分派（重装 / 更新 / 降级 / 不匹配 / 选文件 / 网络安装）。
	 */
	private void showConfirmUi() {
		Descriptor nd = installer == null ? null : installer.getNewDescriptor();
		String appName = nd == null || nd.getName() == null ? "" : nd.getName();
		String currentVersion = currentVersionOrNull();
		String newVersion = nd == null ? null : nd.getVersion();

		String title;
		String message;
		String leftText;
		String rightText;
		switch (confirmType) {
			case CONFIRM_REINSTALL:
				title = "重新安装";
				message = "该应用已安装" + versionSuffix(currentVersion)
						+ "。\n是否重新安装？\n现有存档数据将保留。";
				leftText = "重新安装";
				rightText = "启动";
				break;
			case CONFIRM_UPDATE:
				title = "更新应用";
				message = "检测到新版本 " + safe(newVersion) + "（当前 " + safe(currentVersion) + "）。\n"
						+ "是否更新？\n现有存档数据将保留。";
				leftText = "更新";
				rightText = "取消";
				break;
			case CONFIRM_DOWNGRADE:
				title = "降级安装";
				message = "即将安装旧版本 " + safe(newVersion) + "（当前 " + safe(currentVersion) + "）。\n"
						+ "旧版本可能无法正常读取现有存档。";
				leftText = "仍要安装";
				rightText = "取消";
				break;
			case CONFIRM_UNMATCHED:
				title = "确认安装";
				message = "所选 JAR 与 JAD 描述的应用不一致。\n忽略 JAD，按 JAR 内容安装？";
				leftText = "继续安装";
				rightText = "取消";
				break;
			case CONFIRM_NEED_JAR:
				title = "选择 JAR 文件";
				message = "未找到与该 JAD 匹配的 JAR 文件，\n请手动选择。";
				leftText = "选择文件";
				rightText = "取消";
				break;
			case CONFIRM_DOWNLOAD:
			default:
				title = "网络安装";
				message = "该 JAD 未附带本地 JAR，\n需要从互联网下载应用文件。\n是否继续？";
				leftText = "安装";
				rightText = "取消";
				break;
		}

		if (tvTitle != null) tvTitle.setText(title);
		if (contentLoading != null) contentLoading.setVisibility(View.GONE);
		if (contentResult != null) contentResult.setVisibility(View.VISIBLE);

		if (tvAppName != null) tvAppName.setText(appName);
		KeydroidxTheme.ThemeDef theme = KeydroidxTheme.getCurrentTheme(requireContext());
		if (tvResult != null) {
			tvResult.setText(message);
			tvResult.setTextColor(theme.textColor);
		}
		showIcon(resolveIconPath());

		if (softLeft != null) {
			softLeft.setText(leftText);
			softLeft.setVisibility(View.VISIBLE);
		}
		if (softRight != null) {
			softRight.setText(rightText);
			softRight.setVisibility(View.VISIBLE);
		}
	}

	private void showSuccessUi() {
		if (tvTitle != null) tvTitle.setText("安装完成");
		if (contentLoading != null) contentLoading.setVisibility(View.GONE);
		if (contentResult != null) contentResult.setVisibility(View.VISIBLE);

		if (installedApp != null) {
			if (tvAppName != null) tvAppName.setText(installedApp.getTitle());
			showIcon(installedApp.getImagePathExt());
		}
		if (tvResult != null) {
			tvResult.setText("安装成功");
			tvResult.setTextColor(0xFF64B5F6);
		}

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
		showIcon(null);
		if (tvResult != null) {
			tvResult.setText("错误：" + errorMessage);
			tvResult.setTextColor(0xFF64B5F6);
		}

		if (softLeft != null) softLeft.setVisibility(View.INVISIBLE);
		if (softRight != null) {
			softRight.setText("确定");
			softRight.setVisibility(View.VISIBLE);
		}
	}

	// ============================
	// 小工具
	// ============================

	private void showIcon(String path) {
		Drawable drawable = null;
		if (path != null) {
			try {
				drawable = Drawable.createFromPath(path);
			} catch (Exception e) {
				KeydroidxLog.w(TAG, "读取应用图标失败: " + e.getMessage());
			}
		}
		if (ivIcon == null) return;
		if (drawable != null) {
			ivIcon.setImageDrawable(drawable);
			ivIcon.setVisibility(View.VISIBLE);
		} else {
			ivIcon.setVisibility(View.GONE);
		}
	}

	/**
	 * 图标路径：优先已安装应用的图标（重装/更新场景），否则用本次安装目标的图标路径。
	 * JAR/JAD 不匹配时 targetDir 尚未确定，取不到就只显示文字。
	 */
	private String resolveIconPath() {
		if (installer == null) return null;
		AppItem exists = installer.getExistsApp();
		if (exists != null && exists.getImagePathExt() != null) {
			return exists.getImagePathExt();
		}
		try {
			String path = installer.getIconPath();
			return path != null && new File(path).exists() ? path : null;
		} catch (Exception e) {
			// getIconPath 依赖 targetDir，JAR/JAD 不匹配阶段可能未确定
			return null;
		}
	}

	private String currentVersionOrNull() {
		if (installer == null) return null;
		try {
			return installer.getCurrentVersion();
		} catch (Exception e) {
			return null;
		}
	}

	private static String safe(String text) {
		return text == null ? "未知" : text;
	}

	private static String versionSuffix(String version) {
		return version == null ? "" : "（版本 " + version + "）";
	}
}
