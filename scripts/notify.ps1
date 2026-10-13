<#
    Create / inspect test notifications on the device over adb.

    Purpose : exercise the launcher notification bar and notification center
              (test case in docs\发布前回归测试清单.md, section 【桌面-05】).
    Requires: adb in PATH ; Android 8.0+ (API 26) to post via "cmd notification post".
              On older ROMs the script prints fallback instructions instead.
    NOTE    : every line this script prints is ASCII on purpose -- PowerShell 5.1
              reads UTF-8 files without BOM as ANSI, so Chinese output would be mojibake.
              The notification *content* may be Chinese; pass it via -Title / -Text.

    Usage:
        .\notify.ps1                      post one test notification
        .\notify.ps1 -Count 3             post 3 notifications (check the "N unread" counter)
        .\notify.ps1 -Title "Order" -Text "shipped"
        .\notify.ps1 -List                list the notifications currently posted
        .\notify.ps1 -Clear               best-effort clear, then print how to do it by hand
        .\notify.ps1 -Serial 5990a29a     target a specific device
        .\notify.ps1 -TimeoutMs 5000      notification disappears by itself after 5 s

    Exit codes: 0 = ok ; 1 = failure (no device / post rejected / ROM too old).
#>
[CmdletBinding()]
param(
    [string] $Serial    = '',                              # adb serial; empty = the only connected device
    [int]    $Count     = 1,                               # how many notifications to post
    [string] $Title     = 'Keydroidx test',                # notification title
    [string] $Text      = 'notify.ps1 test message',       # notification body
    [string] $Tag       = 'keydroidx-test',                # tag prefix; -Count N appends -1..-N
    [string] $Style     = 'bigtext',                       # bigtext / inbox / messaging / default
    [int]    $TimeoutMs = 0,                               # >0: auto-cancel after N ms
    [int]    $DelayMs   = 500,                             # gap between posted notifications
    [switch] $List,                                        # list current notifications, then exit
    [switch] $Clear                                        # try to clear all, then exit
)

function Say  ($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Good ($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn ($m) { Write-Host "[!] $m" -ForegroundColor Yellow }
function Bad  ($m) { Write-Host "[-] $m" -ForegroundColor Red }
function Die  ($m) { Bad $m; exit 1 }

function Invoke-Adb {
    param([string[]] $Arguments)
    $call = @()
    if ($Serial) { $call += @('-s', $Serial) }
    $call += $Arguments
    # native stderr comes back as ErrorRecord (with location noise) -- flatten every
    # item to a plain string so the output stays clean and readable.
    $out = & adb @call 2>&1 | ForEach-Object { $_.ToString() }
    return (($out | Out-String) -replace "`r", '').Trim()
}

function Invoke-Shell {
    param([string] $Command)
    return Invoke-Adb -Arguments @('shell', $Command)
}

# ---------------------------------------------------------------- device check
$state = Invoke-Adb -Arguments @('get-state')
if ($state -ne 'device') {
    $hint = ($state -split "`n" | Where-Object { $_ -match 'error|device' } | Select-Object -Last 1)
    if (-not $hint) { $hint = $state }
    Die "no device ready: $hint"
}

$sdk = 0
[int]::TryParse((Invoke-Shell -Command 'getprop ro.build.version.sdk'), [ref] $sdk) | Out-Null
$rel = Invoke-Shell -Command 'getprop ro.build.version.release'
Good "device ready: Android $rel (API $sdk)"

# ---------------------------------------------------------------- -List
if ($List) {
    Say 'reading notification records from dumpsys (can take a second)...'
    $dump = Invoke-Shell -Command 'dumpsys notification --noredact'
    $records = [regex]::Matches($dump, 'NotificationRecord\(')
    if ($records.Count -eq 0) {
        Warn 'no notification records found (bar should show the empty state)'
    } else {
        Good ("{0} notification record(s) on the device" -f $records.Count)
        $dump -split "`n" |
            Where-Object { $_ -match 'NotificationRecord\(|^\s*pkg=|channelId=|tag=' } |
            Select-Object -First 40 |
            ForEach-Object { Write-Host ('   ' + $_.Trim()) }
    }
    exit 0
}

# ---------------------------------------------------------------- -Clear
if ($Clear) {
    Say 'trying to clear all notifications (best effort, unreliable on newer ROMs)...'
    $r = Invoke-Shell -Command 'service call notification 1'
    if ($r -match 'Parcel|Result') { Good "device replied: $r" } else { Warn "device replied: $r" }
    Warn 'if the notifications are still there, clear them by hand:'
    Warn '  1) pull down the system shade and tap "Clear all"'
    Warn '  2) or in the launcher: notification center -> left soft key -> clear all'
    exit 0
}

# ---------------------------------------------------------------- post
if ($sdk -lt 26) {
    Die ("posting over adb needs Android 8.0+ (API 26); this device is API {0}.`n" +
         "    fallbacks: open any app that posts a notification, or run this test on a newer device." -f $sdk)
}

Say "posting $Count notification(s), style=$Style, tag prefix=$Tag"
$ok = 0
for ($i = 1; $i -le $Count; $i++) {
    $itemTag  = if ($Count -gt 1) { "$Tag-$i" } else { $Tag }
    $itemText = if ($Count -gt 1) { "$Text ($i/$Count)" } else { $Text }

    $cmd = "cmd notification post -S $Style -t `"$Title`""
    if ($TimeoutMs -gt 0) { $cmd += " -T $TimeoutMs" }
    $cmd += " `"$itemTag`" `"$itemText`""

    $res = Invoke-Shell -Command $cmd

    if ($res -match 'Error|Exception|Unknown|not found|does not exist|Usage') {
        # older ROMs may not know -S (style); retry with the bare form once
        Warn "#${i}: first attempt rejected, retrying without -S ..."
        $cmd2 = "cmd notification post -t `"$Title`" `"$itemTag`" `"$itemText`""
        $res = Invoke-Shell -Command $cmd2
        if ($res -match 'Error|Exception|Unknown|not found|does not exist|Usage') {
            Bad "#${i} failed: $res"
            if ($i -eq 1) {
                Warn 'this ROM does not implement "cmd notification post".'
                Warn 'fallback: open any app that posts notifications and test with those.'
                exit 1
            }
        } else {
            $ok++
            Write-Host "   #${i} -> posted (no style)"
        }
    } else {
        $ok++
        if ($res) { Write-Host "   #${i} -> $res" } else { Write-Host "   #${i} -> posted" }
    }

    if ($i -lt $Count -and $DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }
}

if ($ok -eq $Count) { Good "$ok notification(s) posted" } else { Warn "posted $ok of $Count" }

Say 'what to check on the device:'
Say '  - notification bar text: "N unread" plus the newest title/body'
Say '  - notification center page lists every posted notification'
Say 'list them again with:  .\notify.ps1 -List'
