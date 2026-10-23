$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here '..\PrintAgent.lib.ps1')

Describe 'New-AgentKey' {
    It 'is 64 hex characters (32 bytes)' {
        (New-AgentKey) | Should Match '^[0-9a-f]{64}$'
    }
    It 'is different every call' {
        (New-AgentKey) | Should Not Be (New-AgentKey)
    }
}

Describe 'Get-KeySha256' {
    It 'equals the SHA-256 hex of the key text' {
        Get-KeySha256 -Key 'abc' | Should Be 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    }
    It 'matches an independent computation for a generated key' {
        $k = New-AgentKey
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $expected = ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($k)) | ForEach-Object { $_.ToString('x2') }) -join ''
        Get-KeySha256 -Key $k | Should Be $expected
    }
}

Describe 'install.ps1 defaults' {
    It 'defaults ApiUrl to the production API' {
        (Get-Content (Join-Path $here '..\install.ps1') -Raw) | Should Match "\[string\]\`$ApiUrl = 'https://api.ariasbodegon.com'"
    }
}

Describe 'New-AgentTaskTriggers' {
    # Task Scheduler restart settings only cover a task that fails to launch, not an agent that
    # exits later, so a repeating trigger relaunches it. The mutex and IgnoreNew prevent duplicates.
    $triggers = @(New-AgentTaskTriggers)

    It 'starts the agent at boot' {
        @($triggers | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_TaskBootTrigger' }).Count | Should Be 1
    }
    It 'relaunches the agent every 5 minutes, indefinitely' {
        $repeat = @($triggers | Where-Object { $_.Repetition.Interval -eq 'PT5M' })
        $repeat.Count | Should Be 1
        [string]::IsNullOrEmpty($repeat[0].Repetition.Duration) | Should Be $true
    }
}
