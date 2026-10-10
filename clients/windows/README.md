# Fileway（栖卷）Windows

Windows 11 x64 native client, using .NET 10, WinUI 3 and the existing Go client core. The application is unpackaged and self-contained; it launches directly as an EXE. There is no WebView in the main interface.

## Toolchain and isolation

The initial verified development toolchain is .NET SDK 10.0.401, Windows App SDK 2.5.1, Windows SDK BuildTools 10.0.26100.9169, winapp CLI 0.7.1 and Go 1.26.6. WinUI source was generated using Microsoft's `Microsoft.WindowsAppSDK.WinUI.CSharp.Templates` 0.0.7-alpha; the template generator is separate from the stable application framework. The actual template options are obtained from `dotnet new winui --help`.

`eng/Use-WindowsEnvironment.ps1` selects project-specific toolchains, caches, build output, data and temporary directories in the current PowerShell process. It does not update global environment variables, install Visual Studio, enable Developer Mode, register certificates or modify Android SDKs.

```powershell
.\eng\Install-WindowsTools.ps1 -ToolsRoot <isolated-tools>
. .\eng\Use-WindowsEnvironment.ps1 -ToolsRoot <isolated-tools> -ArtifactsRoot <external-artifacts>
.\eng\Build-Windows.ps1 -ToolsRoot <isolated-tools> -ArtifactsRoot <external-artifacts> -Configuration Debug
```

The installer script downloads fixed official tool archives, checks published hashes and extracts them into the selected directory. Incomplete downloads are retained for resume; no system installer is run. Defaults use sibling directories relative to the checkout, so the scripts also work with explicit temporary CI roots.

Deep checkout/tool paths can exceed native XAML compiler path limits. Pass an explicit short, externally owned `-NuGetPackagesRoot <path>` to environment/build/test scripts when needed. It is propagated through nested scripts and never changes global NuGet or Windows settings.

Build-Windows builds the Go Host first, embeds the actual Git commit and records dirty state and executable SHA-256 in `host-build.json`. It then copies the matching Host and manifest beside the application. A direct app build without these prerequisites fails explicitly.

The EXE is produced under `<external-artifacts>\bin\Fileway.App\x64\Debug\net10.0-windows10.0.26100.0\win-x64`. Run it with that directory as its working directory. Publishing also stays under the external artifact root. A successful build is not proof of native launch, media support or a clean installation.

## Component boundaries

- `Fileway.App`: native windows and view models, input, theme and accessibility.
- `Fileway.Core`: immutable account/resource identities, typed service contracts and use cases; no WinUI dependency.
- `Fileway.Infrastructure`: process IPC, persistence, credentials, local files and diagnostics.
- `Fileway.Playback`: native media bindings and playback lifecycle.
- `host`: separate Go executable referencing `clients/shared/core`, following [private IPC v1](docs/ipc-v1.md).

The current native scaffold is a technical probe. Visual direction, representative native slice and final product acceptance are separate gates. Build and runtime evidence belongs outside Git.

Windows code uses GPL-3.0, consistent with the shared core. Existing backend and Android licenses and identifiers remain unchanged. Third-party binaries require their own pinned provenance, notices and corresponding source/build materials before distribution.
