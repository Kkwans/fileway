# Native playback diagnostics and comparison

`NativePlayer.diagnosticSnapshot()` exposes a bounded in-memory debug trace of
commands and native events. It contains only action enums, numeric values,
monotonic elapsed times and an open-attempt counter. It accepts no paths, URLs,
track names or credentials. Release builds retain no trace observations.

The open-attempt counter describes when a callback was observed, not the native
origin of that callback. `PLAYING`, `VIDEO_OUTPUT`, `FIRST_CLOCK`, a selected track
or a reported rate do not prove visible frames, audible output or synchronization.
Keep the actual pixel/audio gates alongside the trace. Startup tests append the
trace on failure without changing their time limits or success assertions.

For an owned-fixture debug diagnostic build only:

```sh
./gradlew -PfilewayNativeVerbose=true :app:assembleDebug :app:assembleDebugAndroidTest
```

This explicitly enables libVLC verbose logging and adds a diagnostic version-name
suffix. Native library logs can include media information; use only owned fixtures
and keep them outside Git. Normal debug builds leave verbose logging off, and
release builds always leave it off. Do not publish diagnostic APKs as previews.

Record source/patch hashes, APK/test APK/native-library hashes, the actual
`LibVLC.version()` and `LibVLC.changeset()`, device/API/ABI/page size, network route,
cache/process conditions, operation sequence and test counts for each candidate.
Verify staged source hashes before building. When extracting deterministic source
archives over a reused staging directory, refresh source file metadata after
content verification so Gradle does not reuse an old same-size/mtime content hash.

Compare engines with the same authenticated loopback lease and owned samples.
Preserve the 20-second repeated-start and 25-second native-resume prerequisites,
actual decoded pixels, subtitles after seek/disable, pause intent, and cancellation
assertions. Cold and warm observations remain separate; a warmed repeat does not
erase an initial failure. Hardware/HDR, real audio and long-film acceptance require
the separately identified physical-device gates before engine adoption.
