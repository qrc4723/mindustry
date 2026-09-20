param(
    [string]$PrimaryModel = "glm-5.3-flash:cloud",
    [string]$DeepSeekModel = "deepseek-v4-flash:cloud",
    [string]$PrimaryReasoningEffort = "low",
    [string]$DeepSeekReasoningEffort = "none",
    [string]$PrimaryToken = "local-primary-team-token",
    [string]$DeepSeekToken = "local-deepseek-team-token",
    [switch]$DisableCoreUnits
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$env:MINDUSTRY_TEAM_TOKENS = "sharded=$PrimaryToken;crux=$DeepSeekToken"
$env:MINDUSTRY_AUTO_CORE_DEFENSE = if ($DisableCoreUnits) { "false" } else { "true" }
$env:PVP_PRIMARY_MODEL = $PrimaryModel
$env:PVP_DEEPSEEK_MODEL = $DeepSeekModel
$env:PVP_PRIMARY_REASONING_EFFORT = $PrimaryReasoningEffort
$env:PVP_DEEPSEEK_REASONING_EFFORT = $DeepSeekReasoningEffort

Write-Host "Stockpile RTS teams: GLM-5.3-Flash=sharded, DeepSeek=crux"
Write-Host "After the server prompt appears, enter: rts-start"
Write-Host "Then reconnect the client and enter in game chat: /agent-start 500"
Write-Host "For neutral free-camera viewing, enter in game chat: /spectate"
& (Join-Path $PSScriptRoot "run-server.ps1")
