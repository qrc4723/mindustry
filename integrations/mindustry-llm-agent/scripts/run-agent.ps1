param(
    [string]$Model = "deepseek-v4.1-flash:cloud",
    [string]$ReasoningEffort = "none",
    [int]$MaxTurns = 20,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:LLM_MODEL = $Model
$env:LLM_REASONING_EFFORT = $ReasoningEffort
$arguments = @("-m", "mindustry_agent", "--max-turns", $MaxTurns)
if ($DryRun) { $arguments += "--dry-run" }

Push-Location $repoRoot
try {
    & python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Agent exited with code $LASTEXITCODE." }
} finally {
    Pop-Location
}
