# Optional libmpv comparison

`-PfilewayMpvProbeAar=/absolute/path/libmpv-release.aar` enables only the
instrumentation source set and AAR. The SHA256 pin is
`df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2`.
It is the published [libmpv-android v1.0.0 wrapper](https://github.com/jarnedemeulemeester/libmpv-android/tree/fcf6745703dc1265bca88f12fee8fc355ddf251e).
No MPV library or selector is added to the product APK.

`MpvComparisonTest` reuses the baseline harness. With `nfbProbeEngines=all`, each
round exercises VLC, platform-decoder Media3 and mpv; alternate rounds reverse
order. All candidates use the exact same Go lease, input, bounds, PixelCopy,
clock, seek and paused-rate assertions. `mpv` selects only the optional adapter.
This remains `PASS_BASELINE_ONLY`, not complex media or physical-output acceptance.

`MpvSubtitleTest` checks composed pixels for the owned attached font/drawing,
six PGS tracks, text switching, mid-cue seek, disable, ASS colour/motion/fade and
fresh opens at six seconds into text and ASS cues. Engine OSD is disabled so that
time bars or titles cannot satisfy text-pixel assertions. TrueHD decoder selection
is required, but physical sound and switch continuity remain separate gates.

The fixture adapter uses mpv's Android OpenGL output and MediaCodec preference
with available software fallback. It records actual `hwdec-current`, video/audio
codec, output and FFmpeg version properties instead of labelling the request as
hardware success. No `mediacodec_embed` output bypasses mpv subtitle rendering.
The Java wrapper does not expose the end-file error payload; timeouts still fail
the probe and this limitation must be resolved before any production adoption.
Surface loss closes the probe; rotating/rebinding is not claimed implemented.

The wrapper is MIT, with its notice included in the optional test assets. The
native recipe enables FFmpeg GPL/version3 and bundles mpv, libass, libplacebo and
other dependencies. Complete corresponding sources/notices are a separate
adoption/delivery gate. The published AAR's Java21 bytecode, native ABI alignment,
linker groups and C++ dependency compatibility require real build/runtime checks.
Duplicate exported names alone do not prove cross-binding; use actual dependency
groups and load-order evidence. The optional mpv and enhanced Media3 APKs remain
separate to avoid silently choosing among different bundled native payloads.
Full enhanced-Media3 versus mpv media/HDR/audio comparison is still required.

The published recipe disables Vulkan in libplacebo. The pinned upstream Android
[OpenGL context](https://github.com/mpv-player/mpv/blob/v0.41.0/video/out/opengl/context_android.c)
creates the EGL window surface without colour-space attributes and does not
implement VO control requests. Therefore this tested GPU path must not be labelled
HDR passthrough merely because MediaCodec was selected. Evaluate an explicit HDR
output path and physical display evidence; the upstream Android Vulkan context
is a separate candidate build, not a capability established by this AAR.
