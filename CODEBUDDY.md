# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.


## 目标概述

本应用的目标是成为**诺基亚风格的全功能安卓桌面启动器（Launcher）**，在 J2ME-Loader 基础上修改而来，并作为系统默认 Home 桌面运行。核心诉求：

1. **外观**：模仿诺基亚 S40/S60 风格——顶部状态栏 + 中间内容区 + 底部软键栏。
2. **融合 J2ME 与安卓**：JAR 应用与安卓原生应用视觉上无缝融合，但 JAR 只在「百宝箱」展示，不混入功能表。
3. **物理按键优先**：方向键导航 + 左右软键 + 确认键，所有可选项都可被方向键选中并高亮。
4. **功能对等**：桌面上的联系人、信息、通话记录等入口映射为安卓系统功能。
5. **真实系统信息**：顶栏显示真实信号（含双卡）、WiFi、电量、运营商、时间。
6. **通知展示**：读取系统通知并展示在桌面指定区域，支持滚动与清除。
7. **按键音**：按下物理按键时播放提示音。
8. **可配置**：提供桌面设置入口 + 复用 J2ME-Loader 设置入口；快捷栏可编辑。

**开发重心（重要）**：本仓库的主界面是**诺基亚桌面 `NokiaDesktopActivity`**（`ru.playsoftware.j2meloader.nokia.*`），**不是 J2ME-Loader 的 `MainActivity`**——切勿把 `MainActivity` 当作主界面。

- 入口：`NokiaDesktopActivity` 在 `AndroidManifest.xml` 声明了 `LAUNCHER` + `HOME` + `DEFAULT`，应用图标和按 Home 键都进入它。
- 旧 `MainActivity` 只是 J2ME-Loader 自带的启动器/文件选择器/应用列表界面，现在仅作为「百宝箱」启动 JAR、复用设置入口的底层壳，**不是主界面、不是开发重点**。
- 调试、截图、功能验证一律针对 `NokiaDesktopActivity`（启动命令见「调试与安装」）；新增功能、改 UI 优先在 `app/src/main/java/ru/playsoftware/j2meloader/nokia/` 目录下进行。


## 界面简介

- 桌面
NokiaDesktopActivity， 就是按下HOME返回的界面，这里展示一些信息，和一些快捷入口

- 功能表
从桌面按下左键进入功能表，功能表里就是各种应用，和设置

- 桌面设置
从桌面按下右键进入桌面设置，主要是 诺基亚桌面自身的一些设置。比如，按键绑定，顶部快捷栏设置，壁纸设置，桌面组件设置等。

## J2ME-Loader介绍

J2ME-Loader is a J2ME (MIDP/CLDC) emulator for Android. It runs legacy 2D/3D Java ME games by reimplementing the J2ME APIs on top of the Android runtime and translating MIDlet bytecode to run on Android. This repo is a fork of J2meLoader. It is a standard multi-module Gradle/Android project (Groovy DSL, AGP 8.5.1, Gradle 8.7). A skill documenting the local Gradle network/signing fixes lives at `.claude/skills/android-gradle-build` (read it before changing build config or signing).


## 设计文档索引（改某个子系统前先读对应文档）

`docs/` 目录下有文档

- **详细开发规范（必读）**：`docs/NOKIA_DEVELOPMENT_RULES.md` —— 按键处理、DOWN/UP 配对、软键栏、底部菜单栏、选项弹窗、Android 4.4 兼容、双分辨率适配、设备说明等**硬性规则**全文。




## 调试与安装

使用adb 安装应用，并且以debug模式来安装，这样编译速度快。

调试方面，使用adb截图理解，再使用adb 模拟点击来操作。常用调试命令（多设备一律加 `-s <serial>` 指定目标，避免误装到别的设备）：

```bash
# 一键：构建 openDebug 并安装到全部/指定设备（开发主路径，详见 Common commands）
.\build_install_debug.bat                 # 全部设备
.\build_install_debug.bat <serial>        # 只装指定设备

# 截图到本地（先看当前界面再决定点哪里）
adb -s <serial> exec-out screencap -p > shot.png

# 看日志（J2ME-Loader 日志 TAG 多，过滤关键项）
adb -s <serial> logcat -s JL-Debug:* nokia:* AndroidRuntime:E

# 模拟点击 / 按键
adb -s <serial> shell input tap <x> <y>
adb -s <serial> shell input keyevent KEYCODE_DPAD_CENTER   # 确认键
adb -s <serial> shell input keyevent KEYCODE_HOME          # 回桌面
adb -s <serial> shell input keyevent KEYCODE_MENU          # 左软键

# 直接启动诺基亚桌面（跳过 HOME）
adb shell am start -n io.github.cctyl.nokia.debug/ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity
```


## Common commands

Build a release APK (recommended local flavor `open`):
```
.\gradlew.bat assembleOpenRelease -x lint
```
The `-x lint` flag is needed because the project's Lint config can otherwise abort the build. Output: `app/build/outputs/apk/open/release/J2ME_Loader-*-open-release.apk`. Requires `keystore.properties` + `app/test.jks` (already present) and NDK 22.1.7171670.

Build a debug APK:
```
.\gradlew.bat assembleOpenDebug
```
Debug variant gets a `.debug` applicationId suffix and runs as `JL-Debug`. Use `installOpenDebug` to push to a connected device/emulator.

One-click build + install debug (fast dev loop; read the docs before changing build config):
```
.\build_install_debug.bat                 # build openDebug + install to ALL connected devices
.\build_install_debug.bat <serial>        # build + install only to the given device
```
`build_install_debug.bat` runs `assembleOpenDebug` then `install_debug.py` (which installs to every device in parallel, one failing device doesn't block others). `install_debug.py` reads the APK directly from the build output dir, so there's no stale `/dist` cache issue. Use `.\install_debug.bat [serial]` when the APK is already built.

Run unit tests:
```
.\gradlew.bat testOpenDebugUnitTest
```
Instrumentation (on-device) tests: `.\gradlew.bat connectedOpenDebugAndroidTest`.

Clean and reconfigure:
```
.\gradlew.bat clean
.\gradlew.bat --stop
```

Other flavors: replace `Open` with `Play`/`Fdroid`/`Dev`/`Midlet` (e.g. `assemblePlayRelease -x lint`). The `dev` flavor computes a version code from git history at config time.

## Environment prerequisites (already configured in this checkout)

- `local.properties` points to the Android SDK (`sdk.dir`). It is **git-ignored** and also holds per-developer environment settings that must NOT live in the repo: the Java proxy (`systemProp.https.proxyHost/Port`, e.g. `127.0.0.1:7897` Clash) and `org.gradle.java.home` pointing to a **JDK 17 (Temurin/OpenJDK)**. Do NOT use GraalVM; do NOT use JDK 8/11 or a bare JRE. `gradle.properties` applies this file via `apply from: 'local.properties'`, so each developer edits only `local.properties` — no conflicts.
- The shared `gradle.properties` contains only project-wide Gradle settings (no machine-specific values).
- `settings.gradle` and `gradle-wrapper.properties` use Tencent/Aliyun mirrors + jitpack. Keep `jitpack.io` — many dependencies are `com.github.*` GitHub libraries.
- NDK version is pinned to `22.1.7171670` in `build.gradle` (`ext.NDK_VERSION`); install it via sdkmanager if missing.
- Release signing reads `app/test.jks` via `keystore.properties`. Both are git-ignored; do not commit them.

## Architecture

This is not a normal app — it is an emulator, so most of the "application logic" is a faithful reimplementation of the Java ME platform.

**Two Gradle modules.** `:app` is the emulator Android app. `:dexlib` (`com.android.dx`) is a fork of Android's `dx`/dexlib toolchain, compiled into the app and used at runtime to convert J2ME class files into Android-executable `.dex` so a MIDlet's own classes can be loaded and run on the ART runtime.

**J2ME API reimplemented in `javax.microedition.*`.** The largest source tree (`app/src/main/java/javax/...`, ~324 files) is the project's own implementation of the MIDP/CLDC classes — `MIDlet`, LCDUI (`Display`, `Canvas`, `Form`), RMS record store, media, networking, `m3g` (Mascot Capsule 3D), etc. A J2ME game's bytecode calls these classes, and the implementation bridges them to Android widgets, Canvas, and the native 3D libs. This package is the emulator's core; changes here directly affect game compatibility.

**Emulator core `org.microemu`.** A fork of the MicroEmu Java ME emulator handles class loading, the MIDlet lifecycle, and the event loop. `javax.microedition.shell.MicroActivity` (plus `MidletThread`/`MidletSystem`) is what actually starts and drives a MIDlet.

**Two-process isolation.** `MainActivity` (the original J2ME-Loader launcher, file picker, app list) runs in the default process — note that it is **no longer the app's main UI**; the actual Home/desktop entry is `NokiaDesktopActivity` (see 开发重心与入口说明). The game itself runs in a separate `:midlet` process via `MicroActivity` (`android:process=":midlet"`, see `AndroidManifest.xml`), so a crashing MIDlet does not take down the host app. `com.nokia.mid.ui.NotificationBroadcastReceiver` also lives in `:midlet`.

**Native 3D via NDK.** `app/src/main/cpp` builds two shared libraries through ndkBuild (`Android.mk`): `javam3g` (Mascot Capsule 3D `m3g` over OpenGL ES 1.1, providing `javax.microedition.m3g`) and `micro3d` (Micro3D V3 engine bindings). This native code is why the project pins the older NDK 22.1.7171670 and why Gradle needs the NDK installed.

**App shell in `ru.playsoftware.j2meloader`.** The Android-side UI and services: `MainActivity` (legacy J2ME-Loader launcher, not the main UI anymore), `NokiaDesktopActivity` (the real Home/desktop — the current development focus), `ConfigActivity`, `SettingsActivity`, `KeyMapperActivity`, Room database (per-app configs), file picker, and `storage.DocumentProvider`. The Nokia desktop code lives under `ru.playsoftware.j2meloader.nokia.*`. `com.*`/`mmpp.*` hold Nokia UI extensions (`com.nokia.mid.ui`) and Mascot Capsule helpers.

**Nokia desktop internals (`ru.playsoftware.j2meloader.nokia.*`) — this is the main development surface.** High-level layering to understand before touching any UI:

- **Shell / 中枢层**：`NokiaBaseActivity`（240dp 设计基准 + `scaleMidContent`/`scalePanelContent` 整体缩放、density 修正、`applyBottomText` 动态字号）与 `NokiaDesktopActivity`（按键分发 `dispatchKeyEvent`、DOWN/UP 配对 `lastHandledDownKeyCode`、`refreshPageBar()` 页面装配、暴露 `getKeyBinding()`/`getScale()`/`getMidPanelHeight()`）。
- **页面契约层**：`NokiaPage`（extends `NokiaFocusHost`）提供 `getPageTitle()`/`getSoftLeftText()`/`getSoftRightText()`，由 Activity 声明式装配底部三栏；各页面 Fragment（功能表、百宝箱、桌面设置、组件向导等）实现它并调用 `host.refreshPageBar()`。
- **按键语义层**：`NokiaKeyBinding` 把 keyCode 解析成语义动作（`ACTION_SOFT_LEFT`/`RIGHT`/`SELECT`/`LEFT`/`RIGHT`），弹窗必须自己接入（Dialog 是独立 Window，Activity 的 dispatch 对弹窗无效），禁止写死 keyCode。
- **通用弹窗层**：`NokiaOptionsDialog`（唯一通用「选项/菜单列表」弹窗，`OptionItem` 模型 + `setItems()` 刷新），复用 `dialog_nokia_widget_options.xml`；其余安装/卸载等专用弹窗不得给软键加高亮/焦点。
- **工具与系统信息层**：`NokiaDimens.dp()`（唯一尺寸换算入口，禁止裸写 px/density）、`NokiaDashedLineDrawable`（点线分隔线标准实现）、`StatusBarController`（顶栏信号/WiFi/电量/时间，`SubscriptionManager` 需 `SDK_INT>=22` 守卫）、`NokiaLockScreen`（设备管理员锁屏，`ADD_DEVICE_ADMIN` 不加 NEW_TASK）。
- **布局约束**：Fragment 根布局**宽度固定 240dp**、高度 `match_parent`（或 ≤panelH），网格行数走 `getMidPanelHeight()` 实测反推 + `view.post` 延迟到布局完成，scale 一律走 `getScale()` 单一来源。

**Product flavors (`app/build.gradle`).** `play`/`open`/`fdroid`/`dev` are the full emulator (`FULL_EMULATOR=true`), differing only in distribution channel, `versionNameSuffix`, and proguard files; `open` is the non-Play build and the one to use for local development. `midlet` is special: `FULL_EMULATOR=false`, and instead of building the emulator it builds a standalone Android APK *from a J2ME app's sources* (read from `src/midlet/resources/MIDLET-META-INF/MANIFEST.MF`). `dev` calls `generateVersionCode()` (git rev-list) at configuration time — a non-git working copy falls back to version code 1 (already patched in `app/build.gradle`).

**Release signing.** `signingConfigs.release` reads `keystore.properties` (when not running on the Bitrise CI). Debug builds use the default debug key; release builds need the local `test.jks`.
