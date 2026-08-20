# CODEBUDDY.md 本文档为 CodeBuddy 在本仓库中处理代码时提供指引。


## 目标概述

本应用是诺基亚风格的全功能安卓桌面启动器（Launcher），
在 J2ME-Loader 基础上修改而来，具备运行j2me应用的能力。
核心功能：

1. **外观**：模仿诺基亚 S40/S60 风格——顶部状态栏 + 中间内容区 + 底部软键栏。
2. **融合 J2ME 与安卓**：JAR 应用与安卓原生应用视觉上无缝融合，但 JAR 只在「百宝箱」展示，不混入功能表。
3. **物理按键优先**：方向键导航 + 左右软键 + 确认键，所有可选项都可被方向键选中并高亮。
4. **功能对等**：桌面上的联系人、信息、通话记录等入口映射为安卓系统功能。
5. **真实系统信息**：顶栏显示真实信号（含双卡）、WiFi、电量、运营商、时间。
6. **通知展示**：读取系统通知并展示在桌面指定区域，支持滚动与清除。
7. **按键音**：按下物理按键时播放提示音。
8. **可配置**：提供桌面设置入口 + 复用 J2ME-Loader 设置入口；快捷栏可编辑。

**开发重心（重要）**：本仓库的主界面是**原键桌面 `NokiaDesktopActivity`**（`ru.playsoftware.j2meloader.nokia.*`），**不是 J2ME-Loader 的 `MainActivity`**——切勿把 `MainActivity` 当作主界面。

- 入口：`NokiaDesktopActivity` 在 `AndroidManifest.xml` 声明了 `LAUNCHER` + `HOME` + `DEFAULT`，应用图标和按 Home 键都进入它。
- 旧 `MainActivity` 只是 J2ME-Loader 自带的启动器/文件选择器/应用列表界面，现在仅作为「百宝箱」启动 JAR、复用设置入口的底层壳，**不是主界面、不是开发重点**。
- 调试、截图、功能验证一律针对 `NokiaDesktopActivity`（启动命令见「调试与安装」）；新增功能、改 UI 优先在 `app/src/main/java/ru/playsoftware/j2meloader/nokia/` 目录下进行。


## 界面简介

- 桌面
NokiaDesktopActivity， 就是按下HOME返回的界面，这里展示一些信息，和一些快捷入口

- 功能表
从桌面按下左键进入功能表，功能表里就是各种应用，和设置

- 桌面设置
从桌面按下右软键进入桌面设置，主要是 原键桌面自身的一些设置。比如，按键绑定，顶部快捷栏设置，壁纸设置，桌面组件设置等。


## 操作说明
本应用主要提供按键操作，但是每个手机的实际按键是不确定的，你不知道用户映射了什么键，u偶一不要通过 `adb shell wm size; adb shell input keyevent 82` 这种方式来发送按键。
测试时，能触碰优先触屏操作。

## 设计文档索引（改某个子系统前先读对应文档）

`docs/` 目录下有文档

- **详细开发规范（必读）**：`docs/NOKIA_DEVELOPMENT_RULES.md` —— 按键处理、DOWN/UP 配对、软键栏、底部菜单栏、选项弹窗、Android 4.4 兼容、双分辨率适配、设备说明等**硬性规则**全文。




## 调试与安装

使用adb 安装应用，并且以debug模式来安装，这样编译速度快。

调试方面，使用adb截图理解，再使用adb 模拟点击来操作。常用调试命令（多设备一律加 `-s <serial>` 指定目标，避免误装到别的设备）：

# 直接启动原键桌面（跳过 HOME）
adb shell am start -n io.github.cctyl.nokia.debug/ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity


## 常用命令

构建 release APK（推荐本地 flavor `open`）：
```
.\gradlew.bat assembleOpenRelease -x lint
```
需要 `-x lint` 参数，是因为本工程的 Lint 配置在出错时会中断构建。输出位置：`app/build/outputs/apk/open/release/KeydroidXLauncher-*-open-release.apk`。需要 `keystore.properties` + `app/test.jks`（已存在）以及 NDK 22.1.7171670。

构建 debug APK：
```
.\gradlew.bat assembleOpenDebug
```
Debug 变体会追加 `.debug` 到 applicationId 后缀，并以 `KeydroidXLauncher Debug` 名义运行。使用 `installOpenDebug` 把产物推送到已连接的设备/模拟器。

一键构建 + 安装 debug（快速开发循环；改动构建配置前请先阅读文档）：
```
.\build_install_debug.bat                 # 构建 openDebug 并安装到所有已连接的设备
.\build_install_debug.bat <serial>        # 仅构建并安装到指定设备
```
`build_install_debug.bat` 先运行 `assembleOpenDebug`，再运行 `install_debug.py`（它会并行向所有设备安装，单个设备失败不会阻塞其他设备）。`install_debug.py` 直接从构建输出目录读取 APK，因此不存在 `/dist` 缓存过期的问题。APK 已构建好时可改用 `.\install_debug.bat [serial]`。

运行单元测试：
```
.\gradlew.bat testOpenDebugUnitTest
```
插桩（设备上）测试：`.\gradlew.bat connectedOpenDebugAndroidTest`。

清理并重新配置：
```
.\gradlew.bat clean
.\gradlew.bat --stop
```

其他 flavor：把 `Open` 替换为 `Play`/`Fdroid`/`Dev`/`Midlet`（例如 `assemblePlayRelease -x lint`）。`dev` flavor 会在配置期根据 git 历史计算出一个 version code。

## 环境前置要求（本检出中已配置好）

- `local.properties` 指向 Android SDK（`sdk.dir`）。它是 **git 忽略** 的，并且还保存着每个开发者各自的、不应进入仓库的环境设置：Java 代理（`systemProp.https.proxyHost/Port`，例如 Clash 的 `127.0.0.1:7897`）以及指向 **JDK 17（Temurin/OpenJDK）** 的 `org.gradle.java.home`。不要使用 GraalVM；也不要使用 JDK 8/11 或裸 JRE。`gradle.properties` 通过 `apply from: 'local.properties'` 引入该文件，因此每个开发者只需修改自己的 `local.properties` —— 不会冲突。
- 共享的 `gradle.properties` 只保存工程级别的 Gradle 设置（不含机器相关的值）。
- `settings.gradle` 与 `gradle-wrapper.properties` 使用腾讯/阿里云镜像 + jitpack。请保留 `jitpack.io` —— 许多依赖都是 `com.github.*` 形式的 GitHub 库。
- NDK 版本在 `build.gradle`（`ext.NDK_VERSION`）中固定为 `22.1.7171670`；若缺失可通过 sdkmanager 安装。
- Release 签名通过 `keystore.properties` 读取 `app/test.jks`。两者都是 git 忽略的，请勿提交它们。



## J2ME-Loader介绍

J2ME-Loader 是一个运行在 Android 上的 J2ME（MIDP/CLDC）模拟器。它通过重新实现 J2ME API（构建于 Android 运行时之上）并把 MIDlet 字节码转译为可在 Android 上运行的形式，来运行老式的 2D/3D Java ME 游戏。本仓库是 J2meLoader 的一个分支（fork）。它是一个标准的多模块 Gradle/Android 工程（采用 Groovy DSL，AGP 8.5.1，Gradle 8.7）。
### 架构

这不是一个普通应用 —— 它是一个模拟器，因此大部分「应用逻辑」都是对 Java ME 平台的高保真重新实现。

**两个 Gradle 模块。** `:app` 是模拟器安卓应用。`:dexlib`（`com.android.dx`）是 Android `dx`/dexlib 工具链的 fork，编译进应用内，并在运行时把 J2ME 类文件转换为 Android 可执行的 `.dex`，从而让 MIDlet 自身的类能被加载并在 ART 运行时上运行。

**在 `javax.microedition.*` 中重新实现的 J2ME API。** 最大的源码树（`app/src/main/java/javax/...`，约 324 个文件）是工程自己对 MIDP/CLDC 类的实现 —— `MIDlet`、LCDUI（`Display`、`Canvas`、`Form`）、RMS 记录存储、媒体、网络、`m3g`（Mascot Capsule 3D）等。J2ME 游戏的字节码会调用这些类，而该实现把调用桥接到 Android 控件、Canvas 以及原生 3D 库。这一包是模拟器的核心；在此处的改动会直接影响游戏兼容性。

**模拟器核心 `org.microemu`。** MicroEmu Java ME 模拟器的 fork，负责类加载、MIDlet 生命周期与事件循环。`javax.microedition.shell.MicroActivity`（连同 `MidletThread`/`MidletSystem`）才是真正启动并驱动一个 MIDlet 的东西。

**双进程隔离。** `MainActivity`（原 J2ME-Loader 启动器、文件选择器、应用列表）运行在默认进程中 —— 注意它 **已不再是应用的主界面**；真正的 Home/桌面入口是 `NokiaDesktopActivity`（见开发重心与入口说明）。游戏本身通过 `MicroActivity` 运行在独立的 `:midlet` 进程中（`android:process=":midlet"`，见 `AndroidManifest.xml`），因此崩溃的 MIDlet 不会拖垮宿主应用。`com.nokia.mid.ui.NotificationBroadcastReceiver` 也处在 `:midlet` 进程中。

**基于 NDK 的原生 3D。** `app/src/main/cpp` 通过 ndkBuild（`Android.mk`）构建两个共享库：`javam3g`（基于 OpenGL ES 1.1 的 Mascot Capsule 3D `m3g`，提供 `javax.microedition.m3g`）与 `micro3d`（Micro3D V3 引擎绑定）。正是这段原生代码使得工程固定使用较旧的 NDK 22.1.7171670，并且 Gradle 需要安装 NDK。

**`ru.playsoftware.j2meloader` 中的应用外壳。** 安卓侧的 UI 与服务：`MainActivity`（遗留的 J2ME-Loader 启动器，已不再是主界面）、`NokiaDesktopActivity`（真正的 Home/桌面 —— 当前开发重点）、`ConfigActivity`、`SettingsActivity`、`KeyMapperActivity`、Room 数据库（按应用配置）、文件选择器以及 `storage.DocumentProvider`。原键桌面代码位于 `ru.playsoftware.j2meloader.nokia.*` 之下。`com.*`/`mmpp.*` 持有诺基亚 UI 扩展（`com.nokia.mid.ui`）与 Mascot Capsule 辅助类。

**原键桌面内部（`ru.playsoftware.j2meloader.nokia.*`）—— 这是主要的开发面。** 在触碰任何 UI 之前需要理解的高层分层：

- **Shell / 中枢层**：`NokiaBaseActivity`（240dp 设计基准 + `scaleMidContent`/`scalePanelContent` 整体缩放、density 修正、`applyBottomText` 动态字号）与 `NokiaDesktopActivity`（按键分发 `dispatchKeyEvent`、DOWN/UP 配对 `lastHandledDownKeyCode`、`refreshPageBar()` 页面装配、暴露 `getKeyBinding()`/`getScale()`/`getMidPanelHeight()`）。
- **页面契约层**：`NokiaPage`（extends `NokiaFocusHost`）提供 `getPageTitle()`/`getSoftLeftText()`/`getSoftRightText()`，由 Activity 声明式装配底部三栏；各页面 Fragment（功能表、百宝箱、桌面设置、组件向导等）实现它并调用 `host.refreshPageBar()`。
- **按键语义层**：`NokiaKeyBinding` 把 keyCode 解析成语义动作（`ACTION_SOFT_LEFT`/`RIGHT`/`SELECT`/`LEFT`/`RIGHT`），弹窗必须自己接入（Dialog 是独立 Window，Activity 的 dispatch 对弹窗无效），禁止写死 keyCode。
- **通用弹窗层**：`NokiaOptionsDialog`（唯一通用「选项/菜单列表」弹窗，`OptionItem` 模型 + `setItems()` 刷新），复用 `dialog_nokia_widget_options.xml`；其余安装/卸载等专用弹窗不得给软键加高亮/焦点。
- **工具与系统信息层**：`NokiaDimens.dp()`（唯一尺寸换算入口，禁止裸写 px/density）、`NokiaDashedLineDrawable`（点线分隔线标准实现）、`StatusBarController`（顶栏信号/WiFi/电量/时间，`SubscriptionManager` 需 `SDK_INT>=22` 守卫）、`NokiaLockScreen`（设备管理员锁屏，`ADD_DEVICE_ADMIN` 不加 NEW_TASK）。
- **布局约束**：Fragment 根布局**宽度固定 240dp**、高度 `match_parent`（或 ≤panelH），网格行数走 `getMidPanelHeight()` 实测反推 + `view.post` 延迟到布局完成，scale 一律走 `getScale()` 单一来源。

**Product flavors（`app/build.gradle`）。** `play`/`open`/`fdroid`/`dev` 都是完整模拟器（`FULL_EMULATOR=true`），区别仅在分发渠道、`versionNameSuffix` 与 proguard 文件；`open` 是非 Play 构建，也是本地开发应使用的一个。`midlet` 特殊：`FULL_EMULATOR=false`，它不构建模拟器，而是从 J2ME 应用的源码（读取自 `src/midlet/resources/MIDLET-META-INF/MANIFEST.MF`）构建一个独立的 Android APK。`dev` 在配置期调用 `generateVersionCode()`（git rev-list）—— 非 git 工作副本会回退到 version code 1（已在 `app/build.gradle` 中打补丁）。

**Release 签名。** `signingConfigs.release` 读取 `keystore.properties`（在未运行于 Bitrise CI 时）。Debug 构建使用默认 debug 密钥；release 构建需要本地的 `test.jks`。
