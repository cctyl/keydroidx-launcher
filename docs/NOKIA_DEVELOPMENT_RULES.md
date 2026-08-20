# 原键桌面开发规范（详细版）

> 本文件是 `CODEBUDDY.md` 的扩展：收录原键桌面的详细开发规范。这些是**反复踩坑总结的硬性规则**，新增 / 修改任何诺基亚界面、弹窗、按键逻辑前，务必对照本文件自查。主文件概览见 `CODEBUDDY.md`。

## 图标与矢量字体规范（Material Icons）（重要）

**项目中除「第三方应用/J2ME应用真实图标」外，所有系统小组件、快捷开关、设置菜单列表项、通用弹窗选项的图标一律统一使用内置的 Google Material Icons 矢量字体（`NokiaIcons`），禁止新增零散的 PNG / XML 图标。**

背景与原因：
1. 传统 XML 矢量图和 PNG 图片在不同屏幕分辨率（如 240×320、320×480）和 Android 4.4 旧版本上容易出现失真、模糊、留白比例不一的问题。
2. 阿里 Iconfont 等零散下载方案难以维护，且每次新增组件或开关都需要手动检索、下载、切图并重新打包。
3. 项目内置了 Google 官方 **Material Icons** 字体库（`app/src/main/assets/fonts/MaterialIcons-Regular.ttf`），包含 2500+ 个系统级、硬件级与操作类矢量图标，体积仅约 350KB。
4. `NokiaIcons` 工具类在运行时根据目标尺寸（DP）和颜色直接将字体 Glyph 1:1 光栅化为高质量 BitmapDrawable，自带像素级居中与 LRU 缓存，在任何分辨率下边缘均极其锐利。

### 标准调用方式

#### 1. 小组件 / 列表 / 控件中直接使用
```java
// 获取指定 Unicode 字符、指定颜色、指定 dp 尺寸的 Drawable：
Drawable icon = NokiaIcons.get(context, NokiaIcons.ICON_MEMORY, 0xFFFFFFFF, 20);
imageView.setImageDrawable(icon);
```

#### 2. 通用选项弹窗（`NokiaOptionsDialog`）中使用
`OptionItem` 支持直接传入 Material Icons Unicode 字符串（优先）或资源 ID：
```java
// 传入字体图标编码
items.add(new NokiaOptionsDialog.OptionItem(
    NokiaIcons.ICON_EDIT,  // Material Icons Unicode
    "更换应用",
    true,
    false,
    () -> doEdit()
));
```

#### 3. 常用尺寸规范
- **桌面单行小组件图标**：统一标准为 **`20dp`**（白色 `0xFFFFFFFF`），留白间距 `6dp`；
- **顶部快捷开关栏图标**：统一标准为 **`18dp`**；
- **设置菜单 / 二级子页列表行图标**：统一标准为 **`22dp`**；
- **通用弹窗选项列表图标**：统一标准为 **`18dp`**。

#### 4. 图标命名与扩充
所有图标 Unicode 常量集中定义在 `ru.playsoftware.j2meloader.nokia.NokiaIcons` 中。如需新增图标，只需查阅 Material Icons 官方编码表，向 `NokiaIcons.java` 添加一行常量即可，无需修改资源文件。


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
4. **layer-list 的 `android:width` / `android:height` 是 API 23+ 属性**。Android 4.4 上会被**静默忽略**（不报错），导致所有图层被拉伸成整块叠在一起：信号 4 根竖条合成一整块、电池格子被最后一层盖掉（看起来图标「消失」/「变灰」）。**禁止**在 `<item>` 上用 `android:width/height` 控制图层尺寸（也不要指望 item `gravity` + shape `<size>`——4.4 的 layer bounds 是「layer-list 区域 inset 后的整块」，shape 会填满整块，`<size>` 只影响 intrinsic、不影响绘制尺寸）。**正确做法**：用 `android:left/top/right/bottom` inset 精确控制每个 layer 的绘制区域 = 图层期望大小（如 3×4dp 信号条、2×5dp 电池格），shape 在 inset 后的区域内填充；layer-list 整体大小 = 所有 layer 的 `inset + 图形宽高` 之和的最大值（如信号 15×7dp、电池 18×9dp），ImageView 用固定宽高 + `fitCenter` 缩放。已按此修复：`ic_signal_0..4`、`ic_battery_0/25/50/75/100`、`ic_nokia_battery`（这些文件是标准范例，新增多格图标照抄此结构）。
5. **`Canvas` 的部分 float 重载是 API 21+ 才有，4.4 上运行时调用直接 `NoSuchMethodError` 闪退**。例如 `drawRoundRect(float, float, float, float, float, float, Paint)`（7 参数 float 版）是 API 21 才引入；API 19 只有 `drawRoundRect(RectF, float, float, Paint)`。若在自绘 View 的 `onDraw()` 里调用（页面一打开即执行绘制），会像本次「高级设置 → 电源键拦截开关」一样整个进程闪退——这与「VFY 无害告警」不同，**方法被执行到就会崩**。**正确做法**：自绘时只用 API 1 就有的重载——`drawRoundRect(RectF, rx, ry, Paint)`、`drawRect(RectF, Paint)` / `drawRect(int, int, int, int, Paint)`、`drawCircle(float, float, float, Paint)`（circle 的 float 版 API 1 就有，安全）；需要 float 版本时构造/缓存一个 `RectF`（`onDraw` 里复用字段，不要每帧 `new`）。已按此修复：`NokiaAdvancedSettingsFragment$NokiaSwitchView`（`drawRoundRect(RectF, ...)` + 缓存 `trackRect`）。新写自绘控件时，先查 Android API 参考确认方法的最低 API 级别。

通用原则：所有 API 22+ 的类/方法引用都要 `SDK_INT` 守卫；Dalvik 验证器对运行时不执行到的高版本类引用只会打 `VFY Could not find class '...'` **无害告警**，不算崩溃；**但运行时会实际执行到的方法/重载必须保证 API 19 可用，否则直接 `NoSuchMethodError`**。低版本设备（尤其 4.4）建议用「修一处→构建→装到 4a24ecf 实测」的迭代方式，以设备真实崩溃为准逐个修，而非盲目猜测。

## 双分辨率适配规范（重要）

### 适配目标分级

| 优先级 | 分辨率 | 设备 | 验收标准 |
|---|---|---|---|
| **主适配** | 240×320 | 4a24ecf（Android 4.4，density 120→160） | 所有界面不崩、不裁切、不错位，点线清晰 |
| **主适配** | 320×480 | tcpip 设备（density 136→160） | 同上，且顶栏/中间区比例可接受 |
| **次要适配（兜底）** | 16:9 长屏 | jz5dauzlu8euw4e6 | 不崩、不变形、不裁切、可正常操作即可 |

### 适配架构（响应式原生 DP 模式）

项目已全面升级为 **「响应式原生 DP 渲染」** 体系（弃用旧版 `setScaleX/Y` 整体放大架构，避免 GPU 二次插值模糊与纵向拔高）：

- **无损渲染**：顶栏、中间内容区、底栏均采用原生物理分辨率逐像素直接光栅化绘制，`getScale()` 恒返回 `1.0f`。
- **根宽规范**：所有 Fragment 根布局宽度统一使用 `android:layout_width="match_parent"`，由系统自然填满屏幕宽度。
- **正比例守卫**：图标保持 1:1 正方形标准比例（如快捷栏 36×34dp、开关栏 36×32dp、网格图标 48×48dp），多余高度由壁纸与可滚动区域自然吸收。
- **响应式均分**：功能表与百宝箱 3 列网格采用 `weight=1` 响应式均分宽度，在 240dp / 320dp / 480dp 等各种屏幕上均呈现精致规整的排布。

### 页面 Fragment 必须继承 NokiaPageFragment（强制，防漏缩放）

**所有中间内容区的页面 Fragment 一律继承 `NokiaPageFragment`（模板方法基类），禁止直接 `extends Fragment implements NokiaPage`。** 基类用 `final` 方法固化每个页面都必须执行的样板，子类**无法绕过**：

- `final onCreateView` → 自动 inflate 子类声明的 `getLayoutRes()` 布局；
- `final onViewCreated` → 自动执行 `scaleMidContent` 缩放 + `fixMidContentHeight` 高度调整（topAlign 时）+ 壁纸 + `refreshPageBar()` 底栏装配，随后调用子类 `onPageCreated()` 钩子。

子类只需实现：
- `getLayoutRes()`：返回布局（根宽固定 240dp、根高 match_parent）；
- `onPageCreated(View, Bundle)`：自己的初始化（findViewById / 建列表 / 设焦点等）；
- 特殊页面覆写 `isTopAlign()`（默认 true；百宝箱等居中页返回 false）与 `getWallpaperRes()`（默认 `bg_nokia_menu`；桌面 `bg_nokia_desktop`、百宝箱 `bg_nokia_box`）。

> 历史教训：缩放/高度/壁纸/底栏这四件套曾被每个页面手抄 17 处，漏抄/写错一处就出「右侧露缝/内容偏下」类 bug。收进基类 final 方法后，新页面想漏都不可能（留着旧 `onCreateView`/`onViewCreated` 覆写会直接编译失败）。
>
> 已迁移（2026-08）：`NokiaDesktopFragment`、`NokiaMenuFragment`、`NokiaBoxFragment`、`NokiaDesktopSettingsFragment`、`NokiaShortcutSettingsFragment`、`NokiaWidgetSettingsFragment`、`NokiaWidgetTypePickerFragment`、`NokiaWidgetActivityNameFragment`、`NokiaWidgetActivityPickerFragment`、`NokiaWidgetAppPickerFragment`、`NokiaWidgetUrlEditFragment`、`NokiaBackgroundManagerFragment`、`NokiaKeyBindFragment`、`NokiaKeyBindWizardFragment`、`ShizukuFragment`。

### 纵向列表与文章滚动页基类（强制，防漏按键导航与滚动）

项目提供了通用基类以规范方向键交互与滚动体验：

1. **纵向单列菜单/选项列表页**：必须继承 **`NokiaListPageFragment`**（循环导航基类），禁止直接继承 `NokiaPageFragment` 再手抄焦点三件套。
   - 收编了焦点管理与自动滚动（`setFocusIndex`、`clearFocusBackground`、`applyFocusBackground`、`scrollToVisible` 等）；
   - 固化为**循环导航**（顶部按上跳到末尾，底部按下跳到开头）；
   - 子类只需在 `onPageCreated` 中填充 `itemViews[]` 和 `listScroll`。
   - 子类可通过覆写 `onLeftRight(int)` 处理左右切换页签。

   **已迁移：**
   - `NokiaDesktopSettingsFragment` → 桌面设置主菜单
   - `NokiaSettingsGroupFragment` → 设置二级分组页
   - `NokiaAdvancedSettingsFragment` → 高级设置
   - `NokiaPowerInterceptFragment` → 电源键拦截设置
   - `NokiaShortcutSettingsFragment` → 快捷栏设置
   - `ShizukuFragment` → mini_shizuku 页
   - `ShizukuRootFragment` → root 激活页

2. **纯展示/说明/富文本类可滚动页面**：必须继承 **`NokiaScrollPageFragment`**（如 `ShizukuAdbFragment` 等）。
   - 内部固化了统一的上下方向键平滑滚动（`smoothScrollBy`），步长按视口高度的 `45%` 动态计算（大步翻滚），触底或触顶自动拦截；
   - **重写钩子规范（血泪教训）**：子类**严禁**直接重写 `onPageCreated`（否则会彻底截断父类的 `pageScrollView` 初始化导致其为 `null`，方向键完全失效），子类**必须**重写 `onScrollPageCreated(View view, Bundle savedInstanceState)` 钩子进行视图初始化；
   - **禁止文本获取焦点**：展示型说明布局中的 `TextView` **严禁**添加 `android:textIsSelectable="true"`，否则原生 FocusFinder 会将方向键截获到文本框内部逐字移动光标，导致最外层的 `ScrollView` 完全无法接收到方向键滚动事件。

3. **图文带交互按钮混合页面**（如「关于」页面 `NokiaAboutFragment`）：
   - 继承 `NokiaScrollPageFragment`，并重写 `onScrollPageCreated` 初始化控件；
   - 支持高亮选中项并在方向键移动焦点时自动跟随滚动（`smoothScrollTo` 让目标视图完全进入可视区）；
   - 当焦点已达底部交互项后，继续按「下」方向键无缝切换为文章浏览模式（清除按钮高亮），后续「下」方向键直接调用 `scrollDown()` 驱动 `ScrollView` 继续平滑滚动到底部阅读致谢和许可；按「上」方向键优先向上滚动页面，滚回顶部附近时恢复按钮高亮与焦点。

**不属于列表/文章基类的特殊页面（网格/分页/多模式）：** `NokiaMenuFragment`、`NokiaBoxFragment`、`NokiaWidgetAppPickerFragment`、`NokiaWidgetTypePickerFragment`、`NokiaWidgetActivityNameFragment`、`NokiaWidgetActivityPickerFragment`、`NokiaWidgetUrlEditFragment`、`NokiaKeyBindWizardFragment`、`NokiaKeyBindFragment`、`NokiaBackgroundManagerFragment`、`NokiaWidgetSettingsFragment`。这些直接继承 `NokiaPageFragment` 并自行管理各自的焦点与翻页/网格计算。

#### ScrollView 内部嵌套 View 自动滚动规范（血泪教训）

在网格类或自定义嵌套布局中（如 `ScrollView -> LinearLayout (Container) -> LinearLayout (Row) -> LinearLayout (Cell)`）：
- **严禁直接使用 `cell.getTop()`/`cell.getBottom()` 计算滚动位置**。因为 `cell.getTop()` 获取到的是它在直接父容器（Row）内部的局部相对坐标（永远为 0），会导致 `itemBottom > scrollY + svHeight` 判断永远为 false，方向键移动焦点时滚动条完全不动，表现为“只能触屏滑动、按键无法滚到下方内容”。
- **必须累加所有父级容器的偏移量**，计算相对于 `ScrollView` 的绝对 `top`：
  ```java
  int itemTop = 0;
  View current = item;
  while (current != null && current != scrollView && current.getParent() instanceof View) {
      itemTop += current.getTop();
      current = (View) current.getParent();
  }
  int itemBottom = itemTop + item.getHeight();
  ```
- 计算出正确的 `itemTop` 与 `itemBottom` 后，再与 `scrollView.getScrollY()` 及 `scrollView.getHeight()` 比较执行 `scrollView.smoothScrollTo(0, ...)`。

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

### topAlign 缩放 + 根高不匹配 panelH 的二次缩放陷阱（重要）

**任何调用 `scaleMidContent(view, true)`（topAlign）的 Fragment，根布局高度都必须用 `match_parent`，并补充动态高度调整（推荐调用 `host.fixMidContentHeight(view, true)`），否则在 scale ≠ 1 的设备上不是「内容整体偏下」就是「右侧/底部露缝」。**

背景与原因（触发条件有两条，标题旧版只写了 match_parent 那条，2026-08 桌面设置系列再次踩坑）：

1. **根高 `wrap_content`（内容矮于 panelH）→ 触发二次缩小分支，右侧露缝。** `scaleMidContent` 中：`visualH = contentH × scale`，若 `visualH > panelH` 且 `contentH != panelH`（wrap_content 必然不等），则 `finalScale = panelH / contentH < scale`，**宽度随比例同步缩小** → 240dp×finalScale < 屏幕宽 → 右侧露缝。240×320（scale=1）下内容高通常 ≤ panelH 不触发，掩盖 bug；320×480（scale>1）一放大就露馅。这正是「桌面设置/快捷栏设置/组件设置」等 9 个列表页的问题。
2. **根高 `match_parent` → 跳过二次缩小但视觉偏下。** 内容高 = panelH，`contentFillsPanel=true` 跳过缩小，宽度铺满；但 `topAlign` + `setScaleX/Y(scale)` 后视觉高 = panelH × scale > panelH，内容整体偏下。

正确做法（统一走 `NokiaBaseActivity.fixMidContentHeight(view, topAlign)`，封装了 `NokiaKeyBindFragment` 验证过的逻辑）：

```java
// 在 onViewCreated 中 scaleMidContent 之后（两处 topAlign 传值必须一致）
host.scaleMidContent(view, true);
host.fixMidContentHeight(view, true);
```

```java
// NokiaBaseActivity 内已实现的公共方法（新 Fragment 不必手写 view.post）：
public void fixMidContentHeight(final View content, final boolean topAlign) {
    content.post(() -> {
        View panel = (View) content.getParent();
        if (panel == null || panel.getHeight() <= 0 || content.getHeight() <= 0) return;
        float scale = getScale();                       // 走 getScale() 单一来源
        int panelH = panel.getHeight();
        int targetH = Math.round(panelH / scale);       // 使缩放后视觉高恰好 = panelH
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        if (lp.height != targetH) {
            lp.height = targetH;
            content.setLayoutParams(lp);
            content.post(() -> scaleMidContent(content, topAlign)); // 重新缩放，visualH==panelH 不缩宽度
        }
    });
}
```

- **禁止**：根高 `wrap_content`（矮内容触发缩小分支露缝）；`match_parent` + `topAlign=true` 不加高度调整；自行计算 scale（走 `getScale()` 单一来源）。
- 已修复的案例：`NokiaKeyBindFragment`、`NokiaKeyBindWizardFragment`（早期），`NokiaDesktopSettingsFragment`、`NokiaShortcutSettingsFragment`、`NokiaWidgetSettingsFragment`、`NokiaWidgetTypePickerFragment`、`NokiaWidgetActivityNameFragment`、`NokiaWidgetActivityPickerFragment`、`NokiaWidgetAppPickerFragment`、`NokiaWidgetUrlEditFragment`、`NokiaBackgroundManagerFragment`（2026-08 一次性对齐修复）。

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

- [ ] 页面 Fragment **继承 `NokiaPageFragment`**（禁止裸 `extends Fragment implements NokiaPage`），且未覆写 `onCreateView`/`onViewCreated`（final，覆写即编译错）
- [ ] 纵向列表页**继承 `NokiaListPageFragment`**（禁止直接继承 `NokiaPageFragment` 再手抄焦点三件套），且未覆写 `onDirection`（final，循环导航强制）
- [ ] 已正确实现 `getLayoutRes()` 与 `onPageCreated()`；特殊页面按需覆写 `isTopAlign()`/`getWallpaperRes()`
- [ ] 尺寸换算走 `NokiaDimens.dp()`，无裸 `(int)(v*density)`
- [ ] 布局无 px 写死值（LayoutParams 高度/宽度、padding、margin 等）
- [ ] Fragment 根布局高度为 `match_parent`（非 262dp）
- [ ] Fragment 根布局**宽度为 `match_parent`**（响应式原生 DP 铺满）
- [ ] 弹窗关键尺寸引用 `dimens.xml`（非硬编码 28dp/14sp/12sp）
- [ ] 点线分隔线用 `NokiaDashedLineDrawable(getResources(), ...)`（非 XML shape dash）
- [ ] 网格页面行数走实测 panelH 反推（`getMidPanelHeight()`），非估算公式
- [ ] 网格行高均分拉伸，非写死固定 dp
- [ ] scale 走 `getScale()`（响应式模式下恒为 1.0f）
- [ ] 在 **240×320（4a24ecf）** 和 **320×480（tcpip）** 两台真机上截图验证
- [ ] 验证重点：原生矢量清晰无模糊、无横向溢出与右侧缝隙、图标保持 1:1 正比例、列表最后一项不被底栏遮挡、弹窗比例合适、网格行不裁切也不留白


## 设备说明
- 通过tcpip链接的设备是 320*480分辨率的，可以直接通过adb安装应用。
- 通过usb链接的，adb查看名为jz5dauzlu8euw4e6 的设备，是小米设备，是 现代 16:9 及以上比例的长条形屏幕，不支持直接通过adb安装应用，你推送到 `adb -s jz5dauzlu8euw4e6 push "d:/project/keydroidx_ecosystem/keydroidx-launcher/app/build/outputs/apk/open/debug/KeydroidXLauncher-1.2-open-debug.apk" /sdcard/Download/KeydroidXLauncher-open-debug.apk` 设备文件中即可。我会来安装。这个设备当然也支持adb 查看日志等操作，只是不支持直接安装。

- 设备名为 4a24ecf 的是 240*320分辨率的设备，安卓4.4.

### 11. 全局主题配色与规范约束（NokiaTheme）

为了确保全系统（桌面、功能表、设置、二级页面、弹窗）在切换主题（如曜石黑、翡翠绿、酒红、琥珀金等）时 100% 视觉联动，必须遵守以下硬性规范：

1. **禁止在 Fragment XML 根布局设置不透明背景**：
   - 根布局一律设置 `android:background="@android:color/transparent"`（或不设）；
   - 基类 `NokiaPageFragment.onViewCreated` 会自动执行 `view.setBackgroundResource(0)` 强力保底，确保底层 `NokiaTheme` 主题背景壁纸完美透出。
2. **禁止在布局中写死特定主题色彩（如蓝色 `#2a4a7a`、`#64b5f6`）**：
   - 分隔线使用半透明中性白（如 `#20FFFFFF`）或点线 `NokiaDashedLineDrawable`，确保在任何深色/彩色主题下均自然融合；
   - 文本小标题与说明使用 `#E0FFFFFF`（主文字）或 `#88FFFFFF`（次级文字）。
3. **焦点选中高亮统一来源**：
   - **禁止** 引用任何硬编码的静态选中 Drawable；
   - **必须** 统一调用 `NokiaTheme.createSelectionDrawable(context, radiusDp)` 获取动态半透明焦点色。
### 12. Fragment 生命周期与异步/广播回调上下文安全规范（重要）

**在 Fragment 的异步回调、广播接收器（BroadcastReceiver）、Handler / Runnable 以及动态 UI 构建逻辑中，禁止直接使用严格断言的 `requireContext()`，必须使用可空的 `getContext()` / `view.getContext()` 并做好生命周期守卫。**

背景与原因（2026-08 实测 bug：切回桌面或按挂机键返回时应用抛出 `IllegalStateException` 崩溃）：

1. 当用户按物理挂机键（或系统 Home 键）返回桌面，或者桌面 Activity 在 `goHome()` 中执行 Fragment 事务时，旧的 `NokiaDesktopFragment` 可能正处于销毁 / 分离（`detach` / `onDestroyView`）过渡期。
2. 此时若快捷开关广播（如 `toggleStateReceiver`）收到系统状态变化（如 WiFi/蓝牙状态改变），或者异步任务（如扫描应用、快捷方式更新）完成返回主线程，并在回调中调用 `rebuildQuickToggleBar()` / `createQuickToggleCell()`：
   - 若内部直接调用 `requireContext()`，Fragment 因已处于 detached 状态会立刻抛出 `java.lang.IllegalStateException: Fragment ... not attached to a context` 致命异常；
   - Android 系统捕获该崩溃后，会认为当前 Launcher 异常退出，进而触发 Fallback 机制，强制唤出系统的 `ResolverActivity`（提示“选择主屏幕应用”），造成“按挂机键桌面黑屏/反复弹出选择桌面”的连锁故障。

正确做法：

- **异步 / 广播回调生命周期守卫**：
  ```java
  if (!isAdded() || getContext() == null || getView() == null) {
      return;
  }
  ```
- **动态创建 View 的上下文获取**：
  优先使用传入的 `View` 自身 Context（如 `container.getContext()`），或者 `Context ctx = getContext(); if (ctx == null) return;`。
- **Activity 返回桌面事务安全（`goHome`）**：
  - 判断当前 midPanel 是否已是 `NokiaDesktopFragment` 实例，若是则复用，避免不必要的重复 `replace` 和多实例竞争；
  - 必须使用 `commitAllowingStateLoss()` 和 `popBackStackImmediate()`。

### 13. Launcher 桌面 Intent Filter 规范（重要）

在 `AndroidManifest.xml` 中，桌面启动器的 Intent Filter 必须按照 Android 规范将 `category.HOME` 与 `category.LAUNCHER` 独立拆分声明：

```xml
<!-- 1. 标准 Launcher 默认桌面入口（包含 DEFAULT，供系统 HOME 调度） -->
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.HOME" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>

<!-- 2. 应用列表/抽屉独立展示入口 -->
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
```

**禁止** 将 `category.HOME`、`category.DEFAULT` 与 `category.LAUNCHER` 混合在同一个 `<intent-filter>` 标签内。混合声明会导致部分 Android 系统（特别是 Android 10+ 的 RoleManager / PreferredActivity 解析策略）产生匹配歧义，导致系统无法持久化保存默认桌面设置，从而在按 Home / 挂机键时反复弹出“选择主屏幕应用”。

