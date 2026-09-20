$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$clientRoot = Join-Path $repoRoot ".local\client"
$clientJar = Join-Path $clientRoot "Mindustry.jar"
$javaExecutable = Get-ChildItem -LiteralPath "C:\Program Files\Eclipse Adoptium" -Filter "javaw.exe" -Recurse -ErrorAction SilentlyContinue |
    Where-Object FullName -Like "*jdk-17*\bin\javaw.exe" |
    Select-Object -First 1 -ExpandProperty FullName

if (-not (Test-Path -LiteralPath $clientJar)) {
    & (Join-Path $PSScriptRoot "setup-client.ps1")
}
& (Join-Path $PSScriptRoot "install-client-mod.ps1")
if (-not $javaExecutable) {
    throw "JDK 17 javaw.exe was not found."
}

Start-Process -FilePath $javaExecutable -ArgumentList @("-jar", $clientJar) -WorkingDirectory $clientRoot
Write-Host "Mindustry opened. Join 127.0.0.1:6567 to watch the agent."
