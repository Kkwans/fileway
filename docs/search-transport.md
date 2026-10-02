# Native search transport

This implementation supplies streaming metadata through Go/JNI, a typed Android session Flow and a source-bound ViewModel search controller. The rendered search page is still pending.

## Existing NAS contract

`GET /api/search{escaped directory}?query=...&scope=current|recursive` returns `application/x-ndjson`. Blank heartbeat lines are not results. Result items contain directory/name/relative path/size/modified/risk metadata. A terminal summary identifies completed, limited, timed-out, canceled or failed traversal. Recursive NAS searches default to 1000 results and 30 seconds; current-directory searches have no corresponding server result limit.

The NAS appends its last discovered item before attempting delivery. A timeout can therefore report one more item than the client received. Native `count` tracks actual parsed/queued items; the separate summary retains the server count. Completed/limited counts must match; EOF without a summary, malformed/oversized records and unsafe relative paths are failures. Server error text is not exported as a diagnostic or native error message.

## Additive JNI control operations

Protocol 1 retains its existing commands and adds:

- `search_start`: immutable `session`, service `path` or opaque `wirePath`, `query` and `scope`; returns a random control handle immediately.
- `search_poll`: `session` and `search`; returns up to 32 items, actual count, state, optional summary/HTTP failure and `done`. Completion is authoritative only when `done` is true, after the queue is drained. That final poll releases the handle.
- `search_cancel`: `session` and `search`; releases the handle, discards its queue and cancels upstream HTTP. Canceling a retired handle is harmless; another account cannot read/cancel it.

Workers live under the source session, not the short JNI call context. Replacing a query must cancel its previous handle. Closing a profile/account session cancels every owned search. At most four unconsumed handles exist per session, with 64 queued items each, 64KiB per NDJSON record, and a 45-second client deadline. Full result bodies are not read into memory before delivery. Search reuses the source session's HTTP/embedded client, X-Auth, renewal and redirect policy; it introduces no tasks or backend API.

If an Android caller is canceled while the JNI start response is arriving, `NativeTransport` explicitly releases that returned handle. `NasSession.search` checks identity before start and before/after each poll, emits typed deltas/termination and cancels its handle in a bounded non-cancelable finally block. Server limits/timeouts/permissions/failures receive user-facing messages without raw server details. JNI carries these bounded metadata batches; media bytes still use the existing localhost HTTP lease.

## ViewModel behavior

`SearchController` binds the immutable account session, directory and opaque base wire path when the search opens. Each submit, input/scope edit, cancellation or selection invalidates the prior operation generation. Editing clears results so they never appear under a different keyword. Explicit cancellation and backgrounding retain partial results and query/scope for retry; foreground return does not silently restart traversal. Returning to files, changing directory/tab or closing/switching a server clears the search context. Video playback can return to the retained search results.

Selecting a result first uses the existing read-only `/api/resources{wirePath}?metadata=1` contract. Both display path and original encoded path bytes must match the candidate, and a changed directory/file kind is rejected. Percent-escape case differences are equivalent; literal plus signs and opaque non-UTF8 base bytes are preserved. The authoritative metadata supplies the name/type/size/reference, after which the existing player verifies media identity and applies isolated resume state. Canceled or replaced metadata responses cannot navigate or start playback. Ambiguous names never send a guessed metadata request.

The controller accumulates actual result rows keyed by relative path. The page must describe displayed results, show genuine termination/cancellation and preserve retry input; it must not present server traversal as a known percentage or total library count.

## Resource identity boundary

This NAS search response does not include `wirePath`. Its relative path is metadata, not an Android filesystem path or a player URL. The UI adapter must combine it with the immutable search base, encode ordinary path segments once and use authoritative resource/media identity before playback. A lossy non-UTF8 name containing replacement characters cannot be guessed into an opaque wire path; that case needs an explicit unsupported-result state or a separately authorized backend contract correction. Directory browsing already retains supplied wire paths. No backend change is made here.

## Verification and remaining work

Go tests exercise incremental delivery before completion, source/base/query encoding, heartbeats, summaries/partial timeout, missing/truncated/oversized data, bounded backpressure, actual upstream cancellation, session ownership/closure, handle limits, renewal and permissions. Bridge tests prove the stream survives return from its short native call and a pre-canceled queued start does not contact the source.

`NativeSearchTest` uses the actual byte-array JNI → Go → owned HTTP fixture, requiring an early Unicode result and observing upstream socket closure on cancel. It uses no real NAS identity, activity/player, node enrollment or server data. These checks do not prove the unfinished search UI, real NAS results, external embedded networking, physical devices or the whole goal.

`SearchSessionTest` covers account binding, cancellation/changed-principal rejection, path encoding with an opaque base, partial termination and invalid summaries. `SearchResult.resource` preserves the base wire bytes, encodes ordinary result segments once, and returns no guessed reference for ambiguous replacement characters. It retains legal space-only filename segments. A second real-JNI fixture test collects the typed session Flow before releasing the server's final summary.

`ClientSearchTest` runs the actual ClientModel/Room/Keystore/JNI/Go path against an owned HTTP fixture: incremental results, upstream cancel, keyword replacement with late results, failed-stream retry, account switch, background cancel, rejected ambiguous/moved/type-changed metadata, canceled late selection and Unicode/plus/percent directory navigation. `ClientSessionTest` retains the separate multi-server/account/directory restoration regression. These are emulator integration checks, not actual NAS search results, search-screen interaction or hardware/network acceptance.
