param(
    [string]$MindustryVersion = "v159.7"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$serverRoot = Join-Path $repoRoot ".local\server"
$pluginDir = Join-Path $serverRoot "config\mods"
$serverJar = Join-Path $serverRoot "server-release.jar"
$pluginJar = Join-Path $repoRoot "build\libs\mindustry-llm-bridge.jar"

& (Join-Path $PSScriptRoot "build-plugin.ps1")
New-Item -ItemType Directory -Force -Path $pluginDir | Out-Null

if (-not (Test-Path -LiteralPath $serverJar)) {
    $downloadUrl = "https://github.com/Anuken/Mindustry/releases/download/$MindustryVersion/server-release.jar"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $serverJar
}

Copy-Item -LiteralPath $pluginJar -Destination (Join-Path $pluginDir "mindustry-llm-bridge.jar") -Force
Write-Host "Server ready at $serverRoot"
