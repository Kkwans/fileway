param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ToolsRoot = 'D:\Kkwans\Desktop\Project\MyProject\Fileway-windows-tools',
    [string] $ArtifactsRoot = 'D:\Kkwans\Desktop\Project\MyProject\Fileway-windows-artifacts\local',
    [switch] $Publish
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $windowsRoot 'src\Fileway.App\Fileway.App.csproj'
$buildLog = Join-Path $env:FilewayArtifactsRoot "winui-$Configuration.binlog"
& dotnet build $project -c $Configuration -p:Platform=x64 --nologo "-bl:$buildLog"
if ($LASTEXITCODE -ne 0) { throw 'Windows build failed. See the external binlog.' }
if ($Publish) {
    & dotnet publish $project -c $Configuration -p:Platform=x64 --no-build --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }
}
