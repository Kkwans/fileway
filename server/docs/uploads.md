# Shared resumable uploads

Linux and Windows serve the same TUS routes and session rules. Native filesystem
publication is behind `files.PublishUpload`; Web builds remain identical.

`POST /api/tus/<wirePath>?override=false` establishes a durable session tied to
its transfer ID, user ID, exact ASCII wire path, resolved scope/target, total
length and native part-file identity. The response keeps the existing Location
shape (`?transfer=<id>`), adds `Tus-Resumable: 1.0.0`, and reports the offset,
length and expiration. Repeating creation for the same active session does not
truncate/reset bytes; query HEAD before sending the remaining content. Reusing
an ID for another owner/target/length is rejected.

Bytes remain in an unguessable private `.fileway-upload-<nonce>.part` beside the
final destination. Ordinary listing/search/preview/resource handlers deny that
namespace. Incomplete new files are not published, and approved replacements
retain their original contents until the new part is complete. The final native
operation does not replace a concurrently created target without approval.

`HEAD` reports the verified actual offset and length, including completed
sessions. `PATCH` validates offset/type/remaining length, records a bounded write
intent before append, flushes data and persists actual partial progress even when
the acknowledgement is lost. Native part identity and that write intent permit
crash/restart reconciliation without guessing another file's progress.
Session/path locks serialize writes to one physical destination within the
single database-owning server. This is not a new multi-replica database contract.

Pending parts expire after seven days of inactivity. `Upload-Expires` makes the
deadline visible. Expiration and explicit cancellation address only the original
private part, never a final target; a replaced or externally changed part is
preserved. Completed files cannot be removed by TUS cancellation. The transient
cache's old path-only eviction no longer deletes files. Existing settings,
accounts, files and identifiers are not rebuilt; upload sessions use an additive
Bolt bucket independent of removable transfer/task history.

The Web generates a fresh transfer nonce for an explicit new attempt and restores
the original ID when resuming its persisted same-origin/same-target URL. Android
rebuilds its token-free Go lease from durable task data and obtains HEAD offsets;
it does not persist ephemeral loopback URLs or replay an unconfirmed write blindly.

Legacy pre-upgrade uploads have no verified persistent part/session ownership;
they must not be silently adopted or deleted by this implementation. Retain those
files and transfer history during upgrade, inspect active transfers, and use a new
explicit attempt if they cannot resume. Runtime/database backups must precede
production upgrades. Tests and packaging do not replace real Linux/Windows service
and Android/Web acceptance.
