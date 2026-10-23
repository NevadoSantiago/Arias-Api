$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here '..\PrintAgent.lib.ps1')

$script:TestKey = 'SECRETKEY-0123456789abcdef'
$script:TestToken = '11111111-aaaa-bbbb-cccc-222222222222'

function New-TestConfig {
    param([string]$LogDir)
    [pscustomobject]@{
        ApiUrl            = 'https://example.invalid'
        Key               = $script:TestKey
        PollSeconds       = 5
        AllowedHostPrefix = '192.168.0.'
        AllowedPort       = 9100
        LogDir            = $LogDir
    }
}

Describe 'Test-AllowedPrinter' {
    It 'accepts 192.168.0.121:9100' {
        Test-AllowedPrinter -HostName '192.168.0.121' -Port 9100 | Should Be $true
    }
    It 'rejects other subnet 192.168.1.5' {
        Test-AllowedPrinter -HostName '192.168.1.5' -Port 9100 | Should Be $false
    }
    It 'rejects 10.0.0.1' {
        Test-AllowedPrinter -HostName '10.0.0.1' -Port 9100 | Should Be $false
    }
    It 'rejects a hostname' {
        Test-AllowedPrinter -HostName 'printer.local' -Port 9100 | Should Be $false
    }
    It 'rejects port 80' {
        Test-AllowedPrinter -HostName '192.168.0.121' -Port 80 | Should Be $false
    }
    It 'rejects 192.168.0.1234' {
        Test-AllowedPrinter -HostName '192.168.0.1234' -Port 9100 | Should Be $false
    }
    It 'rejects empty host' {
        Test-AllowedPrinter -HostName '' -Port 9100 | Should Be $false
    }
}

Describe 'Send-RawBytes' {
    It 'sends exactly the bytes, including leading and trailing ESC @' {
        $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            $payload = [byte[]](@(0x1B, 0x40) + [System.Text.Encoding]::ASCII.GetBytes('MESA 5') + @(0x0A, 0x00, 0xFF, 0x1B, 0x40))
            $async = $listener.BeginAcceptTcpClient($null, $null)
            Send-RawBytes -HostName '127.0.0.1' -Port $port -Bytes $payload
            $client = $listener.EndAcceptTcpClient($async)
            $stream = $client.GetStream()
            $ms = New-Object System.IO.MemoryStream
            $buf = New-Object byte[] 1024
            while (($n = $stream.Read($buf, 0, $buf.Length)) -gt 0) { $ms.Write($buf, 0, $n) }
            $client.Close()
            $received = $ms.ToArray()
            ($received -join ',') | Should Be ($payload -join ',')
            $received[0] | Should Be 0x1B
            $received[1] | Should Be 0x40
            $received[$received.Length - 2] | Should Be 0x1B
            $received[$received.Length - 1] | Should Be 0x40
        } finally { $listener.Stop() }
    }
    It 'throws when nothing is listening' {
        $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
        $l.Start(); $port = $l.LocalEndpoint.Port; $l.Stop()
        { Send-RawBytes -HostName '127.0.0.1' -Port $port -Bytes ([byte[]](1, 2)) } | Should Throw
    }
}

Describe 'Get-NextDelay' {
    It 'doubles on failure' {
        Get-NextDelay -Current 5 -Base 5 -Max 60 | Should Be 10
        Get-NextDelay -Current 10 -Base 5 -Max 60 | Should Be 20
    }
    It 'caps at max' {
        Get-NextDelay -Current 40 -Base 5 -Max 60 | Should Be 60
        Get-NextDelay -Current 60 -Base 5 -Max 60 | Should Be 60
    }
    It 'resets to base after success' {
        Get-NextDelay -Current 40 -Base 5 -Max 60 -Success | Should Be 5
    }
    It 'jumps to max on auth failure' {
        Get-NextDelay -Current 5 -Base 5 -Max 60 -AuthFailed | Should Be 60
    }
}

Describe 'Write-AgentLog' {
    It 'writes lines to agent.log' {
        Write-AgentLog -Message 'hello' -LogDir $TestDrive
        (Get-Content (Join-Path $TestDrive 'agent.log') -Raw) | Should Match 'hello'
    }
    It 'rotates at the size limit and keeps 3 files' {
        $dir = Join-Path $TestDrive 'rot'
        for ($i = 0; $i -lt 40; $i++) {
            Write-AgentLog -Message ('line-' + $i + '-' + ('x' * 50)) -LogDir $dir -MaxBytes 500 -Keep 3
        }
        (Test-Path (Join-Path $dir 'agent.log')) | Should Be $true
        (Test-Path (Join-Path $dir 'agent.log.1')) | Should Be $true
        (Test-Path (Join-Path $dir 'agent.log.2')) | Should Be $true
        (Test-Path (Join-Path $dir 'agent.log.3')) | Should Be $false
        (Get-Item (Join-Path $dir 'agent.log')).Length | Should BeLessThan 700
    }
}

Describe 'Test-HeartbeatDue' {
    It 'is due when never logged' {
        Test-HeartbeatDue -Last $null -Now (Get-Date) | Should Be $true
    }
    It 'is not due within 10 minutes' {
        $now = Get-Date
        Test-HeartbeatDue -Last $now.AddMinutes(-9) -Now $now | Should Be $false
    }
    It 'is due after 10 minutes' {
        $now = Get-Date
        Test-HeartbeatDue -Last $now.AddMinutes(-11) -Now $now | Should Be $true
    }
}

Describe 'Get-AgentConfig' {
    It 'applies defaults and trims the trailing slash' {
        $p = Join-Path $TestDrive 'config.json'
        Set-Content -Path $p -Value '{"ApiUrl":"https://x.invalid/","Key":"abc"}'
        $c = Get-AgentConfig -Path $p
        $c.ApiUrl | Should Be 'https://x.invalid'
        $c.PollSeconds | Should Be 5
        $c.AllowedHostPrefix | Should Be '192.168.0.'
        $c.AllowedPort | Should Be 9100
        $c.LogDir | Should Be (Join-Path $TestDrive 'logs')
    }
    It 'throws when Key is missing' {
        $p = Join-Path $TestDrive 'bad.json'
        Set-Content -Path $p -Value '{"ApiUrl":"https://x.invalid"}'
        { Get-AgentConfig -Path $p } | Should Throw
    }
}

Describe 'Invoke-AgentCycle' {
    BeforeEach {
        $script:logDir = Join-Path $TestDrive ([guid]::NewGuid().ToString())
        $script:cfg = New-TestConfig -LogDir $script:logDir
        $script:bytes = [byte[]](0x1B, 0x40, 0x41, 0x1B, 0x40)
        $script:b64 = [Convert]::ToBase64String($script:bytes)
    }

    Context 'does nothing on an empty claim' { It 'runs' {
        Mock Invoke-AgentApi { @() }
        Mock Send-RawBytes { }
        $r = Invoke-AgentCycle -Config $script:cfg
        $r.Success | Should Be $true
        $r.Jobs | Should Be 0
        Assert-MockCalled Send-RawBytes -Times 0 -Exactly
        Assert-MockCalled Invoke-AgentApi -Times 1 -Exactly
    } }

    Context 'acks PRINTED with the claimToken on success' { It 'runs' {
        Mock Invoke-AgentApi {
            if ($Uri -like '*/claim') {
                @([pscustomobject]@{ id = 12; host = '192.168.0.121'; port = 9100; dataBase64 = $script:b64; claimToken = $script:TestToken })
            } else { [pscustomobject]@{ id = 12; status = 'PRINTED' } }
        }
        Mock Send-RawBytes { }
        $null = Invoke-AgentCycle -Config $script:cfg
        Assert-MockCalled Send-RawBytes -Times 1 -Exactly -ParameterFilter {
            $HostName -eq '192.168.0.121' -and $Port -eq 9100 -and (($Bytes -join ',') -eq ($script:bytes -join ','))
        }
        Assert-MockCalled Invoke-AgentApi -Times 1 -Exactly -ParameterFilter {
            $Uri -like '*/jobs/12/ack' -and $Body.status -eq 'PRINTED' -and $Body.claimToken -eq $script:TestToken
        }
    } }

    Context 'acks FAILED with token and error on TCP failure' { It 'runs' {
        Mock Invoke-AgentApi {
            if ($Uri -like '*/claim') {
                @([pscustomobject]@{ id = 13; host = '192.168.0.121'; port = 9100; dataBase64 = $script:b64; claimToken = $script:TestToken })
            } else { [pscustomobject]@{ id = 13; status = 'FAILED' } }
        }
        Mock Send-RawBytes { throw 'connect timeout' }
        $null = Invoke-AgentCycle -Config $script:cfg
        Assert-MockCalled Invoke-AgentApi -Times 1 -Exactly -ParameterFilter {
            $Uri -like '*/jobs/13/ack' -and $Body.status -eq 'FAILED' -and $Body.claimToken -eq $script:TestToken -and $Body.error -like '*connect timeout*'
        }
    } }

    Context 'acks FAILED without connecting when host is not allowed' { It 'runs' {
        Mock Invoke-AgentApi {
            if ($Uri -like '*/claim') {
                @([pscustomobject]@{ id = 14; host = '10.0.0.1'; port = 9100; dataBase64 = $script:b64; claimToken = $script:TestToken })
            } else { [pscustomobject]@{ id = 14; status = 'FAILED' } }
        }
        Mock Send-RawBytes { }
        $null = Invoke-AgentCycle -Config $script:cfg
        Assert-MockCalled Send-RawBytes -Times 0 -Exactly
        Assert-MockCalled Invoke-AgentApi -Times 1 -Exactly -ParameterFilter {
            $Uri -like '*/jobs/14/ack' -and $Body.status -eq 'FAILED' -and $Body.error -eq 'host not allowed' -and $Body.claimToken -eq $script:TestToken
        }
    } }

    Context 'swallows a 409 on ack and continues with the next job' { It 'runs' {
        Mock Invoke-AgentApi {
            if ($Uri -like '*/claim') {
                @(
                    [pscustomobject]@{ id = 21; host = '192.168.0.121'; port = 9100; dataBase64 = $script:b64; claimToken = 'tok-21' },
                    [pscustomobject]@{ id = 22; host = '192.168.0.121'; port = 9100; dataBase64 = $script:b64; claimToken = 'tok-22' }
                )
            } elseif ($Uri -like '*/jobs/21/ack') {
                throw (New-ApiError -Status 409)
            } else { [pscustomobject]@{ id = 22; status = 'PRINTED' } }
        }
        Mock Send-RawBytes { }
        $r = Invoke-AgentCycle -Config $script:cfg
        $r.Success | Should Be $true
        Assert-MockCalled Send-RawBytes -Times 2 -Exactly
        Assert-MockCalled Invoke-AgentApi -Times 1 -Exactly -ParameterFilter { $Uri -like '*/jobs/22/ack' }
        Assert-MockCalled Invoke-AgentApi -Times 1 -Exactly -ParameterFilter { $Uri -like '*/jobs/21/ack' }
    } }

    Context 'reports AuthFailed on claim 401' { It 'runs' {
        Mock Invoke-AgentApi { throw (New-ApiError -Status 401) }
        $r = Invoke-AgentCycle -Config $script:cfg
        $r.Success | Should Be $false
        $r.AuthFailed | Should Be $true
        (Get-Content (Join-Path $script:logDir 'agent.log') -Raw) | Should Match '401'
    } }

    Context 'reports failure on a network error during claim' { It 'runs' {
        Mock Invoke-AgentApi { throw (New-ApiError -Status 0) }
        $r = Invoke-AgentCycle -Config $script:cfg
        $r.Success | Should Be $false
        $r.AuthFailed | Should Be $false
    } }

    Context 'never writes the key, the claim token or the ticket bytes to the log' { It 'runs' {
        Mock Invoke-AgentApi {
            if ($Uri -like '*/claim') {
                @(
                    [pscustomobject]@{ id = 31; host = '192.168.0.121'; port = 9100; dataBase64 = $script:b64; claimToken = $script:TestToken },
                    [pscustomobject]@{ id = 32; host = '10.0.0.1'; port = 9100; dataBase64 = $script:b64; claimToken = $script:TestToken }
                )
            } else { throw (New-ApiError -Status 409) }
        }
        Mock Send-RawBytes { throw ('boom ' + $script:b64) }
        $null = Invoke-AgentCycle -Config $script:cfg
        $log = Get-Content (Join-Path $script:logDir 'agent.log') -Raw
        $log | Should Match '31'
        $log.Contains($script:TestKey) | Should Be $false
        $log.Contains($script:TestToken) | Should Be $false
        $log.Contains($script:b64) | Should Be $false
    } }
}

Describe 'Invoke-AgentApi' {
    It 'wraps a transport error as ApiError with status 0' {
        Mock Invoke-RestMethod { throw 'no network' }
        $err = $null
        try { Invoke-AgentApi -Method Post -Uri 'https://x.invalid/a' -Key 'k' } catch { $err = $_ }
        $err | Should Not BeNullOrEmpty
        $err.Exception.Data['Status'] | Should Be 0
    }
    It 'sends the key header and never puts it in the URI' {
        Mock Invoke-RestMethod { @() }
        $null = Invoke-AgentApi -Method Post -Uri 'https://x.invalid/a' -Key 'KEY123'
        Assert-MockCalled Invoke-RestMethod -Times 1 -Exactly -ParameterFilter {
            $Headers['X-Print-Agent-Key'] -eq 'KEY123' -and $Uri -notlike '*KEY123*'
        }
    }
}
