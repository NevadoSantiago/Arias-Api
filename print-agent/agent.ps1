#Requires -Version 5.1
<#
.SYNOPSIS
    Arias ticket print agent: polls the API for kitchen tickets and sends them to the printer.
#>
param(
    [switch]$Once,
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'PrintAgent.lib.ps1')

if (-not $ConfigPath) { $ConfigPath = Join-Path $root 'config.json' }

$mutex = New-Object System.Threading.Mutex($false, 'Global\AriasTicketeraPrintAgent')
$owned = $false
try {
    try { $owned = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $owned = $true }
    if (-not $owned) {
        Write-Host 'Another instance is already running. Exiting.'
        exit 0
    }

    $config = Get-AgentConfig -Path $ConfigPath
    Write-AgentLog -Message 'agent started' -LogDir $config.LogDir

    $delay = $config.PollSeconds
    while ($true) {
        $success = $false
        $auth = $false
        try {
            $r = Invoke-AgentCycle -Config $config
            $success = $r.Success
            $auth = $r.AuthFailed
        } catch {
            Write-AgentLog -Message ('cycle error: ' + $_.Exception.GetType().Name) -LogDir $config.LogDir
        }
        if ($success) {
            $delay = Get-NextDelay -Current $delay -Base $config.PollSeconds -Success
        } elseif ($auth) {
            $delay = Get-NextDelay -Current $delay -Base $config.PollSeconds -AuthFailed
        } else {
            $delay = Get-NextDelay -Current $delay -Base $config.PollSeconds
        }
        if ($Once) { break }
        Start-Sleep -Seconds $delay
    }
} catch {
    # Config or startup problem: log and exit non-zero so the task restarts us.
    $dir = Join-Path $root 'logs'
    Write-AgentLog -Message ('fatal: ' + $_.Exception.Message) -LogDir $dir
    exit 1
} finally {
    if ($owned) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
