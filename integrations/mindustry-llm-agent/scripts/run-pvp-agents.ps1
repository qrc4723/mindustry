param(
    [string]$PrimaryModel = "glm-5.3-flash:cloud",
    [string]$DeepSeekModel = "deepseek-v4-pro:cloud",
    [string]$PrimaryToken = "local-primary-team-token",
    [string]$DeepSeekToken = "local-deepseek-team-token",
    [string]$PrimaryReasoningEffort = "low",
    [string]$DeepSeekReasoningEffort = "none",
    [string]$ApiUrl = $(if ($env:MINDUSTRY_API_URL) { $env:MINDUSTRY_API_URL } else { "http://127.0.0.1:8765" }),
    [int]$MaxTurns = 500
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$pythonExecutable = Get-Command python -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $pythonExecutable) { throw "python was not found on PATH." }

$primaryHeaders = @{ Authorization = "Bearer $PrimaryToken"; "X-Mindustry-Team" = "sharded" }
$deepseekHeaders = @{ Authorization = "Bearer $DeepSeekToken"; "X-Mindustry-Team" = "crux" }
$ApiUrl = $ApiUrl.TrimEnd('/')
$primaryHealth = Invoke-RestMethod -Uri "$ApiUrl/health" -Headers $primaryHeaders
$deepseekHealth = Invoke-RestMethod -Uri "$ApiUrl/health" -Headers $deepseekHeaders
$primaryState = Invoke-RestMethod -Uri "$ApiUrl/v1/state" -Headers $primaryHeaders
$deepseekState = Invoke-RestMethod -Uri "$ApiUrl/v1/state" -Headers $deepseekHeaders
if (-not $primaryHealth.ok -or $primaryHealth.controlled_team -ne "sharded") { throw "Primary team authentication failed." }
if (-not $deepseekHealth.ok -or $deepseekHealth.controlled_team -ne "crux") { throw "DeepSeek team authentication failed." }
if (-not $primaryState.rules.pvp -or -not $deepseekState.rules.pvp) {
    throw "The loaded game is not PvP. Enter 'pvp-start' in the server console first."
}
if (-not $primaryState.core -or -not $deepseekState.core) { throw "Both PvP teams must have a core on this map." }

$runDirectory = Join-Path $repoRoot "runs"
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$env:MINDUSTRY_API_URL = $ApiUrl

$primaryArguments = @(
    "-m", "mindustry_agent", "--model", $PrimaryModel, "--reasoning-effort", $PrimaryReasoningEffort,
    "--team", "sharded", "--game-token", $PrimaryToken, "--max-turns", $MaxTurns
)
$deepseekArguments = @(
    "-m", "mindustry_agent", "--model", $DeepSeekModel, "--reasoning-effort", $DeepSeekReasoningEffort,
    "--team", "crux", "--game-token", $DeepSeekToken, "--max-turns", $MaxTurns
)
$primaryProcess = Start-Process -FilePath $pythonExecutable -ArgumentList $primaryArguments -WorkingDirectory $repoRoot `
    -RedirectStandardOutput (Join-Path $runDirectory "$stamp-pvp-primary.out.log") `
    -RedirectStandardError (Join-Path $runDirectory "$stamp-pvp-primary.err.log") -WindowStyle Hidden -PassThru
$deepseekProcess = Start-Process -FilePath $pythonExecutable -ArgumentList $deepseekArguments -WorkingDirectory $repoRoot `
    -RedirectStandardOutput (Join-Path $runDirectory "$stamp-pvp-deepseek.out.log") `
    -RedirectStandardError (Join-Path $runDirectory "$stamp-pvp-deepseek.err.log") -WindowStyle Hidden -PassThru

$pidState = @{
    started_at = (Get-Date).ToString("o")
    primary = @{ pid = $primaryProcess.Id; model = $PrimaryModel; team = "sharded" }
    deepseek = @{ pid = $deepseekProcess.Id; model = $DeepSeekModel; team = "crux" }
}
$pidState | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $repoRoot ".local\pvp-agents.json") -Encoding utf8
Write-Host "PvP agents started asynchronously. Primary PID=$($primaryProcess.Id), DeepSeek PID=$($deepseekProcess.Id)"
Write-Host "Model run records are written to runs\*.jsonl; console output is in runs\$stamp-pvp-*.log"
