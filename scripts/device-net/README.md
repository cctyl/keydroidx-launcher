# 设备 USB 联网（UNNO F3 / msm8909_512 / Android 4.4.4）

只做一件事：**让这台测试机通过 USB 线联上互联网**。

| 项 | 值 |
|---|---|
| 设备 | `UNNO_F3`（msm8909_512，512MB） |
| 系统 | Android 4.4.4 / SDK 19 / 内核 3.10.49 |
| adb 序列号 | `5990a29a` |
| root | 有（`/system/xbin/su`，`su -c` 不弹窗） |
| PC 出口网卡 | `以太网 2`（Realtek PCIe GbE）192.168.1.6，网关 192.168.1.1 |
| PC 侧给设备的网卡 | `以太网 4` = Remote NDIS based Internet Sharing Device #2 |

## 1. 为什么只能走 USB

这台机器**没有任何可用的无线通路**，所以 USB 是唯一选择：

| 通路 | 状态 | 依据 |
|---|---|---|
| WiFi | ✗ | WCNSS 射频起不来：驱动能加载，但 `WDI_Start() failure` → `wlan: driver load failure`，`wlan0` 永不出现；系统也未声明 `android.hardware.wifi` |
| 蓝牙 | ✗ | 系统启动即打印 `No Bluetooth Service (Bluetooth Hardware Not Present)`，无蓝牙服务/内核无 HCI 传输驱动 |
| 蜂窝 | ✗（无卡） | `gsm.sim.state=NOT_READY,NOT_READY`；插卡是唯一的真无线方案 |
| NFC / 红外 | ✗ | 无 NFC 特性与栈；`consumer_ir` 只有服务壳，未声明特性 |
| **USB** | **✓ 唯一可用** | 支持 `rndis,adb` gadget、有 USB host 控制器与 `/storage/usbotg` |

顺带一个必须知道的坑：**设备 RTC 不可写**（`busybox hwclock -w` 报 `ioctl RTC_SET_TIME failed`），ROM 也没有 `/system/etc/init.d`，所以**每次重启系统时间都会回旧值**。时间不准会让 HTTPS 证书校验、各类带时间戳的请求直接失败，所以联网流程里带了一步校时。

## 2. 原理

```
手机 rndis0 192.168.137.2/24
        │  USB 线（USB gadget RNDIS）
        ▼
PC  Remote NDIS 网卡 192.168.137.1/24   ← Windows ICS 提供 DHCP / DNS / NAT
        │
        ▼
PC 出口网卡（以太网 2，192.168.1.6）→ 路由器 → 互联网
```

手机侧是**手工配**的 `rndis0`，所以 `ConnectivityService` 并不知道这个网络（见「已知限制」）。

## 3. 目录内容

| 文件 | 作用 |
|---|---|
| `net-up.ps1` | **设备侧**：切 USB 组合为 `rndis,adb` → 配 `rndis0` IP/默认路由/DNS → 校时 → 自检（在 PC 上执行，通过 adb 驱动设备） |
| `pc-ics.ps1` | **PC 侧**：自动识别出口网卡并把网络共享给 RNDIS 网卡（自动提权）；`-Off` 关闭共享，`-DryRun` 只检测不改动（无需管理员） |
| `README.md` | 本文档 |

> 脚本内的提示信息统一用英文：PowerShell 5.1 读取"UTF-8 无 BOM"的 `.ps1` 时会按 ANSI 解码，中文会变乱码。

## 4. 使用步骤

> **日常只有一条命令**：插上手机 → `cd tools\device-net` → **`.\net-up.ps1`**，完事。
>
> `pc-ics.ps1` **不是每次都要跑**：它是 PC 侧的持久配置，只在「第一次」或「PC 侧共享被关掉 / 换了 USB 口导致 RNDIS 网卡重新枚举」时才需要。
> 顺序不能颠倒——`pc-ics.ps1` 要求 PC 上已经存在「Remote NDIS」网卡，而那张网卡只有设备切成 `rndis,adb` 后才会出现（也就是 `net-up.ps1` 做的事）。

| 时机 | 要跑什么 |
|---|---|
| 每次插上手机 / 设备重启后 | `.\net-up.ps1` |
| 第一次用、或换了电脑/USB 口、或跑过 `-Off` | 先 `.\net-up.ps1` → 若自检 `internet` FAIL，再 `.\pc-ics.ps1` |
| 收工恢复 | `.\net-up.ps1 -Reset`（设备）+ `.\pc-ics.ps1 -Off`（PC） |

### 4.1 首次（PC 侧，只需做一次）

```powershell
cd tools\device-net
.\pc-ics.ps1                 # 弹 UAC → 点"是"；自动探测出口网卡并开启共享
```
成功后会看到 `sharing is on: 以太网 2 -> 以太网 4 (device gateway = 192.168.137.1)`。

PC 侧共享是**持久配置**，重启电脑后一般仍在（除非 RNDIS 网卡换了 GUID）。

### 4.2 每次重启设备 / 拔插 USB 之后（设备侧）

```powershell
cd tools\device-net
.\net-up.ps1
```
它会依次做 5 步：检查 `extra` → 切 USB 组合 → 配 `rndis0` → 校时 → 自检。
> 若被策略拦住：`powershell -NoProfile -ExecutionPolicy Bypass -File .\net-up.ps1`

成功结尾（实测输出）：

```
[+] rndis0 = rndis0: ip 192.168.137.2 mask 255.255.255.0 flags [up broadcast running multicast]
[+] default route: rndis0 00000000 0189A8C0 0003 0 0 0 00000000 0 0 0
[+] device time = Wed Sep 23 11:25:51 CST 2026
[+] PC side  192.168.137.1        : OK
[+] internet 8.8.8.8       : OK
[+] dns+http www.baidu.com : OK

[+] device is online through the USB cable.
[*] note: ConnectivityService still reports "no network" (rndis0 is configured by hand).
```

只想重配网络、不动 USB 组合时加 `-SkipUsbSwitch`；不想校时加 `-SkipTime`。

### 4.3 手工验证（可选）

```powershell
adb -s 5990a29a shell "date"                  # 时间要对
adb -s 5990a29a shell "ifconfig rndis0"       # 192.168.137.2
adb -s 5990a29a shell "ping -c 2 8.8.8.8"
adb -s 5990a29a shell "ping -c 2 www.baidu.com"
adb -s 5990a29a shell su -c "busybox wget -T 10 -O /dev/null http://www.baidu.com/"
```

### 4.4 恢复原状

```powershell
.\net-up.ps1 -Reset          # 设备：USB 组合恢复 mtp,adb
.\pc-ics.ps1 -Off            # PC：关闭 ICS 共享
```

## 5. 脚本等价的手工命令（应急用）

设备侧（`su` 后执行，或 `adb shell su -c "..."`）：

```sh
setprop sys.usb.config rndis,adb                       # 若 extra 为空：先 setprop persist.sys.usb.config.extra none
ifconfig rndis0 192.168.137.2 netmask 255.255.255.0 up
route add default gw 192.168.137.1 dev rndis0
ndc resolver setifdns rndis0 192.168.137.1 8.8.8.8
ndc resolver setdefaultif rndis0
busybox date -s 202609231114.22                        # 格式 yyyyMMddHHmm.ss
```

PC 侧（管理员 PowerShell）：

```powershell
$h = New-Object -ComObject HNetCfg.HNetShare
# 遍历 $h.EnumEveryConnection，用 $h.NetConnectionProps($c) 拿到 Name / DeviceName
# 出口网卡：EnableSharing(0)      给设备的 RNDIS 网卡：EnableSharing(1)
```

## 6. 已知限制

1. **系统仍认为"无网络"**：`rndis0` 是手工配的，`ConnectivityService` 不知道它（`dumpsys connectivity` 里 mobile/wifi 都是 DISCONNECTED）。直接开 socket 的 App（自带浏览器、你自己的 HTTP 请求）可用；依赖 `NetworkInfo.isConnected()` 的 App 可能提示无网络，状态栏也不显示联网图标。Android 4.4 没有 EthernetService，想彻底解决不现实。
2. **DNS 必须走 `ndc resolver`**，`setprop net.dns1` 在这台 4.4 上不生效（会 `unknown host`）。
3. **时间每重启必丢**（RTC 写不进去），校时是流程的一部分。
4. **`adb reverse` 不可用**：4.4 的 adbd 不支持（报 `adb.exe: error: closed`），反方向只能靠本方案。
5. **OTG 插 USB 网卡不行**：内核没有任何 `usbnet`/`rndis_host`/`cdc_ether` 类驱动（`/sys/bus/usb/drivers/` 里没有），插上也不认。
6. USB gadget 只支持 `mtp`+`ffs(adb)` 可用组合；切组合瞬间 adb 会断几秒。

## 7. 排错速查

| 现象 | 原因 | 处理 |
|---|---|---|
| PC 上看不到「Remote NDIS」网卡 | USB 组合还是 `mtp,adb` | 跑 `.\net-up.ps1`（或 `setprop sys.usb.config rndis,adb`） |
| RNDIS 网卡状态 `Disconnected` | 设备侧 `rndis0` 没 up | 跑 `.\net-up.ps1` |
| `setprop sys.usb.config rndis,adb` 后没反应 | `persist.sys.usb.config.extra` 为空，展开成 `rndis,,adb` 不匹配规则 | `setprop persist.sys.usb.config.extra none` |
| 切组合后 adb 一直不回来 | USB 重新枚举异常 | 拔插 USB 线，再跑脚本 |
| `ping 192.168.137.1` 不通 | PC 侧共享没开 / 网段不对 | `.\pc-ics.ps1`（确认 RNDIS 网卡拿到 192.168.137.1） |
| IP 通、`www.baidu.com` 解析失败 | DNS 没配给 netd | 重跑 `ndc resolver setifdns/setdefaultif` |
| 一切正常但某些请求失败 | 设备时钟不对 | `.\net-up.ps1`（不要加 `-SkipTime`） |
| 共享开启报错 | 已经给别的网卡开了共享 | `.\pc-ics.ps1 -Off` 后再开（脚本也会自动重试一次） |

## 8. 附：adb 通道备忘

| 命令 | 结果（Android 4.4 实测） |
|---|---|
| `adb -s 5990a29a forward tcp:18080 tcp:8080` | ✓ PC:18080 → 手机 127.0.0.1:8080（PC → 手机） |
| `adb -s 5990a29a reverse tcp:18080 tcp:18080` | ✗ `error: closed`，4.4 的 adbd 不支持 reverse |
| `forward --list` / `forward --remove-all` | 查看 / 清空转发规则 |
| `adb push` / `adb pull` | 传文件（不经网络） |

需要"手机访问 PC / 让手机上网"时，用本目录的方案（RNDIS + ICS），不要指望 `adb reverse`。
