# scripts —— 开发与测试辅助脚本

这个目录放**开发 / 测试用的辅助脚本**，不参与 APK 构建。

| 脚本 / 目录 | 作用 | 依赖 |
|---|---|---|
| `notify.ps1` | 通过 adb 造测试通知（供「通知条 / 通知中心」用例使用） | adb；发通知需 Android 8.0+ |
| `recents.ps1` | 造「最近任务」的测试数据，并打印系统权威记录当对照答案 | adb |
| `device-net/` | 让测试机通过 USB 线联网（RNDIS + Windows ICS） | adb、已 root 的机器、管理员权限 |
| `log-tags/` | 日志 TAG 规范扫描器（提交前 / CI 用） | PowerShell；退出码 0/1 |
| `scan_missing_glyphs.py` | 扫描 UI 文案里的像素字体缺字 | Python + fonttools |
| `merge_glyphs.py` | 把缺字字形合并进内置像素字体 | Python + fonttools |

> 下面的命令都以本目录（`scripts\`）为当前目录执行。

---

## 1. notify.ps1 —— 造测试通知

**给哪些用例造数据**：【桌面-05】通知条、【通知中心-01】【通知中心-02】。

**前置**：adb 能连上手机；手机系统 **Android 8.0 及以上**（`cmd notification post` 从 8.0 才有）。

> 如果 PowerShell 拦着不让跑脚本，改成：
> `powershell -NoProfile -ExecutionPolicy Bypass -File .\notify.ps1 -Count 3`

| 命令 | 干什么 |
|---|---|
| `.\notify.ps1` | 发 1 条测试通知 |
| `.\notify.ps1 -Count 3` | 连发 3 条（验证「N 条未读通知」和"只显示最新一条"） |
| `.\notify.ps1 -Title "订单" -Text "已发货"` | 自定义标题和内容 |
| `.\notify.ps1 -TimeoutMs 5000` | 发一条 5 秒后自动消失的 |
| `.\notify.ps1 -List` | 看当前手机上有几条通知（并列出前 40 行关键信息） |
| `.\notify.ps1 -Clear` | 尝试清空（不可靠；失败会打印手动清理的办法） |
| `.\notify.ps1 -Serial 5990a29a` | 指定设备（连了多台时） |

**典型用法**：

```powershell
cd scripts
.\notify.ps1 -Count 3          # 手机上现在有 3 条
# 回待机屏看通知条：应该是「3 条未读通知 · 最新一条的标题：内容」
.\notify.ps1 -List             # 确认设备上确实有 3 条
# 清空：在桌面「通知中心 → 左软键选项 → 全部清除」，之后通知条应变为「暂无通知」
```

**注意**：

- 脚本自己打印的信息**全是英文**——PowerShell 5.1 会把"无 BOM 的 UTF-8"当 ANSI 读，中文会变乱码。但**通知的标题/内容可以用中文**（通过 `-Title` / `-Text` 传进去）。
- **清空通知没有可靠的 adb 命令**。最干净的做法是用桌面的「通知中心 → 全部清除」（这本身就是被测功能）；脚本的 `-Clear` 只是尽力尝试，失败属正常。
- **Android 8.0 以下**发不了通知，脚本会直接报错并给替代方案（换台机器，或手动打开某个会弹通知的应用）。

---

## 2. recents.ps1 —— 最近任务的测试数据 + 对照答案

**给哪些用例用**：【桌面-08】后台管理组件、【最近任务-01】【最近任务-02】。

**为什么需要它**：桌面的「最近任务」读的是**系统记录的"你打开过什么"**（不是"哪些进程还活着"）。所以测试要分两步——

- 用 `-Seed` **造一批已知的任务**（不然列表可能是空的、或者混着别人打开过的应用，没法判断对错）；
- 用 `-List` **读出系统自己的记录**——那就是"标准答案"，桌面显示得对不对，一比就知道。

| 命令 | 干什么 |
|---|---|
| `.\recents.ps1 -List` | 打印系统当前记录的最近任务（包名 + 个数）——**这就是答案** |
| `.\recents.ps1 -Seed "包名1,包名2"` | 依次打开这些应用（每个都会进入系统最近任务） |
| `.\recents.ps1 -Clear` | 尽力删掉系统里已记录的任务（多数 ROM 需要 mini_shizuku / root） |

**完整测法**：

```powershell
cd scripts
.\recents.ps1 -List
.\recents.ps1 -Seed "com.android.settings,com.android.deskclock,com.android.calculator2"
.\recents.ps1 -List
# 然后按手机 Home 键回待机屏，看桌面组件 / 进最近任务页，跟上面输出逐条比
```

**注意**：

- 包名要真实存在：脚本会先用 `pm list packages` 校验，不存在会跳过并提示。查包名用 `adb shell pm list packages | Select-String 关键字`。
- **系统应用同样算数**——代码注释明确写着"用户打开过的「设置」本来就该出现在最近任务里"。
- **清理请优先在桌面的「最近任务 → 选项 → 清理全部任务」里做**（那本身就是被测功能）；脚本的 `-Clear` 只是备用手段。

---

## 3. device-net/ —— 让测试机通过 USB 联网

只做一件事：让**没有任何无线通路**的测试机（UNNO F3 / Android 4.4.4）通过 USB 线联上互联网。

| 文件 | 作用 |
|---|---|
| `device-net\net-up.ps1` | 设备侧：切 USB 组合为 `rndis,adb` → 配 `rndis0` → 校时 → 自检 |
| `device-net\pc-ics.ps1` | PC 侧：把上网的网卡共享给 RNDIS 网卡（自动提权） |
| `device-net\README.md` | **完整说明**（原理、使用步骤、排错速查表、已知限制） |

日常只有一条命令：插上手机 → `cd scripts\device-net` → `.\net-up.ps1`。详情看该目录自己的 README。

---

## 4. log-tags/ —— 日志 TAG 规范扫描

对全项目扫描 6 类日志 TAG 偏离：双轨 / 未回填、超长（>23 字符）、死常量、常量命名非标准、TAG 共用提示、绕过门面直调 `android.util.Log`。

```powershell
cd scripts
.\log-tags\check-log-tags.ps1
.\log-tags\check-log-tags.ps1 -ProjectRoot D:\project\keydroidx_ecosystem\keydroidx-music
```

退出码 **0 = 全部通过，1 = 存在偏离**，可直接挂到 CI 或提交前钩子。

---

## 5. 字体脚本 —— 缺字扫描与补齐

针对内置像素字体 `app/src/main/assets/fonts/ArkPixel-12px.ttf`（方舟像素字体 zh_cn 子集版）的两个维护脚本。

### 背景

ArkPixel-12px 是子集字体，部分汉字没有字形，UI 上会渲染成**方块**（Android 4.4 对自定义 TTF 没有缺字回退机制）。2026-09 已一次性补齐 36 个缺字（即/执/骤/窗/然/热/滚/毁/聚/旋/遥/避/辨/概/警等），字形来源为同为 12px 像素风格的 FusionPixel-12px。

**新增文案后如果出现方块字，按下面顺序跑这两个脚本即可补齐。**

### 1. scan_missing_glyphs.py — 扫描缺字

扫描项目所有 UI 文案（`values*/strings.xml`、layout 的 `android:text`、Java 里的字符串字面量），列出 ArkPixel 中没有字形的字符及其出现位置。

```powershell
python -X utf8 scripts\scan_missing_glyphs.py
```

无输出 = 全部覆盖；有输出则逐字列出缺字位置。**新加文案后先跑这个确认是否缺字。**

### 2. merge_glyphs.py — 补齐字形

把扫描出的 UI 缺字字形从 `FusionPixel-12px.ttf` 提取并合并进 `ArkPixel-12px.ttf`：

- 复合字形自动展开为简单轮廓
- 同步 `hmtx` / 全部 3 张 cmap 子表（format 4 / 12）
- 写回前校验，写回后独立重载验证 0 缺字、0 悬空引用

```powershell
python -X utf8 scripts\merge_glyphs.py
```

**跑完后必须重新构建 APK 并装机确认显示正常**（构建期 AAPT 不校验字形）。

### 依赖

```
pip install fonttools
```

### 注意

- 两个脚本按自身位置自动定位 `app/src/main`，放在仓库 `scripts/` 下即可，无需改路径
- 补字来源限定 FusionPixel；若某字 FusionPixel 也缺，脚本会跳过并在末尾提示剩余缺字
- ArkPixel 字体文件较大（约 5MB），合并后 diff 是二进制，无法逐行审阅，务必以真机显示为准
