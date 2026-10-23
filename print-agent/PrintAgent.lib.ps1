# PrintAgent.lib.ps1 - functions for the Arias ticket print agent.
# Dot-source this file. Windows PowerShell 5.1 compatible. Pure ASCII.

try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$script:LastHeartbeat = $null

function Write-AgentLog {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [Parameter(Mandatory = $true)][string]$LogDir,
        [int]$MaxBytes = 1048576,
        [int]$Keep = 3
    )
    try {
        if (-not (Test-Path -LiteralPath $LogDir)) {
            $null = New-Item -ItemType Directory -Path $LogDir -Force
        }
        $log = Join-Path $LogDir 'agent.log'
        if ((Test-Path -LiteralPath $log) -and ((Get-Item -LiteralPath $log).Length -ge $MaxBytes)) {
            $last = Join-Path $LogDir ('agent.log.' + ($Keep - 1))
            if (Test-Path -LiteralPath $last) { Remove-Item -LiteralPath $last -Force }
            for ($i = $Keep - 2; $i -ge 1; $i--) {
                $src = Join-Path $LogDir ('agent.log.' + $i)
                if (Test-Path -LiteralPath $src) {
                    Move-Item -LiteralPath $src -Destination (Join-Path $LogDir ('agent.log.' + ($i + 1))) -Force
                }
            }
            Move-Item -LiteralPath $log -Destination (Join-Path $LogDir 'agent.log.1') -Force
        }
        $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $Message + "`r`n"
        [System.IO.File]::AppendAllText($log, $line, [System.Text.Encoding]::ASCII)
    } catch {
        # Logging must never break the loop.
    }
}

function Test-AllowedPrinter {
    param(
        [string]$HostName,
        [int]$Port,
        [string]$AllowedHostPrefix = '192.168.0.',
        [int]$AllowedPort = 9100
    )
    if ([string]::IsNullOrEmpty($HostName)) { return $false }
    if ($Port -ne $AllowedPort) { return $false }
    if ($HostName -notmatch '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$') { return $false }
    for ($i = 1; $i -le 4; $i++) {
        if ([int]$Matches[$i] -gt 255) { return $false }
    }
    return $HostName.StartsWith($AllowedHostPrefix)
}

function Send-RawBytes {
    param(
        [Parameter(Mandatory = $true)][string]$HostName,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][byte[]]$Bytes,
        [int]$ConnectTimeoutMs = 5000,
        [int]$WriteTimeoutMs = 10000
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.SendTimeout = $WriteTimeoutMs
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($ConnectTimeoutMs, $false)) {
            throw ('connect timeout to ' + $HostName + ':' + $Port)
        }
        $client.EndConnect($async)
        $stream = $client.GetStream()
        $stream.WriteTimeout = $WriteTimeoutMs
        $stream.Write($Bytes, 0, $Bytes.Length)
        $stream.Flush()
        $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    } finally {
        $client.Close()
    }
}

function New-ApiError {
    param([int]$Status, [string]$Message)
    if (-not $Message) { $Message = 'HTTP ' + $Status }
    $ex = New-Object System.Exception($Message)
    $ex.Data['Status'] = $Status
    return $ex
}

function Get-ApiErrorStatus {
    param($ErrorRecord)
    $s = $null
    try { $s = $ErrorRecord.Exception.Data['Status'] } catch { }
    if ($null -eq $s) { return 0 }
    return [int]$s
}

function Invoke-AgentApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Key,
        $Body = $null,
        [int]$TimeoutSec = 20
    )
    $headers = @{ 'X-Print-Agent-Key' = $Key }
    try {
        if ($null -ne $Body) {
            $json = ConvertTo-Json -InputObject $Body -Compress
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -Body $json -ContentType 'application/json' -TimeoutSec $TimeoutSec -UseBasicParsing
        }
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec $TimeoutSec -UseBasicParsing
    } catch {
        $status = 0
        try {
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        } catch { }
        throw (New-ApiError -Status $status -Message ('HTTP ' + $status))
    }
}

function Invoke-Claim {
    param([Parameter(Mandatory = $true)]$Config)
    $jobs = Invoke-AgentApi -Method 'Post' -Uri ($Config.ApiUrl + '/api/v1/print-agent/jobs/claim') -Key $Config.Key
    return @($jobs | Where-Object { $null -ne $_ })
}

function Invoke-Ack {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)]$JobId,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$ClaimToken,
        [string]$ErrorText
    )
    $body = @{ status = $Status; claimToken = $ClaimToken }
    if ($Status -eq 'FAILED') {
        if (-not $ErrorText) { $ErrorText = 'error' }
        if ($ErrorText.Length -gt 200) { $ErrorText = $ErrorText.Substring(0, 200) }
        $body.error = $ErrorText
    }
    return Invoke-AgentApi -Method 'Post' -Uri ($Config.ApiUrl + '/api/v1/print-agent/jobs/' + $JobId + '/ack') -Key $Config.Key -Body $body
}

function Test-HeartbeatDue {
    param($Last, [datetime]$Now, [int]$Minutes = 10)
    if ($null -eq $Last) { return $true }
    return (($Now - [datetime]$Last).TotalMinutes -ge $Minutes)
}

function Get-NextDelay {
    param(
        [int]$Current,
        [int]$Base = 5,
        [int]$Max = 60,
        [switch]$Success,
        [switch]$AuthFailed
    )
    if ($Success) { return $Base }
    if ($AuthFailed) { return $Max }
    $start = [Math]::Max($Current, $Base)
    return [Math]::Min($start * 2, $Max)
}

function Get-AgentConfig {
    param([Parameter(Mandatory = $true)][string]$Path)
    $raw = Get-Content -LiteralPath $Path -Raw
    $c = ConvertFrom-Json -InputObject $raw
    if (-not $c.ApiUrl) { throw 'config: ApiUrl is required' }
    if (-not $c.Key) { throw 'config: Key is required' }
    $poll = 5
    if ($c.PollSeconds) { $poll = [int]$c.PollSeconds }
    $prefix = '192.168.0.'
    if ($c.AllowedHostPrefix) { $prefix = [string]$c.AllowedHostPrefix }
    $port = 9100
    if ($c.AllowedPort) { $port = [int]$c.AllowedPort }
    return [pscustomobject]@{
        ApiUrl            = ([string]$c.ApiUrl).TrimEnd('/')
        Key               = ([string]$c.Key).Trim()
        PollSeconds       = $poll
        AllowedHostPrefix = $prefix
        AllowedPort       = $port
        LogDir            = (Join-Path (Split-Path -Parent $Path) 'logs')
    }
}

function Invoke-AgentCycle {
    param([Parameter(Mandatory = $true)]$Config)
    $log = $Config.LogDir
    $result = [pscustomobject]@{ Success = $true; AuthFailed = $false; Jobs = 0 }

    try {
        $jobs = @(Invoke-Claim -Config $Config)
    } catch {
        $status = Get-ApiErrorStatus $_
        Write-AgentLog -Message ('claim failed: HTTP status ' + $status) -LogDir $log
        $result.Success = $false
        $result.AuthFailed = ($status -eq 401)
        return $result
    }

    if ($jobs.Count -eq 0) {
        $now = Get-Date
        if (Test-HeartbeatDue -Last $script:LastHeartbeat -Now $now) {
            Write-AgentLog -Message 'heartbeat: idle, no jobs' -LogDir $log
            $script:LastHeartbeat = $now
        }
        return $result
    }

    $script:LastHeartbeat = Get-Date
    $result.Jobs = $jobs.Count
    foreach ($job in $jobs) {
        $id = $job.id
        $printerHost = [string]$job.host
        $printerPort = 0
        try { $printerPort = [int]$job.port } catch { }
        $token = [string]$job.claimToken
        $status = 'FAILED'
        $errText = $null

        if (-not (Test-AllowedPrinter -HostName $printerHost -Port $printerPort -AllowedHostPrefix $Config.AllowedHostPrefix -AllowedPort $Config.AllowedPort)) {
            $errText = 'host not allowed'
        } else {
            try {
                $bytes = [Convert]::FromBase64String([string]$job.dataBase64)
                Send-RawBytes -HostName $printerHost -Port $printerPort -Bytes $bytes
                $status = 'PRINTED'
            } catch {
                $errText = 'print error: ' + $_.Exception.Message
            }
        }

        # Never log the ticket bytes or tokens: only id, host and result.
        $line = 'job ' + $id + ' host ' + $printerHost + ':' + $printerPort + ' result ' + $status
        if ($status -eq 'FAILED') { $line += ' (' + $(if ($errText -eq 'host not allowed') { $errText } else { 'print error' }) + ')' }
        Write-AgentLog -Message $line -LogDir $log

        try {
            $null = Invoke-Ack -Config $Config -JobId $id -Status $status -ClaimToken $token -ErrorText $errText
        } catch {
            $ackStatus = Get-ApiErrorStatus $_
            Write-AgentLog -Message ('ack failed for job ' + $id + ': HTTP status ' + $ackStatus) -LogDir $log
        }
    }
    return $result
}

function New-AgentKey {
    $bytes = New-Object byte[] 32
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Get-KeySha256 {
    param([Parameter(Mandatory = $true)][string]$Key)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Key))
    } finally { $sha.Dispose() }
    return (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
}

function New-AgentTaskTriggers {
    # Boot starts the agent. Task Scheduler restart settings only cover a launch failure, not an
    # agent that exits later, so a trigger repeating every 5 minutes (no duration = indefinitely)
    # relaunches it. The task's IgnoreNew setting and the agent mutex prevent duplicate instances.
    $boot = New-ScheduledTaskTrigger -AtStartup
    $repeat = New-ScheduledTaskTrigger -Once -At (Get-Date) -RepetitionInterval (New-TimeSpan -Minutes 5)
    @($boot, $repeat)
}
