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

## Base-path comparison harness

`EngineComparisonTest` uses the debug-only, non-exported `EngineProbeActivity`.
It does not construct the product view model or initialize an unselected engine.
Media3 1.11.1 is an **androidTest-only** Apache-2.0 dependency; neither Media3 nor
this activity is added to the release runtime. The product still uses libVLC.
References: [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
and [pinned source/license](https://github.com/androidx/media/tree/1.11.1).

Run only on the identified fixture device, through the existing test APK runner:

```sh
adb -s "$FIXTURE_SERIAL" shell am instrument -w -r \
  -e class io.github.kkwans.nasfilebrowser.EngineComparisonTest \
  -e nfbProbeEngines vlc,media3 -e nfbProbeRounds 2 \
  io.github.kkwans.nasfilebrowser.test/androidx.test.runner.AndroidJUnitRunner
```

Both adapters receive the **same** authenticated Go lease and generated H.264/AAC
sample. Empty cache identity disables persistent transport caching, without
clearing anyone's cache. Rounds reverse candidate order; each attempt constructs
a new engine. The probe hosts equally sized 16:9 native surfaces and requires
actual colored pixels via PixelCopy in addition to an advancing clock. Every
candidate attempt emits a `filewayEngineProbe` JSON instrumentation status;
failures are retained and other candidates still run. No URLs or credentials are
included. One candidate can be selected to isolate a failure, but that result is
not a complete comparison.

`firstPixelMs` includes engine construction/open and excludes prior login/lease
creation. `seekClockAndPixelsMs` proves a matching clock and visible video only:
it does **not** correlate rendered frame PTS, so it is not the final seek-output
performance gate. Reported speed and audio-clock callbacks do not prove real
tempo, audible output or synchronization. This harness does not clear font caches
or app data; record actual process/cache/device conditions externally, and do not
call a retained-data run cold. Debug timing is diagnostic, not a release p95 gate.

Current adapters are libVLC and **platform-decoder Media3 baseline only**.
FFmpeg audio, libass, PGS/ASS/TrueHD/HDR, switching continuity and mpv comparison
remain required before adoption. `PASS_BASELINE_ONLY` explicitly preserves that
boundary; no settings or product engine selection are changed by this probe.
