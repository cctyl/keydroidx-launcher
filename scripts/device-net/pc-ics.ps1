<#
    PC side: share the internet connection to the USB RNDIS adapter (Windows ICS),
             so the Android device sitting behind USB gets online.

    Companion : net-up.ps1 (device side) , README.md
    Needs     : Administrator -- the script re-launches itself elevated (UAC prompt)
    NOTE      : all messages are ASCII on purpose -- PowerShell 5.1 reads UTF-8
                files without BOM as ANSI, Chinese text would show up as mojibake.

    Usage:
        .\pc-ics.ps1                          detect the online adapter and share it (UAC)
        .\pc-ics.ps1 -PublicAdapter "Ethernet 2"
        .\pc-ics.ps1 -DryRun                  show what would be done, change nothing
        .\pc-ics.ps1 -Off                     disable all ICS sharing

    ICS always gives the shared (private) adapter 192.168.137.1/24 plus DHCP/DNS/NAT,
    which is exactly what net-up.ps1 expects on the device side.
#>
[CmdletBinding()]
param(
    [string] $PublicAdapter = '',                 # adapter that has internet (name or device-name keyword); empty = auto detect
    [string] $PrivateMatch  = '*Remote NDIS*',    # adapter exposed to the device (USB RNDIS)
    [switch] $Off,
    [switch] $DryRun,
    [switch] $Pause                               # internal: keeps the elevated window open
)

function Say  ($m) { Write-Host "[*] $m" -ForegroundColor Cyan }
function Good ($m) { Write-Host "[+] $m" -ForegroundColor Green }
function Warn ($m) { Write-Host "[!] $m" -ForegroundColor Yellow }
function Bad  ($m) { Write-Host "[-] $m" -ForegroundColor Red }
function Die  ($m) { Bad $m; if ($Pause) { Read-Host 'Press Enter to close' | Out-Null }; exit 1 }

# ---------------------------------------------------------------- need admin
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin -and -not $DryRun) {
    Say 'Administrator rights required - relaunching elevated (click "Yes" in the UAC dialog)'
    $a = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"", '-Pause')
    if ($PublicAdapter) { $a += @('-PublicAdapter', "`"$PublicAdapter`"") }
    if ($PrivateMatch)  { $a += @('-PrivateMatch',  "`"$PrivateMatch`"") }
    if ($Off) { $a += '-Off' }
    Start-Process powershell -Verb RunAs -ArgumentList $a
    exit 0
}

# ---------------------------------------------------------------- dry run (no COM, no admin needed)
if ($DryRun) {
    Say 'DRY RUN - nothing is changed. Plan:'
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
             Sort-Object RouteMetric | Select-Object -First 1
    if (-not $route) { Die 'no default route found - cannot detect the adapter that has internet.' }
    $pubName = if ($PublicAdapter) { $PublicAdapter } else { $route.InterfaceAlias }
    Say ("  public  (has internet) : {0}   next hop {1} metric {2}" -f $pubName, $route.NextHop, $route.RouteMetric)
    $ad = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceDescription -like $PrivateMatch } | Select-Object -First 1
    if ($ad) { Say ("  private (USB RNDIS)    : {0}   [{1}]   status {2}" -f $ad.Name, $ad.InterfaceDescription, $ad.Status) }
    else     { Warn ("private adapter not found (pattern {0}) - plug the device in and run .\net-up.ps1 first" -f $PrivateMatch) }
    Say  '  action                 : EnableSharing(0) on public + EnableSharing(1) on private (Windows ICS)'
    exit 0
}

# ---------------------------------------------------------------- enumerate connections (needs admin)
$h = New-Object -ComObject HNetCfg.HNetShare
$conns = @()
foreach ($c in $h.EnumEveryConnection) {
    $p = $h.NetConnectionProps($c)
    $conns += [pscustomobject]@{ Conn = $c; Name = $p.Name; Device = $p.DeviceName; Status = $p.Status }
}
if ($conns.Count -eq 0) {
    Die 'cannot enumerate network connections (HNetCfg.HNetShare returned nothing). Run this script elevated.'
}
Say 'network connections:'
$conns | ForEach-Object { Write-Host ("      {0,-32} {1}" -f $_.Name, $_.Device) }

# ---------------------------------------------------------------- resolve public (internet) adapter
if (-not $PublicAdapter) {
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
             Sort-Object RouteMetric | Select-Object -First 1
    if (-not $route) { Die 'no default route found - cannot detect the adapter that has internet.' }
    $PublicAdapter = $route.InterfaceAlias
    Say ("auto detected online adapter: {0} (next hop {1}, metric {2})" -f $route.InterfaceAlias, $route.NextHop, $route.RouteMetric)
}
$pub = $conns | Where-Object { $_.Name -eq $PublicAdapter -or $_.Device -like "*$PublicAdapter*" } | Select-Object -First 1
if (-not $pub) { Die "public adapter not found: $PublicAdapter" }

$priv = $conns | Where-Object { $_.Device -like $PrivateMatch -or $_.Name -like $PrivateMatch } | Select-Object -First 1
if (-not $priv) {
    Die ("private adapter not found (pattern $PrivateMatch). Plug the device in and run .\net-up.ps1 first, " +
         'the PC only sees the RNDIS adapter while USB composition is rndis,adb.')
}

# ---------------------------------------------------------------- helpers
function Get-Cfg ($connRef) { return $h.INetSharingConfigurationForINetConnection($connRef) }

function Disable-AllSharing {
    foreach ($c in $conns) {
        try {
            $cfg = Get-Cfg $c.Conn
            if ($cfg.SharingEnabled) { $cfg.DisableSharing(); Say "sharing disabled on: $($c.Name)" }
        } catch { Warn "disable failed on $($c.Name): $($_.Exception.Message)" }
    }
}

function Enable-Sharing ($connRef, [int] $type, [string] $label) {
    $cfg = Get-Cfg $connRef
    if ($cfg.SharingEnabled -and $cfg.SharingConnectionType -eq $type) {
        Good "already enabled as $label : $($conns | Where-Object { $_.Conn -eq $connRef } | Select-Object -ExpandProperty Name)"
        return
    }
    $cfg.EnableSharing($type)
    Good "enabled as $label"
}

# ---------------------------------------------------------------- do it
if ($Off) {
    Disable-AllSharing
    Good 'ICS sharing disabled.'
} else {
    try {
        Enable-Sharing $pub.Conn  0 'public (shared)'
        Enable-Sharing $priv.Conn 1 'private (home)'
    } catch {
        Warn "enable failed: $($_.Exception.Message)"
        Say  'another adapter is probably already shared - clearing all sharing and retrying once'
        Disable-AllSharing
        Enable-Sharing $pub.Conn  0 'public (shared)'
        Enable-Sharing $priv.Conn 1 'private (home)'
    }

    Start-Sleep -Seconds 3
    $ad = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceDescription -like $priv.Device } | Select-Object -First 1
    $alias = if ($ad) { $ad.Name } else { $priv.Name }
    $ip = (Get-NetIPAddress -InterfaceAlias $alias -AddressFamily IPv4 -ErrorAction SilentlyContinue |
           Where-Object { $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress
    if ($ip) { Good "sharing is on: $($pub.Name) -> $($priv.Name)   (device gateway = $ip)" }
    else     { Warn "$($priv.Name) has no usable IPv4 yet - wait a few seconds and check with Get-NetAdapter" }
    Say 'next step: run .\net-up.ps1 to configure the device side'
}

if ($Pause) { Read-Host 'Press Enter to close' | Out-Null }
