param(
    [string]$Model = "deepseek-v4.1-flash:cloud",
    [string]$ReasoningEffort = "none"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$serverRoot = Join-Path $repoRoot ".local\server"
$serverJar = Join-Path $serverRoot "server-release.jar"
$javaExecutable = Get-ChildItem -LiteralPath "C:\Program Files\Eclipse Adoptium" -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue |
    Where-Object FullName -Like "*jdk-17*\bin\java.exe" |
    Select-Object -First 1 -ExpandProperty FullName

if (-not (Test-Path -LiteralPath $serverJar)) {
    & (Join-Path $PSScriptRoot "setup-server.ps1")
}
if (-not $javaExecutable) {
    throw "JDK 17 java.exe was not found."
}

$pythonExecutable = Get-Command python -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $pythonExecutable) {
    throw "python was not found on PATH."
}
$env:MINDUSTRY_AGENT_PROJECT_DIR = $repoRoot
$env:MINDUSTRY_AGENT_PYTHON = $pythonExecutable
$env:LLM_MODEL = $Model
$env:LLM_REASONING_EFFORT = $ReasoningEffort

function Find-ListeningProcessId([int]$Port) {
    foreach ($line in (& netstat -ano -p TCP 2>$null)) {
        if ($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$' -and
            [int]$Matches[1] -eq $Port) {
            return [int]$Matches[2]
        }
    }
    return $null
}

$bridgePort = if ($env:MINDUSTRY_AGENT_PORT) { [int]$env:MINDUSTRY_AGENT_PORT } else { 8765 }
$occupied = @{}
foreach ($port in @($bridgePort, 6567)) {
    $ownerPid = Find-ListeningProcessId $port
    if ($ownerPid) { $occupied[$port] = $ownerPid }
}
if ($occupied.Count -gt 0) {
    Write-Host ""
    Write-Host "Mindustry 서버가 이미 실행 중이라 새 서버를 시작할 수 없습니다." -ForegroundColor Yellow
    foreach ($entry in $occupied.GetEnumerator() | Sort-Object Name) {
        $process = Get-Process -Id $entry.Value -ErrorAction SilentlyContinue
        $processName = if ($process) { $process.ProcessName } else { "unknown" }
        Write-Host "  포트 $($entry.Key): PID $($entry.Value) ($processName)"
    }
    $uniquePids = @($occupied.Values | Sort-Object -Unique)
    Write-Host "기존 서버 창에서 Ctrl+C를 누른 뒤 다시 실행하세요."
    Write-Host "기존 창을 찾을 수 없다면 직접 실행: Stop-Process -Id $($uniquePids -join ',')"
    throw "Existing Mindustry server process is still listening."
}

Push-Location $serverRoot
try {
    & $javaExecutable -jar $serverJar
} finally {
    Pop-Location
}
