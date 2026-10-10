param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'Build-GoHost.ps1') -Configuration $Configuration -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$harnessProject = Join-Path $windowsRoot 'tests\Fileway.HostTestHarness\Fileway.HostTestHarness.csproj'
& dotnet build $harnessProject -c $Configuration -p:Platform=x64 --nologo
if ($LASTEXITCODE -ne 0) { throw 'Host test harness build failed.' }
$harnessTargetDirectory = (& dotnet msbuild $harnessProject -getProperty:TargetDir -p:Configuration=$Configuration -p:Platform=x64 -nologo).Trim()
if ($LASTEXITCODE -ne 0 -or -not [System.IO.Path]::IsPathFullyQualified($harnessTargetDirectory)) { throw 'Cannot resolve the external harness output.' }
$env:FILEWAY_TEST_HOST_EXE = Join-Path $env:FilewayArtifactsRoot "host\$Configuration\fileway-host.exe"
$env:FILEWAY_TEST_HOST_MANIFEST = Join-Path $env:FilewayArtifactsRoot "host\$Configuration\host-build.json"
$env:FILEWAY_TEST_HARNESS_EXE = Join-Path $harnessTargetDirectory 'Fileway.HostTestHarness.exe'
$env:FILEWAY_TEST_OUTPUT_ROOT = Join-Path $env:FilewayArtifactsRoot 'tests\infrastructure'
New-Item -ItemType Directory -Path $env:FILEWAY_TEST_OUTPUT_ROOT -Force | Out-Null
$testProject = Join-Path $windowsRoot 'tests\Fileway.Infrastructure.Tests\Fileway.Infrastructure.Tests.csproj'
Push-Location -LiteralPath $windowsRoot
try {
    & dotnet test --project $testProject -c $Configuration -p:Platform=x64 --minimum-expected-tests 1 --results-directory $env:FILEWAY_TEST_OUTPUT_ROOT --report-trx --report-trx-filename infrastructure.trx --no-ansi --no-progress
    if ($LASTEXITCODE -ne 0) { throw 'Windows infrastructure tests failed.' }
}
finally { Pop-Location }
