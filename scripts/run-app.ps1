param([ValidateSet('community', 'full')][string]$Edition = 'community')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$applicationJar = Join-Path $projectRoot "m3-app\target\m3-$Edition.jar"
if (-not (Test-Path -LiteralPath $applicationJar)) { throw "Build the $Edition edition first; missing $applicationJar" }
Push-Location -LiteralPath $projectRoot
try {
    & java -jar $applicationJar '--vaadin.launch-browser=false'
    if ($LASTEXITCODE -ne 0) { throw "Application exited with code $LASTEXITCODE" }
} finally { Pop-Location }
