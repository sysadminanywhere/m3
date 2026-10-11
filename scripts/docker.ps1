param(
    [Parameter(Position=0)][ValidateSet('build', 'compose')][string]$Action = 'build',
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$DockerArguments
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
[xml]$project = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot 'pom.xml')
$isScale = $project.project.artifactId -eq 'm3-scale'
$community = if ($isScale) { Join-Path $ProjectRoot '../m3' } else { $ProjectRoot }
[xml]$config = Get-Content -Raw -LiteralPath (Join-Path $community 'build-config/pom.xml')
$version = [string]$config.project.properties.revision
if ($version -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') { throw "Invalid application version: $version" }
$previousVersion = $env:M3_VERSION
$previousImage = $env:M3_IMAGE
try {
    $env:M3_VERSION = $version
    if ($Action -eq 'build' -and -not $env:M3_IMAGE) {
        $env:M3_IMAGE = if ($isScale) { "m3:$version" } else { "m3:community-$version" }
    }
    if ($Action -eq 'compose') {
        & docker compose --project-directory $ProjectRoot @DockerArguments
    } else {
        $buildArguments = @('build', '--build-arg', "APP_VERSION=$version", '-t', $env:M3_IMAGE)
        if ($isScale) { $buildArguments += @('--build-context', "community=$community") }
        & docker @buildArguments @DockerArguments $ProjectRoot
    }
    if ($LASTEXITCODE -ne 0) { throw "Docker failed with exit code $LASTEXITCODE" }
} finally {
    $env:M3_VERSION = $previousVersion
    $env:M3_IMAGE = $previousImage
}
