$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHomeCandidate = Get-ChildItem -LiteralPath "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
    Where-Object Name -Like "jdk-17*" |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $javaHomeCandidate) {
    throw "JDK 17 is required. Install Temurin/Adoptium JDK 17 first."
}

$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA "mindustry-llm-agent\gradle-cache"
$env:JAVA_HOME = $javaHomeCandidate
$clientMods = Join-Path $env:APPDATA "Mindustry\mods"
$spectatorJar = Join-Path $repoRoot "build\libs\mindustry-rts-spectator.jar"

Push-Location $repoRoot
try {
    & ".\gradlew.bat" spectatorJar --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Spectator mod build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

New-Item -ItemType Directory -Force -Path $clientMods | Out-Null
Copy-Item -LiteralPath $spectatorJar -Destination (Join-Path $clientMods "mindustry-rts-spectator.jar") -Force
Write-Host "RTS spectator cleanup mod installed."
