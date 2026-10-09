# Android uploads

Upload records belong to the original profile revision, account and target
wire path. Browsing another account/server cannot rebind an existing transfer.
Source files are read-only SAF documents or app-owned MediaStore files; uploads
never delete/move/edit the local original. URI grants and source size/modification
identity are checked again when the worker starts or resumes. A missing reliable
modification identity prevents unsafe resumption.

The directory toolbar offers file/folder selection through the system picker.
Preparation reads source metadata and target conflicts before creating tasks.
Folder selection preserves relative paths, with bounded scanning (5,000 files,
2,000 directories, 64 levels). Conflicts default to keep-both; replacing requires
explicit selection and current modify permission. Targets that change before a
worker starts are rejected rather than silently overwritten.

Room7→8 adds an independent uploads ledger and indexes. Existing downloads,
directory state, accounts, credentials, theme/history and embedded-node identity
remain unchanged. Generation checks fence callbacks after pause/retry. Each task
has a unique UIDT job on API34+; older versions use a data-sync foreground service.
Notifications navigate the original MainActivity to the upload page.

The existing OkHttp stack sends byte bodies to a token-free Go upload lease.
It never connects directly to a NAS/Tailscale address or stores JWTs in URLs.
The Go capability validates pinned original wire bytes, method/size/offset and
Location transitions, reuses the selected HTTP client and cancels both source
and upstream flow on release. The app uses the existing resource POST for empty
files and 2MiB TUS PATCH requests for resumable content. It obtains the actual
remote HEAD offset before appending; a lost chunk response is not treated as a
license to send the same bytes again blindly.

Transfer speed/progress come from the current lease's sent bytes plus its start
offset. Durable progress uses confirmed offsets. The UI stays below100% until
the response, remote metadata/length and unchanged local-source identity confirm
completion. Only a completed record can be removed without abandoning partial
state; removing a record leaves local and server files intact.

## Acceptance boundaries

`UploadStorageTest` opens an isolated historical Room7 database and checks
download URI/bytes/directory preservation plus late-upload callback rejection.
`UploadUiTest` uses a UUID-owned MediaStore file and loopback original-byte server
through the native Go broker. It accepts real page confirmation/cancel,
pause/resume after a late accepted chunk, exact final/original bytes and notification
navigation. It intentionally separates system document-picker, real NAS/Tailscale,
folder/provider, background/process and broader conflict acceptance.

The legacy server's temporary TUS state expires after short inactivity and cannot
provide reliable long-pause/restart resume; completed HEAD semantics also differ.
Do not label those behaviors as fixed by this client. Shared-server persistence
and session-binding changes require Linux/Windows native tests and real service
acceptance. A preview's packaging or scoped fixture result does not complete them.
