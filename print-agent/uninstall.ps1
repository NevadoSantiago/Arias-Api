#Requires -Version 5.1
param(
    [string]$InstallDir = 'C:\Arias-ticketera',
    [switch]$RemoveFiles
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host 'ERROR: this script must run as Administrator (PowerShell -> Run as administrator).' -ForegroundColor Red
    exit 1
}

$taskName = 'Arias Ticketera'
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host 'Tarea eliminada.'
} else {
    Write-Host 'La tarea no existia.'
}

if ($RemoveFiles) {
    Start-Sleep -Seconds 2
    if (Test-Path -LiteralPath $InstallDir) {
        Remove-Item -LiteralPath $InstallDir -Recurse -Force
        Write-Host ('Carpeta eliminada: ' + $InstallDir)
    }
} else {
    Write-Host ('Se conserva la carpeta ' + $InstallDir + ' (use -RemoveFiles para borrarla).')
}
