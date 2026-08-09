# KeyMapper 专家模式拦截 Power 键原理分析

> 文档性质：技术调研结论，供审核后决定本项目（诺基亚桌面 Launcher）是否借鉴实现。
> 调研对象：`D:\project\KeyMapper`（开源项目，包名 `io.github.sds100.keymapper`，Kotlin + Rust + C++）。
> 调研目的：用户反馈本桌面上 power 键与 home 键关联会导致进程退出，希望了解 KeyMapper 在
> 「专家模式（需 shizuku）」下能录制、拦截 power 键的实现原理，评估能否借鉴到本项目。

---

## 1. 结论摘要

KeyMapper 拦截 power 键**不是**在 Android 框架层做的，而是在 **Linux 内核输入子系统（evdev）层**：
通过 `EVIOCGRAB` ioctl 对物理输入设备（`/dev/input/eventX`）做**独占抓取（grab）**，使系统
InputReader 完全收不到该设备的按键事件；同时创建 **uinput 虚拟设备**将未消费的事件原样回放给
系统。因为拦截发生在内核层、早于 framework 的 `PhoneWindowManager`，所以 power 键这类系统
保留键也能被截获。

**权限要求**：打开并 grab `/dev/input` 设备、写 `/dev/uinput` 需要 shell 或 root 身份。
KeyMapper 的专家模式正是通过 shizuku（或 adb/root）启动一个独立的高权限进程（SystemBridge）
来实现的。

**对本项目的意义**：本项目已统一使用 mini_shizuku（`app_process` + TCP，shell 身份），与
KeyMapper 的 shizuku 模式**权限完全等价**，理论上具备实现同样能力的基础。

---

## 2. 需求背景

- 本应用是诺基亚风格安卓桌面 Launcher（`NokiaDesktopActivity`），已作为系统默认 Home 运行。
- 用户反馈：**按下 power 键时，power 键与 home 键关联，会导致桌面进程退出**。
- 此前已有《挂机键拦截方案设计文档》，分析了 `KEYCODE_ENDCALL` 在 Android 4.4 下被
  `PhoneWindowManager` 清除 `ACTION_PASS_TO_USER`、App 层无法拦截的问题，并给出
  "AccessibilityService 事后拉回 + 修改 END_BUTTON_BEHAVIOR" 的兜底方案。
- 本次调研是想确认：是否有比"事后拉回"更彻底的"事前拦截"手段，即像 KeyMapper 一样在内核
  输入层直接截获 power 键。

---

## 3. KeyMapper 整体架构

| 模块 | 职责 |
|------|------|
| `app/` | 入口：`MainActivity`、`KeyMapperApp`、`MyAccessibilityService`（普通模式） |
| `base/` | UI 与 ViewModel：keymap 配置、触发器、专家模式设置页等 |
| `system/` | 设备 API、权限管理、shizuku 适配 |
| `sysbridge/` | C++/JNI 与系统桥：`SystemBridge`、shizuku 启动器 |
| `evdev/` | **Rust 实现的内核输入事件处理核心**（grab/uinput/事件循环） |
| `common/`、`data/`、`api/` 等 | 工具、Room 数据库、AIDL 接口 |

两条工作通道：

1. **普通模式**：`AccessibilityService` 监听按键，只能拿到框架放行的键（Home/Back/音量等），
   **拿不到 power 键**。
2. **专家模式（SystemBridge）**：以 shell/root 身份运行的独立进程，通过 evdev grab 拿到
   **所有物理按键**，包括 power 键。

---

## 4. 核心原理：evdev 独占抓取（EVIOCGRAB）+ uinput 回放

### 4.1 抓取设备（grab）

`evdev/src/main/rust/evdev_manager/core/src/grabbed_device.rs`：

```rust
// GrabbedDevice::new()
let mut evdev = Self::open_evdev_device(device_path)?;   // 打开 /dev/input/eventX（O_NONBLOCK）
evdev.grab(GrabMode::Grab).map_err(EvdevError::from)?;   // ★ EVIOCGRAB：独占抓取
let uinput = UInputDevice::create_from_device(&evdev)?;  // ★ 创建 uinput 虚拟设备用于回放
```

关键点：

- `grab(GrabMode::Grab)` 对应内核 `EVIOCGRAB` ioctl。**grab 之后，该设备的所有按键事件
  只有抓取方（SystemBridge）能读到，Android 的 InputReader 彻底收不到**。
- 同时用 `UInputDevice::create_from_device()` 创建**同型号的 uinput 虚拟设备**，用于把事件
  回放给系统，保证"没被消费的按键行为不变"。
- 抓取目标按设备标识匹配（名称、总线、vendor、product），见 `grab_target.rs` 的
  `matches_device_info()`；具体抓哪些设备由 app 端下发（`setGrabTargets`）。

### 4.2 事件循环：消费则拦截，不消费则回放

`evdev/src/main/rust/evdev_manager/core/src/event_loop.rs`：

```rust
// process_event()
let consumed = match event.event_code {
    EventCode::EV_KEY(_) | EventCode::EV_UNK { event_type: 1, .. } => {
        self.callback.on_evdev_event(device_id, &grabbed_device.device_info, event)
        // ★ 回调到 Kotlin/App 层，返回 true = 已消费（拦截），false = 未消费
    }
    _ => false,
};

if !consumed {
    // ★ 未消费 → 原样写入 uinput 虚拟设备回放给系统
    grabbed_device.uinput.write_event(event_type, event_code, event.value).ok();
}
```

完整数据流：

```
物理按键（含 power）
   │ 内核 evdev 设备 /dev/input/eventX（已被 EVIOCGRAB 独占）
   ▼
SystemBridge（shell 身份，Rust 事件循环，mio 轮询 fd）
   │ JNI 回调 onEvdevEvent()
   ▼
App 层判断是否有 KeyMap 命中（录制/重映射/宏）
   ├─ 命中 → 返回 consumed=true → 事件丢弃，系统无感知（拦截成功）★
   └─ 未命中 → 返回 consumed=false → 写入 uinput 虚拟设备 → 系统正常收到（回放）
```

### 4.3 注入能力

除拦截外，SystemBridge 还通过 uinput 实现**任意按键注入**：

- `write_event(deviceId, type, code, value)`：直接写 evdev 事件。
- `write_key_code_event()`：按 Android keyCode 查 keylayout 映射回 scancode 后写入 uinput。
- `injectInputEvent()`：直接调系统 `IInputManager.injectInputEvent()`（shell 有 INJECT 权限）。

这使 KeyMapper 能"把 power 键重映射为别的键"或"录制后回放宏"。

---

## 5. 为什么需要 shizuku/root（专家模式）

`sysbridge/service/SystemBridge.kt` 明确要求进程身份：

```kotlin
private val processPackageName: String = when (Process.myUid()) {
    Process.ROOT_UID -> "root"
    Process.SHELL_UID -> "com.android.shell"
    else -> throw IllegalStateException("SystemBridge must run as root or shell user")
}
```

需要 shell/root 才能做的事：

1. 打开并写入 `/dev/input/eventX`（普通 App 无权限，/dev/input 通常 `root:input` 660）。
2. 执行 `EVIOCGRAB`。
3. 写 `/dev/uinput` 创建虚拟设备。

### 5.1 shizuku 模式下如何启动

`sysbridge/starter/SystemBridgeStarter.kt`：

1. 通过 `Shizuku.bindUserService()` 启动 `ShizukuStarterService`（shizuku 的 UserService，
   以 shell 身份跑）。
2. `ShizukuStarterService` 执行 `sh /data/local/tmp/.../start.sh`（`executeCommand()`）。
3. `start.sh`（`sysbridge/src/main/res/raw/start.sh`）把 starter 二进制拷到
   `/data/local/tmp/keymapper_sysbridge_starter`，`chown 2000`（shell），然后
   `app_process` 方式启动 `SystemBridge`，加载 `libevdev_manager.so`（Rust）。
4. SystemBridge 作为**独立常驻进程**运行，即使 shizuku 挂了也继续工作。

### 5.2 三种启动方式对比

| 方式 | 身份 | 能力 |
|------|------|------|
| shizuku（专家模式） | shell | 可 grab 所有输入设备，包括 power 键 |
| adb（`startWithAdb`） | shell | 同上，需每次开机后 adb 手动执行 |
| root（`startWithRoot`） | root | 同上，无额外限制 |

---

## 6. 关键安全机制：长按 power 10 秒紧急退出

`SystemBridge.kt`（`onEmergencyKillSystemBridge`）与 Rust 侧配合：

- 当 **power 键被长按超过 10 秒**时，Rust 通过 JNI 回调通知 app 紧急销毁 SystemBridge
  （`destroy()` → `exitProcess(0)`）。
- 目的：防止 SystemBridge 意外挂掉（crash/被杀）后，已 grab 的设备没有被释放，导致
  **power 键永久失效、无法关机或唤醒**。
- 这是实现该方案时**必须考虑**的兜底：SystemBridge 自身要能识别"长时间无心跳"并主动释放
  所有 grab，或者依赖系统的长按强制重启路径。

---

## 7. 对本项目的启示与评估

### 7.1 权限基础已经具备

- 本项目 mini_shizuku 已统一为 `app_process` + TCP 的 shell 身份通道（`mini_shizuku` 模块），
  与 KeyMapper 的 shizuku 模式权限等价。
- 理论上可以让 mini_shizuku 侧增加 evdev grab + uinput 回放能力，从而在内核层拦截
  power/挂机键。

### 7.2 需要新增的工作量（原生层）

| 工作项 | 说明 |
|--------|------|
| evdev 设备枚举 | 遍历 `/dev/input`，识别出物理按键所在设备（power 一般在 `gpio-keys`/`pmic-keys`） |
| EVIOCGRAB 抓取 | 对目标设备执行独占抓取，并创建 uinput 回放设备 |
| 事件循环 | 轮询 fd、读事件、回调判断消费/回放 |
| keylayout 映射 | 把 Android keyCode 与 evdev scancode 互转（KeyMapper 用 Rust 解析 `.kl` 文件） |
| 兜底释放 | 长按 power 超时 / 进程死亡时释放所有 grab，避免 power 键失效 |

本项目已有 `app/src/main/cpp`（NDK）基础设施，可用 C 实现上述逻辑，或参考 KeyMapper 的
Rust `evdev_manager`。

### 7.3 关键约束与风险

1. **grab 是全局性的**：一旦 grab 某个设备，系统在应用存活期间对该设备的所有按键都失明，
   回放逻辑必须非常可靠，否则正常按键会失灵。
2. **安全兜底**：必须实现"进程死亡/长按超时 → 自动释放 grab"，否则可能导致 power 键失效、
   无法关机（KeyMapper 用 10 秒长按触发紧急退出兜底）。
3. **仅按需抓取**：建议只 grab 出问题的那个键所在的设备（如 power 键），不要全量 grab，
   减少回放链路出错面。
4. **Android 版本差异**：本项目兼容 Android 4.4（API 19），`/dev/input` 与 `/dev/uinput`
   的行为在内核层基本一致，但需在目标设备实测（240×320 真机）。
5. **与现有方案的关系**：evdev grab 是"事前拦截"，比现有《挂机键拦截方案》的"事后拉回"
   更彻底，但复杂度与风险显著更高，建议作为后续增强项而非当前默认方案。

### 7.4 一个更轻的替代思路

如果目的只是"避免按 power 键导致进程退出"，可以**只 grab power 键所在设备并消费掉该键**
（或重映射为锁屏等其他动作），其余设备不抓取；这样系统其它按键完全不受影响，风险面最小。

---

## 8. 参考文件索引（KeyMapper 源码）

| 文件 | 关键内容 |
|------|----------|
| `sysbridge/src/main/java/.../service/SystemBridge.kt` | SystemBridge 主进程、shell/root 身份检查、JNI 声明、长按 power 兜底 |
| `sysbridge/src/main/java/.../starter/SystemBridgeStarter.kt` | shizuku UserService 启动、start.sh 生成与执行 |
| `sysbridge/src/main/java/.../shizuku/ShizukuStarterService.kt` | shizuku 侧执行 shell 命令 |
| `sysbridge/src/main/res/raw/start.sh` | 拷贝 starter 到 /data/local/tmp、chown shell、app_process 拉起 |
| `evdev/src/main/rust/evdev_manager/core/src/grabbed_device.rs` | EVIOCGRAB 独占抓取 + uinput 虚拟设备创建 |
| `evdev/src/main/rust/evdev_manager/core/src/event_loop.rs` | 事件循环、消费/回放决策 |
| `evdev/src/main/rust/evdev_manager/core/src/evdev_grab_controller.rs` | 抓取目标管理、设备热插拔（inotify） |
| `evdev/src/main/rust/evdev_manager/core/src/grab_target.rs` | 抓取目标匹配（name/bus/vendor/product） |
| `system/src/main/java/.../shizuku/ShizukuAdapterImpl.kt` | shizuku 状态监听与权限请求 |
| `base/src/main/java/.../detection/DetectKeyMapsUseCase.kt` | App 层按键消费判断、注入入口 |
