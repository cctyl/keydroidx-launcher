# API 19 兼容修复（之二）：SubscriptionManager 未隔离导致 NoClassDefFoundError

> 设备：`4a24ecf`（Android 4.4 / SDK 19，240×320）
> 关联计划：`docs/vector-drawable-api19-fix.md`（矢量图崩溃已修复并通过构建验证）

## 一、故障现象（修复矢量图后的第二个崩溃）
修复矢量图并重新安装后，`KeydroidxDesktopActivity` 能完成布局膨胀，但在 `onResume` 阶段再次崩溃：

```
E/AndroidRuntime: FATAL EXCEPTION: main
  Process: io.github.cctyl.nokia.debug, PID: 7141
  java.lang.NoClassDefFoundError: android.telephony.SubscriptionManager
    at ru.playsoftware.j2meloader.nokia.StatusBarController.start(StatusBarController.java:126)
    at ru.playsoftware.j2meloader.nokia.KeydroidxDesktopActivity.onResume(KeydroidxDesktopActivity.java:85)
```

## 二、根因
- `android.telephony.SubscriptionManager` 是 **API 22（Android 5.1）** 才引入的类。
- `StatusBarController.start()` 第 126 行**无条件**执行：
  ```java
  subscriptionManager = (SubscriptionManager) activity.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
  ```
- 该强制转换会触发 `SubscriptionManager` 类的加载，API 19 上该类不存在 → `NoClassDefFoundError`。
- 注意：`Context.TELEPHONY_SUBSCRIPTION_SERVICE` 是 `static final String` 常量，编译期内联，不会触发类加载（已通过实测崩溃栈确认，崩溃只指向 `SubscriptionManager` 类，而非该常量）。

## 三、为什么其它 SubscriptionManager 使用点是安全的（已逐项核对）
- 第 159、215、254 行：均有 `Build.VERSION.SDK_INT >= 22 && subscriptionManager != null` 守卫。
- `probeSubIdsAndListen()` 第 318 行：`if (Build.VERSION.SDK_INT < 24)` 时降级为 `telephonyManager.listen(listener1, LISTEN_SIGNAL_STRENGTHS)`（默认卡监听）。
- `getPhoneCount()` 第 482 行：`>= 23` 守卫，低版本返回 1（单卡）。
- `stop()` 第 194 行：`subscriptionManager != null` 守卫，低版本为 null 自然跳过。

## 四、修复方案（最小改动）
将第 126 行用 `Build.VERSION.SDK_INT >= 22` 包裹，使 API 19 上 `subscriptionManager` 保持 `null`，自动走单卡降级路径：

```java
// SubscriptionManager 仅在 API 22+ 存在（双卡订阅），API 19 无此类，
// 直接引用会 NoClassDefFoundError；低版本保持 null，后续使用点均已守卫并降级单卡。
if (Build.VERSION.SDK_INT >= 22) {
    subscriptionManager = (SubscriptionManager) activity.getSystemService(
            Context.TELEPHONY_SUBSCRIPTION_SERVICE);
}
```

影响：单向安全。API 22+ 行为完全不变；API 19 仅丢失双卡能力（4.4 设备本无双卡订阅机制，符合实际），单卡信号/WiFi/蓝牙/飞行/电池均正常。

## 五、验证方式（迭代式）
1. 修改后重新 `assembleOpenDebug -x lint` 构建并 `adb -s 4a24ecf install -r`。
2. 启动 `KeydroidxDesktopActivity`，抓取 logcat，确认：
   - 不再出现 `NoClassDefFoundError: android.telephony.SubscriptionManager`；
   - 不再出现 `invalid drawable tag vector` / `InflateException`。
3. 若仍崩溃，以设备真实崩溃栈为准，继续“修一处→构建→安装→实测”的迭代，直至桌面在 4.4 上稳定进入。
4. 顶栏图标（信号/WiFi/蓝牙/飞行/电池）与桌面快捷入口在 4a24ecf 上目视确认正常显示。
5. 高版本设备（320×480、16:9）回归，确认无回退。

## 六、验证结果（2026-07-30，设备 4a24ecf / SDK 19）
- 重新 `assembleOpenDebug -x lint` 构建成功，`adb -s 4a24ecf install -r` 安装成功。
- 启动 `KeydroidxDesktopActivity`，logcat 确认：
  - `I/ActivityManager: Displayed ... KeydroidxDesktopActivity` —— **Activity 正常显示**（首次安装 MultiDex 解压约 20s，属正常）。
  - `I/KeydroidxDesktop: [Desktop] 首次启动：进入按键绑定向导` —— 桌面逻辑已跑起来。
  - `D/KeydroidxSB: registerSignalListeners fallback: probe subIds (both SIMs)` + `onSignalStrengthsChanged slot=0 level=0` —— 顶栏信号走 API 19 单卡降级路径，正常工作。
  - 原 `NoClassDefFoundError: android.telephony.SubscriptionManager` 已消失。
- 日志残留 `E/dalvikvm: Could not find class 'android.telephony.SubscriptionManager'` 等是 **Dalvik 验证器无害告警（VFY）**：类引用已被 `SDK_INT >= 22` 守卫、运行时不进入该分支，方法完整执行（后续 `KeydroidxSB` 日志可证），**非崩溃**。不同 4.4 ROM 的验证器严格度可能不同，但本设备实测通过。
- 说明：该 4.4 设备的 `screencap` 无法取帧（pull 仅 1024 字节），故未做截图目视确认；以 logcat 中 Activity `Displayed` + 桌面启动日志为判定依据。

## 七、受影响文件
- `app/src/main/java/ru/playsoftware/j2meloader/nokia/StatusBarController.java`（仅第 126 行）
- 不涉及布局/资源改动；构建配置（`vectorDrawables.useSupportLibrary` 等）不改。
