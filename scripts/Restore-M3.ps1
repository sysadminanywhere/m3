param(
    [Parameter(Mandatory)][string]$Snapshot,
    [Parameter(Mandatory)][string]$DatabaseContainer,
    [Parameter(Mandatory)][string]$Database,
    [string]$DatabaseUser='postgres',
    [Parameter(Mandatory)][string]$ArchiveLocation
)
$ErrorActionPreference='Stop'
function Invoke-Docker([string[]]$CommandArguments){$result=& docker @CommandArguments;if($LASTEXITCODE -ne 0){throw 'Docker operation failed'};return $result}
Get-Command docker,mc -ErrorAction Stop | Out-Null
$restoreRoot=[IO.Path]::GetFullPath($Snapshot)
$manifest=Get-Content -Raw -LiteralPath (Join-Path $restoreRoot 'manifest.json') | ConvertFrom-Json
if($manifest.formatVersion -ne 1 -or -not $manifest.complete){throw 'A complete supported snapshot is required'}
$databaseFile=Join-Path $restoreRoot 'database.dump'
if((Get-FileHash -LiteralPath $databaseFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.databaseSha256){throw 'Database backup checksum failed'}
$objectRoot=Join-Path $restoreRoot 'objects'
foreach($item in $manifest.objects){
    $candidate=[IO.Path]::GetFullPath((Join-Path $objectRoot $item.path))
    if(-not $candidate.StartsWith($objectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Object path escapes the snapshot directory'}
    if((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw 'Object backup checksum failed'}
}
$tables=(Invoke-Docker @('exec',$DatabaseContainer,'psql','-U',$DatabaseUser,'-d',$Database,'-At','-c',"SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")).Trim()
if($tables -ne '0'){throw 'Restore requires an empty PostgreSQL database; no existing database is overwritten'}
$existing=& mc ls --json $ArchiveLocation
if($LASTEXITCODE -ne 0){throw 'Destination bucket must already exist and be accessible'}
if(@($existing).Count -gt 0){throw 'Restore requires an empty object-store destination'}
$containerFile='/tmp/m3-restore-'+[guid]::NewGuid().ToString()+'.dump'
try {
    & mc mirror $objectRoot $ArchiveLocation
    if($LASTEXITCODE -ne 0){throw 'Object-store restore failed; do not start M3'}
    $verifyParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $verifyRoot=Join-Path $verifyParent ('m3-restore-verify-'+[guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $verifyRoot | Out-Null
    try {
        & mc mirror $ArchiveLocation $verifyRoot
        if($LASTEXITCODE -ne 0){throw 'Restored object read-back failed; do not start M3'}
        foreach($item in $manifest.objects){
            $candidate=[IO.Path]::GetFullPath((Join-Path $verifyRoot $item.path))
            if(-not $candidate.StartsWith($verifyRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Object path escapes the verification directory'}
            if((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw 'Restored object checksum failed; do not start M3'}
        }
    } finally {
        $verifiedTarget=[IO.Path]::GetFullPath($verifyRoot)
        if(-not $verifiedTarget.StartsWith($verifyParent,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $verifiedTarget) -notlike 'm3-restore-verify-*'){throw 'Unsafe verification cleanup path'}
        Remove-Item -LiteralPath $verifiedTarget -Recurse -Force
    }
    Invoke-Docker @('cp',$databaseFile,($DatabaseContainer+':'+$containerFile)) | Out-Null
    Invoke-Docker @('exec',$DatabaseContainer,'pg_restore','-U',$DatabaseUser,'-d',$Database,'--no-owner','--exit-on-error',$containerFile) | Out-Null
    $installation=(Invoke-Docker @('exec',$DatabaseContainer,'psql','-U',$DatabaseUser,'-d',$Database,'-At','-c','SELECT installation_id FROM m3_installation WHERE singleton')).Trim()
    if($installation -ne $manifest.installationId){throw 'Restored installation identity does not match the snapshot'}
    Write-Output 'Restore completed. Keep the original installation inactive; configure the separately backed-up encryption key and archive credentials before starting updated M3 containers.'
} finally {
    try{Invoke-Docker @('exec',$DatabaseContainer,'rm','-f',$containerFile) | Out-Null}catch{Write-Warning 'The temporary dump remains inside the PostgreSQL container'}
}
