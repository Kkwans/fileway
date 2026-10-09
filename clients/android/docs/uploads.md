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
completion. Completed, confirmed-canceled and restarted records can be removed
without abandoning partial state; removing a record leaves local and server
files intact.

## Pause, cancel and restart

Pause retains the original session and confirmed position. Continue uses the
original transfer ID and obtains its current HEAD offset before appending.
Reauthorizing a lost source grant requires the same document identity, name,
size and modification identity; selecting an unrelated same-name file is rejected.

Restart is an explicit separate action for paused, failed, interrupted, expired
or canceled records. The user reselects the original document, which may now have
changed metadata, and confirms its current name/size and the original target.
Before confirmation, remote fragments and durable progress are untouched. A source
change during confirmation is rejected before cleanup.

Confirmed restart stops the old writer and verifies cleanup of its original
modern TUS session. Missing cleanup capability, permission or acknowledgement
does not create a replacement task. If the server already completed the original
upload, its complete file is retained and no duplicate task is created.

After confirmed cleanup, one Room transaction ends the old record as `restarted`
and creates a new UUID/job/batch from zero. The original account, server revision,
wire target and explicit overwrite identity remain pinned. Generation and terminal
state guards reject late source-picker/write/cleanup callbacks and duplicate
confirmations. The old record keeps its historical confirmed bytes but cannot
resume. A crash after cleanup leaves a canceled record eligible for explicit
restart; a crash after the transaction leaves one new task for normal recovery.
This is a state-value extension, not a Room schema/version change.

## Acceptance boundaries

`UploadStorageTest` opens an isolated historical Room7 database and checks
download URI/bytes/directory preservation plus late-upload callback rejection.
`UploadUiTest` uses a UUID-owned MediaStore file and loopback original-byte server
through the native Go broker. It accepts real page confirmation/cancel,
pause/resume after a late accepted chunk, exact final/original bytes and notification
navigation. It intentionally separates system document-picker, real NAS/Tailscale,
folder/provider, background/process and broader conflict acceptance.

`UploadRestartTest` verifies changed-source confirmation/cancel, different actual
native TUS session and final bytes, cleanup failure without a new task, retry from
confirmed cancellation, preservation of an already-completed file, and unchanged
local contents. `UploadStorageTest` also fences stale restart generations and
duplicate/late results. These owned fixtures do not replace real server/provider
and process-recovery acceptance.

The shared server now persists bound TUS sessions with a default seven-day
retention; its Linux/Windows persistence and completed-HEAD contracts are distinct
from client UI acceptance. Legacy servers expire temporary state after short
inactivity and cannot provide reliable long-pause/restart resume. Cleanup refuses
their unverifiable path-only DELETE behavior. Client changes alone do not fix
legacy servers. A preview's packaging or scoped fixture result does not complete
real server, device or upgrade acceptance.
