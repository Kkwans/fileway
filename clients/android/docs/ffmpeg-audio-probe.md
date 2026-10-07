# Media3 FFmpeg audio comparison build

This reproducible library now supplies the product's Media3 audio extension.
The historical `probe` filenames are retained so the verified input lock and build
recipe remain reproducible. Engine integration and device acceptance remain open.
`native/media3-ffmpeg.lock.json` pins Media3 1.11.1 audio Java/JNI sources by commit
and individual SHA256, and the FFmpeg 9.0.2 release archive by SHA256.
Upstream sources: [Media3 decoder module](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg)
and [FFmpeg releases](https://ffmpeg.org/download.html).

The original Media3 README recommends an older FFmpeg branch. This comparison
intentionally evaluates a current release against the pinned JNI interface;
compilation and runtime compatibility must be established, not assumed. No
experimental FFmpeg video renderer is imported: Android video/HDR still belongs
to the separately evaluated platform-video path.

## Build

Use the reusable/manual `Media3 FFmpeg native library` workflow, or an installed Linux
x86_64 NDK 28.2.13676358 with JDK 17 and the project's Android SDK inputs. Refresh
the shared migration gate before invoking builds on a host that uses the shared
tools/caches. Approve output placement before creating a local work directory.
All directories below are explicit, task-owned absolute paths:

```sh
python3 scripts/prepare-ffmpeg-probe.py \
  --work-root "$PROBE_WORK" --ndk "$ANDROID_HOME/ndk/28.2.13676358" --jobs 4
./gradlew :ffmpeg-probe:assembleRelease :ffmpeg-probe:lintRelease \
  -PfilewayFfmpegWorkDir="$PROBE_WORK"
python3 scripts/package-ffmpeg-probe.py \
  --work-root "$PROBE_WORK" --ndk "$ANDROID_HOME/ndk/28.2.13676358" \
  --aar ffmpeg-probe/build/outputs/aar/ffmpeg-probe-release.aar \
  --output-dir "$PROBE_OUTPUT"
```

`--fetch-only` validates sources without compiling or installing tools; an
already downloaded release can be supplied with `--ffmpeg-archive`. Different
existing inputs are preserved and rejected. Use a new owned work root for changed
pins. Source archives reject links and paths outside their expected root.

The build enables only the listed audio decoders and resampling. FFmpeg network,
demuxing, video processing, GPL, version3-only and nonfree components are disabled.
The configured decoder and license flags are checked before compilation.
ARM64 assembly is retained; the x86_64 portability choice follows the upstream
recipe and must not be used to draw physical-device performance conclusions.

The packager requires arm64-v8a and x86_64 ELF64 shared objects, 16 KiB-compatible
LOAD segments, the JNI entry point, and no exposed FFmpeg symbols that could bind
to libVLC's copy in the same process. The AAR carries Media3, FFmpeg and NDK notices.
It is accompanied by source archives, pinned bridge sources, build recipes,
configuration headers and a hash manifest linked to the Fileway source commit.
Use that exact checkout for the surrounding Gradle project and wrapper.

## Acceptance boundary

Supply the product artifact with
`-PfilewayFfmpegManifest=/absolute/path/ffmpeg-probe.json`. Configuration
checks the input/recipe and AAR hashes against this checkout. The adjacent AAR is
required; no candidate is downloaded or selected automatically. The legacy
`filewayFfmpegProbeManifest` alias additionally enables the `src/ffmpegProbeTest`
source set and `FfmpegAudioProbeTest` using the same product dependency.

The audio test selects the owned TrueHD/AAC/FLAC/Opus tones through one Go lease,
verifies the actual FFmpeg decoder and PCM frequency/energy, and repeats TrueHD
after intervening codec, seek and paused-rate changes. Its TeeAudioProcessor is
before Sonic/output routing: it proves decoded PCM content, not actual speaker
sound, pitch preservation after time stretching, or end-to-end switching latency.

`packagingVerified` is a binary/source check. `runtimeVerified` remains false:
the library still has to load through an explicitly selected test candidate and
decode the owned TrueHD/AAC/FLAC/Opus fixtures. Actual output, track/rate/seek
continuity, libass/PGS integration, 16 KiB device execution, HDR and physical audio
remain separate gates. Neither a successful build nor decoder enumeration is
evidence that the user hears sound. Final binary/transitive license review remains
part of candidate adoption and delivery.
