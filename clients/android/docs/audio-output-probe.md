# Optional current-player output diagnostic

Build with `-PfilewayAudioCaptureProbe=true` on the existing native build host.
It adds a debug-only Activity, MediaProjection foreground service and test source
set. Ordinary debug and release do not contain those classes or RECORD_AUDIO /
FOREGROUND_SERVICE_MEDIA_PROJECTION permissions. Do not publish the diagnostic APK.

The focused `NativeAudioOutputTest#realPlaybackMixSurvivesTracksSubtitlesAndRates`
requires `-e nfbOwnedAudioCapture true` and an explicitly supported owned device
(Xiaomi14/houji or an owned SDK emulator). It uses no ClientModel, credential store,
personal media, database or embedded-node enrollment. It restores system volume and
releases the projection, recorder, player and authenticated owned HTTP lease.

Android's actual projection consent is required. The capture configuration matches
only this app UID's MEDIA/UNKNOWN playback; it never configures a microphone,
creates a virtual display or writes raw PCM files. See the official
[AudioPlaybackCapture requirements](https://developer.android.com/media/platform/av-capture).

A 1000Hz calibration followed by silence verifies the observation path before
player claims. The owned MKV contains TrueHD220/AAC440/FLAC660/Opus880 Hz tracks,
text/ASS/PGS subtitles. Recovery measures the mixed output tone, not the selected
track label. Rate and subtitle changes must retain that output; media generation
and clock must not reset. Seek is a prerequisite between operations, not part of
the measured track/subtitle/rate command. Reader gaps invalidate attribution.

Statistics are bounded 20ms windows and a 200ms frequency/energy window. The
optional silent calibration AudioTrack holds the capture mix clock open; it adds
no audible signal and is separately checked to be silent. `nfbCaptureClock=none`
disables that hold after calibration. Never weaken validity limits to force a pass.

An output-mix pass is narrower than physical speaker/Bluetooth sound, A/V sync,
actual playback tempo, HDR and long-film acceptance. Keep those gates separate.
Preserve the first failure, output timeline and player trace before teardown.
After diagnostics, restore an ordinary same-key APK without clearing user data.

On devices that leave instrumentation Activity launches in the background, the
host may foreground `.AudioCaptureProbeActivity` after the
`AUDIO_PROBE_STAGE=activity-launch-requested` checkpoint. Use MAIN/LAUNCHER and
NEW_TASK/SINGLE_TOP flags to match the ActivityScenario launch intent. Do not
launch MainActivity over the probe. The diagnostic Activity is exported only in
this opt-in variant and requires the platform `android.permission.DUMP` permission
(verified on the owned ADB shell; callers without it are rejected). Its owned-device guard also runs
at creation. The service remains non-exported, and actual projection consent and
runtime permission are still required; foregrounding does not start capture.
Normal debug and release contain neither this entry point nor capture permissions.
