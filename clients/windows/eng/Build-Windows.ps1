param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot,
    [switch] $Publish
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'Build-GoHost.ps1') -Configuration $Configuration -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$project = Join-Path $windowsRoot 'src\Fileway.App\Fileway.App.csproj'
$buildLog = Join-Path $env:FilewayArtifactsRoot "winui-$Configuration.binlog"
& dotnet build $project -c $Configuration -p:Platform=x64 --nologo "-bl:$buildLog"
if ($LASTEXITCODE -ne 0) { throw 'Windows build failed. See the external binlog.' }
if ($Publish) {
    & dotnet publish $project -c $Configuration -p:Platform=x64 --no-build --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }
}
