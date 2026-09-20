$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$statePath = Join-Path $repoRoot ".local\pvp-agents.json"
if (-not (Test-Path -LiteralPath $statePath)) {
    Write-Host "No recorded PvP agent processes."
    exit 0
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
# Keep the legacy glm key so a match started before the model switch can still be stopped cleanly.
foreach ($entry in @($state.primary, $state.qwen, $state.glm, $state.deepseek)) {
    $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
    if ($process -and $process.ProcessName -like "python*") {
        Stop-Process -Id $process.Id
        Write-Host "Stopped $($entry.team) agent PID=$($process.Id)"
    }
}
Remove-Item -LiteralPath $statePath
