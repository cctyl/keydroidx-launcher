<#
.SYNOPSIS
  日志 TAG 规范扫描器（KeydroidxLog 门面 + TAG 常量），支持 Java / Kotlin 与任意生态项目。

.DESCRIPTION
  规范见 keydroidx-core/docs/NOKIA_DEVELOPMENT_RULES.md §15「生态统一日志规范」。
  检查六类：

    1) 双轨 / 未回填：文件定义了日志 TAG 常量，却仍以字面量 tag 记日志
       （字面量与常量不一致时最危险——grep TAG 会扑空；一致时属"未回填常量"）；
    2) 超长：TAG 常量值超过 23 字符（4.4 时代 Log.isLoggable 依赖的系统属性长度限制）；
    3) 死常量：TAG 常量定义后零引用；
    4) 常量命名非标准：常量名不是 `TAG`（如 `TAG_DETAIL`），破坏统一 grep；
    5) 提示：TAG 值被多个文件共用（同一子系统共用是正常设计，仅作人工确认，不判失败）；
    6) 绕过门面直调 android.util.Log（白名单之外）。

  判定口径：「日志 TAG 常量」= 名为 `TAG` 的常量（约定），或（在没有 `TAG` 时）
  确实被用作日志调用第一个参数的 `TAG_*` 常量。仅名字像 TAG、实际是偏好键等其他用途的
  常量（如 `TAG_FREEZE_BADGE`）不参与判定，避免误报。

  白名单只按「场景」放行、不按次数，逐条可辩护。

.PARAMETER ProjectRoot
  项目根目录（该目录下会自动发现所有 `**/src/main/java` 源码根）。

.PARAMETER DirectWhitelist
  允许直调 android.util.Log 的路径前缀（匹配「相对项目根路径」或「包路径」两者之一）。

.OUTPUTS
  退出码 0 = 全部通过；1 = 存在偏离（可直接用于 CI / 提交前钩子）。

.EXAMPLE
  # keydroidx-launcher（默认参数即为该项目的白名单）
  .\scripts\log-tags\check-log-tags.ps1

  # 其他项目（按项目补充白名单）
  .\scripts\log-tags\check-log-tags.ps1 -ProjectRoot D:\project\keydroidx_ecosystem\keydroidx-music
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [int]$MaxTagLength = 23,
    # J2ME 移植层 / 模拟器内核（规范例外④）：不受 TAG 常量规范约束，也允许直调 android.util.Log。
    [string[]]$PortLayerPackages = @('com/', 'javax/', 'org/', 'mmpp/'),
    # 按场景放行的直调白名单（keydroidx-launcher 默认值）。
    [string[]]$DirectWhitelist = @(
        'dexlib/',
        'mini_shizuku/',
        'app/src/main/java/ru/playsoftware/j2meloader/EmulatorApplication.java',
        'app/src/main/java/ru/playsoftware/j2meloader/nokia/KeydroidxShizukuProvider.java'
    )
)

$ErrorActionPreference = 'Stop'

function Test-Any([string]$s, $prefixes) {
    foreach ($p in $prefixes) { if ($p -and $s -like "$p*") { return $true } }
    return $false
}

$srcRoots = Get-ChildItem -Path $ProjectRoot -Recurse -Directory -Filter 'java' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -replace '\\', '/' -match '/src/main/java$' } |
    Select-Object -ExpandProperty FullName
if (-not $srcRoots) { throw "未在 $ProjectRoot 下找到任何 src/main/java 源码根" }

$SEP = '/src/main/java/'
$reBareLog   = [regex]'(?<![\w.])Log\.[diwev]\('
$reFqLog     = [regex]'(?<![\w.])android\.util\.Log\.[diwev]\('
$reConstJava = [regex]'static\s+final\s+String\s+(?<n>TAG\w*)\s*=\s*"(?<v>[^"]*)"'
$reConstKt   = [regex]'(?:const\s+)?val\s+(?<n>TAG\w*)\s*(?::\s*String)?\s*=\s*"(?<v>[^"]*)"'
$reLiteral   = [regex]'(?<![\w])(?:KeydroidxLog|NLog|Log)\.[diwev]\("(?<lit>[^"]*)"'
$reLogArg    = [regex]'(?<![\w])(?:KeydroidxLog|NLog|Log)\.[diwev]\((?<a>[A-Za-z_]\w*)'

$dual = @{}; $long = @(); $dead = @(); $naming = @(); $direct = @()
$tagValues = New-Object System.Collections.ArrayList

foreach ($srcRoot in $srcRoots) {
    $files = Get-ChildItem -Path $srcRoot -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.java', '.kt' }

    foreach ($f in $files) {
        $full = $f.FullName.Replace('\', '/')
        $rel  = $full.Substring($ProjectRoot.Replace('\', '/').TrimEnd('/').Length).TrimStart('/')
        $idx  = $full.IndexOf($SEP)
        $pkg  = if ($idx -ge 0) { $full.Substring($idx + $SEP.Length) } else { $rel }

        # 去掉整行注释，避免把「注释掉的日志」当成违规
        $text = (([System.IO.File]::ReadAllLines($f.FullName)) |
                Where-Object { $_ -notmatch '^\s*(//|\*|/\*)' }) -join "`n"

        $isPortLayer = Test-Any $pkg $PortLayerPackages

        if (-not $isPortLayer) {
            # 收集本文件所有 TAG* 常量，以及被用作日志调用第一个参数的标识符
            $consts = @{}
            $reConst = if ($f.Extension -eq '.kt') { $reConstKt } else { $reConstJava }
            foreach ($m in $reConst.Matches($text)) {
                $n = $m.Groups['n'].Value
                if (-not $consts.ContainsKey($n)) { $consts[$n] = $m.Groups['v'].Value }
            }
            $logArgs = @{}
            foreach ($m in $reLogArg.Matches($text)) { $logArgs[$m.Groups['a'].Value] = $true }

            # 日志 TAG 常量：优先名为 TAG；否则取「确实被日志调用引用」的 TAG_* 常量
            $tagName = $null
            if ($consts.ContainsKey('TAG')) {
                $tagName = 'TAG'
            } else {
                $cand = @($consts.Keys | Where-Object { $logArgs.ContainsKey($_) })
                if ($cand.Count -ge 1) { $tagName = $cand[0] }
            }

            if ($tagName) {
                $tagVal = $consts[$tagName]
                [void]$tagValues.Add([pscustomobject]@{ Value = $tagVal; Rel = $rel })

                if ($tagVal.Length -gt $MaxTagLength) {
                    $long += ('{0}  {1}="{2}" 长度 {3}' -f $rel, $tagName, $tagVal, $tagVal.Length)
                }
                if ($tagName -ne 'TAG') {
                    $naming += ('{0}  常量名 {1}（应为 TAG）值 "{2}"' -f $rel, $tagName, $tagVal)
                }
                $reName = [regex]('(?<![\w])' + [regex]::Escape($tagName) + '(?![\w])')
                if ($reName.Matches($text).Count -le 1) {
                    $dead += ('{0}  {1}="{2}"' -f $rel, $tagName, $tagVal)
                }
                foreach ($lit in $reLiteral.Matches($text)) {
                    $v = $lit.Groups['lit'].Value
                    $kind = if ($v -eq $tagVal) { '未回填常量' } else { '与常量不一致(双轨)' }
                    $key = '{0}|{1}|{2}|{3}' -f $rel, $kind, $v, $tagVal
                    if ($dual.ContainsKey($key)) { $dual[$key]++ } else { $dual[$key] = 1 }
                }
            }
        }

        if (-not ($isPortLayer -or (Test-Any $rel $DirectWhitelist) -or (Test-Any $pkg $DirectWhitelist))) {
            # 只有「本文件确实引用了 android.util.Log」才判定为直调，避免把 NLog 别名误判
            $isAndroidLog = $text -match '(?m)^\s*import\s+android\.util\.Log\s*;?\s*$' -or $reFqLog.IsMatch($text)
            if ($isAndroidLog) {
                $n = $reBareLog.Matches($text).Count + $reFqLog.Matches($text).Count
                if ($n -gt 0) { $direct += ('{0}  {1} 处' -f $rel, $n) }
            }
        }
    }
}

$dualList = @($dual.Keys | Sort-Object | ForEach-Object {
    $p = $_ -split '\|'
    '{0}  {1}: 字面量 "{2}" vs 常量 "{3}"（{4} 处）' -f $p[0], $p[1], $p[2], $p[3], $dual[$_]
})

$dup = @()
foreach ($g in ($tagValues | Group-Object -Property Value)) {
    if ($g.Count -gt 1) {
        $dup += ('TAG="{0}" 被 {1} 个文件共用: {2}' -f $g.Name, $g.Count, (($g.Group | ForEach-Object { $_.Rel }) -join ', '))
    }
}

function Show-Result([string]$title, $items) {
    Write-Host ''
    Write-Host ("== {0} ({1}) ==" -f $title, $items.Count) -ForegroundColor Cyan
    $items | Sort-Object | ForEach-Object { Write-Host "  $_" }
}

Write-Host ("扫描项目: {0}" -f $ProjectRoot) -ForegroundColor Yellow
Show-Result '1) TAG 双轨 / 未回填常量' $dualList
Show-Result '2) TAG 超过 23 字符' $long
Show-Result '3) 死 TAG 常量' $dead
Show-Result '4) TAG 常量命名非标准' $naming
Show-Result '5) 提示：TAG 值被多个文件共用（同一子系统属正常，需人工确认）' $dup
Show-Result '6) 白名单外直调 android.util.Log' $direct

$bad = $dualList.Count + $long.Count + $dead.Count + $naming.Count + $direct.Count
Write-Host ''
if ($bad -eq 0) {
    Write-Host 'PASS: 未发现 TAG 规范偏离' -ForegroundColor Green
    exit 0
}
Write-Host ("FAIL: 共 {0} 项偏离" -f $bad) -ForegroundColor Red
exit 1
