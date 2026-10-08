$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$privateDirectory = Join-Path $workspace '.m3'
$target = Join-Path $privateDirectory 'local-security.properties'
if (Test-Path -LiteralPath $target) { Write-Output 'Local security is already initialized.'; exit 0 }
New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
function New-Secret([int]$length) { [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes($length)) }
$contents = @(
    'm3.security.admin-password=' + (New-Secret 24)
    'm3.security.operator-password=' + (New-Secret 24)
    'm3.security.viewer-password=' + (New-Secret 24)
    'm3.secret-key=' + (New-Secret 32)
)
[IO.File]::WriteAllLines($target, $contents, [Text.UTF8Encoding]::new($false))
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $acl = Get-Acl -LiteralPath $privateDirectory
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $privateDirectory -AclObject $acl
} else { & chmod 700 $privateDirectory; & chmod 600 $target }
Write-Output "Created private credentials for admin, operator and viewer in $target. Keep this file and encryption key backed up."
