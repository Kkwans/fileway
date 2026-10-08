# Opt-in real NAS subtitle acceptance

Build debug and test APKs with `-PfilewayRealMediaProbe=true`. This adds
`RealNasMediaTest` and a diagnostic-only, DUMP-protected foreground entry for
`EngineProbeActivity`. It cannot be combined with the audio capture probe.
Ordinary debug and release builds do not enable this entry.

Run only the named test on the audited Xiaomi device with instrumentation
argument `nfbRealMedia=true`. Do not run the entire instrumentation suite on a
personal phone. Cover-install with the existing signing identity; never clear
data or uninstall. Restore the ordinary package after diagnostics.

The test announces a random `REAL_MEDIA_SOCKET` name. An authorized ADB host
forwards a temporary loopback TCP port to that local abstract socket and sends
one UTF-8 JSON line containing `baseUrl`, `token`, `path`, `wirePath`, `size`,
`subtitleCount`, and `positionMs`. The socket accepts only root/shell peers.
Keep the token in memory, suppress request bodies and credentials in logs, and
remove the owned forwarding port afterward. The host must bound the test and
configuration exchange with a deadline. The test never creates a saved profile,
opens Room, or writes playback history.

Choose an authorized original MKV containing multiple full-canvas PGS tracks and
a known timestamp where every tested track has a visible caption. The test reads
metadata and streams the original resource through the production authenticated
lease. It waits for ordinary read-ahead, checks each selected track's rendered
pixels, disables/restores subtitles, adjusts/restores subtitle time, then seeks
forward and back while preserving pause. Private screenshots are written under
`Download/nfb-client-acceptance`; do not commit copyrighted frames or private
source paths.

PGS cache regression: retaining full decoded bitmaps for all tracks can evict the
current captions before presentation, even during normal short read-ahead. Keep
Media3's losslessly encoded bitmap cues in the bounded cache and decode only the
selected display set. Empty display sets must still clear prior captions.

This verifies subtitle output on the selected source/device. It does not certify
HDR activation, audible switching continuity, long-play performance, or all
subtitle formats. Small owned fixtures remain useful but do not replace this
real-source check.
