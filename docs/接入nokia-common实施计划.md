# 接入 nokia-common 实施计划

> **状态**：已评审（决策已拍板），待实施
> **分支**：`feature/common-module`
> **前置文档**：`keydroidx-core/docs/12-architecture-layering.md`（注意：该文档已过时，common 实际范围更大，见本文 §二）
> **调研日期**：2026-08-28

---

## 一、背景与目标

桌面（keydroidx-launcher）与 `keydroidx-core` 仓库的 `nokia-common` 模块存在大量同职责组件。core 仓库的拆分已完成：`nokia-common`（纯基础库，含主题/日志/字体/图标/尺寸/Drawable/page 体系/dialog/focus/NokiaBaseActivity/feedback 协议层）+ `nokia-key-core`（薄壳，@Deprecated 桥接类 extends common，真正自有的仅 NokiaClient/NokiaKeyBinding/改键向导/Activity 托管壳）。

目标：launcher 依赖 `nokia-common`（**不依赖 nokia-key-core**），逐步删除桌面 nokia 包下的重复组件，全生态底层代码单一源码。

红线（全程不变）：SP 文件名与 key（`nokia_desktop_settings`、`nokia_key_bindings`）、keyprovider uri、`ACTION_*` 常量值（0-8）——保老用户配置与独立 App 跨进程协议。

---

## 二、调研结论摘要（2026-08-28 实测）

### common 现状（io.github.cctyl.nokia.common.*，坐标 nokia-common:1.0.0）

- 已含：NokiaTheme（13 字段 ThemeDef 超集 + ThemeProvider 注入）、NokiaLog、NokiaFontManager、NokiaIcons（143 常量，**launcher 版 64 常量的超集**）、NokiaDimens、NokiaBatteryDrawable、NokiaDashedLineDrawable、NokiaBaseActivity（零 Client 依赖，implements NokiaPageHost + KeyResolver）、ui/page/*、ui/dialog/*（NokiaOptionsDialog/NokiaConfirmDialog，Dialog+builder 形态）、ui/focus/*、model/*（NokiaKeyAction/KeyResolver/DefaultKeyResolver）、contract/NokiaProviderContract、feedback/*（HTTP+HMAC-SHA256，KdfbUploader 已废弃）、ui/about/*。
- 发布：已配 maven-publish（`publishReleasePublicationToMavenLocal`）。

### 与 launcher 的实测分叉点

| 项 | 结论 |
|---|---|
| 调色板 | common 与 launcher 六套主题**色值不同**（如 classic_blue bg 系列 `0xFF001428` vs `0xFF0D1B3E`）→ 见 D3 |
| NokiaLog | common 版是重构超集（v 级/分级开关/崩溃捕获）；launcher 版独有 keyName（按键域）、setEnabled/isEnabled（**无调用方，死代码**）、fileCrash(String,Throwable) → 见 D6 |
| NokiaIcons | common 是 launcher 超集，可直接替换（实施时逐常量核对 unicode 值） |
| NokiaFontManager | common 缺自定义字体（importFontFromUri/getAvailableFonts/deleteCustomFont/FONT_ID_CUSTOM_PREFIX）→ 见 D5 |
| NokiaOptionsDialog | 两版 API 完全不同；桌面版有 `show(fm,title,items,int[] keyCodes)` 注入模式，`:midlet` 进程 MicroActivity/AbstractSoftKeysBar 依赖 → 见 D4 |
| 页面契约 | ACTION_* 常量值 0-8 两边一致；桌面 Fragment 强转 NokiaDesktopActivity，common 走 NokiaPageHost 接口 |
| NokiaBaseActivity | common 版无 240dp 缩放、SOFT_RIGHT 兜底 finish() → 桌面保留自有版 |
| minSdk | core=19，launcher=14 → 见 D1 |

### nokia 包外引用面（替换时必须同步）

- `javax.microedition.lcdui`：Font / Screen / TextFieldImpl / commands.ScreenSoftBar / commands.AbstractSoftKeysBar
- `javax.microedition.shell.MicroActivity`
- `ru.playsoftware.j2meloader`：EmulatorApplication、Config/ConfigActivity/各 Dialog、settings.KeyMapperActivity、info 四个 Dialog
- `ru.woesss.j2me.installer`：InstallerDialog / NokiaInstallerDialog

---

## 三、决策记录（已拍板）

### D1 minSdk 对齐 —— ✅ 已决：launcher 升 19

launcher `MIN_SDK` 14 → 19。桌面目标设备即 Android 4.4（API 19）；14 是 J2ME-Loader 遗留。代码中 `SDK_INT < 19` 的兼容分支**只确认不清理**，避免范围蔓延。

### D2 依赖方式 —— ✅ 已决：坐标声明 + includeBuild 联调替换

```groovy
// app/build.gradle（始终不变，与独立 App 接入形态一致）
implementation 'io.github.cctyl.nokia:nokia-common:1.0.0'

// settings.gradle（联调期存在，common 稳定后删除）
includeBuild('../keydroidx-core')
```

Gradle 复合构建会自动把同坐标依赖替换为本地源码模块（dependency substitution）。
**理由**：纯 mavenLocal 每次改 common 都要重新 publish，联调摩擦大且换机器不可重复；纯 includeBuild 与独立 App 真实接入形态不一致。本方案两者优点兼得：依赖声明始终与独立 App 一致，切换只动 settings.gradle 一行。接入期 common 会频繁改（调色板对齐等），用源码联动；common 稳定后删 includeBuild 一行即回到 mavenLocal/远程坐标形态（settings.gradle 已有 `mavenLocal()` 兜底）。

### D3 调色板 —— ✅ 已决：以桌面色值为准

core 仓库修改 common 六套主题色值对齐桌面现值（桌面是主题的 Provider 宿主与定义方；独立 App 只用 primary/dark/text/card 字段，bg\*/softKey\* 改动不影响它们），发 common 1.0.1，launcher 升依赖版本。

### D4 NokiaOptionsDialog —— ✅ 已决：本期不替换

桌面保留自有 NokiaOptionsDialog（仅内部逐步换用 common 的 Theme/Font/Log/Dimens）。
**后续议项（另行立项，属 core 演进）**：在 common 增补"DialogFragment + 键码注入"形态的 OptionsDialog（支持 `show(fm, title, items, int[] keyCodes)`，供 `:midlet` 进程等非 NokiaPageHost 宿主使用），桌面再统一切换。届时需同步替换 javax.microedition 包内的 4 处调用（MicroActivity、AbstractSoftKeysBar 等）。

### D5 NokiaFontManager 自定义字体 —— ✅ 已决：桌面保留薄封装

自定义字体导入/列表/删除（importFontFromUri/getAvailableFonts/deleteCustomFont/FONT_ID_CUSTOM_PREFIX/FontItem）留在桌面薄封装类中；typeface 加载与视图树应用委托 common `applyToViewTree`；保留 `getGlobalTypeface(ctx)` 门面供 midlet 进程 `javax.microedition.lcdui.Font` 使用。
**后续议项**：自定义字体能力贡献回 common。

### D6 NokiaLog —— ✅ 已决：选 common 版，launcher 版删除

common 版是后出的重构版、能力超集（v 级/分级持久化/installCrashHandler/7天轮转/异步写/自定义 TAG）。launcher 版独有项处理：

| launcher 独有 | 处理 |
|---|---|
| `keyName(keyCode)`（24 处调用，按键域） | 移到 `NokiaKeyBinding` |
| `setEnabled/isEnabled` | 全仓库无调用方，死代码，删 |
| `fileCrash(String, Throwable)`（EmulatorApplication 一处） | 改调 common `fileCrash(Thread, Throwable)` |

同步方式：EmulatorApplication 调 common `init(ctx)` + `setTag("NokiaDesktop")` 保持 logcat TAG 不变；600+ 调用点 `d(sub,msg)` 与 common `d(tag,msg)` 形参兼容，纯机械换 import；桌面 `log_file_enabled` 设置项保留、内部代理到 common 分级控制（`nokia_desktop_settings` 的 key 不变）。

### D7 feedback 反馈 —— ✅ 已决：直接接入

桌面原本无反馈功能，本期接入。密钥从 `keydroidx-music/local.properties` 原样复制 `FEEDBACK_UPLOAD_URL` / `FEEDBACK_SECRET_KEY` 两行到 launcher `local.properties`（已 git 忽略），`app/build.gradle` 注入 BuildConfig，EmulatorApplication `NokiaFeedback.init(config)`。反馈页用 common `NokiaFeedbackFragment`，入口加在设置组页/关于页。

---

## 四、实施计划

> 约定：**每个组件替换 = 一个独立 commit**，随时可单点 revert。每步完成后：编译 → 装机 → 该组件走查清单 → 截图对比基线。

### 阶段 0：基线准备（不动代码）

1. **截图基线**（真机 adb，存档到分支外目录）：
   - 桌面主页（状态栏/通知区/快捷栏）；功能表、百宝箱（列表+网格焦点态）
   - 设置主页、主题设置（6 套主题各截桌面+功能表）、字体设置（3 套字体+自定义缩放）
   - 快捷栏编辑、widget 设置各页、按键绑定页+向导、后台管理页
   - 弹窗：功能表选项弹窗、百宝箱卸载弹窗、widget 类型选择、确认弹窗
   - JAR 游戏：主画面、绿键/软键菜单（注入模式 OptionsDialog）、TextField 输入画面
   - 关于页、Shizuku 各页
2. **测试基线**：`.\gradlew.bat testOpenDebugUnitTest` 记录当前通过情况。

### 阶段 1：引入 common + 注入（无行为变化）

1. settings.gradle 加 `includeBuild('../keydroidx-core')`（D2）。
2. `build.gradle` `MIN_SDK` 14→19（D1）。
3. `app/build.gradle` 加 `implementation 'io.github.cctyl.nokia:nokia-common:1.0.0'`。
4. 新增 `nokia/LauncherThemeProvider.java`：实现 common `ThemeProvider`，`getCurrentTheme(Context)` → `new NokiaSettingsStorage(ctx).getTheme()`。
5. `EmulatorApplication.attachBaseContext` 调 `NokiaTheme.setThemeProvider(new LauncherThemeProvider(this))`。
6. **验证**：编译 + 装机 + 冷启动走查桌面/功能表/设置——零行为变化为合格。

### 阶段 2：纯工具/绘制类替换（低风险，外观应像素级一致）

**2.1 NokiaDialogFocus**（3 处：NokiaOptionsDialog、NokiaUninstallDialog、NokiaInstallerDialog）
换 import、删桌面类。走查：OptionsDialog 弹出后首个方向键不丢（Android 12+ 重点）、卸载弹窗、JAR 安装弹窗。

**2.2 NokiaDashedLineDrawable**（NokiaDesktopFragment 3 处、NokiaQuickToggleSettingsFragment 1 处）
先 diff 构造参数（桌面版 73 行 vs common 49 行，桌面多配置项需贡献进 common 或保留桌面版）。走查：桌面通知区分隔线、快捷开关设置页分隔线。

**2.3 NokiaBatteryDrawable**（仅 StatusBarController）
换 import、删桌面类。走查：电量 4 档、充电闪电、低电变红。

**2.4 NokiaIcons**（18 文件）
逐常量核对 unicode 值（抽查 TOGGLE_*、widget 系列）；MaterialIcons ttf 保留 launcher assets 那份（同路径不冲突），后续删重复资源。换 import、删桌面类。
走查：功能表/widget/快捷开关/弹窗行图标——截图对比（图标字体渲染是视觉回归高发点）。

**2.5 NokiaLog**（约 49 文件 600+ 调用点，含包外文件）
按 D6：日志实现统一采用 common；迁移事项如下：
- `keyName(int)` 从 NokiaLog 迁入 `NokiaKeyBinding.keyName()`（逻辑归属更合理），6 文件 22 处调用改走 `NokiaKeyBinding.keyName(...)`。
- 删桌面 `NokiaLog` 类；所有调用文件切到 `io.github.cctyl.nokia.common.log.NokiaLog`。
- 旧 API：`setEnabled/isEnabled` 已确认无调用，直接删除；`fileCrash(String, Throwable)` 改为 common 的 `fileCrash(Thread, Throwable)`。
- 目录按生态约定改为 `<外存>/Android/data/<pkg>/files/log`（旧 `/log` 目录不再清理，历史日志保留）。
- 桌面「日志记录」开关（`nokia_desktop_settings.log_file_enabled`）继续作为用户级持久源：
  - `EmulatorApplication` 在 `NokiaLog.init(this)` 后用 `NokiaSettingsStorage.isFileLogEnabled()` 覆盖 common 初始化出的 `sFileMinLevel`。
  - `NokiaSettingsStorage.setFileLogEnabled()` 双写：写桌面 SP 后调用 `NokiaLog.setDetailedLogEnabled()`，让 common SP 同步，保证两端取值一致。
- common `NokiaLog.init` 在 `Application.attachBaseContext()` 阶段会因 `getApplicationContext()` 为 null 而 NPE，已修复 common（回退到传入 Context + 加保护）。
- 标签语义变化：桌面原 `[sub] msg` 统一由 logcat tag 承载，即原 sub 字符串成为 common 的 tag；日志文件不再含统一 `NokiaDesktop` tag。adb 过滤改为 `-s Desktop:* KeyBinding:*` 等组合。
走查：日志落盘、7 天轮转、debug/release 默认级别、设置页日志开关、崩溃 CRASH 记录、:midlet 进程是否也记录（本阶段仍保持仅主进程）。

**2.6 NokiaTheme**（26 文件，含 J2ME 层 3 处）— 已完成
前置：D3 调色板对齐（已在 core 完成：6 套主题 7 个桌面字段全部采用桌面值，主题名也用桌面命名）。
全部 `getSelectedTheme(ctx)` → `getCurrentTheme(ctx)`；`NokiaSettingsStorage.getTheme()` 返回 common ThemeDef；删除桌面 `NokiaTheme.java`。
common 侧同时对齐绘制语义：`createSelectedRowDrawable`/`createFocusDrawable` 改为纯色半透明 `focusColor`，`createDialogBodyDrawable` 渐变改为 `bgCenter -> bgEnd`；新增 `getThemes()` 主题清单。
**风险点已验证**：`:midlet` 进程 ThemeProvider 注入有效（EmulatorApplication 在双进程均注入，实测 midlet 进程 `getCurrentTheme` 返回桌面所选主题）。
走查：桌面背景渐变像素采样与调色板一致；列表高亮为 focusColor 叠加；**J2ME 画面/软键栏待真机目视确认**（待补）。

**2.7 NokiaDimens + NokiaFontManager** — 已完成（尺寸部分）
- `NokiaDimens.dp/dpF` 全部改用 common `io.github.cctyl.nokia.common.util.NokiaDimens`（25 文件，含 J2ME TextFieldImpl），删除桌面 `NokiaDimens.java`。
- `NokiaDimens.textSize(TextView, float)`（51 处）迁入桌面 `NokiaFontManager.textSize()`：它带桌面语义（用户字体缩放 + 应用全局/自定义字体），不适合放进 common；`sUserFontScale` 缓存同步改为 `NokiaFontManager.setUserFontScale()`。
- 按 D5 桌面 `NokiaFontManager` 保留（自定义字体导入/删除/列举是桌面能力，未贡献回 common）。
- **暂不做的部分**：common `NokiaFontManager` 的字体/缩放状态未在 EmulatorApplication 初始化、也未在设置变更处双写。原因：当前桌面没有任何代码消费 common 的字体状态，且 common 不认识 `custom_*` 字体 id（会退回内置字体），此时同步会埋下"自定义字体被静默替换"的隐患。**待阶段 3 引入 common 控件（或 D5 把自定义字体能力贡献回 common）时一并处理。**
走查：字体缩放切换后文字尺寸按倍率变化（0.85x/1.0x/1.5x 实测状态栏文字高度 15/19/27px）；**3 套内置字体切换、自定义字体导入/删除、J2ME 文字渲染待真机目视确认**（待补）。

**阶段 2 出口标准**：工具类全部单一来源到 common；真机全功能走查通过；单测全绿；`assembleOpenRelease -x lint` + proguard 装机验证一次。

### 阶段 3：页面契约体系（中风险，逐层小步）

**已完成（第 1 步）**：
1. `NokiaDesktopActivity` 实现 common `NokiaPageHost`（refreshPageBar/exitCurrent 签名一致）+ `KeyResolver`（`resolveAction` 委托 NokiaKeyBinding，纯查询不做 reload）。
2. 桌面 `NokiaFocusHost` 删除，改用 common 同名接口（16 文件）；桌面 `NokiaPage` 改为继承 common `NokiaPage`，中软键给 `default null` 实现；`refreshPageBar` 局部变量改 CharSequence。
3. `NokiaOptionsDialog` 按键解析改走 `KeyResolver` 契约（不再强转 Activity）。
4. NokiaBaseActivity/NokiaOptionsDialog 按 D4 保留，仅内部调用已换 common。

**待做（第 2 步，风险最高的一块）**：
5. 桌面三个页面基类**保留**（240dp/壁纸/isDirectionEnabled 等桌面语义），把 `(NokiaDesktopActivity) requireActivity()` 强转改为宿主接口调用。
   现状：约 **90 处强转 / 30 个文件**，且绝大多数用的是桌面专有能力（`openFragment`/`openBox`/`openMenu`/`openDesktopSettings`/`recreate`/`isDefaultLauncher`/`reloadKeyBindings`/`getKeyBinding`/`requestSetDefaultLauncher`），不是 common `NokiaPageHost` 能覆盖的。
   做法：新增桌面级 `NokiaDesktopHost extends NokiaPageHost`（声明上述桌面能力），`NokiaDesktopActivity` 实现它；页面强转逐个改为该接口。需逐调用点判断：凡是把宿主当 Activity/Context 用的（如 `safeDrawable(host, resId)`）保持原强转不动。

走查（全页面回归）：14 个 PageFragment + 9 个 ListPage + 2 个 ScrollPage 子类逐一：进入/返回、软键三栏文案、方向键循环焦点、LEFT/RIGHT 钩子、DOWN/UP 配对（软键不双触发）、录制态按键捕获、锁屏键、页面状态上报（Shizuku）。

### 阶段 4：feedback 接入（D7）

1. local.properties 复制密钥两行 → app/build.gradle 注入 BuildConfig。
2. EmulatorApplication `NokiaFeedback.init(config)`。
3. 设置组页/关于页加「意见反馈」入口，push common `NokiaFeedbackFragment`（依赖阶段 3 的 NokiaPageHost 对接）；文本输入用 common `NokiaTextInputFragment`。
4. 走查：提交（含/不含日志附件）、联系方式输入、类型选择弹窗、成功/失败反馈、meta 正确性、9MB 截断。

---

## 五、贯穿性验证要求

- 每个 commit 可独立 revert；阶段 2 每步截图对比基线。
- **双进程**：涉及 Theme/Font/Log 的改动必须同时验证主进程与 `:midlet` 进程。
- **API 19 回归**：阶段 2.6/2.7 完成后在 4.4 模拟器跑一轮（4.4 兼容是硬规则）。
- **release 验证**：阶段 2 出口、阶段 3 出口各做一次 `assembleOpenRelease -x lint` 装机（proguard keep 规则可能需为 common 增补）。
- **兼容红线**：SP 文件名/key、keyprovider uri、ACTION_* 常量值全程不变。

## 六、后续议项（本计划不覆盖）

1. **NokiaOptionsDialog 统一**（D4）：common 增补 DialogFragment + 键码注入形态后，桌面统一切换（含 javax.microedition 包内 4 处）。
2. **NokiaBaseActivity 骨架统一**：桌面 240dp 缩放体系与 common 差异大，暂保留各自实现。
3. **自定义字体能力贡献回 common**（D5）。
4. **common 调色板对齐后**，独立 App（keydroidx-music 等）升级 common 版本的观感验证。
5. 桌面 NokiaConfirmDialog 类需求评估（桌面现有 NokiaUninstallDialog 等专用弹窗，不在本期范围）。
