# Android download locations

The default destination uses MediaStore Downloads with `Download/fileway` as its
relative path. A custom destination uses a system-selected SAF tree with persisted
read/write permission. Changing the default affects new downloads only; each
existing record retains its original tree and file URI.

Reauthorization accepts only the original provider authority and opaque tree ID.
It preserves the default location, existing file, record and downloaded prefix.
It does not restart a paused download. Missing grants are reported before resume;
the user can choose **重新授权目录** in that record's menu and then resume explicitly.

Folder viewing and folder selection use separate activity-result launchers:

- Folder viewing resolves a system `ACTION_OPEN_DOCUMENT_TREE` handler and stays
  within that package. Generic share/upload handlers for directory MIME types are
  not offered as file managers. Package visibility is limited to that intent.
- Viewing a MediaStore destination does not attempt to delegate an unowned SAF
  directory grant. Viewing an authorized SAF destination delegates read access.
- If direct viewing is unavailable or rejected, the system directory browser is
  opened at the requested directory. Its result never changes the download target.
- Selecting or reauthorizing a location uses the directory document URI as the
  initial hint; the returned tree URI is used to persist the user's grant.

Android 11+ disallows selecting the `Download` root itself; a child such as
`Download/fileway` can be selected. See the official
[SAF directory and permission contract](https://developer.android.com/training/data-storage/shared/documents-files).
Provider-specific write, seek and directory-view capabilities still require
device verification; a persisted grant alone does not prove that a file exists.

## Owned device acceptance

`DownloadDirectoryTest` checks scope rejection and absent grants using isolated
preferences and a nonexistent provider, without changing user permissions.
`DownloadPlaybackTest` normally exercises the owned MediaStore fixture.

For a real SAF run, first select `Download/fileway` through the system picker,
then run that same test with `-e nfbOwnedSafDownload true`. The opt-in mode requires
the external-storage provider's exact `primary:Download/fileway` tree and valid
read/write grants before creating its fixture profile or file. It exercises
prefix playback, authenticated missing ranges, resume, offline seek and notification
navigation, and removes only its UUID-named fixture file/record/profile afterward.
Restore the prior default location after this acceptance run; do not revoke grants
that other retained files or downloads still need.
