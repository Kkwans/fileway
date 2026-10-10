# Windows local preview packaging

`../eng/Package-Preview.ps1` consumes an existing self-contained publish directory;
it does not build, publish, sign, install or launch the application.

```powershell
./clients/windows/eng/Package-Preview.ps1 `
  -PublisherInput <artifacts>/publish/Release/win-x64 `
  -ArtifactsRoot <artifacts> -ToolsRoot <isolated-tools> `
  -Version 0.1.0-preview.1
```

Outputs are an unsigned ZIP, a current-user NSIS installer, and a SHA-256 receipt
under the external `dist` directory. Existing delivery files are never replaced.
The script requires the app, Go Host, media runtime lock, libmpv, and nonempty media
license evidence. It rejects reparse points and common data/signing files.

NSIS 3.12 is fixed to its [official portable release](https://sourceforge.net/projects/nsis/files/NSIS%203/3.12/).
When absent, the archive is fetched into the isolated tools directory and checked
against the recorded SHA-256 from the reviewed official download. This is a local
content pin, not a claim of an upstream signature or published upstream SHA-256.

Installation uses `%LOCALAPPDATA%\Programs\Fileway Preview\versions\<version>`.
It refuses to overwrite an existing version. Install/uninstall checks only process
names through Windows Toolhelp and asks the user to close Fileway normally. It
never kills a process. A version-specific Start menu shortcut and HKCU uninstall
entry are created; no file association, startup entry or service is configured.

Uninstall deletes the exact packaged file list and removes only empty directories.
Extra user files and downloads are retained. There is no recursive directory
deletion. The installer does not automatically run the application.

These artifacts are **unsigned local development previews**. The presence of
license files does not establish redistribution completeness. G5 license review,
installation acceptance and public-release approval remain separate gates.
