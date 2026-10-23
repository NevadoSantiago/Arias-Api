#Requires -Version 5.1
<#
.SYNOPSIS
    Installs the Arias ticket print agent as a scheduled task (runs at startup as SYSTEM).
#>
param(
    [string]$ApiUrl = 'https://api.ariasbodegon.com',
    [string]$InstallDir = 'C:\Arias-ticketera',
    [switch]$NewKey
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host 'ERROR: this script must run as Administrator (PowerShell -> Run as administrator).' -ForegroundColor Red
    exit 1
}

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here 'PrintAgent.lib.ps1')

$taskName = 'Arias Ticketera'
$configPath = Join-Path $InstallDir 'config.json'

# Stop a running instance before replacing files.
$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($existing) {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

if (-not (Test-Path -LiteralPath $InstallDir)) { $null = New-Item -ItemType Directory -Path $InstallDir -Force }
foreach ($f in 'agent.ps1', 'PrintAgent.lib.ps1') {
    Copy-Item -LiteralPath (Join-Path $here $f) -Destination (Join-Path $InstallDir $f) -Force
}

# Reuse the existing key and settings unless -NewKey is given.
$old = $null
if (Test-Path -LiteralPath $configPath) {
    try { $old = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json } catch { $old = $null }
}
$usedApiUrl = $ApiUrl
if ($old -and $old.ApiUrl -and -not $PSBoundParameters.ContainsKey('ApiUrl')) { $usedApiUrl = [string]$old.ApiUrl }

$keyKept = $false
if ($old -and $old.Key -and -not $NewKey) {
    $key = [string]$old.Key
    $keyKept = $true
} else {
    $key = New-AgentKey
}

$cfg = [ordered]@{
    ApiUrl            = $usedApiUrl.TrimEnd('/')
    Key               = $key
    PollSeconds       = 5
    AllowedHostPrefix = '192.168.0.'
    AllowedPort       = 9100
}
if ($old) {
    if ($old.PollSeconds) { $cfg.PollSeconds = [int]$old.PollSeconds }
    if ($old.AllowedHostPrefix) { $cfg.AllowedHostPrefix = [string]$old.AllowedHostPrefix }
    if ($old.AllowedPort) { $cfg.AllowedPort = [int]$old.AllowedPort }
}

# Lock the ACL first (SYSTEM + Administrators only), then write the secret.
if (-not (Test-Path -LiteralPath $configPath)) { $null = New-Item -ItemType File -Path $configPath -Force }
& icacls.exe $configPath /inheritance:r /grant:r '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict config.json permissions (icacls failed).' }
$json = ConvertTo-Json -InputObject ([pscustomobject]$cfg)
[System.IO.File]::WriteAllText($configPath, $json, (New-Object System.Text.UTF8Encoding($false)))

# Scheduled task: replace if present.
if ($existing) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }
$agentPath = Join-Path $InstallDir 'agent.ps1'
$taskArgs = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $agentPath + '"'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $taskArgs -WorkingDirectory $InstallDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
$null = Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Arias: imprime las comandas de cocina'

Start-ScheduledTask -TaskName $taskName
Start-Sleep -Seconds 6

$hash = Get-KeySha256 -Key $key
Write-Host ''
Write-Host 'Instalacion completa.' -ForegroundColor Green
if ($keyKept) { Write-Host 'Se conserva la clave existente.' } else { Write-Host 'Se genero una clave nueva.' }
Write-Host 'En Railway, configure la variable:'
Write-Host ('  PRINT_AGENT_KEY_SHA256 = ' + $hash) -ForegroundColor Yellow
Write-Host '(Solo se muestra el hash; la clave queda guardada en config.json.)'
Write-Host ''
Write-Host ('Ultimas lineas del log (' + (Join-Path $InstallDir 'logs\agent.log') + '):')
$logFile = Join-Path $InstallDir 'logs\agent.log'
if (Test-Path -LiteralPath $logFile) { Get-Content -LiteralPath $logFile -Tail 10 } else { Write-Host '(todavia no hay log)' }
