# mini_shizuku 暴露给外部应用方案设计（待审核）

> 状态：**草案，待审核**
> 作者：—
> 关联文档：`docs/mini_shizuku设计文档.md`、`docs/电源键拦截方案设计.md`、`docs/按键机生态与独立应用扩展架构设计.md`

## 0. 目标

1. 把 mini_shizuku 的「以 shell 身份执行命令」能力抽取到 `keydroidx-core` 仓库，供生态内第三方应用（音乐播放器、阅读器、浏览器、工具箱等）以 SDK 形式集成后调用。
2. 加入鉴权：**只有与 launcher 签名相同的应用**才能执行 shell 命令；拦截器等 launcher 专属能力不对外暴露。
3. 顺带堵住当前 server **零鉴权**的安全洞（任何本机应用都能连 `127.0.0.1:10500` 执行任意命令）。

## 1. 现状梳理

### 1.1 mini_shizuku 现有结构（`mini_shizuku` 模块，全在 launcher 仓库）

| 层 | 类 | 职责 | 是否可复用给外部应用 |
|---|---|---|---|
| 门面 | `ru.playsoftware.mini_shizuku.Shizuku` | 对外 API：`isSupported/isRunning/exec/execWithOutput/enablePowerInterceptor/setPageState` | 通用部分（`isRunning/exec/execWithOutput`）可复用；后两个拦截器方法是 **launcher 专属** |
| 客户端 | `client.ShizukuClient` | TCP `127.0.0.1:10500` 行协议 `EXEC\|…` / `EXEC_OUT\|…` | **完全可复用**，外部应用靠它连服务 |
| 服务端 | `server.AdbProcess / SocketService / MsgProcess / InterceptorNative / ShellUtil` | `app_process` 以 shell（uid 2000）身份跑，监听 TCP，分发命令 | **必须留在 launcher**：server 由 launcher 的 APK + `mini_shizuku.sh` 拉起，且 `InterceptorNative` 要从 launcher APK 解 `libnokiainterceptor.so` |

### 1.2 关键事实

1. 当前 server **零鉴权**：任何本机应用都能连 `127.0.0.1:10500` 执行任意 shell 命令。这是把它「暴露给外部」前**必须先堵的洞**。
2. launcher 启动 server 时已通过 `-Djava.class.path=<apk>` 和 `-Dapp.package=<pkg>` 注入了自身 APK 路径与包名（见 `mini_shizuku.sh`、`InterceptorNative.findApkPath`）。可借此拿到「宿主签名基准」。
3. core 仓库已有 `NokiaProviderContract`（正式/Debug 双 authority），已有「按签名区分正式/Debug」的先例可循。
4. core 模块 `nokia-key-core` 通过 `includeBuild` 以源码替换方式被 launcher 引用，core 新增的 client 代码会自动对 launcher 可见。

### 1.3 安全要点

把任意 shell 执行暴露给第三方 = 高危。鉴权必须满足：

- 只放行**与 launcher 同签名**的生态应用（正式版用 release key、Debug 版用 debug key，二者不互通——天然隔离）；
- 拒绝 `adb`/`shell`（uid 2000）之外的未知 uid 直连；
- 拦截器专属命令（`INTERCEPTOR_*` / `PAGE_STATE`）即便同签名，也**应限定只允许 launcher 自身包名**调用，避免第三方误触发电源键拦截。

## 2. 总体方案

### 2.1 模块拆分

```
keydroidx-core/  (新增子模块)
└── nokia-mini-shizuku/                        ← 新模块，纯 client + 契约
    build.gradle                               (com.android.library + maven-publish,
                                               groupId io.github.cctyl.nokia:mini-shizuku-client)
    consumer-rules.pro
    src/main/AndroidManifest.xml
    src/main/java/io/github/cctyl/nokia/shizuku/
        ├── MiniShizuku.java                   ← 对外门面（通用：isRunning/exec/execWithOutput）
        ├── MiniShizukuClient.java             ← IPC 客户端（原 ShizukuClient 搬来改包名）
        ├── MiniShizukuConst.java              ← socket 名、协议前缀等契约常量
        └── CallerIdentity.java                ← 客户端侧自报身份（pkg + signature digest）（可选）

keydroidx-launcher/
├── mini_shizuku/                              ← 保留，但只剩 server + launcher 专属门面
│   └── src/main/java/ru/playsoftware/mini_shizuku/
│       ├── Shizuku.java                      ← 改为委托 core 的 MiniShizuku + 追加拦截器方法
│       └── server/...                         ← 不变（SocketService 改 LocalSocket + 鉴权）
└── app/                                       ← 继续依赖 :mini_shizuku
```

**core 仓库 `settings.gradle` 增加 `include ':nokia-mini-shizuku'`**；`nokia-key-core` 不强依赖它（保持轻量），需要 shell 能力的应用自己 `implementation 'io.github.cctyl.nokia:mini-shizuku-client'`。

### 2.2 通信链路改造：TCP → 抽象套接字 + SO_PEERCRED 鉴权

这是方案的核心，决定鉴权能否落地。

**问题**：`java.net.Socket` 连 `127.0.0.1` 无法拿到对端 uid。Android 的 `android.net.LocalSocket`（AF_UNIX）在 accept 后可调 `getPeerCredentials()` 拿到 `Credentials(pid, uid, gid)`——这是 Shizuku/Sui 等同类项目验证调用方的标准手段。

**改造**：

- server `SocketService` 改用 `LocalServerSocket(NAME)`，`NAME` 取固定抽象命名空间地址 `"mini_shizuku"`（抽象套接字，无文件权限问题，跨 uid 可连）。
- client `MiniShizukuClient` 改用 `LocalSocket()` + `connect(new LocalSocketAddress(NAME, NAMESPACE_ABSTRACT))`。
- 协议（`EXEC|` / `EXEC_OUT|` / `EXIT:` / `INTERCEPTOR_*` / `PAGE_STATE` / `SERVER_STOP`）**保持不变**，迁移成本最低。

> 之所以选抽象套接字而非文件套接字：避免 `/data/local/tmp/...sock` 在 adb/root 切换激活时属主错乱（与现有 `minishizuku.log` 那个坑同源）。

### 2.3 鉴权设计（server 侧）

server 启动时（`AdbProcess.main` → `SocketService` 构造）一次性完成「宿主签名基准」采集：

```
启动时：
  1. 从 System.getProperty("app.package") 得到宿主包名 hostPkg
     （脚本已注入；缺失则用 java.class.path 的 APK 反查 pm path 兜底）
  2. 通过 IPackageManager（ServiceManager.getService("package") 反射拿 stub）
     getPackageInfo(hostPkg, GET_SIGNATURES) → 取签名摘要 hostSigDigest
     并缓存 hostUid = 该 applicationInfo.uid
  3. 把 (hostPkg, hostSigDigest, hostUid) 存为 ServerContext 静态字段
```

每条连接进来时（`MsgProcess` 处理前）：

```
accept 后：
  Credentials c = localSocket.getPeerCredentials();   // 拿到 client 的 uid
  ┌─ 规则 A：uid == hostUid（launcher 自己进程） → 放行全部命令（含拦截器）
  │   用于 launcher 自身的电源键拦截、快捷开关等
  ├─ 规则 B：uid == 2000（shell/adb，即激活者自己） → 放行 SERVER_STOP / 状态探测
  │   仅限维护命令，禁止 EXEC 执行任意 shell（避免 adb shell 直连干坏事）
  ├─ 规则 C：其它 uid → 走「同签名校验」
  │   pkgs = IPackageManager.getPackagesForUid(uid)
  │   任一 pkg 的签名摘要 == hostSigDigest → 放行通用 EXEC/EXEC_OUT
  │   但【禁止】INTERCEPTOR_* / PAGE_STATE（拦截器为 launcher 专属）
  └─ 否则：直接关闭连接，回写 "ERR:unauthorized"
```

签名比对用 SHA-256 of 整个签名字节数组（Android `Signature.toByteArray()`），与 `PackageManager` 签名比对惯例一致；正式/Debug 因签名不同天然隔离。

> 注：`IPackageManager` 反射在 app_process(shell) 里可用——shell 有权调 `PackageManager` 的 Binder 接口（`getPackagesForUid` / `getPackageInfo` 是公开 API，无需特殊权限）。这条链路 Shizuku 本身就在用，可行性已验证。

### 2.4 客户端（core 侧）门面

```java
// io.github.cctyl.nokia.shizuku.MiniShizuku
public final class MiniShizuku {
    public static boolean isSupported();            // 恒 true
    public static boolean isRunning();              // 连抽象套接字
    public static boolean exec(String cmd);         // EXEC|…
    public static String execWithOutput(String cmd);// EXEC_OUT|… → EXIT:
    public static boolean stopServer();             // SERVER_STOP
}
```

外部生态应用集成 core 后：

```java
if (MiniShizuku.isRunning()) {
    String out = MiniShizuku.execWithOutput("dumpsys ...");
}
```

服务端若判定该应用签名不符，client 会收到连接被关闭/`ERR:unauthorized`，`exec*` 返回 `false/null`——与「服务离线」行为一致，外部应用无需特殊处理。

### 2.5 launcher 侧门面（保留专属能力）

`mini_shizuku` 模块里的 `Shizuku.java` 改为：

```java
public final class Shizuku {
    public static boolean isSupported()              { return MiniShizuku.isSupported(); }
    public static boolean isRunning()                { return MiniShizuku.isRunning(); }
    public static boolean exec(String cmd)           { return MiniShizuku.exec(cmd); }
    public static String execWithOutput(String cmd)  { return MiniShizuku.execWithOutput(cmd); }
    // 以下为 launcher 专属（服务端规则 A 下才放行）
    public static boolean enablePowerInterceptor(boolean e) {
        return MiniShizuku.exec("INTERCEPTOR_" + (e ? "START" : "STOP"));  // 复用 execInterceptor 语义
    }
    public static boolean setPageState(boolean isMain) {
        return MiniShizuku.exec("PAGE_STATE|" + (isMain ? 1 : 0));
    }
}
```

launcher 所有 `Shizuku.xxx` 调用点（`NokiaQuickToggleManager`、`NokiaFreezeManager`、`NokiaBgManagerHelper`、`NokiaPowerInterceptFragment`、`MicroActivity`、`NokiaDesktopActivity` 等）**零改动**——签名/包名都不变，仅底层换实现。

### 2.6 模块依赖与联调

- `keydroidx-launcher/mini_shizuku/build.gradle`：新增 `implementation 'io.github.cctyl.nokia:mini-shizuku-client'`，沿用现有 `includeBuild('../keydroidx-core')` 源码替换机制联调（core 需加 `dependencySubstitution` 或直接走 mavenLocal；现有 includeBuild 已对 `nokia-common` 做了替换，可仿照为 mini-shizuku-client 加一行）。
- core 发布到 mavenLocal 后，其它生态应用用普通坐标引用。
- `consumer-rules.pro`：core 侧 client 类不参与 app_process 反射，无需 keep（与现状一致）。launcher 侧 `mini_shizuku/proguard-rules.pro` 的 `-keep server.**` 规则**保持不变**。

## 3. 实施步骤（建议顺序，每步可独立验证）

1. **core：新建 `nokia-mini-shizuku` 模块**
   - `settings.gradle` include；`build.gradle` 仿 `nokia-common`（library + maven-publish，坐标 `io.github.cctyl.nokia:mini-shizuku-client:1.0.0`，依赖仅 androidx.annotation 之类）。
   - 把 `ShizukuClient.java` 搬入并改：① 包名 `io.github.cctyl.nokia.shizuku`；② `java.net.Socket` → `android.net.LocalSocket`（抽象命名空间 `mini_shizuku`）；③ 同步 `MiniShizuku` 门面、`MiniShizukuConst`。

2. **launcher：server 端鉴权改造**（先于 client 切换，可单独验证）
   - `SocketService` 改 `LocalServerSocket`；`MsgProcess` 接 `LocalSocket`。
   - 新增 `ServerAuth`：启动时采集 `(hostPkg, hostSigDigest, hostUid)`；每连接做 A/B/C 规则判定。
   - 命令分发里增加「拦截器命令仅 hostUid 可调」的二级校验。

3. **launcher：`Shizuku` 门面委托 core**
   - `mini_shizuku/build.gradle` 加 core client 依赖 + includeBuild 替换规则。
   - `Shizuku.java` 改写为薄委托（保留全部公开方法签名）。
   - 编译 + 跑 `assembleOpenDebug`，安装后用现有桌面设置页验证 `isRunning()`、快捷开关、电源键拦截全部回归正常（**回归点：launcher 自身走规则 A，应完全不受影响**）。

4. **联调验证「外部应用同签名可调用」**
   - 用 core 的 `sample` 模块（或临时写个最小 demo APK，用 **同一 release keystore** 签名）调 `MiniShizuku.execWithOutput("id")`，预期成功返回 `uid=2000(shell)` 行。
   - 换一个**不同签名**的 demo（如默认 debug 签名），预期 `execWithOutput` 返回 `null`（连接被关），`isRunning` 仍可 true（探测不鉴权或单独放行）。
   - 验证拦截器命令：同签名的非 launcher 包调 `INTERCEPTOR_START` 应被拒（`ERR:forbidden`），仅 launcher 自身可触发。

5. **文档**：core `docs/` 新增 `13-mini-shizuku.md`（接入说明 + 鉴权规则 + 「必须与宿主同签名」约束）；launcher `docs/` 更新 mini_shizuku 章节（说明已对外开放、鉴权机制）。

## 4. 风险与决策点（请先确认）

1. **链路切换会破坏老版本激活吗？** 改 TCP→抽象套接字是**不向后兼容**的：旧 launcher 拉起的旧 server（TCP）与新 client（LocalSocket）连不上。由于 server 跟随 launcher APK 升级、且 `mini_shizuku.sh` 每次重跑都会用新 APK，升级后用户重新激活一次即可。**建议把「升级后需重新激活」写进发版说明**。是否接受？
2. **`isRunning()` 是否对未知应用也开放？** 建议探测命令（无 shell 执行）对所有应用开放（便于生态应用判断「装没装、激活没激活」），只对 `exec*` 做签名校验。同意吗？
3. **`SERVER_STOP` 的鉴权**：建议只允许 hostUid 或 shell uid 调（避免第三方把别人激活的 server 关掉）。同意吗？
4. **是否需要白名单包名**（除同签名外再限定一组已知生态包名）？倾向「只靠签名，不绑包名」——签名已足够，加包名会拖慢迭代。请定夺。

## 5. 鉴权规则速查表

| 调用方 uid | 身份 | `isRunning` | `exec` / `execWithOutput` | `INTERCEPTOR_*` / `PAGE_STATE` | `SERVER_STOP` |
|---|---|---|---|---|---|
| hostUid | launcher 自身进程 | ✅ | ✅ | ✅ | ✅ |
| 2000 | shell / adb（激活者） | ✅ | ❌ | ❌ | ✅ |
| 其它 + 同签名 | 生态内应用 | ✅ | ✅ | ❌ | ❌ |
| 其它 + 异签名 | 未知应用 | ✅ | ❌ | ❌ | ❌ |

> 「✅」= 放行；「❌」= 关闭连接 / 回写 `ERR:unauthorized` / `ERR:forbidden`。
