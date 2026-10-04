param(
    [ValidatePattern('^[a-z][a-z0-9-]{1,78}$')]
    [string]$Pool = 'default'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    # Run in a separate process/terminal from the Vaadin UI. Both share the configured database.
    & (Join-Path $projectRoot 'mvnw.cmd') spring-boot:run '-Dspring-boot.run.profiles=worker' "-Dspring-boot.run.arguments=--m3.worker.pool=$Pool"
    if ($LASTEXITCODE -ne 0) { throw "Worker exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
