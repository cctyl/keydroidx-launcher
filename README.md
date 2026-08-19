# Nokia Launcher

> 一个以诺基亚 S40 / S60 风格重塑的安卓桌面启动器（Launcher）
>
> 基于 [J2ME-Loader](https://github.com/nikita36078/J2ME-Loader) 改造，把 J2ME 应用与安卓原生应用融合在同一个诺基亚桌面上，并完整适配物理按键（方向键 / 左右软键 / 确认键）。

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84)](#)
[![Style](https://img.shields.io/badge/style-Nokia%20S40%2FS60-124191)](#)

---

## 那种熟悉的感觉，又回来了

![诺基亚桌面预览](docs/screenshot.png)

这不是一个普通启动器。它把诺基亚的功能表、软键栏、信号格，原样搬到了安卓上；
同时把 **J2ME-Loader 里安装的 JAR 应用** 收进「百宝箱」，让老游戏和安卓应用看起来像是同一类东西。

---

## 主要特性

- **诺基亚风格 UI**：顶部真实信号 / WiFi / 电量 / 时间状态栏，底部左右软键 + 中间键。
- **融合应用**：功能表第 1 页为系统功能（信息 / 联系人 / 通话记录 / 日历 / 相册 / 相机 / 设置 / 桌面设置），第 2 页起为安卓已装应用；JAR 应用统一收口到「百宝箱」。
- **桌面快捷栏**：可自由编辑的快捷方式，支持安卓应用与 JAR 混合摆放，图标即真实应用图标。
- **物理按键优先**：方向键在可选中项之间移动并高亮，确认键打开，左右软键映射到当前页面动作；所有可点击项均可被按键选中。
- **真实系统信息**：双卡信号、运营商名、WiFi、电量百分比、实时时间全部来自系统。
- **通知区**：读取系统通知并在桌面下半部滚动展示，支持清除。
- **按键音**：按下物理按键时播放系统按键音反馈。
- **成为默认桌面**：可作为系统 Home 桌面运行。

---

## 预览

### 桌面

![诺基亚桌面预览](docs/screenshot.png)

顶栏显示信号、电量与时间；中间是桌面快捷栏与通知区；底部是左右软键 + 中键。

### 功能表

![诺基亚功能表预览](docs/screenshot_menu.png)

按中键打开功能表。第 1 页为系统功能（信息 / 联系人 / 通话记录 / 日历 / 相册 / 影音天地 / 相机 / 一键通 / 百宝箱），第 2 页起为安卓已装应用；JAR 应用统一收口到「百宝箱」。

---

## 构建与安装

环境要求（详见 `CODEBUDDY.md`）：

- Android SDK + **JDK 17**（不要用 JDK 8/11 或 GraalVM）
- NDK `22.1.7171670`
- 本地签名：`app/test.jks` + `keystore.properties`（已 git-ignore，切勿提交）

构建发行版 APK（推荐 `open` 渠道）：

```bash
.\gradlew.bat assembleOpenRelease -x lint
```

输出：`app/build/outputs/apk/open/release/J2ME_Loader-*-open-release.apk`

安装后设为默认桌面：进入系统「设置 → 主屏幕 / 默认启动器」，选择本应用。

---

## mini_shizuku 权限服务

部分功能需要系统级（shell/adb）权限。本应用内置了 **mini_shizuku**：服务端以 `app_process`（shell 身份）运行在手机上，应用通过本地 TCP 通道以 shell 权限执行系统命令。

### 什么时候需要它

- 桌面设置里的「mini_shizuku」页面显示「离线」。

### 激活步骤（只需一次，需要电脑 + USB 数据线）

1. 手机开启「开发者选项 → USB 调试」，用数据线连接电脑。
2. 手机进入「桌面设置 → 高级设置 → mini_shizuku → adb 激活」，页面会显示一条命令（左软键可复制）。
3. 在电脑命令行执行这条命令（脚本已随应用自动释放到手机，无需单独下载）：

   ```bash
   adb shell sh /sdcard/Android/data/io.github.cctyl.nokia/files/mini_shizuku.sh
   ```

   > 调试版（包名后缀 `.debug`）命令路径为 `/sdcard/Android/data/io.github.cctyl.nokia.debug/files/mini_shizuku.sh`，以手机页面显示为准。

4. 手机回到 mini_shizuku 页面，按左软键「刷新」，看到「在线」即激活成功。

> 提示：手机重启后服务会停止，重新执行第 3 步即可。激活后请及时断开 USB 调试，注意保护手机安全。

---

## 致谢与开源许可

本项目基于开源社区优秀项目与资源开发，特此致谢：

- **J2ME 模拟核心**：基于 [J2ME-Loader](https://github.com/nikita36078/J2ME-Loader) 开发，感谢原作者 [nikita36078](https://github.com/nikita36078)。
- **复古像素字体**：
  - **方舟像素字体 (Ark Pixel Font)**：由 [TakWolf](https://github.com/TakWolf/ark-pixel-font) 开发设计的开源泛中日韩像素字体（SIL Open Font License 1.1），提供 1:1 精修的 S40 经典 12px 点阵质感。
  - **缝合怪像素字体 (Fusion Pixel Font)**：由 [TakWolf](https://github.com/TakWolf/fusion-pixel-font) 整合开发的超全字符集像素字体（SIL Open Font License 1.1），提供 100% 汉字全覆盖与纯正复古液晶感。
- **矢量图标体系**：
  - **Google Material Icons**：由 Google 团队提供的开源矢量图标字库（Apache License 2.0）。
  - **S60 图标库 (s60-icon-pack)**：由 [x1unix](https://github.com/x1unix) 整理的塞班 S60 系统图标库（[s60-icon-pack](https://github.com/x1unix/s60-icon-pack)），用于还原诺基亚经典界面风格。
