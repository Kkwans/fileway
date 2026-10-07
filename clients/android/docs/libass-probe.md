# libass overlay comparison

This optional test source set uses the published `ass-kt` 0.5.1 AAR, SHA256
`1051212faf98ef956e06992b002d43cfdc81b6c60dcd32662e8d3ff52d584d65`.
Source: https://github.com/peerless2012/libass-android/tree/v0.5.1 .
The AAR already includes libass, the Kotlin JNI bridge and libc++_shared for each
ABI. Do not add the separate `ass` AAR redundantly.

Enable explicitly with `-PfilewayLibassProbeAar=/absolute/path/ass-kt-0.5.1.aar`
and the verified `-PfilewayFfmpegProbeManifest=...` audio artifact. The application
does not acquire a libass dependency; only the instrumentation APK changes.

The Fileway test adapter uses public Matroska/TrackOutput APIs for fonts and ASS
packets. A serial worker owns native calls and coalesces frame requests; a separate
bitmap overlay leaves the video Surface untouched. Renderer time subtracts the
actual `onRendererOffsetChanged` value. The standard subtitle view is cleared for
selected ASS so that only one visible path is used. Native unchanged-frame results
retain the previous pixels, while track disable/replacement invalidates queued UI
updates. Storage geometry is the owned fixture's 640x360, not a production default.

`LibassRenderingTest` checks actual composed screenshot pixels: the embedded
two-bar font, vector drawing, mid-cue seek/backward clear, disable, same-bounds
colour change, motion, fade, six PGS tracks and long text after selection/seek.
Media3's publicly decoded SubRip cues are retained within the comparison session
with event/text budgets, allowing already-parsed long cues to be shown after a
mid-cue selection. This does not establish recovery of cues never read before a
fresh open directly into the middle of a long file; that remains a separate gate.
FFmpeg TrueHD decoder selection is required, but physical
sound and HDR output are not established by these checks.

The wrapper is MIT; bundled native code has separate ISC/MIT/FreeType/LGPL/Unicode/
zlib and NDK obligations. Release adoption still requires complete native notices,
corresponding source and symbol-coexistence review. Verified ELF alignment of the
published arm64/x86_64 payloads does not prove runtime rendering or HDR. The
wrapper selects Fontconfig during renderer initialization; record its actual
startup cost and test fallback fonts before selecting an engine.
