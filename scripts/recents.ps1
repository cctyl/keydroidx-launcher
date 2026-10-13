<#
    Inspect / seed the Android "recent tasks" list over adb.

    Why this exists
    ---------------
    The launcher's recent-tasks feature (desktop widget + recent tasks page)
    reads the SYSTEM's own task record. So to test it you need two things:
      1) a known set of tasks -> open apps yourself (-Seed), and
      2) an independent answer key to compare against (-List).
    -List prints exactly the record the launcher parses, so a mismatch
    between it and the UI is a real bug, and the "N tasks" counter can be
    checked instead of guessed.

    Requires: adb in PATH. -List / -Seed work on any version.
              -Clear needs mini_shizuku or root (am task remove / am stack remove);
              if it fails, clear from the launcher page instead -- that IS a test case.

    NOTE: all messages are ASCII on purpose -- PowerShell 5.1 reads UTF-8 files
          without BOM as ANSI, so Chinese output would show up as mojibake.

    Usage:
        .\recents.ps1 -List                       what does the system record right now?
        .\recents.ps1 -Seed "com.android.settings,com.android.deskclock"
        .\recents.ps1 -Clear                      best effort: drop every recorded task
        .\recents.ps1 -List -Serial 5990a29a      pick a device

    Exit codes: 0 = ok ; 1 = failure (no device).
#>
[CmdletBinding()]
param(
    [string] $Serial = '',
    [switch] $List,
    [string] $Seed   = '',      # comma separated package names to open
    [switch] $Clear,
    [int]    $WaitMs = 1200     # settle time after each launch
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
    # native stderr comes back as ErrorRecord (with location noise) -- flatten it
    $out = & adb @call 2>&1 | ForEach-Object { $_.ToString() }
    return (($out | Out-String) -replace "`r", '').Trim()
}

function Invoke-Shell {
    param([string] $Command)
    return Invoke-Adb -Arguments @('shell', $Command)
}

# Same idea as KeydroidxRecentTasksHelper.parseDumpsys(): walk Task{...} blocks and
# take A=<uid>:<pkg>, falling back to cmp=/realActivity=. Kept tolerant on purpose --
# the dumpsys layout differs a lot between ROM versions, so this is an answer key,
# not a bit-exact re-implementation.
function Get-RecentTaskPackages {
    param([string] $Dump)
    $found = New-Object System.Collections.Generic.List[string]
    if (-not $Dump) { return $found }

    foreach ($block in [regex]::Matches($Dump, 'Task(?:Record)?\{([^}]*)\}')) {
        $body = $block.Groups[1].Value
        $m = [regex]::Match($body, 'A=\d+:([A-Za-z0-9_.]+)')
        if (-not $m.Success) { $m = [regex]::Match($body, '(?:cmp|realActivity)=([A-Za-z0-9_.]+)') }
        if ($m.Success) { $found.Add($m.Groups[1].Value) }
    }
    if ($found.Count -eq 0) {
        # some ROMs print no Task{} blocks at all -- fall back to a loose scan
        foreach ($m in [regex]::Matches($Dump, 'A=\d+:([A-Za-z0-9_.]+)')) { $found.Add($m.Groups[1].Value) }
    }
    return $found
}

# ---------------------------------------------------------------- device check
$state = Invoke-Adb -Arguments @('get-state')
if ($state -ne 'device') {
    $hint = ($state -split "`n" | Where-Object { $_ -match 'error|device' } | Select-Object -Last 1)
    if (-not $hint) { $hint = $state }
    Die "no device ready: $hint"
}
$sdk = Invoke-Shell -Command 'getprop ro.build.version.sdk'
Good "device ready (API $sdk)"

$didSomething = $false

# ---------------------------------------------------------------- -List
if ($List) {
    $didSomething = $true
    Say 'reading "dumpsys activity recents" (this is the answer key the launcher reads)...'
    $dump = Invoke-Shell -Command 'dumpsys activity recents'
    if (-not $dump -or $dump -notmatch 'Task') {
        Warn 'no Task blocks in the dump -- falling back to "dumpsys activity activities"'
        $dump = Invoke-Shell -Command 'dumpsys activity activities'
    }
    $pkgs = Get-RecentTaskPackages -Dump $dump
    $uniq = $pkgs | Select-Object -Unique
    if (-not $uniq -or $uniq.Count -eq 0) {
        Warn 'the system records 0 recent tasks right now (launcher should show the empty state)'
    } else {
        Good ("system records {0} recent task(s):" -f $uniq.Count)
        $i = 0
        foreach ($p in $uniq) { $i++; Write-Host ("   {0,2}. {1}" -f $i, $p) }
        Write-Host ''
        Say 'compare with the launcher:'
        Say '  - desktop widget should say "N 个任务" with N = the number above'
        Say '  - the recent tasks page should list these packages, most recent first'
    }
}

# ---------------------------------------------------------------- -Seed
if ($Seed) {
    $didSomething = $true
    $list = $Seed.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    Say ("opening {0} app(s); each one enters the system recent list" -f $list.Count)

    $okCount = 0
    foreach ($pkg in $list) {
        $installed = Invoke-Shell -Command "pm list packages $pkg"
        if ($installed -notmatch [regex]::Escape("package:$pkg")) {
            Warn "skipped (not installed): $pkg"
            continue
        }

        # resolve the launcher activity; on ROMs without "cmd package" fall back to monkey
        $comp = ''
        $resolved = Invoke-Shell -Command "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg"
        $hit = ($resolved -split "`n" | Where-Object { $_ -match '/' } | Select-Object -Last 1)
        if ($hit) { $comp = $hit.Trim() }

        if ($comp) {
            $r = Invoke-Shell -Command ("am start -n " + $comp)
        } else {
            $r = Invoke-Shell -Command ("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        }

        if ($r -match 'Error|Exception|No activities|does not exist') {
            Bad "failed to open $pkg : $r"
        } else {
            $okCount++
            Good ("opened {0}" -f $pkg)
        }
        if ($WaitMs -gt 0) { Start-Sleep -Milliseconds $WaitMs }
    }
    Good "$okCount app(s) opened"
    Say 'now press HOME on the device and check the desktop widget / recent tasks page.'
    Say 'run ".\recents.ps1 -List" to print the answer key.'
}

# ---------------------------------------------------------------- -Clear
if ($Clear) {
    $didSomething = $true
    $dump = Invoke-Shell -Command 'dumpsys activity recents'
    $ids = [regex]::Matches($dump, 'Task(?:Record)?\{#(\d+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    if (-not $ids) {
        # some ROMs do not number tasks this way; try the loose form
        $ids = [regex]::Matches($dump, '#(\d+)') | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
    }
    if (-not $ids) { Warn 'no task ids found in dumpsys -- nothing to remove'; exit 0 }

    Say ("trying to remove {0} recorded task(s) ..." -f $ids.Count)
    $removed = 0
    foreach ($id in $ids) {
        if ($sdk -ge 30) { $r = Invoke-Shell -Command "am task remove $id" }
        else             { $r = Invoke-Shell -Command "am stack remove $id" }
        if ($r -match 'Error|Exception|Unknown|not found') {
            # some versions use the other spelling
            if ($sdk -ge 30) { $r = Invoke-Shell -Command "am stack remove $id" }
            else             { $r = Invoke-Shell -Command "am task remove $id" }
        }
        if ($r -notmatch 'Error|Exception|Unknown|not found') { $removed++ }
    }
    if ($removed -gt 0) { Good "removed $removed task(s)" }
    else {
        Warn 'could not remove anything (needs mini_shizuku / root on most ROMs)'
        Warn 'clear them from the launcher instead: recent tasks page -> Options -> clear all'
        Warn '  (that page-level clearing is itself a test case, so it is fine to use it)'
    }
}

if (-not $didSomething) {
    Say 'nothing to do -- pass -List, -Seed "pkg1,pkg2" or -Clear'
    Say 'example:  .\recents.ps1 -Seed "com.android.settings,com.android.deskclock"'
}
