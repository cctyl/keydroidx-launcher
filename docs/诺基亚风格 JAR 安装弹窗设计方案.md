# 诺基亚风格 JAR 安装弹窗设计方案

> 版本：v1.0  
> 日期：2026-07-31  
> 状态：待审核

---

## 一、需求概述

### 1.1 背景

当前原键桌面的 JAR 安装入口（功能表 → 应用程序 → 安装）在调用文件选择器后，会直接弹出 J2ME-Loader 原有的 `InstallerDialog` 弹窗。该弹窗是标准的 Android `AlertDialog` 风格，与诺基亚 S40/S60 的视觉风格不一致。

### 1.2 目标

- **安装过程中**：显示符合诺基亚风格的安装进度弹窗
- **安装完成后**：显示符合诺基亚风格且可被方向键操作的结果弹窗
- **J2ME-Loader 原有界面不做改动**：仅套新的 UI 壳，不改动原有安装逻辑
- **分支兼容**：复杂安装场景（版本冲突、JAD/JAR 不匹配等）回退到原有 `InstallerDialog` 处理

### 1.3 非目标

- 不修改 `AppInstaller.java` 的任何逻辑
- 不修改 `InstallerDialog.java` 的任何逻辑
- 不修改 J2ME-Loader 原有的安装入口（如从 MainActivity 触发的安装）

---

## 二、现有代码分析

### 2.1 核心类职责

| 类 | 包路径 | 职责 |
|---|---|---|
| `AppInstaller` | `ru.woesss.j2me.installer` | 安装核心逻辑：加载信息、下载、转换 DEX、写入文件系统 |
| `InstallerDialog` | `ru.woesss.j2me.installer` | J2ME-Loader 原有安装弹窗：处理所有安装状态分支 |
| `NokiaBoxFragment` | `ru.playsoftware.j2meloader.nokia` | 原键桌面"应用程序"页面，包含"安装"入口 |

### 2.2 AppInstaller 状态码

```java
static final int STATUS_OLDEST   = -1;  // 版本更旧
static final int STATUS_EQUAL    =  0;  // 版本相同
static final int STATUS_NEWEST   =  1;  // 版本更新
static final int STATUS_NEW      =  2;  // 全新应用（主路径）
static final int STATUS_UNMATCHED = 3;  // JAD/JAR 不匹配
static final int STATUS_NEED_JAD  = 4;  // 需要选择 JAR 文件
static final int STATUS_SUCCESS   =  5;  // 安装成功
```

### 2.3 原有安装流程

```
NokiaBoxFragment.onPickFileResult(uri)
    └── InstallerDialog.newInstance(uri).show(...)
        └── onStart()
            └── installApp(path, uri)
                ├── Single.create(installer::loadInfo)
                │   └── onProgress(status)
                │       ├── STATUS_NEW → convert() → install()
                │       ├── STATUS_EQUAL/OLDEST/NEWEST → 显示确认信息
                │       ├── STATUS_UNMATCHED → 提示不匹配
                │       └── STATUS_NEED_JAD → 启动文件选择器
                └── Single.create(installer::install)
                    └── onProgress(STATUS_SUCCESS)
```

### 2.4 InstallerDialog 的 UI 结构

- **Dialog 类型**：`AlertDialog`（标准 Android 风格）
- **布局**：`dialog_installer.xml`（ProgressBar + TextView）
- **按钮**：动态控制 Positive/Negative/Neutral 按钮的显示/隐藏
- **交互**：触摸点击，不支持方向键导航

---

## 三、设计方案

### 3.1 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    NokiaBoxFragment                          │
│                      (安装入口)                               │
└─────────────────────────┬───────────────────────────────────┘
                          │ onPickFileResult(uri)
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              NokiaInstallerDialog (新建)                     │
│                 诺基亚风格安装弹窗                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 主路径：STATUS_NEW → 直接安装 → 显示进度 → 显示结果  │   │
│  │        （80% 常见场景，诺基亚风格 UI）                │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ 分支路径：其他状态 → dismiss() → InstallerDialog     │   │
│  │          （复杂场景，回退原有弹窗）                   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 状态分发策略

| 状态码 | 场景 | 处理方式 | 原因 |
|---|---|---|---|
| `STATUS_NEW` | 全新应用安装 | ✅ **主路径，走诺基亚弹窗** | 最常见，无需用户确认 |
| `STATUS_EQUAL` | 版本相同，重新安装 | ❌ 回退 `InstallerDialog` | 需用户确认是否覆盖 |
| `STATUS_OLDEST` | 版本更旧 | ❌ 回退 `InstallerDialog` | 需用户确认是否降级 |
| `STATUS_NEWEST` | 版本更新 | ❌ 回退 `InstallerDialog` | 需用户确认是否升级 |
| `STATUS_UNMATCHED` | JAD/JAR 不匹配 | ❌ 回退 `InstallerDialog` | 需用户选择文件 |
| `STATUS_NEED_JAD` | 需要选择 JAR | ❌ 回退 `InstallerDialog` | 需启动文件选择器 |
| `STATUS_SUCCESS` | 安装成功 | ✅ **主路径，显示结果弹窗** | 正常完成 |

### 3.3 核心设计原则

1. **套壳不改动**：`AppInstaller` 和 `InstallerDialog` 完全不动
2. **主路径优先**：覆盖最常见的"选 jar → 直接安装"场景
3. **分支无缝回退**：复杂场景自动回到原有弹窗，用户无感知
4. **UI 风格统一**：完全复用诺基亚已有的视觉元素（蓝渐变、软键栏、方向键导航）

---

## 四、详细路径与操作逻辑

### 4.1 主路径：全新应用一键安装（STATUS_NEW → STATUS_SUCCESS）

#### 4.1.1 流程图

```
用户操作                          系统响应
─────────                        ─────────
  │                                  │
  │  点击"安装"                      │
  ▼                                  │
┌─────────────┐                     │
│ 文件选择器   │                     │
│ 选择 .jar   │                     │
└──────┬──────┘                     │
       │                            │
       │ 返回 uri                   │
       ▼                            ▼
┌─────────────────────────────────────────┐
│     NokiaInstallerDialog 显示           │
│     标题：安装                           │
│     内容：进度条 + "正在加载..."          │
│     软键：左=取消  右=(空)               │
└─────────────────────────────────────────┘
       │                            │
       │ 调用 loadInfo()            │
       ▼                            │
  状态 = STATUS_NEW                 │
       │                            │
       ▼                            ▼
┌─────────────────────────────────────────┐
│     进度条变为不确定模式                 │
│     文字："正在安装..."                  │
│     软键：左=取消  右=(空)               │
└─────────────────────────────────────────┘
       │                            │
       │ 调用 install()             │
       ▼                            │
  状态 = STATUS_SUCCESS             │
       │                            │
       ▼                            ▼
┌─────────────────────────────────────────┐
│     切换到结果视图                       │
│     标题：安装完成                       │
│     内容：应用图标 + 应用名称 + "安装成功" │
│     软键：左=打开  右=完成               │
└─────────────────────────────────────────┘
       │                            │
       ├──────── 点击"打开" ────────┤
       │                            ▼
       │                    启动应用，关闭弹窗
       │
       └──────── 点击"完成" ───────┤
                                    ▼
                            关闭弹窗，回到应用程序页
```

#### 4.1.2 按键操作逻辑

**安装中状态**：

| 按键 | 行为 |
|---|---|
| 左软键（取消） | 关闭弹窗，清理临时文件（调用 `installer.deleteTemp()` + `clearCache()`） |
| 右软键 | 无功能 |
| 方向键 | 无功能（进度条不可导航） |
| 确认键 | 无功能 |
| 返回键 | 等效"取消" |

**结果状态（成功）**：

| 按键 | 行为 |
|---|---|
| 左软键（打开） | 启动应用（`Config.startApp(...)`），关闭弹窗 |
| 右软键（完成） | 关闭弹窗，回到应用程序页 |
| 方向键 | 在"打开"和"完成"之间切换高亮 |
| 确认键 | 触发当前高亮项 |
| 返回键 | 等效"完成" |

**结果状态（失败）**：

| 按键 | 行为 |
|---|---|
| 左软键 | 无功能 |
| 右软键（确定） | 关闭弹窗 |
| 方向键 | 无功能 |
| 确认键 | 等效"确定" |
| 返回键 | 等效"确定" |

---

### 4.2 分支路径：复杂场景回退

#### 4.2.1 回退流程

```
NokiaInstallerDialog 检测到非 STATUS_NEW 状态
              │
              ▼
    ┌─────────────────┐
    │  dismiss()      │  关闭诺基亚弹窗
    │  （无动画）      │
    └────────┬────────┘
             │
             ▼
    ┌─────────────────────────┐
    │ InstallerDialog.newInstance(uri).show(...) │
    │ 原有弹窗接管后续流程      │
    └─────────────────────────┘
```

#### 4.2.2 各分支回退场景

**场景 A：版本冲突（STATUS_EQUAL/OLDEST/NEWEST）**

```
NokiaInstallerDialog
    └── loadInfo() 返回 STATUS_EQUAL
        ├── 关闭自身
        └── InstallerDialog.newInstance(uri).show()
            └── 显示原有确认弹窗：
                "已安装版本 X.X，当前版本 Y.Y，是否重新安装？"
                [安装] [取消]
```

**场景 B：JAD/JAR 不匹配（STATUS_UNMATCHED）**

```
NokiaInstallerDialog
    └── loadInfo() 返回 STATUS_UNMATCHED
        ├── 关闭自身
        └── InstallerDialog.newInstance(uri).show()
            └── 显示原有弹窗，提示不匹配并提供解决方案
```

**场景 C：需要选择 JAR（STATUS_NEED_JAD）**

```
NokiaInstallerDialog
    └── loadInfo() 返回 STATUS_NEED_JAD
        ├── 关闭自身
        └── InstallerDialog.newInstance(uri).show()
            └── 显示原有弹窗，启动文件选择器让用户选 JAR
```

---

### 4.3 错误处理路径

#### 4.3.1 安装过程中抛出异常

```
Single.create(installer::install)
    └── onError(Throwable e)
        ├── 清理：installer.clearCache() + deleteTemp()
        ├── 显示错误提示（Toast 或弹窗内文字）
        └── 切换到错误结果视图
```

**错误结果视图**：

```
┌─────────────────────────────────────────┐
│ 标题：安装失败                            │
│ 内容："错误：无法解析 JAR 文件"            │
│ 软键：左=(空)  右=确定                    │
└─────────────────────────────────────────┘
```

---

## 五、核心代码设计

### 5.1 文件清单

#### 5.1.1 新增文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `NokiaInstallerDialog.java` | Java | 诺基亚风格安装弹窗（进度+结果） |
| `dialog_nokia_installer.xml` | Layout | 安装弹窗布局 |

#### 5.1.2 修改文件

| 文件 | 修改内容 |
|---|---|
| `NokiaBoxFragment.java` | `onPickFileResult()` 中替换 `InstallerDialog` 为 `NokiaInstallerDialog` |

#### 5.1.3 不动文件

| 文件 | 原因 |
|---|---|
| `AppInstaller.java` | 核心安装逻辑，无需改动 |
| `InstallerDialog.java` | 分支回退目标，保持原样 |
| `dialog_installer.xml` | 原有弹窗布局，保持原样 |

---

### 5.2 NokiaInstallerDialog 完整代码

```java
package ru.playsoftware.j2meloader.nokia;

import android.app.Dialog;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
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
import ru.woesss.j2me.installer.AppInstaller;
import ru.woesss.j2me.installer.InstallerDialog;

/**
 * 诺基亚风格 JAR 安装弹窗。
 *
 * 设计原则：
 * 1. 只处理最常见的"全新安装"主路径（STATUS_NEW → STATUS_SUCCESS）
 * 2. 复杂分支（版本冲突、不匹配、需选文件）回退到原有 InstallerDialog
 * 3. UI 完全复用诺基亚风格：蓝渐变标题栏、深色内容区、软键栏、方向键导航
 * 4. 不改动 AppInstaller 和 InstallerDialog 的任何逻辑
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

    // 焦点（结果状态下左右软键切换）
    private int focusIndex = 0; // 0 = 左软键，1 = 右软键

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
        uri = requireArguments().getParcelable(ARG_URI);

        AppListModel appListModel = new ViewModelProvider(requireActivity())
                .get(AppListModel.class);
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
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        initViews(dialog);
        setupKeyListener(dialog);
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
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return true; // 消费抬起事件
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (uiState == UI_STATE_SUCCESS) {
                        setFocus(0);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (uiState == UI_STATE_SUCCESS) {
                        setFocus(1);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (uiState == UI_STATE_SUCCESS) {
                        trigger(focusIndex);
                    } else if (uiState == UI_STATE_ERROR) {
                        dismiss();
                    }
                    return true;
                case KeyEvent.KEYCODE_SOFT_LEFT:
                    onSoftKey(0);
                    return true;
                case KeyEvent.KEYCODE_SOFT_RIGHT:
                    onSoftKey(1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    onBackKey();
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
    // 焦点管理（结果状态）
    // ============================

    private void setFocus(int index) {
        focusIndex = index;
        applyFocus();
    }

    private void applyFocus() {
        if (softLeft == null || softRight == null) return;
        if (focusIndex == 0) {
            softLeft.setBackgroundResource(R.drawable.bg_nokia_selected);
            softRight.setBackgroundResource(0);
        } else {
            softRight.setBackgroundResource(R.drawable.bg_nokia_selected);
            softLeft.setBackgroundResource(0);
        }
    }

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

        if (status == AppInstaller.STATUS_NEW) {
            // ✅ 主路径：直接安装
            startInstall();
        } else {
            // ❌ 分支路径：回退到原有 InstallerDialog
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

        if (status == AppInstaller.STATUS_SUCCESS) {
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
        // 清理当前 installer
        if (installer != null) {
            installer.deleteTemp();
            installer.clearCache();
        }
        compositeDisposable.dispose();

        // 关闭自身
        dismissAllowingStateLoss();

        // 启动原有 InstallerDialog
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
                    ivIcon.setImageDrawable(android.graphics.drawable.Drawable.createFromPath(iconPath));
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

        // 默认焦点在"完成"（右软键），避免误触打开
        focusIndex = 1;
        applyFocus();
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
```

---

### 5.3 布局文件 dialog_nokia_installer.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical">

    <!-- ========== 标题栏 ========== -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="28dp"
        android:background="@drawable/bg_nokia_softkey"
        android:gravity="center_vertical"
        android:paddingStart="10dp"
        android:paddingEnd="10dp">

        <TextView
            android:id="@+id/install_title"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="安装"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:textStyle="bold" />
    </LinearLayout>

    <!-- ========== 内容区 ========== -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#102040"
        android:gravity="center"
        android:minHeight="80dp"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- 加载/安装中视图 -->
        <LinearLayout
            android:id="@+id/content_loading"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:orientation="vertical">

            <ProgressBar
                android:id="@+id/install_progress"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:indeterminate="true" />

            <TextView
                android:id="@+id/install_status"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="正在加载..."
                android:textColor="#FFFFFF"
                android:textSize="12sp" />
        </LinearLayout>

        <!-- 结果视图（成功/失败共用） -->
        <LinearLayout
            android:id="@+id/content_result"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center"
            android:orientation="vertical"
            android:visibility="gone">

            <ImageView
                android:id="@+id/install_app_icon"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:scaleType="fitCenter" />

            <TextView
                android:id="@+id/install_app_name"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:textColor="#FFFFFF"
                android:textSize="14sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/install_result_text"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:textColor="#64b5f6"
                android:textSize="12sp" />
        </LinearLayout>
    </LinearLayout>

    <!-- ========== 底部软键栏 ========== -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="28dp"
        android:background="@drawable/bg_nokia_softkey"
        android:orientation="horizontal"
        android:paddingStart="10dp"
        android:paddingEnd="10dp">

        <TextView
            android:id="@+id/softLeft"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center_vertical|start"
            android:text="取消"
            android:textColor="#64b5f6"
            android:textSize="12sp" />

        <TextView
            android:id="@+id/softRight"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center_vertical|end"
            android:textColor="#64b5f6"
            android:textSize="12sp"
            android:visibility="invisible" />
    </LinearLayout>
</LinearLayout>
```

---

### 5.4 NokiaBoxFragment 修改点

```java
// 文件：app/src/main/java/ru/playsoftware/j2meloader/nokia/NokiaBoxFragment.java

// 修改方法：onPickFileResult(Uri uri)

private void onPickFileResult(android.net.Uri uri) {
    if (uri == null) {
        NokiaLog.i("Box", "文件选择器返回 null（用户取消）");
        return;
    }
    NokiaLog.i("Box", "文件选择器返回: " + uri);
    preferences.edit()
            .putString(Constants.PREF_LAST_PATH, FilteredFilePickerFragment.getLastPath())
            .apply();

    // ===== 修改前 =====
    // InstallerDialog.newInstance(uri).show(getChildFragmentManager(), "installer");

    // ===== 修改后 =====
    NokiaInstallerDialog.newInstance(uri).show(getChildFragmentManager(), "nokia_installer");
}
```

---

## 六、时序图

### 6.1 主路径：全新安装成功

```
用户    NokiaBoxFragment    NokiaInstallerDialog    AppInstaller    InstallerDialog
 │            │                    │                    │                │
 │──选jar────►│                    │                    │                │
 │            │───uri─────────────►│                    │                │
 │            │                    │──newInstance()     │                │
 │            │                    │──show()            │                │
 │            │                    │                    │                │
 │            │                    │──loadInfo()───────►│                │
 │            │                    │◄──STATUS_NEW───────│                │
 │            │                    │                    │                │
 │            │                    │──install()────────►│                │
 │            │                    │◄──STATUS_SUCCESS───│                │
 │            │                    │                    │                │
 │            │                    │ 显示成功结果        │                │
 │            │                    │ 左=打开 右=完成     │                │
 │◄──弹窗─────│                    │                    │                │
 │            │                    │◄──点击"完成"───────│                │
 │            │                    │──dismiss()         │                │
 │            │◄───────────────────│                    │                │
 │            │                    │                    │                │
```

### 6.2 分支路径：版本冲突回退

```
用户    NokiaBoxFragment    NokiaInstallerDialog    AppInstaller    InstallerDialog
 │            │                    │                    │                │
 │──选jar────►│                    │                    │                │
 │            │───uri─────────────►│                    │                │
 │            │                    │──newInstance()     │                │
 │            │                    │──show()            │                │
 │            │                    │                    │                │
 │            │                    │──loadInfo()───────►│                │
 │            │                    │◄──STATUS_EQUAL─────│                │
 │            │                    │                    │                │
 │            │                    │──dismiss()         │                │
 │            │                    │──newInstance(uri)────────────────────►
 │            │                    │                    │                │
 │            │                    │                    │                │──show()
 │◄──弹窗─────│                    │                    │                │
 │            │                    │                    │                │ 显示确认
 │            │                    │                    │                │ [安装][取消]
```

---

## 七、风险与应对

| 风险 | 影响 | 应对措施 |
|---|---|---|
| `AppInstaller` 内部抛出异常 | 弹窗崩溃 | `onError()` 捕获，显示错误视图，清理临时文件 |
| 用户快速按取消 | 线程未结束 | `compositeDisposable.dispose()` 切断回调，清理资源 |
| 回退时 `InstallerDialog` 也失败 | 双重弹窗 | 确保 `dismissAllowingStateLoss()` 在 `show()` 之前调用 |
| 低版本 Android 兼容 | UI 显示异常 | 使用基础 View，不依赖高版本 API；已在 4.4 设备验证 |
| 安装过程中 Activity 重建 | 状态丢失 | `setCancelable(false)` + `savedInstanceState` 检测 |

---

## 八、测试清单

### 8.1 主路径测试

- [ ] 选择正常 `.jar` 文件，显示进度条，安装成功，显示结果弹窗
- [ ] 结果弹窗：方向键左右切换高亮，确认键触发，左软键打开应用，右软键关闭
- [ ] 安装中按取消，弹窗关闭，无残留临时文件
- [ ] 安装中按返回键，等效取消

### 8.2 分支路径测试

- [ ] 安装已存在的应用（版本相同），回退到原有弹窗显示确认
- [ ] 安装旧版本应用，回退到原有弹窗显示降级提示
- [ ] 选择 `.jad` 文件需要选 `.jar`，回退到原有弹窗

### 8.3 错误路径测试

- [ ] 选择损坏的 `.jar`，显示错误弹窗，显示具体错误信息
- [ ] 安装过程中存储空间不足，显示错误弹窗

### 8.4 兼容性测试

- [ ] 240×320 分辨率设备（4a24ecf）
- [ ] 320×480 分辨率设备
- [ ] 现代长屏设备（jz5dauzlu8euw4e6）

---

## 九、附录

### 9.1 相关文件索引

| 文件 | 路径 |
|---|---|
| `AppInstaller.java` | `app/src/main/java/ru/woesss/j2me/installer/AppInstaller.java` |
| `InstallerDialog.java` | `app/src/main/java/ru/woesss/j2me/installer/InstallerDialog.java` |
| `NokiaBoxFragment.java` | `app/src/main/java/ru/playsoftware/j2meloader/nokia/NokiaBoxFragment.java` |
| `NokiaUninstallDialog.java` | `app/src/main/java/ru/playsoftware/j2meloader/nokia/NokiaUninstallDialog.java` |
| `dialog_installer.xml` | `app/src/main/res/layout/dialog_installer.xml` |
| `dialog_nokia_uninstall.xml` | `app/src/main/res/layout/dialog_nokia_uninstall.xml` |

### 9.2 诺基亚风格 UI 元素复用

| 元素 | 资源 |
|---|---|
| 标题栏/软键栏背景 | `@drawable/bg_nokia_softkey` |
| 焦点高亮背景 | `@drawable/bg_nokia_selected` |
| 内容区背景色 | `#102040`（深蓝） |
| 软键文字颜色 | `#64b5f6`（浅蓝） |
| 内容文字颜色 | `#FFFFFF`（白色） |

---

> 本方案通过"主路径覆盖 + 分支回退"的策略，在最小改动现有代码的前提下，实现诺基亚风格的安装弹窗。核心逻辑完全复用 `AppInstaller`，UI 层独立封装，确保与原有系统解耦。
