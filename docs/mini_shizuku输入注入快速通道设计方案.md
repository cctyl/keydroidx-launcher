# mini_shizuku 输入注入快速通道设计方案

> 状态：**方案设计阶段，代码未改动**
> 目标：把 `keydroidx-foucs` 在 API 19-23 设备上的确认键点击延迟从实测 **~1.25s 降到 <50ms**。
> 本方案只改动 launcher 侧服务端（`mini_shizuku` 模块），foucs 侧零改动。

---

## 一、背景：1 秒延迟从哪来

foucs 在 API < 24 设备上没有 `dispatchGesture`，点击/滑动靠 `MiniShizuku.exec("input swipe …")`
（`keydroidx-foucs/app/src/main/java/io/github/cctyl/keydroidx/focus/ShellGesturePerformer.java:41/51/62`）。

在 4.4 真机上实测（PC 侧计时，已扣掉 `adb shell true` 的 186ms 基线）：

| 命令 | 实测耗时 |
|:---|:---|
| `input keyevent 0` | 1115ms（命令本身 ≈928ms） |
| `input tap 120 160` | 1104ms |
| `input swipe x y x y 0` | 1150ms |
| `input swipe x y x y 100`（foucs 现用） | 1247ms |
| `app_process … com.android.commands.input.Input`（空跑，不注入） | 891ms |

结论：**`input` 命令 ≈704ms 花在 Dalvik VM 冷启动**，其中：

```
ShellUtil.execute("input swipe …")            ShellUtil.java:33-41
  → fork /system/bin/sh -c <cmd>              ← 第 1 次 fork
  → /system/bin/input（shell 脚本）
      exec app_process /system/bin
           com.android.commands.input.Input   ← 第 2 次 fork + 全新 VM 冷启动
  → InputManager.injectInputEvent(...)
  → main() 结束，VM 销毁                       ← 一次都没复用
```

每次点击都要付两次 fork + 一次完整 VM 冷启动 + 加载 `input.jar` + 进程销毁。

---

## 二、现状链路（代码为准）

```
[foucs 进程 u0_aXX]  MiniShizuku.exec("input swipe …")         ShellGesturePerformer.java:81
      │  取密钥 K（launcher Provider，同签名才放行）
      ▼
   TCP 127.0.0.1:10500  报文 "<K>|EXEC|<cmd>"                  SocketService.java:24
      ▼
[app_process 服务端，uid 2000(shell) 或 0(root)，常驻]
   AdbProcess.main → Looper.prepareMainLooper/loop             AdbProcess.java:21-45
   SocketService.start → 线程池(4~16, CallerRunsPolicy)         SocketService.java:27-36
   MsgProcess.run（校验 K）→ dispatch(inner)                    MsgProcess.java:44-108
       ├─ EXEC_OUT|…  → ShellUtil.execWithOutputAndCode         MsgProcess.java:86-88, 186-197
       ├─ INTERCEPTOR_* / PAGE_STATE| / SERVER_STOP             MsgProcess.java:95-102
       └─ 其余       → ShellUtil.execute(cmd)                  MsgProcess.java:103-107
                          └─ Runtime.exec({"sh","-c",cmd}) + waitFor   ShellUtil.java:33-41
```

**服务端进程形态**（`ShizukuRootFragment.java:305`）：

```
app_process -Djava.class.path=<launcher.apk> -Dapp.package=<pkg> \
            ru.playsoftware.mini_shizuku.server.AdbProcess
```

server 类打在 launcher APK 里（靠 `mini_shizuku` 的 `consumerProguardFiles` keep `server.**`）。

---

## 三、方案设计

### 3.1 一句话

**在服务端已经活着的那个 VM 里直接注入事件**，不再为每个 `input` 命令冷启第二个 VM。

```
[foucs] MiniShizuku.exec("input swipe 120 160 120 160 100")   ← 完全不变
   │  TCP 127.0.0.1:10500，报文 "<K>|EXEC|<cmd>"               ← 完全不变
   ▼
[app_process 服务端，uid 2000/0，常驻]                          ← 进程模型完全不变
   MsgProcess.dispatch(cmd)
       ├─ 新增快速路径：cmd 严格匹配 input tap|swipe|keyevent
       │     → 构造 MotionEvent / KeyEvent
       │     → 反射 InputManager.getInstance().injectInputEvent(event, ASYNC)
       │     → 失败/不匹配 → 回退 ShellUtil.execute(cmd)
       └─ 其余命令 → 原路径不变
```

### 3.2 三条不变（"不是另开一套通道"）

| 层次 | 是否变化 |
|:---|:---|
| 客户端 API `MiniShizuku.exec / execWithOutput / execAcked` | **不变** |
| 线上协议 `<K>\|EXEC\|<cmd>` / `<K>\|EXEC_OUT\|<cmd>` | **不变** |
| 服务端进程（`app_process` + Looper + TCP + 线程池） | **不变** |
| 服务端"怎么执行这条命令字符串" | **变**（仅此一处） |

### 3.3 为什么必须由服务端注入

- `INJECT_EVENTS` 是 signature 级权限，只授予 shell(2000)/root(0) 这类 uid；foucs 是普通应用 uid，拿不到。
- 服务端进程已经付过一次 `app_process` 的 VM 启动代价；`sh -c input` 是在**另一个进程里再付一次**。
- 因此"在服务端 VM 内注入"是唯一既合规又能省掉冷启动的做法。

### 3.4 实现要点（落地时的硬性约束）

1. **入口位置**：在 `MsgProcess.dispatch()` 里，`Exec` 静默分支（现 `MsgProcess.java:103-107`）之前，且**只处理 `EXEC|` 前缀**；`EXEC_OUT|` 分支若也要走快路径，必须回写 `EXIT:0`（见 R4）。
2. **参数白名单解析，不猜**：仅接受严格形态——
   - `input tap <int x> <int y>`
   - `input swipe <int x1> <int y1> <int x2> <int y2> [<int durMs>]`
   - `input keyevent <int keyCode>`（纯整数；带 `KEYCODE_*` 符号名或逗号多键的**一律回退**）
   任何不符合形态、解析异常、参数非整数 → `ShellUtil.execute(cmd)` 原路径。
3. **注入参数对齐 AOSP `Input.java`**：`source = InputDevice.SOURCE_TOUCHSCREEN`、`deviceId`、`pressure/size`、`xPrecision/yPrecision`、swipe 期间的 MOVE 插值序列，需按 4.4 ROM 的 `Input.java` 逐项对齐（见 R2）。
4. **注入模式用 `INJECT_INPUT_EVENT_MODE_ASYNC`**，不用 `WAIT_FOR_RESULT/WAIT_FOR_FINISH`（见 R1）。
5. **全程 `catch (Throwable)`**，任何一步异常都回退 `ShellUtil.execute(cmd)` 并打日志（tag `MiniShizuku`）。
6. **`MotionEvent` 用完 `recycle()`**，避免常驻进程内存泄漏。
7. 新增类放在 `ru.playsoftware.mini_shizuku.server` 包下（已被 proguard 规则 keep，无需新增规则）。

### 3.5 影响面

全生态扫描：目前**只有 foucs 发 `input` 命令**。launcher 自身从未发过 `input` 命令
（`KeydroidxLockReceiver.java:14` 明确注明"不依赖 `input keyevent` 各 ROM 兼容性差"）；
core 的 `sample` 只发 `id; whoami`。故本次改动的实际影响面 = foucs 一家的手势/点击。

---

## 四、风险与应对

| 编号 | 风险 | 严重度 | 依据 | 应对 |
|:---|:---|:---:|:---|:---|
| **R1** | **故障隔离边界被拆掉**：注入在常驻进程内，异常/挂死可打瘫整个服务端 | **高** | `MsgProcess.run` 只 catch `IOException`（`MsgProcess.java:74`）；`ShellUtil.execute` 只吞 IO/中断异常（`ShellUtil.java:38`）；线程池 4~16 + `CallerRunsPolicy`（`SocketService.java:27-29`）——16 线程全占后 accept 线程被拖去跑 `MsgProcess`，服务端对所有生态应用失联（快捷开关/冻结/foucs 全断） | ① `catch (Throwable)` 全覆盖并回退 `ShellUtil.execute`；② 注入用 `ASYNC` 模式，binder 不阻塞；③ 解析失败立即回退；④ 建议给快路径加一次性自检（首次注入失败后本进程内永久回退） |
| **R2** | **事件语义与 AOSP `input` 不一致**：`source` 不为 `SOURCE_TOUCHSCREEN` 时部分 View/WebView 会忽略事件；swipe 不插值 MOVE 会影响 fling 判定 | 中 | AOSP `Input.java` 对 tap/swipe 有具体的 source/deviceId/插值/注入模式组合，各 ROM 版本有差异 | 逐项对齐 4.4 ROM 的 `Input.java` 常量；真机回归：点击、长按、拖拽三类在至少 3 个不同 App 上验证 |
| **R3** | **keyevent 解析面扩大**：`input keyevent` 支持符号名与逗号多键 | 低 | AOSP `Input.java` 参数解析 | 只接受纯整数单键，其余回退；当前生态无调用方，短期零影响 |
| **R4** | **`EXEC_OUT` 语义**：客户端读到 `EXIT:<code>` 才结束，快路径不回写会卡到 3s 读超时 | 低 | core 文档 `docs/guide/17-mini-shizuku.md` §三；`MsgProcess.java:186-197` | 只对 `EXEC|` 生效；若未来扩展到 `EXEC_OUT`，必须合成回写 `EXIT:0` |
| **R5** | **发布链路**：server 类在 launcher APK 内，改一处需发 launcher 新版并重新激活 `app_process` | 低 | `ShizukuRootFragment.java:305`；`mini_shizuku/build.gradle` 的 `consumerProguardFiles` | 旧 APK 的服务端继续走老路径，行为兼容，foucs 不需要配合发版；发版说明里注明"需重新激活 mini_shizuku" |
| **R6** | **与 native 拦截器共存**：同进程内 `InterceptorNative` 走 /dev/uinput 做电源键拦截 | 低 | `MsgProcess.java:113-134`；`InterceptorNative` | 注入事件从 InputDispatcher 下发、不经 /dev/input，理论无干扰；上线前真机回归一次电源键拦截与挂机键拦截 |

### 不是风险的点（已核实）

- **无新增安全面**：能连上 10500 并持有 K 的客户端，本来就能 exec 任意 shell 命令（含 `input`），快路径不放大任何权限。
- **协议与客户端零改动**：`MiniShizuku.exec` 的返回值语义（写入成功即 true）保持不变，foucs 不需要重新编译。
- **`InputManager` 可用性**：`android.hardware.input.InputManager` 自 API 16 起存在，4.4 上反射隐藏方法无隐藏 API 限制。

---

## 五、验证方案（上线前必须做）

1. **延迟**：`MiniShizuku.exec("input tap 120 160")` 前后打点，目标 <50ms（原 ~1247ms）。
2. **正确性**：沿用既有方法——按数字键 5 把光标送到屏幕中心 (120,160)，截图用像素分析定位橙色点，确认仍在 (119,159) 附近，且点击命中的目标与光标一致。
3. **故障回退**：故意传畸形命令（如 `input swipe a b c d`），确认不崩进程、回退到 shell、服务端仍对其他命令可用。
4. **稳定性**：连续注入 200 次后确认 `app_process` 内存无异常增长（`MotionEvent.recycle` 生效）、线程池未耗尽。
5. **回归**：R2 的点击/长按/拖拽 + R6 的电源键/挂机键拦截。

## 六、未决事项

- 快路径是否也覆盖 `input keyevent`（当前生态无调用方，建议**先不做**，保持改动面最小）。
- 是否同步把 foucs 的 swipe 时长 100 → 0（可再省约 100ms，属 foucs 侧独立小改动，与本方案正交）。
- `MOVE` 插值策略：严格复刻 AOSP 还是简化为 DOWN→(可选 MOVE)→UP，需真机对比 fling 手感后定。
