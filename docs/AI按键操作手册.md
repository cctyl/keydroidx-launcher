# AI 按键操作手册（纯按键）

适用：原键桌面 `KeydroidxDesktopActivity`（`keydroidx-launcher`，实体按键机模式）。
术语对齐 `keydroidx-core/docs/reference/FEATURE_PHONE_UI_SPEC.md`（§2.2 逻辑键名、§4 SOFTKEY SYSTEM、§47 GLOBAL BACK BEHAVIOR）。

**三条铁律**

1. **先读映射，再按键**——每台手机把物理键映射成的 keycode 不同，禁止凭默认值猜。
2. **只用按键，不用触摸**——禁止 `input tap` / `input swipe` / `input motionevent`；所有交互必须用 `input keyevent` 完成。
3. **每步截图确认**——按键没有返回值，唯一反馈是屏幕内容（截图/焦点判定/结果验证见 §5，必读）。

---

## 0. 速查卡（完整流程一屏看完，细节点章节号）

```text
0) 确认装的是 debug 还是正式包，后面所有命令的包名/authority 跟着换（§1）
1) content query 读键位映射——确认键是 23 还是 66 每台机不一样（§1）
2) dumpsys power 查亮屏 → POWER(26) 唤醒 → MENU(82) 解锁 → am start 桌面 → 截图确认待机屏（§5.7）
3) 截图认屏：底栏三栏文字判页面、右上 N/M 判页码、放大读高亮（§5.1/5.2）
4) 发键：动作→keycode 用第 1 步读到的映射值；每步后等 300~800ms（§2/§4）
5) 验证：logcat -d -s <tag>:* 最可靠，其次 Toast（1~1.5s 内截图）、界面状态（§5.3）
6) 走丢按 HOME(3)；收尾先 HOME 回待机屏，再删两端临时文件（§5.5/§5.6）
```

---

## 1. 拿到本机真实 keycode（必做第一步）

**第 0 步：先确认设备装的是哪个包**（debug 与正式包的包名/authority 都不同，报 `Could not find provider` 多半是包猜错了）：

```powershell
adb -s <serial> shell pm list packages | Select-String "cctyl"
# io.github.cctyl.nokia.debug = debug 包；io.github.cctyl.nokia = 正式包
```

然后读键位映射：

```powershell
adb -s <serial> shell content query --uri content://io.github.cctyl.nokia.debug.keyprovider/keys   # debug 包
adb -s <serial> shell content query --uri content://io.github.cctyl.nokia.keyprovider/keys         # 正式包
```

返回 `action`（动作）/ `actionId` / `keyCode`（要发的值）/ `keyName`。真机实测（Q968）：

```
action=UP,         actionId=0, keyCode=19, keyName=上
action=DOWN,       actionId=1, keyCode=20, keyName=下
action=LEFT,       actionId=2, keyCode=21, keyName=左
action=RIGHT,      actionId=3, keyCode=22, keyName=右
action=SELECT,     actionId=4, keyCode=66, keyName=确定
action=SOFT_LEFT,  actionId=5, keyCode=82, keyName=菜单
action=SOFT_RIGHT, actionId=6, keyCode=4,  keyName=返回
action=LOCK_SCREEN,actionId=7, keyCode=17, keyName=*号
action=HANGUP,     actionId=8, keyCode=5,  keyName=通话
```

- 这台机器：确认键 = **66**（不是 23）、左软键 = **82**、右软键 = **4**。**照默认值发必然点错。**
- Provider 免 root、不要求应用在前台；权威表恒为 9 行，缺行时该动作未绑定。
- **第三种状态：行在，但 keyName 是 `KEYCODE_<数字>`（无中文名）**——如实测某机 `action=HANGUP, keyCode=142, keyName=KEYCODE_142`，说明该动作落在一个无名扫描码上（通常是用户没自定义、用了出厂默认值），这个键**不要依赖**，按了没反应先回来查这里。

**动作 ↔ 规范逻辑键名对照**（发键时看左列，发中列）

| SPEC 逻辑键名 | 本工程动作（Provider `action`） | 默认 keycode |
|---|---|---|
| `KEY_UP/DOWN/LEFT/RIGHT` | `UP` / `DOWN` / `LEFT` / `RIGHT` | 19 / 20 / 21 / 22 |
| `KEY_CENTER` | `SELECT` | 23 |
| `KEY_LSK` | `SOFT_LEFT` | 1 |
| `KEY_RSK` | `SOFT_RIGHT` | 2 |
| `KEY_CALL` | `HANGUP`（桌面=最近任务；JAR 内=挂机菜单） | 5 |
| `KEY_END` | `LOCK_SCREEN`（桌面=锁屏；子页面=回待机屏） | 6 |

**兜底校验（可选）**：`adb -s <serial> logcat -d -s KeyBinding:*`，每按一次键会打印 `resolveAction 菜单 -> 左软键(5)`，用来确认「键送到了没有 / 解析成了什么」。`-d` 表示 dump 完就退出；**不要顺手加 `-c` 清空日志**——缓冲区是共享的，清空会把上一次操作的现场一起抹掉（过滤技巧见 §5.3）。

**兜底读取（仅 debug 包）**：`adb -s <serial> shell run-as io.github.cctyl.nokia.debug cat shared_prefs/nokia_key_bindings.xml`（键名 `up/down/left/right/select/soft_left/soft_right/lock_screen/hangup`，未出现的键即未绑定）。

---

## 2. 发 keycode 操作界面

做操作前，先看清楚当前是什么界面，如果不知道是什么界面，就按照5.5节的说明先返回主页。


```powershell
# 0) 先解锁并确认桌面在前台（Home 应用；已在前台会提示 intent delivered，不影响）
#    设备可能息屏/锁屏，锁屏时按键不会送到应用——唤醒+解锁+截图确认见 §5.7
adb -s <serial> shell am start -n io.github.cctyl.nokia.debug/ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity

# 1) 发送映射里读到的 keycode（数字或 KEYCODE_ 名称都行）
adb -s <serial> shell input keyevent 82     # 左软键 → 进功能表
adb -s <serial> shell input keyevent 20     # 下
adb -s <serial> shell input keyevent 66     # 确认
adb -s <serial> shell input keyevent 4      # 右软键（＝本机 BACK）
adb -s <serial> shell input keyevent 5      # 拨号键 → 最近任务
```

真机已实测：`82`→功能表；`22`→焦点右移；`5`→最近任务页；`4`→返回上一层。

**关于 `am start` 的两种回显（都算成功，别当成失败）**：

- 冷启动回显 `Starting: Intent { ... }`；
- 已在运行时回显 `Warning: Activity not started, intent has been delivered to currently running top-most instance.`——这表示 intent 只是投递给了栈顶已存在的实例，**不是报错**。
- 注意后一种情况**不会切换界面**：若当前停在子页面（功能表/设置等），这条 `am start` 不会把你带回待机屏，界面仍停在原页。**要回待机屏请用 `input keyevent 3`（HOME），完整三招见 §5.5**。
- 判断「现在到底在哪一屏」不要靠这条回显，**以截图为准**。

---

## 3. 主要界面与底部软键栏 ★重点

### 3.1 四个主要界面：从哪进、长什么样 ★已实测

本应用只有四个主要界面。认清它们的**页面标题（底栏中栏）**和**底栏左右文字**，就能随时判断自己停在哪一屏。

| 界面 | 底栏中栏（页面标题） | 怎么进 | 底栏左 / 右 | 界面内容 |
|---|---|---|---|---|
| **待机屏（主页）** | 无（中栏空） | 按 `HOME`；或 `am start` 本应用（见 §2 与 §5.5） | `功能表` / `桌面设置` | 顶栏（真实信号/WiFi/电量/时间）→ 快捷栏 → 通知区 → 日历·内存·后台管理 → 开关栏 |
| **功能表** | `功能表` | **待机屏按左软键** | `选项` / `退出` | 3 列网格，右上角有页指示器 `N/M`；第 1 页是固定槽位的系统功能入口（实测 240×320：设置、应用程序、J2ME Loa.、桌面设置、通知中心、下载、文件管理、短信、计算器；实测 320×480：联系人、相册、文件、设置、应用程序、通知中心…），第 2 页起是已装的安卓应用 |
| **应用程序** | `应用程序` | **功能表 →「应用程序」格子 → 确认** | 焦点在 JAR 上时 `选项`，否则**空**（该软键隐藏） / `退出` | 桌面自己的 **JAR 列表**；一台没装 JAR 的机器上它是**空白页**（不是坏了）。它不是 `J2meLoaderActivity` 那个 Android 风格外壳，别混（见 §5.5） |
| **桌面设置** | `桌面设置` | **待机屏按右软键**（也可从功能表网格里的「桌面设置」格子进） | `选择` / `返回` | 6 项列表：外观与显示、按键与操作、桌面内容、系统与权限、高级设置、关于 |

- 上表一律用**动作**（左软键 / 右软键 / 确认）表述——**每台机型的实际 keycode 不同**，先按 §1 读 Provider（本次实测的 4.4.4 机型：左软键 `82`、右软键 `4`、确认 **`23`**，与前文那台 320×480 的 `66` 就不一样）。
- **网格每页格数不要写死**：它由屏幕高矮决定（实测 240×320 是 3×3＝9 格/页、共 `1/2` 页；320×480 是 3×4＝12 格/页、共 `1/4` 页），判断页码只看右上角 `N/M`。
- 四个界面的**返回都走右软键**；要一步回待机屏用 `HOME`（见 §5.5）。
- **待机屏是唯一同时有「功能表」和「桌面设置」两个软键的界面**——截图里看到这两个词，就说明已经在待机屏，可以开始发操作键了（这正是 §5.7 的确认标准）。

### 3.2 底部软键栏：三栏的含义

屏幕底部恒为三栏，**只有左右两栏是软键**：

```
┌──────────────────────────────────────────┐
│                页面内容                   │
├──────────────────────────────────────────┤
│  左软键文字   │   页面名   │   右软键文字   │
└──────────────────────────────────────────┘
    SOFT_LEFT       SELECT       SOFT_RIGHT
    (KEY_LSK)      (页面标题)      (KEY_RSK)
```

- **中间栏显示的是「当前页面名」**（如 `功能表`、`最近任务`），**不是软键**，不要去找对应它的物理键；个别页面（如意见反馈页）会在此声明中键动作（`提交`），此时才对应确认键。
- 左右栏文字为 `null` 时该软键隐藏（空栏占位）。

### 3.3 操作三步法

```
① 看底部左/右栏文字  →  ② 判断它属于哪个动作  →  ③ 发该动作的 keycode  →  截图确认
```

按规范（SPEC §4.2/§4.3、RULE 05/06）：**RSK 正常含义＝返回/取消，LSK＝当前屏的上下文动作**。本工程符合同一套约定：

| 底部文字（示例） | 动作 | 本机 keycode |
|---|---|---|
| `功能表`（待机屏左） | `SOFT_LEFT` → 进功能表 | 82 |
| `桌面设置`（待机屏右） | `SOFT_RIGHT` → 进桌面设置 | 4 |
| `选项`（功能表/最近任务左） | `SOFT_LEFT` → 打开选项弹窗 | 82 |
| `选择`（列表页左） | `SOFT_LEFT` → 等同确认 | 82 |
| `退出` / `返回`（各页右） | `SOFT_RIGHT` → 返回上一层；待机屏无此键 | 4 |

### 3.4 弹窗里的软键（最容易踩坑）

弹窗是独立窗口，Activity 会先把动作**翻译成标准键码**再送入：

| 你发的动作 | 弹窗收到的键 |
|---|---|
| 上/下/左/右 | `DPAD_UP/DOWN/LEFT/RIGHT` |
| 确认 | `DPAD_CENTER` |
| 左软键 | `SOFT_LEFT` |
| **右软键 / 拨号键 / 锁屏** | **`BACK`** |

于是弹窗里的通用套路是：

- **确认当前项**：`SELECT`（确认键）或 `SOFT_LEFT`（左软键）——选项弹窗、确认弹窗都是「执行并关闭」。
- **取消 / 关闭**：`SOFT_RIGHT`（右软键）或 `BACK`，二者等价。
- **移动高亮**：`UP` / `DOWN`（边界不循环，自动跳过禁用项）；`LEFT`/`RIGHT` 在弹窗里无效果。
- 例外：**卸载弹窗里确认键不执行任何操作**（必须用左软键确认卸载）；**安装弹窗**左软键=主操作，右软键=次操作，确认键仅在确认态等同左软键。

---

## 4. 纯按键操作原则

1. **焦点唯一**：任何界面同时只有一个高亮项。移动规律分两类，**发键前先看清当前是哪一类**：
   - **列表页**（设置、选项、应用列表）：`UP`/`DOWN` 每次移动一项，强制循环（顶部再按上跳到末尾，末尾再按下回到顶部）。
   - **网格页**（功能表等）：`LEFT`/`RIGHT` 只在**本行内**移动，且**行内循环**（行尾再按右回到本行首，不会自动换行）；换行只能用 `UP`/`DOWN`；**在最后一行再按 `DOWN` 会翻到下一页**（`LEFT`/`RIGHT` 不翻页）。
   - **翻页与页数**：页面右上角有 `N/M` 页指示器，是判断「是否切页成功」的唯一依据；总页数随内容数量变化（不同机器/装的应用不同就不一样），**禁止写死页码或格子序号**。
   - 两类的共同点：焦点到边界后**要么循环、要么翻页，不会卡住不动**；若按下方向键后截图毫无变化，先怀疑「键没送到」（见 §1 兜底校验），而不是「到底了」。
2. **进入/激活**：`SELECT`（=SPEC 的 `CENTER`，主激活键）。功能表/网格里激活焦点图标，列表/设置里进入该项。
3. **返回**：优先 `SOFT_RIGHT`；`SOFT_RIGHT` 无效或未绑定时才用 `BACK`（BACK 未绑定时的兜底是「先关弹窗 → 再交给当前页返回 → 否则退出应用」）。
4. **回待机屏**：`HOME` 键（发 `3`）最稳，任何未知页面都能回（**走丢了先按它**，完整三招见 §5.5）；或按 §1 表里的 `LOCK_SCREEN`/`END`（表里 `6` 是**默认** keycode，本机 Provider 只有 `LOCK_SCREEN=17`、无独立 END 行——同一物理键在待机屏＝真锁屏，在子页面内＝回待机屏，发之前务必先用截图确认当前屏，见 §4.6）。待机屏才有 `功能表`/`桌面设置` 两个软键。
5. **只发整键**：`input keyevent` 自带 DOWN+UP 配对；不要手工拆分发送。**长按连发被过滤，仅方向键保留连发**（按住方向键会连续移动/翻页，注意别发太长）。
6. **锁屏动作在抬起（UP）时才执行**：发 `17`（本机锁屏键）会真的锁屏，测试时避免；锁屏后需 `input keyevent 224`(WAKEUP) 唤醒。
7. **桌面必须在前台**才能接键；多设备一律带 `-s <serial>`。
8. **触屏模式**：若 `nokia_desktop_settings.xml` 里 `touch_mode_enabled=true`，用户绑定失效，改用固定预设（上/下/左/右=19/20/21/22，确认=23，左软键=1，右软键=2，锁屏=`*`(17)，拨号键=5）；此时也仍然只允许发键，不要点虚拟键盘。
9. **定位目标项：禁止盲发固定次数**。界面里条目数量与顺序会随内容（已装应用数、系统版本、当前状态等）变化，**任何「按 N 次方向键就到目标」的假设都不可靠**。通用做法：
   1. 先截图，读页指示器与整屏条目，判断目标在第几页第几行；
   2. 用 `DOWN` 先落到目标所在行（比逐格按右快得多），再用 `LEFT`/`RIGHT` 在行内微调；
   3. **每次移动后截图确认高亮框已落在目标上**，确认无误再按 `SELECT`/软键。
   若步数较多，可一次发 2~3 次方向键再截图（比一次一格省截图），但方向键连发会连续移动，发完务必核对，发现越界就反向修正。
10. **时序：按键之后要留等待时间**。`input keyevent` 返回只代表事件已投递，界面刷新需要时间——发完立刻截图容易截到上一帧。建议：普通移动后等 **300~800ms**，打开弹窗 / 跨页 / 启动应用等重操作等 **800~1500ms**；连续发多条 keyevent 时命令之间也留约 200ms 间隔，无间隔连发有丢键风险。
11. **弹窗/列表打开后的默认高亮位置**：默认停在**第一项**（不是上次位置）。若目标就是第一项，可直接按确认，不必先发方向键；若不是，先发 `DOWN` 移动并截图确认。

---

## 5. 截图、焦点判定与结果验证 ★必读

按键没有返回值，所以「看得对」和「验得准」是纯按键操作的全部。本节给出通用方法，与具体功能无关。

> **动手前先看 §5.7**：设备可能息屏/锁屏，或停在别的界面上——先把「唤醒 → 解锁 → 确认停在待机屏」做完，否则后面的按键全是打空的。

### 5.1 截图（Windows 下必须这样，不能用 `>` 重定向）

**截图先落到手机上的一个文件**，再用 `adb pull` 拷回本机（不能直接把二进制管道到本机文件，见下）：

```powershell
# 推荐：写设备临时目录（不在用户存储里，不会被媒体库扫描）
adb -s $S shell screencap -p /data/local/tmp/kx.png
adb -s $S pull /data/local/tmp/kx.png $env:TEMP\kx.png
adb -s $S shell rm -f /data/local/tmp/kx.png

# 也可改用 /sdcard（用户可见存储），三条命令把路径换成 /sdcard/kx.png 即可
# 截出来是黑屏/锁屏？先按 §5.7 完整流程「唤醒 → 解锁 → 回待机屏」，再回来重截
```

- **不要用** `adb exec-out screencap -p > x.png`：PowerShell 的 `>` / 管道会按文本处理二进制流，得到的 PNG 是损坏的（可读但会解码失败）。
- **设备端路径怎么选**：
  - `/data/local/tmp/`（**推荐**）：shell 可写、不在用户可见存储里、**不会被媒体库扫描**（实测 Android 4.4.4 可用）。
  - `/sdcard/`：用户可见存储，**会被媒体扫描器索引**——随手丢的 `kx.png` 可能出现在用户「图库/相册」里，属于污染用户设备。只有在 `/data/local/tmp` 不可写时才退而用 `sdcard`。
- 固定用一个文件名（如 `kx.png`）覆盖上一次即可，别依赖「最新的那张」，也别用 `kx1/kx2/...` 递增命名。
- ⚠️ **截图一定会在手机上留下文件**：操作结束、中途放弃、脚本报错中断，都必须清理——见 §5.6。

### 5.2 焦点在哪：怎么读，以及怎么避免误判

- **高亮项的特征**：该图标/文字行的背景变成浅色方块（本工程是半透明蓝底 + 一圈边框），**图标本身不变色**。小屏（如 320×480）上这块底色很淡，直接看整图经常看错——**必须放大再读**。
- 放大建议：把内容区裁剪出来、用最近邻放大约 2 倍。示例（需 Pillow，仅本地调试用）：
  ```python
  from PIL import Image
  im = Image.open("kx.png").crop((0, 55, 320, 440))   # 只截内容区，去掉顶栏；裁剪范围按自己机型调整
  im.resize((im.width * 2, im.height * 2), Image.NEAREST).save("kx_big.png")
  ```
- **读图顺序**：① 看底部三栏文字 → 判断现在在哪一屏、左右软键是什么；② 看页指示器 `N/M` → 判断页码；③ 看高亮框落在哪一项。
- **不要依赖 `uiautomator dump` 判断焦点/内容**：本工程 UI 多为自绘，`dump` 常返回过期缓存（实测两次不同布局可能 dump 出完全相同的 bounds），**一律以截图为准**。

### 5.3 结果怎么验证（关键：很多操作成功后界面毫无变化）

按键/操作的结果反馈只有三种渠道，按可靠性排序：

1. **`logcat`：最可靠、可回溯**（**首选**）。关键是**学会过滤**，而不是清空日志。
   - ⚠️ **不要用 `logcat -c` 清空日志**：环形缓冲区是共享的，清空会永久抹掉「上一次操作的现场」「刚发生的崩溃堆栈」，以及别的排查正在进行中的证据。想「只看我这一段」，请用下面的**过滤条件**把范围收窄（按 tag 过滤最省事；也可以先 `adb shell date` 记下时间，再用时间窗过滤）。
   - **一律加 `-d`**（dump 完即退出）。不加 `-d` 会一直 follow，命令不返回，只能手动中断。
   - **按 tag 过滤（最常用，实测有效）**：`-s` 后列多个 tag 是「或」的关系。
     ```powershell
     adb -s $S logcat -d -s KeyBinding:*            # 按键解析：resolveAction 左 -> 左(2)
     adb -s $S logcat -d -s Menu:*                  # 功能表（列表枚举/页面装配/焦点）
     adb -s $S logcat -d -s KeyBinding:* Menu:*     # 组合：同时看两类
     ```
      实测有输出的 tag：

     | 用途 | tag | 状态 |
     |---|---|---|
     | 按键解析（任何页面发键都会打） | `KeyBinding` | ✅ 实测 |
     | 功能表（列表枚举/页面装配/焦点） | `Menu` | ✅ 实测 |
     | 应用程序页（JAR 列表/百宝箱） | `Box` | 源码核实 |
     | 桌面/待机屏 | `Desktop` | 源码核实 |

     ⚠️ **tag 是 `KeydroidxLog.d/i/w/e("Tag", ...)` 里写死的字符串，与源码里的 `TAG` 常量不是一回事**（`Menu`/`Box` 都不是常量值）。要找其它页面的 tag，去 `app/src/main/java/ru/playsoftware/j2meloader/nokia/` 搜 `KeydroidxLog.` 看第一个参数。
   - **加 `-v time` 看时间戳**（实测可用；判断「这行是不是我这次操作产生的」很有用）：
     ```powershell
     adb -s $S logcat -d -v time -s KeyBinding:*
     ```
   - **按级别 / 异常过滤**：
     ```powershell
     adb -s $S logcat -d "*:E"                      # 只看 Error 及以上（含崩溃）
     adb -s $S logcat -d -s AndroidRuntime:E        # 只看未捕获异常的堆栈
     ```
     给 `*` 加引号是保险写法（4.4 实测不加引号也能匹配上——设备端 shell 展开失败会原样透传，但换 ROM 不保证）。
   - **要「只看最近几行」请用本机截尾，别拿 `-t` 配 `-s`**：`-t N` 是先在**未过滤**的原始缓冲区上取最后 N 行、**再**套 tag 过滤，过滤后经常一行不剩（实测 `-t 200 -s KeyBinding:*` 输出为 0，而同刻 `-s KeyBinding:*` 有 13 行）。正确写法：
     ```powershell
     adb -s $S logcat -d -s KeyBinding:* | Select-Object -Last 20
     ```
     （`-t N` 单独用是正常的：实测 `-t 3` 会打印原始最近 3 行。）
   - **拉回本机后再按关键词过滤**（比在设备上 grep 灵活，中文也不会乱码）：
     ```powershell
     adb -s $S logcat -d -s Menu:* | Select-String "冻结|解冻|失败"
     ```
   - **另一条路：直接读应用自己落盘的日志**（比 logcat 缓冲区持久，不受环形覆盖影响）：
     ```powershell
     adb -s $S shell ls /sdcard/Android/data/<包名>/files/log/          # yyyyMMdd.log，自动保留 7 天
     adb -s $S shell cat /sdcard/Android/data/<包名>/files/log/20261009.log
     ```
     注意：① 文件名按**设备本地日期**命名，设备时钟不准时会很怪（实测见过 `19700102.log`）；② 同目录还有崩溃待上报标记 `pending_report.json`；③ 只有桌面设置里打开「详细日志」才写 DEBUG/INFO，否则只写 ERROR；④ `cat` 只适用于**文本**，二进制（截图）绝不能这么干，必须走 §5.1 的 `pull`。
   - **发了按键却没有新日志？先看屏幕是不是黑的**：息屏时按键不会送到应用。实测：屏幕 `mScreenOn=false` 时连按方向键，`KeyBinding` 零新增；亮屏后再按立刻出现。唤醒/解锁完整流程见 §5.7。
     ```powershell
     adb -s $S shell dumpsys power | Select-String "mScreenOn"   # false = 息屏
     ```
   - 实测可用：`logcat -d -s KeyBinding:*` 会打印 `resolveAction 菜单 -> 左软键(5)`，可确认「键送到没有 / 解析成了什么」。
2. **Toast：最常见，但有时效**。结果常以底部一条浮出提示给出，**约 2 秒后自动消失**；截图太早（界面还没刷新）或太晚（已消失）都会漏。建议操作后等 **1~1.5s** 再截。
3. **界面状态变化**：页指示器、列表内容、图标状态等。这类变化是持续性的，适合反复截图比对。

> **三种反馈都没有时，不要假定操作成功**：重截一张、或改用 `logcat` 确认。宁可多截一张图，也不要凭猜测继续下一步（后面所有步骤都会建立在错误前提上）。

### 5.4 通用检查循环

```
发键 → 等待(300~800ms) → 截图 → 放大读焦点/页号 → 与预期一致？
   ├─ 一致 → 继续下一动作
   └─ 不一致 → 先确认键是否送到（logcat -d -s KeyBinding:*）→ 再判断是越界、翻页了还是没生效
```

### 5.5 走丢了怎么办：回到待机屏 ★已实测

在未知页面（进错菜单、忘了自己在哪、被外部 Android 应用挡住）时，按下面顺序尝试，**每招之后都要截图确认**。待机屏的特征：底部左栏 `功能表`、右栏 `桌面设置`。

| # | 做法 | 结果 | 副作用 |
|---|---|---|---|
| 1 | `adb -s $S shell input keyevent 3`（HOME） | ✅ 回待机屏 | 无，**首选** |
| 2 | `adb -s $S shell am start -a android.intent.action.MAIN -c android.intent.category.HOME` | ✅ 回待机屏 | 无，第 1 招不灵时用 |
| 3 | `adb -s $S shell am start -S -n <包名>/ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity` | ✅ 回待机屏 | ⚠️ 会**先杀掉整个应用进程**再冷启动：正在跑的 JAR 游戏/未保存状态会丢，桌面组件重新初始化（实测未读通知数、IP 连接状态等临时状态被重置） |

- 包名：debug 为 `io.github.cctyl.nokia.debug`，正式包为 `io.github.cctyl.nokia`。
- 实测覆盖的起点：功能表九宫格、应用内子页面、**外部 Android 应用（系统设置）**——第 1 招均有效。第 3 招只在第 1/2 招都失效（应用卡死等）时才用。
- 第 2 招虽然回显 `Warning: Activity not started, intent has been delivered to currently running top-most instance.`，但界面**确实回到了待机屏**——所以判断成败依旧以截图为准，别被这条回显误导。

**以下几条「常见猜想」实测无效，别用**：

- ❌ `am start -n <包名>/ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity`（**不带任何 flag**）：只会回显 `intent has been delivered to currently running top-most instance`，**界面停在原页不动**（在功能表里发就等于没发），不会回待机屏。
- ❌ `am start -n <包名>/ru.playsoftware.j2meloader.MainActivity`：**该类在运行时不注册**，直接报 `Error type 3 / Error: Activity class { ... } does not exist.`。
- ❌ `am start -n <包名>/ru.playsoftware.j2meloader.J2meLoaderActivity`：**实测**打开的是 J2ME-Loader 自带外壳（Android 风格的应用列表），**不是待机屏**，也不能借它回待机屏。

> 术语提示（避免按界面文字找不到东西）：本手册只用**界面上能看到的文字**。功能表第 1 页有个「**应用程序**」格子，它是桌面自己的 JAR 列表页；该页面在代码/设计文档里叫 `KeydroidxBoxFragment`、文档中偶称「百宝箱」，**但界面上显示的是「应用程序」**。它与上面那个 `J2meLoaderActivity`（J2ME-Loader 自带外壳）是**两个不同的入口**，别混。

### 5.6 收尾：清掉临时截图（必做）

**先把设备留在干净状态**：按一次 `HOME`（`input keyevent 3`）回待机屏，别把用户留在你测试时的半路上。然后清理文件——截图会在**两个地方**各留一份：手机上的设备路径、你本机的拉取目录。操作结束（含中途放弃、报错中断）都要删：

```powershell
# 1) 设备端：写过哪个目录就删哪个，不确定就两条都执行（rm -f 文件不存在也不报错）
adb -s $S shell rm -f /data/local/tmp/kx.png
adb -s $S shell rm -f /sdcard/kx.png

# 2) 本机
Remove-Item $env:TEMP\kx.png -ErrorAction SilentlyContinue
```

- 用**固定文件名**（如 `kx.png`）而不是 `kx1.png`/`kx2.png`/… 的原因就在这里：一条 `rm -f` 就能清干净，也不会在手机上越积越多。
- 不确定有没有残留时先看一眼：`adb -s $S shell ls /data/local/tmp/`（以及 `adb -s $S shell ls /sdcard/`）。
- 清理也是「每步截图确认」流程的一部分：**任务做完先清理，再回复用户**，否则用户会在自己的手机上看到一堆来路不明的 png。

### 5.7 开操作前：唤醒 → 解锁 → 确认停在应用主界面 ★最容易被漏掉的一步

**为什么必须做**：设备很可能处于**息屏 / 锁屏**状态，而锁屏时按键根本不会送到应用（§5.3 有实测），表现为「按了完全没反应、日志也零新增」；即使屏幕是亮的，前台也未必是本应用（可能停在锁屏前的别的应用或桌面子页面）。**在错误的界面上发键，后面每一步都建立在错误前提上。**

**标准流程（全程只发按键，不碰屏幕）**

```powershell
# 1) 先判断状态，别盲目按 POWER 把亮屏按成息屏
adb -s $S shell dumpsys power | Select-String "mScreenOn"        # false = 息屏
# 2) 唤醒：POWER 最通用（4.4 实测可用；224/KEYCODE_WAKEUP 在 4.4 上唤不亮）
adb -s $S shell input keyevent 26
# 3) 解锁：无凭据锁屏，亮屏后按一次 MENU 键（跨版本最常用的做法）
adb -s $S shell input keyevent 82
# 4) 落到本应用主界面
adb -s $S shell am start -n <包名>/ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity
# 5) 截图确认是「待机屏」：底部左栏「功能表」＋ 右栏「桌面设置」
#    若停在子页面（如功能表），补一记 HOME：adb -s $S shell input keyevent 3
```

**怎么判断「还锁着 / 没停在本应用」**

- **首选截图**：锁屏界面一眼可辨（大时钟、没有底部三栏软键）。这条跨版本都准，**不要只信 dumpsys**。
- 可选辅助：`dumpsys power | Select-String "mScreenOn"`（`false` = 息屏）；`dumpsys window | Select-String "mCurrentFocus"` 里若看不到本应用包名，说明还没回到桌面。旧版本还能用 `dumpsys window | Select-String "mDreamingLockscreen|mShowingLockscreen"`（`true` = 锁屏），**但这些字段名各版本会变，别写死进脚本**。
- 只有确认「已解锁 + 停在待机屏」之后，才开始发第 1 个操作键。

**版本差异（别只照着一个版本写死）**

| 手段 | 适用范围 | 说明 |
|---|---|---|
| `input keyevent 26`（POWER） | 全部版本 | 唤醒首选；**4.4.4 实测可用** |
| `input keyevent 224`（KEYCODE_WAKEUP） | 较新版本 | **4.4.4 实测无效**（发完仍 `mWakefulness=Asleep`）；能用 26 就别依赖它 |
| `input keyevent 82`（MENU） | 无凭据锁屏，跨版本通用做法 | 亮屏后按一次即可解锁（**本机未实测**；个别 ROM 行为不同，无效就改人工解锁） |
| `wm dismiss-keyguard` | **Android 6.0（API 23）及以上** | **4.4.4 实测报 `Error: unknown command 'dismiss-keyguard'`**；本工程 `minSdk=19`，**不能依赖它** |
| `input swipe`（上滑解锁） | 全部版本 | **本手册禁止触摸操作**（铁律 2），只在人工协助时用 |

**有密码 / PIN / 图案 / 指纹的锁屏**：命令行无法（也不应该）绕过——这种情况必须请人工解锁后再继续；本手册只覆盖「无凭据锁屏」。

**解锁后仍然「按了没反应」？** 按 §5.4 的顺序排查：先截图看当前界面 → 再看 `logcat -d -s KeyBinding:*` 有没有新行 → 都没有就回到本节第 1 步复查屏幕状态。

### 5.8 辅助验证：绕开 UI 直接查数据 ★数东西/存疑时用

「数装了几个 JAR」「有没有留下临时文件」这类问题，除了看界面还可以直接查数据，两条结论互相印证：

```powershell
# 应用外部数据目录（shell 可读，无需 root；只有 log ⇒ 没装过 JAR）
adb -s $S shell ls -R /sdcard/Android/data/<包名>/files/
# 应用私有目录只有 debug 包能进（run-as），正式包会 Permission denied
adb -s $S shell run-as io.github.cctyl.nokia.debug ls shared_prefs/
```

- 实测案例：「应用程序」页显示空白 → `Menu` 日志确认页面正常打开（不是崩溃空白）→ `files/` 下只有 `log`、无任何 JAR 安装产物 ⇒ 三重印证「装了 0 个 JAR」。
- **界面状态 + 日志 + 数据目录三者对上才下结论**；只看其中任何一条都可能误判。

---

## 6. 例子

### 例 A：待机屏 → 功能表 → 打开「设置」→ 返回

```powershell
S=C65GA202401021974
adb -s $S shell am start -n io.github.cctyl.nokia.debug/ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity
adb -s $S shell input keyevent 82          # 左软键「功能表」
# 等待 500~800ms 后截图，确认已进入功能表九宫格（底部：选项 | 功能表 | 退出），记下右上角页指示器 N/M
adb -s $S shell input keyevent 20          # 下：换到下一行
adb -s $S shell input keyevent 22          # 右：本行内右移一格
# 反复「发方向键 → 等 300~800ms → 截图（放大）」直到截图里“设置”图标带高亮边框
adb -s $S shell input keyevent 66          # 确认：打开桌面设置
adb -s $S shell input keyevent 4           # 右软键「返回」：回上一屏
```

关键点：焦点位置只能靠截图判断，**不要假设「设置」在第几格、第几页**（不同机型/页数不同），用「发一次方向键 → 截图」逐步逼近。

### 例 B：在选项弹窗里选一项 / 取消

```powershell
adb -s $S shell input keyevent 82          # 左软键「选项」→ 弹出选项列表
adb -s $S shell input keyevent 20          # 下：高亮下移一项（不循环）
adb -s $S shell input keyevent 66          # 确认：执行当前高亮项并关闭弹窗
# 若要放弃：改用
adb -s $S shell input keyevent 4           # 右软键 / BACK：取消关闭（弹窗里两者等价）
```

### 例 C：随时回待机屏 / 打开最近任务

```powershell
adb -s $S shell input keyevent 5           # 拨号键 → 最近任务页（弹窗内也可用于关闭）
adb -s $S shell input keyevent 4           # 右软键「退出」→ 回上一层
adb -s $S shell input keyevent 3           # HOME：任何一屏都直接回待机屏（最稳，推荐）
adb -s $S shell input keyevent 17          # LOCK_SCREEN（本机实测=17）：待机屏=真锁屏，子页面内=回待机屏；发之前先截图确认当前屏
```

> 以上每个例子里「发键 → 截图 → 判断」的细节（截图命令、放大读焦点、结果验证、时序）统一见 §5。

---

## 7. 参考

- 规范：`keydroidx-core/docs/reference/FEATURE_PHONE_UI_SPEC.md`（§4 软键、§47 全局返回、RULE 01-20）；`keydroidx-core/docs/guide/03-key-model.md`、`07-dialogs.md`
- 源码：映射与兜底 `.../nokia/KeydroidxKeyBinding.java`；只读 Provider `.../nokia/KeydroidxKeyProvider.java`（`AndroidManifest.xml` L306-309）；桌面分发 `.../nokia/KeydroidxDesktopActivity.java`（L541-718 按键分发、L781-815 动作→弹窗键码）；底栏装配 `refreshPageBar()`（L344-368）
