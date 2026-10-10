<#
.SYNOPSIS
  日志 TAG 规范扫描器（KeydroidxLog 门面 + TAG 常量）。

.DESCRIPTION
  规范见 keydroidx-core/docs/NOKIA_DEVELOPMENT_RULES.md §15「生态统一日志规范」。
  检查五类偏离：
    1) 双轨 / 未回填：文件定义了 TAG 常量，却仍以字面量 tag 记日志（字面量与常量不一致时最危险——grep TAG 会扑空）；
    2) 超长：TAG 常量值超过 23 字符（4.4 时代 Log.isLoggable 依赖的系统属性长度限制）；
    3) 死常量：TAG 常量定义后零引用；
    4) 提示：TAG 值被多个文件共用（同一子系统共用是正常设计，仅作人工确认，不判失败）；
    5) 绕过门面直调 android.util.Log（白名单之外）。

  白名单只按「场景」放行、不按次数，逐条可辩护（见 $portLayerPrefixes / $directWhitelistExtra）。

.OUTPUTS
  退出码 0 = 全部通过；1 = 存在偏离（可直接用于 CI / 提交前钩子）。

.EXAMPLE
  .\tools\log-tags\check-log-tags.ps1
  .\tools\log-tags\check-log-tags.ps1 -Root D:\project\keydroidx_ecosystem\keydroidx-launcher
#>
[CmdletBinding()]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [int]$MaxTagLength = 23
)

$ErrorActionPreference = 'Stop'

$scanDirs = @(
    'app/src/main/java',
    'dexlib/src/main/java',
    'mini_shizuku/src/main/java'
) | ForEach-Object { Join-Path $Root $_ } | Where-Object { Test-Path $_ }

# J2ME 移植层 / 模拟器内核（规范例外④）：不受 TAG 常量规范约束，也允许直调 android.util.Log。
$portLayerPrefixes = @(
    'app/src/main/java/com/',
    'app/src/main/java/javax/',
    'app/src/main/java/org/',
    'app/src/main/java/mmpp/'
)

# 其余允许直调 android.util.Log 的文件（按场景放行）：
#   - dexlib/            : 字节码工具链 fork，不依赖 keydroidx-common，无门面可用
#   - mini_shizuku/      : 服务端模块（规范例外④）
#   - EmulatorApplication.java      : 门面 init 之前（attachBaseContext）/ :midlet 不 init / 崩溃链自身防递归
#   - KeydroidxShizukuProvider.java : mini_shizuku 服务端组件（provider call 可能早于 Application 初始化）
$directWhitelistExtra = @(
    'dexlib/',
    'mini_shizuku/',
    'app/src/main/java/ru/playsoftware/j2meloader/EmulatorApplication.java',
    'app/src/main/java/ru/playsoftware/j2meloader/nokia/KeydroidxShizukuProvider.java'
)

$reBareLog  = [regex]'(?<![\w.])Log\.[diwev]\('
$reFqLog    = [regex]'(?<![\w.])android\.util\.Log\.[diwev]\('
$reTagConst = [regex]'static\s+final\s+String\s+TAG\s*=\s*"(?<v>[^"]*)"'
$reLiteral  = [regex]'(?<![\w])KeydroidxLog\.[diwev]\("(?<lit>[^"]*)"'
$reTagWord  = [regex]'(?<![\w])TAG(?![\w])'

$dual = @(); $long = @(); $dead = @(); $direct = @()
$tagValues = New-Object System.Collections.ArrayList

function Test-Prefix([string]$path, $prefixes) {
    foreach ($p in $prefixes) { if ($path -like "$p*") { return $true } }
    return $false
}

foreach ($d in $scanDirs) {
    foreach ($f in Get-ChildItem -Path $d -Recurse -Filter '*.java' -File) {
        $rel  = $f.FullName.Substring($Root.Length).TrimStart('\', '/').Replace('\', '/')
        # 去掉整行注释，避免把「注释掉的日志」当成违规
        $text = (([System.IO.File]::ReadAllLines($f.FullName)) |
                Where-Object { $_ -notmatch '^\s*(//|\*|/\*)' }) -join "`n"
        $isPortLayer = Test-Prefix $rel $portLayerPrefixes

        if (-not $isPortLayer) {
            $tagMatch = $reTagConst.Match($text)
            if ($tagMatch.Success) {
                $tagVal = $tagMatch.Groups['v'].Value
                [void]$tagValues.Add([pscustomobject]@{ Value = $tagVal; Rel = $rel })

                if ($tagVal.Length -gt $MaxTagLength) {
                    $long += ('{0}  TAG="{1}" 长度 {2}' -f $rel, $tagVal, $tagVal.Length)
                }
                if ($reTagWord.Matches($text).Count -le 1) {
                    $dead += ('{0}  TAG="{1}"' -f $rel, $tagVal)
                }
                foreach ($lit in $reLiteral.Matches($text)) {
                    $v = $lit.Groups['lit'].Value
                    $kind = if ($v -eq $tagVal) { '未回填常量' } else { '与常量不一致(双轨)' }
                    $dual += ('{0}  {1}: 字面量 "{2}" vs 常量 "{3}"' -f $rel, $kind, $v, $tagVal)
                }
            }
        }

        $whitelisted = $isPortLayer -or (Test-Prefix $rel $directWhitelistExtra)
        if (-not $whitelisted) {
            $n = $reBareLog.Matches($text).Count + $reFqLog.Matches($text).Count
            if ($n -gt 0) { $direct += ('{0}  {1} 处' -f $rel, $n) }
        }
    }
}

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

Show-Result '1) TAG 双轨 / 未回填常量' $dual
Show-Result '2) TAG 超过 23 字符' $long
Show-Result '3) 死 TAG 常量' $dead
Show-Result '4) 提示：TAG 值被多个文件共用（同一子系统属正常，需人工确认）' $dup
Show-Result '5) 白名单外直调 android.util.Log' $direct

$bad = $dual.Count + $long.Count + $dead.Count + $direct.Count
Write-Host ''
if ($bad -eq 0) {
    Write-Host 'PASS: 未发现 TAG 规范偏离' -ForegroundColor Green
    exit 0
}
Write-Host ("FAIL: 共 {0} 项偏离" -f $bad) -ForegroundColor Red
exit 1
