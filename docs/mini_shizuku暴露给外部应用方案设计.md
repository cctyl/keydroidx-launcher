# mini_shizuku 暴露给外部应用方案设计（最终落地版）

## 1. 方案背景与目标

`mini_shizuku` 是本 Launcher 自带的轻量级 Shell 进程执行组件，运行在 Android 的 `shell` (UID 2000) 权限下。
本方案目标：
1. 将客户端调用封装为通用 SDK（`keydroidx-core:nokia-mini-shizuku` 模块），供外部生态应用集成。
2. **严格鉴权**：只有与 Launcher 拥有**完全相同签名**的应用，才能通过 SDK 执行 Shell 命令；防止设备上的恶意/第三方应用提权。
3. **兼容性**：在 Android 4.4 到最新版本均可稳定运行。

---

## 2. 详细鉴权流程（核心机制）

为避免 SELinux 跨域拦截（Android 8+ 禁止普通应用连接 shell 域的 unix domain socket），网络传输沿用 **TCP 127.0.0.1:10500**。
鉴权核心在于 **“动态随机密钥 K + 启动器 Provider 签名核验”** 的双重闭环。

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. 密钥生成阶段                                                        │
│    Launcher 启动时，NokiaShizukuKeyHolder 在进程内存中通过              │
│    SecureRandom 懒加载生成 32 字节（64 位十六进制）的高强度随机密钥 K   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
       ┌────────────────────────────┴────────────────────────────┐
       ▼                                                         ▼
┌───────────────────────────────────────┐ ┌───────────────────────────────────────┐
│ 2. 第三方应用获取 K (SDK 侧)           │ │ 3. 服务端获取真实 K (Server 侧)       │
│                                       │ │                                       │
│ ① 外部应用调用 MiniShizuku.exec(...)  │ │ ① Server (UID 2000) 收到带 K 命令，   │
│ ② SDK 自动通过 ContentResolver 调用： │ │   发现本地未缓存 K                    │
│    content://<host>.shizuku/getKey    │ │ ② Server 执行 shell 命令：            │
│ ③ Launcher 的 NokiaShizukuProvider:   │ │    content call --uri content://...   │
│    - 通过 Binder.getCallingUid() 取   │ │    --method getServerKey              │
│      调用方 UID                       │ │ ③ Launcher 的 NokiaShizukuProvider:   │
│    - PackageManager 反查调用方签名    │ │    - 校验调用者 UID == 2000 (shell)   │
│    - 与 Launcher 自身签名逐字节比对   │ │    - 验证通过，返回真实密钥 K          │
│    - 【同签名】：返回密钥 K           │ │ ④ Server 解析输出并在内存中缓存 K    │
│    - 【异签名】：直接拒绝，返回 null  │ └───────────────────┬───────────────────┘
└──────────────────┬────────────────────┘                     │
                   │                                          │
                   └────────────────────┬─────────────────────┘
                                        │
                                        ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. 指令执行与鉴权拦截阶段                                              │
│                                                                        │
│ ① 客户端建立 TCP 连接，发送：                                          │
│    "<K>|EXEC|<command>\n"   或   "<K>|EXEC_OUT|<command>\n"            │
│ ② Server (MsgProcess) 读取第一行，按第一个 '|' 拆出 candidateKey 与命令│
│ ③ Server 将 candidateKey 与本地缓存的真实 K 进行比对：                 │
│    - 【比对成功】：以 UID 2000 执行命令，回传结果                       │
│    - 【比对失败 / 无 K】：直接输出 "ERR:unauthorized\n" 并立即断开连接 │
│ ④ 若命令为探活 "PING\n"：无需 K，直接响应 "OK:pong\n"（开放探活）     │
└────────────────────────────────────────────────────────────────────────┘
```

### 关键细节说明：

1. **为什么恶意应用无法伪造密钥？**
   - 密钥 K 在内存随机生成，不存磁盘文件，不写死在任何代码里。
   - 外部应用想拿到 K，唯一途径是调用 Launcher 的 `NokiaShizukuProvider.getKey`。
   - Provider 使用 Android 底层 Binder 内核维护的 `Binder.getCallingUid()` 确定调用者真实 UID，再通过系统 `PackageManager` 验证签名。调用者无法伪造自己的 UID 或签名。
2. **为什么 Server 端可以用 `content call` 获取真实 K？**
   - Server 是通过 `app_process` 启动的守护进程，运行在 UID 2000（shell）。
   - Android 系统自带的 `content` CLI 工具运行身份就是 shell。
   - `NokiaShizukuProvider.getServerKey` 仅对特权 UID（2000 / 1000 / 0）放行，第三方普通应用调用此方法一律返回 null，杜绝了普通应用冒充 Server 读取真实 K 的可能。
3. **Launcher 进程重启后的自愈机制**：
   - 若 Launcher 意外被杀后重启，会生成新的 K。
   - 客户端（Launcher 或第三方）重新调用时会向 Provider 拉取新 K 并发送。
   - Server 端若比对失败，会**自动触发一次重新拉取**（强制从 Provider 获取最新 K），重新比对成功后执行，**无需重新运行激活脚本**。

---

## 3. 权限控制矩阵

| 操作主体 | 探活 `isRunning()` | 获取密钥 K (`getKey`) | 获取服务端密钥 (`getServerKey`) | 执行命令 `exec` |
|---|---|---|---|---|
| **Launcher 自身** | ✅ 允许 | ✅ 允许（同签名） | ❌ 拒绝（非 shell UID） | ✅ 允许（带有效 K） |
| **同签名第三方应用** | ✅ 允许 | ✅ 允许（同签名） | ❌ 拒绝（非 shell UID） | ✅ 允许（带有效 K） |
| **不同签名应用** | ✅ 允许 | ❌ 拒绝（返回 null） | ❌ 拒绝（非 shell UID） | ❌ 拒绝（报 ERR:unauthorized） |
| **电脑 adb shell (2000)** | ✅ 允许 | ❌ 拒绝（无 Android 签名） | ✅ 允许（UID 为 2000） | ✅ 允许（可通过 getServerKey 取 K 执行） |

---

## 4. 接口与类定义

### 4.1 核心 SDK 接口（供外部应用调用）

```java
package io.github.cctyl.nokia.shizuku;

public final class MiniShizuku {
    // 1. 初始化（在 Application 或 Activity 中注入 Context）
    public static void init(Context context);

    // 2. 检查 mini_shizuku 是否已激活且在线
    public static boolean isRunning();

    // 3. 静默执行命令（返回是否成功发起）
    public static boolean exec(String command);

    // 4. 执行命令并读取 stdout / stderr / exitCode
    public static ExecResult execWithOutput(String command);
}
```

### 4.2 启动器 Provider 契约

- Authority: `${applicationId}.shizuku`（Release: `io.github.cctyl.nokia.shizuku`，Debug: `io.github.cctyl.nokia.debug.shizuku`）
- 方法 `getKey`：入参无，返回 `Bundle["k" -> String]`。签名不匹配时返回 null。
- 方法 `getServerKey`：入参无，返回 `Bundle["k" -> String]`。调用方非 shell/system UID 时返回 null。
