param(
    [string]$ProxyHost = "127.0.0.1",
    [int]$ProxyPort = 8082,
    [string]$HttpsUrl = "https://example.com",
    [string]$HttpUrl = "http://example.com",
    [int]$TimeoutSeconds = 20
)

$ErrorActionPreference = "Stop"

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "ASSERT FAIL: $Message"
    }

    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Run-Curl {
    param(
        [string[]]$Args
    )

    $cmd = @("--max-time", "$TimeoutSeconds") + $Args
    $output = (& curl.exe @cmd 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE
    return [PSCustomObject]@{
        Output = $output
        ExitCode = $exitCode
        Command = "curl.exe " + ($cmd -join " ")
    }
}

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    throw "未找到 curl.exe。请确认 Windows 11 已安装 curl（系统自带）。"
}

$proxy = "http://$ProxyHost`:$ProxyPort"
Write-Host "HTTPS 代理冒烟测试开始，代理地址: $proxy" -ForegroundColor Cyan

# Test 1: CONNECT + HTTPS 请求应该成功
$t1 = Run-Curl -Args @("-sS", "-v", "-x", $proxy, $HttpsUrl, "-I")
Write-Host "`n[Test1] CONNECT 隧道建立 + HTTPS HEAD" -ForegroundColor Yellow
Write-Host $t1.Command
Assert-True ($t1.ExitCode -eq 0) "curl 通过 HTTPS 代理访问目标站点成功（退出码为0）"
Assert-True (($t1.Output -match "CONNECT\s+[^\s:]+:443") -or ($t1.Output -match "CONNECT\s+\[[^\]]+\]:443")) "请求中出现 CONNECT host:443"
Assert-True ($t1.Output -match "HTTP/1\.[01]\s+200\s+Connection\s+Established") "代理返回 200 Connection Established"

# Test 2: HTTPS-only 代理下，普通 HTTP 请求应该被拒绝（405）
$t2 = Run-Curl -Args @("-sS", "-i", "-x", $proxy, $HttpUrl, "-I")
Write-Host "`n[Test2] 非 CONNECT 请求应被拒绝（405）" -ForegroundColor Yellow
Write-Host $t2.Command
Assert-True ($t2.Output -match "405") "HTTPS 代理拒绝普通 HTTP 代理请求（状态码 405）"

# Test 3: 不可达目标应失败（验证异常路径）
$badUrl = "https://nonexistent.invalid"
$t3 = Run-Curl -Args @("-sS", "-v", "-x", $proxy, $badUrl, "-I")
Write-Host "`n[Test3] 不可达域名异常路径" -ForegroundColor Yellow
Write-Host $t3.Command
Assert-True (
    ($t3.ExitCode -ne 0) -or ($t3.Output -match "502") -or ($t3.Output -match "504") -or ($t3.Output -match "Could not resolve host")
) "不可达目标时出现预期失败（curl 非0退出码或 502/504）"

Write-Host "`n全部断言通过：HTTPS CONNECT 代理功能正常。" -ForegroundColor Green
