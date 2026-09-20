param(
    [string]$MindustryVersion = "v159.7"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$clientRoot = Join-Path $repoRoot ".local\client"
$clientJar = Join-Path $clientRoot "Mindustry.jar"

New-Item -ItemType Directory -Force -Path $clientRoot | Out-Null
if (-not (Test-Path -LiteralPath $clientJar)) {
    $downloadUrl = "https://github.com/Anuken/Mindustry/releases/download/$MindustryVersion/Mindustry.jar"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $clientJar
}

Write-Host "Mindustry client ready at $clientJar"
