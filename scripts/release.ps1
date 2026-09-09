# 在 Windows 构建并打包原生程序，不上传产物。
param(
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?$')]
    [string]$Version = '0.0.1'
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$module = $repository
Push-Location $repository
try {
    & ./gradlew.bat 'nativeCompile' "-PgatewayVersion=$Version" '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw 'Native 构建失败' }
    $release = Join-Path $module "build/release/mcp-gateway-$Version-windows-x64"
    New-Item -ItemType Directory -Path $release -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $module 'build/native/nativeCompile/mcp-gateway.exe') -Destination $release
    Get-ChildItem -LiteralPath (Join-Path $module 'build/native/nativeCompile') -Filter '*.dll' | Copy-Item -Destination $release
    Copy-Item -LiteralPath (Join-Path $module 'README.md'), (Join-Path $module 'THIRD-PARTY-NOTICES.md') -Destination $release
    $archive = "$release.zip"
    Compress-Archive -LiteralPath $release -DestinationPath $archive -Force
    $checksum = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$archive.sha256" -Value "$checksum  $([IO.Path]::GetFileName($archive))" -Encoding ascii
    Write-Output $archive
}
finally { Pop-Location }
