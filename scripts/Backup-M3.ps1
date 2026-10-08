param(
    [Parameter(Mandatory)][string]$DatabaseContainer,
    [Parameter(Mandatory)][string[]]$ApplicationContainers,
    [Parameter(Mandatory)][string]$Database,
    [string]$DatabaseUser='postgres',
    [Parameter(Mandatory)][string]$ArchiveLocation,
    [Parameter(Mandatory)][string]$Destination
)
$ErrorActionPreference='Stop'
function Invoke-Docker([string[]]$CommandArguments) { $result=& docker @CommandArguments; if($LASTEXITCODE -ne 0){throw 'Docker operation failed'}; return $result }
Get-Command docker,mc -ErrorAction Stop | Out-Null
if($ApplicationContainers.Count -eq 0 -or $ApplicationContainers -contains $DatabaseContainer){throw 'Specify all M3 app/worker containers separately from PostgreSQL'}
foreach($name in @($ApplicationContainers)+@($DatabaseContainer)){if($name -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]*$'){throw 'Invalid container name'}}
$backupRoot=[IO.Path]::GetFullPath($Destination)
if(Test-Path -LiteralPath $backupRoot){throw 'Backup destination must be new; existing backups are never overwritten'}
New-Item -ItemType Directory -Path $backupRoot | Out-Null
$objectRoot=Join-Path $backupRoot 'objects'
New-Item -ItemType Directory -Path $objectRoot | Out-Null
$databaseFile=Join-Path $backupRoot 'database.dump'
$containerFile='/tmp/m3-backup-'+[guid]::NewGuid().ToString()+'.dump'
$restartNames=@()
try {
    foreach($name in $ApplicationContainers){
        $running=Invoke-Docker @('inspect','--format','{{.State.Running}}',$name)
        if($running -eq 'true'){$restartNames+=@($name);Invoke-Docker @('stop','--time','120',$name) | Out-Null}
    }
    Invoke-Docker @('exec',$DatabaseContainer,'pg_dump','-U',$DatabaseUser,'-d',$Database,'-Fc','-f',$containerFile) | Out-Null
    Invoke-Docker @('cp',($DatabaseContainer+':'+$containerFile),$databaseFile) | Out-Null
    & mc mirror $ArchiveLocation $objectRoot
    if($LASTEXITCODE -ne 0){throw 'Object-store backup failed; this snapshot is incomplete'}
    $installation=(Invoke-Docker @('exec',$DatabaseContainer,'psql','-U',$DatabaseUser,'-d',$Database,'-At','-c','SELECT installation_id FROM m3_installation WHERE singleton')).Trim()
    $catalog=Invoke-Docker @('exec',$DatabaseContainer,'psql','-U',$DatabaseUser,'-d',$Database,'-At','-c',"SELECT json_build_object('key',archive_key,'sha256',archive_sha256)::text FROM message WHERE archive_key IS NOT NULL ORDER BY message_id")
    foreach($entry in $catalog){
        $item=$entry | ConvertFrom-Json
        $candidate=[IO.Path]::GetFullPath((Join-Path $objectRoot $item.key))
        if(-not $candidate.StartsWith($objectRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Archive key escapes the backup directory'}
        if(-not(Test-Path -LiteralPath $candidate) -or (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.sha256){throw 'Archive catalog checksum verification failed'}
    }
    $files=Get-ChildItem -LiteralPath $objectRoot -File -Recurse | ForEach-Object {@{path=[IO.Path]::GetRelativePath($objectRoot,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}}
    $manifest=@{formatVersion=1;createdAt=[DateTimeOffset]::UtcNow.ToString('o');installationId=$installation;databaseSha256=(Get-FileHash -LiteralPath $databaseFile -Algorithm SHA256).Hash.ToLowerInvariant();objects=@($files);applicationContainers=$ApplicationContainers;complete=$true}
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backupRoot 'manifest.json') -Encoding utf8
    Write-Output "Verified PostgreSQL/object-store snapshot: $backupRoot"
} finally {
    try{Invoke-Docker @('exec',$DatabaseContainer,'rm','-f',$containerFile) | Out-Null}catch{Write-Warning 'The temporary dump remains inside the PostgreSQL container'}
    foreach($name in $restartNames){try{Invoke-Docker @('start',$name) | Out-Null}catch{Write-Warning "Restart $name manually"}}
}
