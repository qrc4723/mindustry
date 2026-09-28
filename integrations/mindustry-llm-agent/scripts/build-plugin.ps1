$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHomeCandidate = Get-ChildItem -LiteralPath "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Where-Object Name -Like "jdk-17*" |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $javaHomeCandidate) {
    throw "JDK 17 is required. Install Temurin/Adoptium JDK 17 first."
}

$gradleCacheRoot = Join-Path $env:LOCALAPPDATA "mindustry-llm-agent\gradle-cache"
$env:GRADLE_USER_HOME = $gradleCacheRoot
$env:JAVA_HOME = $javaHomeCandidate

Push-Location $repoRoot
try {
    & ".\gradlew.bat" jar --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

$builtJar = Join-Path $repoRoot "build\libs\mindustry-llm-bridge.jar"
$serverRoot = Join-Path $repoRoot ".local\server"
$serverMods = Join-Path $serverRoot "config\mods"
if (Test-Path -LiteralPath $serverRoot) {
    New-Item -ItemType Directory -Path $serverMods -Force | Out-Null
    Copy-Item -LiteralPath $builtJar -Destination (Join-Path $serverMods "mindustry-llm-bridge.jar") -Force
    Write-Host "Deployed plugin to $serverMods"
}
