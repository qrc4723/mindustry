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
