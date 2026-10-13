<#
    UNNO F3 (msm8909_512 / Android 4.4.4) -- USB reverse tethering, DEVICE side setup.

    Companion : pc-ics.ps1 (enable/disable ICS sharing on the PC) , README.md
    Requires  : adb in PATH ; device rooted (su -c works, no prompt)
    NOTE      : all messages are ASCII on purpose -- PowerShell 5.1 reads UTF-8
                files without BOM as ANSI, Chinese text would show up as mojibake.

    Usage:
        .\net-up.ps1                  switch USB to (rndis,adb) + configure rndis0 + sync time + self test
        .\net-up.ps1 -SkipUsbSwitch   only (re)configure rndis0 / time / test  (after replug it is needed anyway)
        .\net-up.ps1 -SkipTime        do not touch the device clock
        .\net-up.ps1 -Reset           restore normal USB (mtp,adb)

    If Windows blocks the script:  powershell -NoProfile -ExecutionPolicy Bypass -File .\net-up.ps1
#>
[CmdletBinding()]
param(
    [string] $Serial  = '5990a29a',       # adb serial of the device (fixed for this unit)
    [string] $PhoneIp = '192.168.137.2',  # address for rndis0 on the device
    [string] $PcIp    = '192.168.137.1',  # PC side RNDIS adapter address (ICS always uses this)
    [string] $DnsAlt  = '8.8.8.8',        # secondary DNS
    [switch] $SkipTime,
    [switch] $SkipUsbSwitch,
    [switch] $Reset
)

function Say  ($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Good ($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn ($m) { Write-Host "[!] $m" -ForegroundColor Yellow }
function Bad  ($m) { Write-Host "[-] $m" -ForegroundColor Red }
function Die  ($m) { Bad $m; exit 1 }

function Invoke-Shell {
    param([string] $Command, [switch] $Root)
    $line = if ($Root) { "su -c '$Command'" } else { $Command }
    $out = & adb -s $Serial shell $line 2>&1
    return (($out | Out-String) -replace "`r", '').Trim()
}

function Get-DeviceState {
    return ((& adb -s $Serial get-state 2>&1 | Out-String) -replace "`r", '').Trim()
}

function Wait-Device {
    param([int] $TimeoutSec = 40)
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Seconds 2
        if ((Get-DeviceState) -eq 'device') { return $true }
    }
    return $false
}

# ---------------------------------------------------------------- pre-checks
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Die 'adb not found in PATH. Add platform-tools to PATH, e.g. C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools'
}
if ((Get-DeviceState) -ne 'device') {
    Say "waiting up to 20s for device '$Serial' (Windows may still be enumerating the USB port)..."
    if (-not (Wait-Device 20)) {
        Die "device '$Serial' is not connected/authorized (adb get-state = $(Get-DeviceState)). Check the cable / 'adb devices'."
    }
}
$rootId = Invoke-Shell 'id' -Root
if ($rootId -notmatch 'uid=0') {
    Die "device has no root ('su -c id' -> $rootId). pc-side sharing alone is not enough."
}

# ---------------------------------------------------------------- reset mode
if ($Reset) {
    Say 'Restore normal USB composition (mtp,adb)'
    Invoke-Shell 'setprop sys.usb.config mtp,adb' -Root | Out-Null
    $null = Wait-Device 20
    Good "sys.usb.config = $(Invoke-Shell 'getprop sys.usb.config')"
    exit 0
}

# ---------------------------------------------------------------- 1/5 usb composition
if (-not $SkipUsbSwitch) {
    Say '1/5 check persist.sys.usb.config.extra (must not be empty)'
    $extra = Invoke-Shell 'getprop persist.sys.usb.config.extra'
    if ([string]::IsNullOrWhiteSpace($extra)) {
        Invoke-Shell 'setprop persist.sys.usb.config.extra none' -Root | Out-Null
        Warn 'extra was empty -> set to "none" (otherwise "rndis,adb" expands to "rndis,,adb" and matches no init rule)'
    } else {
        Good "extra = $extra"
    }

    Say '2/5 switch USB composition to rndis,adb (adb drops for a few seconds)'
    Invoke-Shell 'setprop sys.usb.config rndis,adb' -Root | Out-Null
    if (-not (Wait-Device 40)) {
        Die 'adb did not come back. Unplug/replug the USB cable, then run this script again.'
    }
    Good "sys.usb.state = $(Invoke-Shell 'getprop sys.usb.state')"
} else {
    Say '1-2/5 USB switch skipped (-SkipUsbSwitch)'
}

# ---------------------------------------------------------------- 3/5 rndis0 config
Say '3/5 configure rndis0 (ip / default route / dns)'
Invoke-Shell "ifconfig rndis0 $PhoneIp netmask 255.255.255.0 up" -Root | Out-Null
$link = Invoke-Shell 'ifconfig rndis0'
if ($link -notmatch [regex]::Escape($PhoneIp)) {
    Die "cannot configure rndis0 -> $link   (is the USB composition really rndis,adb ?)"
}
Good ("rndis0 = " + ($link -replace '\s+', ' '))

# NOTE: switching the USB composition resets rndis0, so ip + route + dns are ALWAYS (re)applied here
$routes = Invoke-Shell 'cat /proc/net/route'
if ($routes -notmatch '(?m)^\s*rndis0\s+00000000') {
    $r = Invoke-Shell "route add default gw $PcIp dev rndis0" -Root
    if ($r) { Warn "route add: $r" }
    $routes = Invoke-Shell 'cat /proc/net/route'
}
$defGw = (($routes -split "`n" | Where-Object { $_ -match '^\s*rndis0\s+00000000' }) | Select-Object -First 1)
if ($defGw) { Good "default route: $($defGw -replace '\s+',' ')" }
else { Warn 'default route via rndis0 still missing -- check the PC side (pc-ics.ps1)' }

$r1 = Invoke-Shell "ndc resolver setifdns rndis0 $PcIp $DnsAlt" -Root
if ($r1 -notmatch '200') { Warn "ndc resolver setifdns -> $r1" } else { Good 'resolver: setifdns ok' }
$r2 = Invoke-Shell 'ndc resolver setdefaultif rndis0' -Root
if ($r2 -notmatch '200') { Warn "ndc resolver setdefaultif -> $r2" } else { Good 'resolver: setdefaultif ok' }

# ---------------------------------------------------------------- 4/5 clock
if (-not $SkipTime) {
    Say '4/5 sync device clock (its RTC is not writable, a reboot rolls the clock back)'
    $stamp = Get-Date -Format 'yyyyMMddHHmm.ss'
    $out   = Invoke-Shell "busybox date -s $stamp" -Root
    if ($out -and $out -match '(?i)invalid|error|usage|denied|cannot') { Warn "date -s: $out" }
    Good "device time = $(Invoke-Shell 'date')"
    Warn 'PC and device must use the same UTC offset, otherwise the epoch is wrong'
} else {
    Say '4/5 clock sync skipped (-SkipTime)'
}

# ---------------------------------------------------------------- 5/5 self test
Say '5/5 connectivity self test (waiting 3s for the USB link to settle)'
Start-Sleep -Seconds 3
$gwPing  = Invoke-Shell "ping -c 2 $PcIp"
if (-not ($gwPing -match '(^|\s)0% packet loss')) { Start-Sleep -Seconds 3; $gwPing = Invoke-Shell "ping -c 2 $PcIp" }
$netPing = Invoke-Shell 'ping -c 2 8.8.8.8'
$dnsPing = Invoke-Shell 'ping -c 2 www.baidu.com'

function Test-PingOk ($txt) { return ($txt -match '(^|\s)0% packet loss') }

# gateway icmp is informational only: Windows Firewall may drop it while NAT still works
if (Test-PingOk $gwPing)  { Good "PC side  $PcIp        : OK" }
else { Warn "PC side  $PcIp        : no ICMP reply (Windows Firewall may drop it - not fatal if internet is OK)" }
if (Test-PingOk $netPing) { Good  'internet 8.8.8.8       : OK' } else { Bad 'internet 8.8.8.8       : FAIL' }
if (Test-PingOk $dnsPing) { Good  'dns+http www.baidu.com : OK' } else { Bad 'dns  www.baidu.com     : FAIL (check ndc resolver output above)' }

Write-Host ''
if ((Test-PingOk $netPing) -and (Test-PingOk $dnsPing)) {
    Good 'device is online through the USB cable.'
    Say  'note: ConnectivityService still reports "no network" (rndis0 is configured by hand).'
    Say  '      Apps that open sockets directly work; apps checking NetworkInfo.isConnected() may not.'
} else {
    Warn 'not online yet. Checklist:'
    Say  '  1) PC sharing enabled?      .\pc-ics.ps1        (needs admin, UAC)'
    Say  '  2) PC itself online?        ping 8.8.8.8        (on the PC)'
    Say  '  3) RNDIS adapter up?        Get-NetAdapter      (status must be Up)'
    exit 1
}
