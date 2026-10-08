param(
    [ValidatePattern('^[a-z][a-z0-9-]{1,78}$')]
    [string]$Pool = 'default',
    [ValidateSet('community', 'full')]
    [string]$Edition = 'community'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    # Run in a separate process/terminal from the Vaadin UI. Both share the configured database.
    $applicationJar = Join-Path $projectRoot "m3-app\target\m3-$Edition.jar"
    if (-not (Test-Path -LiteralPath $applicationJar)) { throw "Build the $Edition edition first; missing $applicationJar" }
    & java -jar $applicationJar '--spring.profiles.active=worker' "--m3.worker.pool=$Pool"
    if ($LASTEXITCODE -ne 0) { throw "Worker exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
