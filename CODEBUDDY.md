# CODEBUDDY.md This file provides guidance to CodeBuddy when working with code in this repository.


##  目标概述

本应用的目标是成为一个**诺基亚风格的全功能安卓桌面启动器（Launcher）**，在J2ME-Loader的基础上修改而来，并作为系统默认 Home 桌面运行。核心诉求：

1. **外观**：模仿诺基亚 S40/S60 风格——顶部状态栏 + 中间内容区 + 底部软键栏。
2. **融合 J2ME 与安卓**：将 J2ME-Loader 已安装的 JAR 应用与安卓原生应用**视觉上无缝融合**，但 JAR 只在「百宝箱」中展示，不混入功能表。
3. **物理按键优先**：方向键导航 + 左右软键 + 确认键，所有可选项都可被方向键选中并高亮，最大程度模拟真机。
4. **功能对等**：桌面上的联系人、信息、通话记录等入口映射为安卓系统功能。
5. **真实系统信息**：顶栏显示真实信号（含双卡）、WiFi、电量、运营商、时间。
6. **通知展示**：读取系统通知并展示在桌面指定区域，支持滚动与清除。
7. **按键音**：按下物理按键时播放提示音。
8. **可配置**：提供桌面设置入口 + 复用 J2ME-Loader 自身设置入口；快捷栏可编辑。


## 开发重心与入口说明（重要）

**本仓库的开发重心已经转移到「诺基亚桌面」，而不是原本的 J2ME-Loader 主界面。**

- 真正的桌面（Home / Launcher）入口是 **`ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity`**，它在 `AndroidManifest.xml` 中同时声明了 `LAUNCHER` + `HOME` + `DEFAULT` 三个 category，即应用图标入口和按 Home 键都会进入这个诺基亚桌面。
- 原本的 **`MainActivity`** 是 J2ME-Loader 自带的启动器 / 文件选择器 / 应用列表界面，**它不再是本应用的主界面，也不是开发重点**。它只是作为「百宝箱」里启动 JAR 应用、以及复用其设置入口的底层壳存在。
- 因此，调试、截图、功能验证时，应当启动 / 操作的是 `NokiaDesktopActivity`，而不是 `MainActivity`。例如：
  ```bash
  adb shell am start -n io.github.cctyl.nokia.debug/ru.playsoftware.j2meloader.nokia.NokiaDesktopActivity
  ```
  或直接模拟按 Home 键进入桌面：
  ```bash
  adb shell input keyevent KEYCODE_HOME
  ```
- 新增功能、改 UI、加逻辑时，优先在 `app/src/main/java/ru/playsoftware/j2meloader/nokia/` 目录下的诺基亚桌面相关代码中进行，而非 J2ME-Loader 原有的 `MainActivity` 等模块。


## 界面简介

- 桌面
NokiaDesktopActivity， 就是按下HOME返回的界面，这里展示一些信息，和一些快捷入口

- 功能表
从桌面按下左键进入功能表，功能表里就是各种应用，和设置

- 桌面设置
从桌面按下右键进入桌面设置，主要是 诺基亚桌面自身的一些设置。比如，按键绑定，顶部快捷栏设置，壁纸设置，桌面组件设置等。

## J2ME-Loader介绍

J2ME-Loader is a J2ME (MIDP/CLDC) emulator for Android. It runs legacy 2D/3D Java ME games by reimplementing the J2ME APIs on top of the Android runtime and translating MIDlet bytecode to run on Android. This repo is a fork of J2meLoader. It is a standard multi-module Gradle/Android project (Groovy DSL, AGP 8.5.1, Gradle 8.7). A skill documenting the local Gradle network/signing fixes lives at `.claude/skills/android-gradle-build` (read it before changing build config or signing).



## 调试与安装

使用adb 安装应用，并且以debug模式来安装，这样编译速度快。

调试方面，使用adb截图理解，再使用adb 模拟点击来操作。




## 重要事项

在应该加日志的地方，都要加上日志输出，尽可能多的加日志。方便排查问题。

没有我的允许，不能私自提交git。

## 按键处理规范（重要）

**凡是菜单 / 弹窗 / Dialog 中涉及物理按键处理的地方，都必须复用用户自定义的按键映射（`NokiaKeyBinding`），禁止写死 keyCode。**

背景与原因：

1. 用户在「桌面设置 → 按键绑定设置」里可以自定义左软键、右软键、确认键、方向键等映射。这套映射由 `ru.playsoftware.j2meloader.nokia.NokiaKeyBinding` 统一维护，桌面层 `NokiaDesktopActivity.dispatchKeyEvent` 通过 `keyBinding.resolveAction(event)` 把 keyCode 解析成语义动作（`ACTION_SOFT_LEFT` / `ACTION_SOFT_RIGHT` / `ACTION_SELECT` / `ACTION_LEFT` / `ACTION_RIGHT` 等），并自带兜底（如 `KEYCODE_MENU` → `ACTION_SOFT_LEFT`、`KEYCODE_ENTER`/`SPACE`/`BUTTON_A` → `ACTION_SELECT`）。
2. **Dialog / DialogFragment 是独立 Window，弹出后按键事件到不了 `NokiaDesktopActivity`，Activity 的 `dispatchKeyEvent` 对其无效。** 所以弹窗必须自己接入 `NokiaKeyBinding`，不能指望桌面帮它解析。
3. 写死 `KEYCODE_SOFT_LEFT` 这类 keyCode 的写法是错误的：(a) 漏掉 `KEYCODE_MENU` 等用户实际设备发出的键码，导致左/右软键"没反应"；(b) 完全无视用户在按键绑定设置里的自定义，用户改了绑定对弹窗无效。

正确做法：

- 在弹窗 `onCreate` 里通过 `((NokiaDesktopActivity) requireActivity()).getKeyBinding()` 取得真实绑定实例（`NokiaDesktopActivity` 已暴露 public 方法）。
- `setOnKeyListener` 里先 `keyBinding.resolveAction(event)` 解析成动作，再按动作分发；`KEYCODE_BACK` 由弹窗自己单独处理（`NokiaKeyBinding` 不管 BACK）。
- 已有案例：`NokiaUninstallDialog` 当前是写死 + 硬编码 `KEYCODE_MENU` 才"恰好能用"，同样未接入绑定，属于同类隐患，应一并改成接入 `NokiaKeyBinding`；新增 / 修改任何弹窗、菜单按键逻辑时，一律以接入 `NokiaKeyBinding` 为标准，与桌面行为 100% 一致。


## 物理按键 DOWN / UP 配对规范（重要）

**只要在 Activity / Fragment 层消费了某个按键的 `ACTION_DOWN`，就必须把对应的 `UP`（必要时含 `REPEAT`）一并消费，禁止只消费 DOWN 就放手。** 这是 Android 输入管线的一个经典坑，曾导致 320×480 设备上「一次确认键按压被识别成两次动作」。

背景与原因（2026-08 实测 bug：添加应用组件确认键连发两次动作）：

1. `NokiaDesktopActivity.dispatchKeyEvent` 旧实现只处理 `ACTION_DOWN`，非 DOWN 事件一律 `return super.dispatchKeyEvent(event)` 放行到 view 层级。
2. 按键处理时 `flashBottomBar(ACTION_SELECT)` 会对底部中间软键 `setPressed(true)`（延时 100ms 复位）。DOWN 已被本层消费（view 从未收到 DOWN），但 **UP 到达 view 层级时该 View 仍处于 pressed 且 clickable / focusable**，系统会在 UP 时自动合成 `performClick()` → 底部栏 `setOnClickListener` 再次 `dispatchActionToHost(ACTION_SELECT)` → 第二次动作。
3. 第二次动作恰好落在**刚切换完成的新 Fragment** 上，于是表现为：S6 确认「应用」→ 自动选中第一个应用；ADD 确认出栈回 S1 → 自动选中组件进入 EDIT（「更换应用」）。
4. **跨版本 / 跨设备差异会掩盖时序 bug**：该 bug 只在 320×480（Android 13，确认键为 `ENTER`）复现；240×320（Android 4.4，确认键为 `DPAD_CENTER`）的 UP→click 合成行为与 Fragment 切换时序不同，完全不触发。**不要因为某个机型"没复现"就认为没问题。**

正确做法（已修复，见 `NokiaDesktopActivity.java`）：

- 用字段记录最近一次被本层消费的 keyCode：`private int lastHandledDownKeyCode = KeyEvent.KEYCODE_UNKNOWN;`
- `dispatchKeyEvent` 的非 DOWN 分支：若 `event.getKeyCode() == lastHandledDownKeyCode` → 记日志并 `return true`（吞掉 UP/REPEAT，杜绝 click 合成）；否则才 `return super...`。
- 在所有 DOWN 被消费并 `return true` 的路径设置该字段：录制态捕获、BACK→`host.onBack()`、锁屏动作、`dispatchActionToHost(...) == true`。
- 在所有 DOWN **未消费**交给系统的路径复位为 `KEYCODE_UNKNOWN`，否则会误吞后续无关按键的 UP。
- 未绑定键 / EditText 打字键的 DOWN 走系统，字段被复位，UP 正常透传，搜索框物理键盘输入不受影响（与 `NokiaKeyBinding.dispatchDialogKey` 已消费非 DOWN 事件的既有模式一致）。

关键认知：

- **消费了 DOWN 不等于消费了整次按键**；被本层消费 DOWN 的按键，其 UP 必须同步拦截，否则会穿透到 view 层级。
- **警惕「按下状态 + 可点击」的合成点击**：任何在按键处理期间 `setPressed(true)` 的可点击 View，都可能因后续 UP 而被系统合成 `performClick`——即使该 View 从未收到过 DOWN。
- 新增 / 修改任何按键分发、底部栏视觉反馈逻辑时，以「输入事件完整配对」为标准自查，而不是按某个机型打补丁。


## 软键栏（底部左右菜单）禁止加高亮 / 焦点逻辑（重要）

**底部软键栏的左右两个文字，就只是物理左 / 右软键的标签，禁止给它加任何"选中态高亮"或"焦点切换"机制。** 这是反复踩过的坑，务必遵守。

背景与原因：

1. 在真机上，左软键、右软键是**两个固定的物理键**，底部左右文字只是它们的标签，左右键直接对应左右文字，**不存在"当前选中的是哪个软键"这种概念**。给软键栏套"焦点 + 高亮"逻辑是错误的。
2. 软键栏底部布局通常是 `layout_width="0dp" + layout_weight="1"` 把宽度**平分给左右各 50%** 的写法（如 `dialog_nokia_installer.xml`、`dialog_nokia_uninstall.xml`、`nokia_bottom_bar.xml`）。一旦给某个软键设置 `bg_nokia_selected` 背景，背景会**填满整个 TextView 的 bounds**，于是出现"明明只有两个字，高亮却占了 50% 宽度"的色块——这是之前频繁出现的高亮 bug 的真正根因，**不是布局 weight 的问题，而是代码多余的高亮机制**。
3. 列表 / 菜单里的**条目**用 `bg_nokia_selected` / `bg_nokia_selected_dark` 高亮是合理的（方向键导航选中某个应用 / 选项，属于需求"所有可选项可被方向键选中并高亮"）。**本规范只针对软键栏（底部左右菜单），不针对列表项。**

正确做法（弹窗 / 软键栏）：

- **彻底删掉**软键栏上的 `focusIndex` / `setFocus()` / `applyFocus()` 这套焦点状态，以及任何 `setBackgroundResource(R.drawable.bg_nokia_selected)` 给软键设置背景的代码。**软键不需要高亮。**
- **按键语义回归真机**：
  - 左软键（`ACTION_SOFT_LEFT`）→ 触发左文字动作。
  - 右软键（`ACTION_SOFT_RIGHT`）→ 触发右文字动作。
  - 中间确认键（`ACTION_SELECT` / `DPAD_CENTER` / `ENTER`）→ **只确认"内容区"的选中项，绝不等于左或右软键**；弹窗里若没有列表项可确认，确认键不触发任何软键（消费掉即可），不可把确认键当成切换 / 触发左右菜单。
  - 方向键左 / 右（`ACTION_LEFT` / `ACTION_RIGHT`）→ 软键没有"焦点"概念，不要再用来切换焦点，直接忽略。
  - 返回键（`BACK`）→ 保留（安装完成→完成、卸载→取消）。
- 删除 `showXxxUi()` 里类似 `focusIndex = 1; applyFocus();` 这种调用。
- 布局 `0dp + weight=1` 可以保留（左右各 50% 没问题），因为没有背景去撑满它，左右文字会各自靠左 / 靠右静默显示，符合诺基亚观感。

已有反例 / 待修清单（新增或修改弹窗时对照自查）：

- `NokiaInstallerDialog.java`：曾用 `applyFocus()` 给 `softLeft` / `softRight` 设置 `bg_nokia_selected`，并用 `DPAD_LEFT` / `DPAD_RIGHT` 切焦点、`DPAD_CENTER` 触发 `trigger(focusIndex)` —— 这套全部应删除。
- `NokiaUninstallDialog.java`：同样的 `applyFocus()` 高亮 + 焦点切换逻辑 —— 同样应删除。
- 凡是底部只有左右两个软键的弹窗，一律照此处理，不要再写回高亮 / 焦点代码。


## 底部菜单栏与界面名规范（重要）

**所有页面统一为「顶部无标题 + 底部菜单栏（左软键 / 中间界面名 / 右软键）」结构，页面自身禁止直接操作底部栏的三个 TextView，统一走声明式装配。**

背景与原因：

1. 早期各 Fragment 各自写死 `setBottomBar(...)`、直接 `findViewById(R.id.bottomLeft)` 等，散乱且易出错。现已在 `ru.playsoftware.j2meloader.nokia.NokiaPage` 接口上收敛为统一契约。
2. 底部栏三栏是 `layout_width="0dp" + layout_weight="1"` 平分宽度的布局（`nokia_bottom_bar.xml`）。某栏文字为空时**必须用 `View.INVISIBLE` 隐藏，禁止用 `View.GONE`**：
   - `GONE` 会释放占位宽度 → 剩余两栏重新平分 → 中间界面名会偏移到空位一侧（真实踩过的 bug）；
   - `INVISIBLE` 保留占位宽度（三栏宽度不变），中间标题**始终居中**，且 INVISIBLE 的 View 不接收触摸，不会误触。
3. 界面名可能较长（如「桌面组件设置」），固定字号在 240px 宽的小屏上显示不全。处理方式是**按字符数动态缩字号 + 单行省略号兜底**（已在 `NokiaBaseActivity.applyBottomText` 实现），不要再另想换行/截断字符串的方案。

正确做法：

- **声明式装配（NokiaPage）**：
  - 页面实现 `NokiaPage` 接口（extends `NokiaFocusHost`），提供三个可动态取值的 getter：`getPageTitle()`（中间界面名）、`getSoftLeftText()`（左软键）、`getSoftRightText()`（右软键），**返回 null 表示隐藏该栏**（如桌面中间留空、组件类型选择页左软键为空）。
  - `NokiaDesktopActivity.refreshPageBar()` 通过 `findFragmentById(R.id.midPanel)` 取当前顶层 Fragment，若实现 `NokiaPage` 则自动调用 `setBottomBar(左, 中, 右)` 装配。
  - Fragment 在 `onViewCreated` / `onResume` 以及内部状态变化（焦点变化、mode 切换、覆盖模式、向导步骤切换等）后调用 `host.refreshPageBar()` 重新装配。
- **动态字号规则**（`NokiaBaseActivity.applyBottomText`，只对中间界面名生效）：`≤4 字 12sp`、`5-6 字 11sp`、`≥7 字 10sp`。
- **省略号兜底**：三个 TextView 均 `singleLine="true"`；中间栏 `ellipsize="middle"`，左右栏 `ellipsize="end"`（已写在 `nokia_bottom_bar.xml`）。
- **禁止**：在 Fragment 里直接 `findViewById(R.id.bottomLeft / bottomCenter / bottomRight)` 改文字/可见性；用 `View.GONE` 隐藏空栏；给中间标题加换行或多行。
- 桌面场景：中间界面名为空，顶部也不显示标题（`nokia_top_bar.xml` 已删除 topTitle）。


## 选项弹窗规范（NokiaOptionsDialog）（重要）

**所有「选项 / 菜单列表」类弹窗一律使用 `NokiaOptionsDialog`（完整版），它是唯一的通用选项弹窗组件，旧弹窗类已删除，禁止再新建同类弹窗。**

背景与原因：

1. 早期有 `NokiaAppOptionsDialog` / `NokiaWidgetOptionsDialog` / `NokiaWidgetDeleteDialog` 三个各自为政的弹窗，能力不全且行为不一致。现统一收敛为 `NokiaOptionsDialog`（复用 `dialog_nokia_widget_options.xml` 布局），旧类与旧布局已删除。
2. 弹窗是独立 Window，`NokiaDesktopActivity.dispatchKeyEvent` 对其无效，弹窗必须自己接入 `NokiaKeyBinding`（见「按键处理规范」），禁止写死 keyCode。
3. 弹窗底部左右软键同样禁止加高亮 / 焦点逻辑（见「软键栏规范」）。

数据模型 `OptionItem`（`NokiaOptionsDialog.OptionItem`）：

- `icon`：图标资源 id，`0` 表示无图标。
- `label`：选项文案（通过 `setItems()` 可整体替换刷新）。
- `enabled`：`false` = 灰色不可选，方向键导航自动跳过。
- `keepOpen`：`true` = 点击后不关闭弹窗（用于全选 / 取消全选后刷新文案）。
- `action`：点击动作（`Runnable`）。

正确做法：

- 打开：`NokiaOptionsDialog.show(fm, title, items)`，返回实例以便后续刷新。
- 动态刷新：宿主在选项动作里更新数据后调用 `dialog.setItems(newItems)`，重建列表容器并修正焦点（跳过禁用项），**不重新膨胀整个布局**。
- 交互语义：点击已启用项执行 `action`；`keepOpen=false` 的项执行后自动 `dismiss()`；`keepOpen=true` 的项（全选/取消全选）执行后不关闭，配合 `setItems()` 刷新。
- 按键：`onCreate` 里通过 `((NokiaDesktopActivity) requireActivity()).getKeyBinding()` 取得真实绑定，`setOnKeyListener` 内先 `keyBinding.resolveAction(event)` 解析成语义动作再分发；`KEYCODE_BACK` 由弹窗单独处理关闭。
- **禁止**：新建/复用旧弹窗类或旧布局 `dialog_nokia_app_options.xml`；写死 keyCode；给软键加高亮/焦点；点击选项后无法刷新文案。


## Android 4.4 (API 19) 兼容性踩坑

1. **矢量图 / drawable 膨胀**：4.4 的 `Resources` 在膨胀含特定 `vectorDrawables` 或 drawable 的布局时易抛 `InflateException` / `invalid drawable`。涉及顶栏、桌面背景等图形资源时，优先用兼容写法（如 `AppCompat` 矢量、或自定义 `Drawable`）；构建侧已开启 `vectorDrawables.useSupportLibrary`。
2. **`android.telephony.SubscriptionManager` 是 API 22+ 才有的类**。`StatusBarController` 中对该类的强制类型转换必须用 `Build.VERSION.SDK_INT >= 22` 守卫，否则 4.4 上 `NoClassDefFoundError`。其余使用点（双卡监听、`getPhoneCount` 等）也须守卫并降级单卡。
3. **设备管理员激活页 `ACTION_ADD_DEVICE_ADMIN` 不能用 `FLAG_ACTIVITY_NEW_TASK` 启动**。4.4（及部分 ROM）的 `DeviceAdminAdd` 会直接拒绝：`W/DeviceAdminAdd: Cannot start ADD_DEVICE_ADMIN as a new task`，导致锁屏按钮「点击无反应」（激活页不弹出）。该 intent 应从前台 Activity 上下文启动（**不加** NEW_TASK）；只有当 `context` 非 Activity 时才补 NEW_TASK 兜底（实际调用方均为前台 Activity，见 `NokiaLockScreen`）。

通用原则：所有 API 22+ 的类/方法引用都要 `SDK_INT` 守卫；Dalvik 验证器对运行时不执行到的高版本类引用只会打 `VFY Could not find class '...'` **无害告警**，不算崩溃。低版本设备（尤其 4.4）建议用「修一处→构建→装到 4a24ecf 实测」的迭代方式，以设备真实崩溃为准逐个修，而非盲目猜测。

## 双分辨率适配规范（重要）

### 适配目标分级

| 优先级 | 分辨率 | 设备 | 验收标准 |
|---|---|---|---|
| **主适配** | 240×320 | 4a24ecf（Android 4.4，density 120→160） | 所有界面不崩、不裁切、不错位，点线清晰 |
| **主适配** | 320×480 | tcpip 设备（density 136→160） | 同上，且顶栏/中间区比例可接受 |
| **次要适配（兜底）** | 16:9 长屏 | jz5dauzlu8euw4e6 | 不崩、不变形、不裁切、可正常操作即可 |

### 适配架构（理解后才能改）

项目采用 **「240×320 dp 设计基准 + 运行时整体缩放」** 方案，中枢在 `NokiaBaseActivity.java`：

- **设计常量**：`BASE_W=240`、`TOP_H=36`、`BOT_H=22`、`MID_H=262`
- **缩放计算**：`scale = 屏宽dp / 240`（高度不足退化为 `屏高dp / 320` 的 contain 模式）
- **中间面板**：`scaleMidContent()` 对 midPanel 内容 `setScaleX/Y` 整体放大，接近整数时（±0.04）吸附到整数 scale；内容高 = panelH（match_parent）时跳过二次缩小分支
- **底栏**：`scalePanelContent()` 缩放内层内容，栏高设为 `22*scale`
- **顶栏**：**不参与缩放**（原生 dp 渲染保图标清晰）
- **density 修正**：`attachBaseContext()` 把 <160 DPI 强制吸附到 160（mdpi），非标准值吸附到最近标准值

**关键约束：不改动「240 基准 + 整体缩放」架构，不引入资源限定符目录（如 values-sw320dp、layout-hdpi 等），避免与代码缩放双轨冲突。**

### 尺寸规范

#### 统一尺寸工具类 NokiaDimens

所有 dp → px 换算**必须**通过 `ru.playsoftware.j2meloader.nokia.NokiaDimens` 完成，**禁止**在 Fragment / Dialog / Drawable 中自行写 `(int)(v * density)`：

```java
// 正确
NokiaDimens.dp(getResources(), 36)

// 禁止
(int)(36 * getResources().getDisplayMetrics().density)
```

#### 禁止 px 写死

**任何地方都不允许写死 px 值**（如 `LayoutParams(..., 1)`、`setPadding(12, 8, 12, 8)`），必须通过 `NokiaDimens.dp()` 换算。此前 `NokiaKeyBindFragment` 的分隔线高度、margin、padding 全是 px，已修复——不要重蹈。

#### 弹窗尺寸收敛至 dimens.xml

弹窗标题栏/底栏高度、标题/内容字号已收敛至 `values/dimens.xml`（`nokia_dialog_title_bar_height` 等），新增弹窗同理，禁止在布局 XML 中硬编码 `28dp` / `14sp` / `12sp`。

#### 写死高度导致二次缩小的风险

任何 Fragment 根布局 `android:layout_height="262dp"`（写死设计稿高度）在 panelH < 262dp 时会触发 `scaleMidContent` 二次缩小分支（`finalScale = panelH / contentH`），导致内容整体缩水、右侧出现缝隙。**新 Fragment 一律用 `match_parent`，或确保内容总高 ≤ panelH。**

已修复的案例：`fragment_nokia_desktop.xml`、`fragment_nokia_key_bind.xml`、`fragment_nokia_key_bind_wizard.xml`。

### 点线（虚线分隔线）标准实现

项目使用 `NokiaDashedLineDrawable` 绘制横向点线分隔线（如桌面快捷栏上下方），**禁止使用 XML shape dash 虚线或 DashPathEffect**：

- **XML `shape="line"` + `dashWidth/dashGap`**：部分 ROM/API 不渲染
- **`DashPathEffect` + 硬件加速**：Android 4.4 上可能画成实线或不渲染；1px 线宽 + 抗锯齿会羽化糊成实线
- **正确做法**：`NokiaDashedLineDrawable` 用 `drawRect` 循环画实心方块点阵（FILL 样式无抗锯齿，全版本硬件加速正常），构造时传入调用方 `Resources`（不可用 `Resources.getSystem()`，会绕过 density 修正）

```java
// 正确：传入 getResources()，点宽 3dp 间隔 3dp
view.setBackground(new NokiaDashedLineDrawable(getResources(), 0x60FFFFFF, 3, 3));

// 禁止
view.setBackground(new NokiaDashedLineDrawable(0x60FFFFFF, 3, 3)); // 旧构造，用 Resources.getSystem()
```

### 布局原则：固定区 + 弹性区

- **顶栏与快捷应用栏（含上下点线）位置优先保障**，稳定可见，不可被压缩/裁切
- **中间通知区/桌面组件区**为弹性区（`layout_height="match_parent"` + `layout_below` 下点线），允许被挤压（后续会做可滚动）
- 新页面设计时遵循同样原则：标题/工具栏固定 + 内容区弹性

### 行数空间预算（重要）

**所有网格类页面（功能表、百宝箱等）的行数计算必须基于实测 panelH 反推，禁止使用估算公式。行高必须均分拉伸，禁止写死固定 dp。**

背景与原因：

1. 早期 `NokiaMenuFragment` 和 `NokiaBoxFragment` 使用 `(heightDp - BAR_H_DP) / scale` 估算可用空间，但 `BAR_H_DP` 是假设值（顶栏 36dp + 底栏 22dp = 58dp），**实际顶栏因 wrap_content + 系统状态栏高度差异而偏高**（如 240×320 设备实测 panelH=253 而非 262），导致高估可用空间 → 行数算多 → 最后一行被底栏裁切。
2. 早期行高写死固定值（菜单 58dp、百宝箱 64dp），320×480 设备实测 panelH=408 但公式低估 → 4 行仅占 232dp → 底部留白 69px，浪费空间。
3. `scale` 来源不统一：`NokiaBaseActivity.scaleMidContent()` 和各 Fragment 的 `computeRowsPerPage()` 各自独立计算 scale，容易因 density 修正后的微小差异导致空间预算与缩放不同步。

正确做法：

- **scale 单一来源**：Fragment 一律通过 `((NokiaDesktopActivity) requireActivity()).getScale()` 获取缩放比，不再自行计算。
- **panelH 实测反推**：通过 `((NokiaDesktopActivity) requireActivity()).getMidPanelHeight()` 获取 midPanel 真实像素高度，公式为 `availDesign = panelH(px) / density / scale`，再计算 `rows = (availDesign - TITLE_H_DP) / ROW_H_DP`。
- **行高均分拉伸**：行数确定后，每行实际 dp = `(availDesign - TITLE_H_DP) / rows`，避免底部留白或裁切。`ROW_H_DP` 降级为 fallback 值。
- **时序**：`computeRowsPerPage()` 和 `buildGrid()/buildCurrentPage()` 必须延迟到 midPanel 布局完成后执行（`view.post(() -> { ... })`），确保 panelH > 0。
- **禁止**：自行计算 scale；写死 `BAR_H_DP` 等假设值；行高写死固定 dp；在 panelH=0 时提前建页。

### match_parent 根布局的二次缩放陷阱（重要）

**使用 `match_parent` 根布局 + `scaleMidContent(view, true)`（topAlign）的 Fragment，必须补充 `view.post` 动态高度调整逻辑，否则 scale > 1 时内容视觉偏下。**

背景与原因：

1. 根布局 `match_parent` → 内容高度 = panelH。`scaleMidContent` 判定 `contentFillsPanel=true` 跳过二次缩小。
2. 但 `topAlign=true` + `setPivotY(0)` + `content.setScaleX/Y(scale)`，视觉高度 = panelH × scale。
3. scale > 1 时（如 320×480 设备 scale≈1.33），visualH > panelH，缩放后视觉位置从顶部偏移，内容整体偏下。
4. 240×320 设备 scale=1 不受影响，因此此 bug 只在较高分辨率设备上暴露。

正确做法（`NokiaKeyBindFragment` / `NokiaKeyBindWizardFragment` 已有正确实现）：

```java
// 在 onViewCreated 中 scaleMidContent 之后
host.scaleMidContent(view, true);

view.post(() -> {
    View panel = (View) view.getParent();
    if (panel == null || panel.getHeight() <= 0 || view.getHeight() <= 0) return;
    float scale = host.getScale();
    int panelH = panel.getHeight();
    int targetH = Math.round(panelH / scale);  // 使缩放后视觉高恰好 = panelH
    ViewGroup.LayoutParams lp = view.getLayoutParams();
    if (lp.height != targetH) {
        lp.height = targetH;
        view.setLayoutParams(lp);
        // 高度变化后重新缩放：此时 visualH == panelH，不触发缩小分支
        view.post(() -> host.scaleMidContent(view, true));
    }
});
```

- **禁止**：`match_parent` + `topAlign=true` 的 Fragment 不加此调整；自行计算 scale（走 `getScale()` 单一来源）。
- 已修复的案例：`NokiaKeyBindFragment`（已有）、`NokiaKeyBindWizardFragment`（补上后修复 320×480 偏下）。

### Fragment 根布局宽度必须固定 240dp，禁止 match_parent（重要）

**所有 Fragment 根布局宽度必须固定为 `240dp`（设计基准），禁止用 `match_parent`。** 否则在 scale>1 的设备（如 320×480）上会被 `scaleMidContent` 二次放大导致**横向溢出屏幕**，右侧内容（网格第 3 列等）被推出屏幕之外。

背景与原因（2026-08 实测 bug：百宝箱「应用程序」第 3 列跑到屏幕右侧）：

1. 项目架构为「240dp 设计基准 + 运行时整体缩放」：`NokiaBaseActivity.scaleMidContent()` 对 Fragment 根视图执行 `setScaleX/Y(scale)`，其中 `scale = 屏宽dp / 240`。
2. 根宽 `match_parent` 时，内容**已经占满整个 midPanel 全宽**（如 320×480 设备即 320px），`scaleMidContent` 再乘 `scale`：
   - 320×480（density 吸附到 1.0）scale=1.333 → `320 × 1.333 ≈ 427px > 320px`，右侧约 107px 溢出屏幕，网格第 3 列（qq2009）被推出右缘。
   - 240×320 设备 scale=1，`scaleMidContent` 因 `Math.abs(scale-1) < 0.001` 跳过缩放，正常。故仅较高分辨率设备复现。
3. 根宽固定 `240dp` 后，`240 × scale = 屏幕宽`，正好铺满，不再横向溢出。

正确做法：

- Fragment 根布局 `android:layout_width="240dp"`，与功能表 / 桌面 / 设置 / 向导 / 组件选择等全部 Fragment 一致。
- **高度可用 `match_parent`**（配合 ScrollView 纵向滚动，如桌面、百宝箱），仅宽度必须 240dp。
- 行内均分（如网格 cell 的 `0dp + weight=1`）按 240dp 计算，随后被整体缩放，逻辑不变。
- **禁止**：根宽用 `match_parent`；在 240 基准之外再写宽度（如写死 dp 撑满屏）。
- 已修复的案例：`fragment_nokia_box.xml`（根宽 `match_parent` → `240dp`，修复 320×480 横向溢出）。

### 新界面 Checklist

新增或修改任何 nokia 界面时，逐项自查：

- [ ] 尺寸换算走 `NokiaDimens.dp()`，无裸 `(int)(v*density)`
- [ ] 布局无 px 写死值（LayoutParams 高度/宽度、padding、margin 等）
- [ ] Fragment 根布局高度为 `match_parent`（非 262dp）
- [ ] Fragment 根布局**宽度固定 `240dp`**（非 `match_parent`，否则 scale>1 设备横向溢出）
- [ ] 弹窗关键尺寸引用 `dimens.xml`（非硬编码 28dp/14sp/12sp）
- [ ] 点线分隔线用 `NokiaDashedLineDrawable(getResources(), ...)`（非 XML shape dash）
- [ ] 网格页面行数走实测 panelH 反推（`getMidPanelHeight()`），非估算公式
- [ ] 网格行高均分拉伸，非写死固定 dp
- [ ] scale 走 `getScale()` 单一来源，不自算
- [ ] `match_parent` 根布局 + `topAlign=true` 的 Fragment 需补 `view.post` 动态高度调整
- [ ] 在 **240×320（4a24ecf）** 和 **320×480（tcpip）** 两台真机上截图验证
- [ ] 验证重点：点线清晰、无右侧缝隙、列表最后一项不被底栏遮挡、弹窗比例合适、网格行不裁切也不留白





## 设备说明
- 通过tcpip链接的设备是 320*480分辨率的，可以直接通过adb安装应用。
- 通过usb链接的，adb查看名为jz5dauzlu8euw4e6 的设备，是小米设备，是 现代 16:9 及以上比例的长条形屏幕，不支持直接通过adb安装应用，你推送到 `adb -s jz5dauzlu8euw4e6 push "d:/project/nokia_desktop/app/build/outputs/apk/open/debug/J2ME_Loader-1.8.2-open-debug.apk" /sdcard/Download/J2ME_Loader-open-debug.apk` 设备文件中即可。我会来安装。这个设备当然也支持adb 查看日志等操作，只是不支持直接安装。

- 设备名为 4a24ecf 的是 240*320分辨率的设备，安卓4.4.


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

**Product flavors (`app/build.gradle`).** `play`/`open`/`fdroid`/`dev` are the full emulator (`FULL_EMULATOR=true`), differing only in distribution channel, `versionNameSuffix`, and proguard files; `open` is the non-Play build and the one to use for local development. `midlet` is special: `FULL_EMULATOR=false`, and instead of building the emulator it builds a standalone Android APK *from a J2ME app's sources* (read from `src/midlet/resources/MIDLET-META-INF/MANIFEST.MF`). `dev` calls `generateVersionCode()` (git rev-list) at configuration time — a non-git working copy falls back to version code 1 (already patched in `app/build.gradle`).

**Release signing.** `signingConfigs.release` reads `keystore.properties` (when not running on the Bitrise CI). Debug builds use the default debug key; release builds need the local `test.jks`.

