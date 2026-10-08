$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$applicationJar = Join-Path $projectRoot 'target\m3-community.jar'
if (-not (Test-Path -LiteralPath $applicationJar)) { throw "Build Community first with .\mvnw.cmd package; missing $applicationJar" }
Push-Location -LiteralPath $projectRoot
try {
    & java -jar $applicationJar '--vaadin.launch-browser=false'
    if ($LASTEXITCODE -ne 0) { throw "Application exited with code $LASTEXITCODE" }
} finally { Pop-Location }
