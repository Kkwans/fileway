# Local profile and account state

## Renewed tokens and keep-login credentials

The Android session writes renewed JWTs back to the existing Keystore vault after
API/search requests and native playback/download progress sampling, and when the
app leaves the foreground. Unchanged tokens do not rewrite storage. Updates check
the live source revision, origin, network mode, account ID and credential reference;
older issued tokens cannot replace newer ones. A late renewal cannot recreate a
credential removed by sign-out. Room serializes credential renewal and removal.

The connection form's “保持登录” option applies on successful password login. It
stores the password in a separate AES-GCM/Keystore entry bound to that account's
existing opaque credential reference. Choosing not to retain it on a subsequent
login removes that password. Sign-out and profile removal remove both entries.
Passwords never enter Room, SavedState, logs or backup. The remembered checkbox
choice contains only a boolean; it is not credential storage.

Startup/account restore and resumed downloads first validate the saved token with
a read. Only an explicit 401 permits one saved-password login, followed by a
same-user-ID check. Network errors and 403 do not trigger password retries; rejected
passwords are forgotten without erasing a newer saved password. Mutating requests
are never replayed by this recovery helper. The server's JWT expiry is unchanged;
no backend deployment or new authentication endpoint is involved.

Legacy profiles have no recoverable saved password. If their token has already
expired, one successful login with “保持登录” provisions the encrypted entry.
Existing JWT storage and Room schema remain compatible. Rollback uses the original
certificate and a higher versionCode repair package, never uninstalling or clearing
personal data. The extra encrypted password entry is separate from the legacy JWT.


## Active-session restoration (schema v5, 2026-10-04)

`active_session` holds a foreign key to the selected account and a non-secret owner nonce. Startup resolves the exact profile/source revision, reads the Keystore-protected JWT, verifies it through an authenticated NAS resources request and then restores the existing directory/layout. A loading view avoids flashing the onboarding form. Rejected authentication keeps the saved server/account and offers existing account restoration; it never claims connected before the read succeeds. Root back exits the Activity rather than disconnecting the NAS session.

The 4→5 migration atomically backs up the five old metadata tables before creating the new table/index. For older packages that did not record the active reference, it seeds only the unique latest authenticated account timestamp among current NAS profile revisions. Ties require user selection. This upgrade inference happens once; later missing/cleared active references never fall back to other accounts. Credentials remain in the vault and must pass server validation. Explicit sign-out clears the reference; profile deletion cascades it; revision changes invalidate it. Explicit embedded disconnection releases only the captured session owner's reference, so late cleanup cannot remove a newer login for the same account.

TX5Pro passed `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest` (unit task NO-SOURCE). The focused installed API35 suite passed 13 tests in 19.712s: schema 1–5 migrations/backups and blocked backups, unique/tied recovery, reopen/sign-out/source change/late cleanup, actual JNI account restoration without another password login, directory/layout, rejected 401 and retry, and root-back Activity destruction/relaunch. Physical-device process death, signed package upgrade and authenticated tailnet restoration remain unverified. Rollback source is `8d4a32f`; retain the previous APK and pre-upgrade metadata backup. A v5 database cannot be handed directly to a v3/v4 APK.

Schema v1 uses Room 2.8.5, KSP 2.3.12 and Android's platform SQLite. Profile metadata, account references and directory state are separate tables with indexed foreign keys; parent updates use Upsert so they do not delete child records. No destructive migration fallback is enabled. Exported schemas are versioned; migration backup/upgrade verification is a release gate before v2 is delivered.

An account key binds profile UUID, source revision and the authenticated NAS `user.id` (verified against the current backend token contract). Names do not identify accounts. Changing address or backend increments the source revision; old local data remains isolated and cannot silently resume against the new source. Renaming a profile or changing its network mode preserves the source revision. A session still must be replaced when its connection changes.

JWTs are encrypted by the existing Android Keystore vault. SQLite stores opaque credential references. A late login cannot persist credentials after its source profile changes. Credential restoration rechecks the saved profile and account rather than accepting a caller-provided reference. Sign-out removes its token and preserves isolated directory metadata; removing a profile removes credentials and cascades its metadata while leaving other sources intact.

`wirePath` is stored byte-for-byte as a string. It is not decoded, rewritten by Android filesystem rules or encoded a second time. Windows profiles can be stored, but authenticated browsing remains unsupported until its adapter is implemented.

This data layer precedes the multi-profile UI/session integration; it does not by itself complete the server-switching or playback-history gate. The NAS backend and database are unchanged.

Schema v2 adds source-identity playback snapshots. The 1→2 migration writes an atomic private JSON backup of v1 metadata before changing tables; credential values and node state remain in their separate protected stores. Migration fails if the backup cannot be written. Both exported schema versions are retained. Downgrade remains the documented export/reinstall/relogin flow; this backup is not a promise of automatic Android package downgrade.

Snapshots bind account key, opaque resource key and file identity. Locally they use milliseconds; the existing NAS playback API uses seconds and Unix milliseconds for updatedAt. A pending newer local value may win over remote progress only for a matching identity. A synced local row does not resurrect progress cleared remotely.

Synchronization marks only the exact version it sent; an older HTTP completion cannot replace a later saved position. File replacement aborts writes when observed before PUT. The existing backend PUT has no expected-identity argument, so the client also validates its result and guards a replacement identity from inheriting the stale write. It does not delete other clients' remote progress. Non-UTF8 wire paths that cannot be represented by the existing JSON mutation remain local-only with an explicit unsupported-sync status; their raw media bytes and opaque path remain playable.

Dependency sources: [Room release](https://developer.android.com/jetpack/androidx/releases/room), [KSP release](https://github.com/google/ksp/releases/tag/2.3.12).

Schema v3 adds a typed, device-wide appearance preference (`SYSTEM`, `LIGHT`, `DARK`) in `app_preferences`. No saved row means follow system. This preference deliberately survives server/account changes; credentials, directory/history and media caches retain their existing isolation. AppearanceStore is a Room-backed flow and write adapter; it does not introduce a second preference file or backend API.

The 2→3 migration atomically backs up all four existing metadata tables, including playback snapshots, before creating the preference table. Both migrations share the existing metadata-only backup writer. The supported upgrade chain is 1→2→3; exported schemas 1/2/3 are preserved. Backup failure stops the migration with old version/state intact. Schema v3 cannot be opened by an older schema-v2 binary without the documented export/reinstall or a deliberate state restoration; source rollback alone is insufficient after migration. Automatic downgrade/restoration is not implemented.

Schema v4 stores the typed file layout (`COVER`, `DETAIL`, `LIST`, `COMPACT`) in each account's existing directory state. The account key already includes profile UUID, source revision and service user ID, so the same username on another server or a changed source does not inherit the old layout. Fresh and upgraded accounts default to cover grid. Directory navigation updates path/wirePath in a Room transaction while preserving layout; layout changes preserve the saved directory.

The 3→4 migration atomically backs up all five prior metadata tables, including theme and playback state, before adding the column. Existing opaque paths and state remain intact; a backup failure aborts the migration. The complete upgrade chain is 1→2→3→4. An older schema-v3 APK cannot open migrated v4 state: preserve the previous APK plus its pre-upgrade state backup; reinstall/state restoration and re-login are required for a deliberate downgrade. Automatic downgrade and restoration remain unsupported.

The client serializes rapid layout writes, applies a layout only after its write succeeds, and loads it before presenting a newly connected account's files. Every result checks the bound session and request identity before changing visible state. A failed write retains the last confirmed layout and offers retry by reselecting, rather than presenting an unsaved preference as durable. No NAS endpoint, server database or credential format changes.

The v4 slice passed TX5Pro `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest` (unit task has no local sources), plus 14 installed API35 tests in 48.035s: FileLayoutStorageTest, ProfileStorageTest, AppearanceStorageTest, HistoryMigrationTest, ClientSearchTest's layout/account/new-ViewModel method, BrowserScreenTest and ThemeScreenTest. The tests include actual Room reopen/upgrade/backup failure, three rapid layout selections, account restoration, all four installed layouts and activity theme recreation. Four captured layout screens were inspected at default font scaling; the fixture intentionally returns no thumbnails. This is owned-fixture/emulator evidence, not NAS-account thumbnail, physical-device or final signed-release acceptance. No 130%/200% font checks were run. Public 0.2.1-preview remains on schema v3; this source slice has not been published as an APK. Before any APK containing v4 is delivered, retain the prior package and pre-upgrade backup as the rollback point.
