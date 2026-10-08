# Owned media fixture

`fixture.mkv` is a 12-second 320×180 H.264/AAC test pattern and generated sine tone. It contains no third-party film, credentials or personal data. It tests actual libVLC decode, duration, seek and resume through the Go HTTP lease. It is not evidence for HDR, HEVC, PGS, ASS or the required full-length sample.

Generated locally with FFmpeg (the generated asset is project-owned and distributed under the repository license):

```sh
ffmpeg -f lavfi -i 'testsrc2=size=320x180:rate=15:duration=12' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=12' \
  -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 15 \
  -c:a aac -b:a 48k -shortest fixture.mkv
```

## Subtitle and audio fixture

`fixture-front.mp4` and `fixture-tail.mp4` are stream-copy remuxes of the same
owned H.264/AAC fixture. They cover both MP4 index positions during partial
offline playback; no additional film content is introduced:

```sh
ffmpeg -i fixture.mkv -map 0:v:0 -map 0:a:0 -c copy -movflags +faststart fixture-front.mp4
ffmpeg -i fixture.mkv -map 0:v:0 -map 0:a:0 -c copy fixture-tail.mp4
```

`subtitle-fixture.mkv` is a generated 12-second 640×360 black H.264 video with:

- Four generated tones: default stereo TrueHD 220 Hz, AAC 440 Hz, FLAC 660 Hz and Opus 880 Hz.
- SubRip Latin/Chinese text spanning 0.5–11 seconds, deliberately long enough to test seeking into an active cue.
- ASS positioning/drawing and an embedded, project-owned `NfbFixtureBars` font. Its `I` glyph is two separate red bars; the ASS drawing is a blue rectangle. A system-font fallback cannot reproduce that glyph shape.
- Six PGS streams containing red, green, blue, yellow, cyan and magenta rectangles. Display sets repeat every second so each stream can be selected independently.

The glyph outlines, text, shapes and tones are project-owned and distributed under GPL-3.0-or-later. No system font or film is copied. `subtitle-fixture.json` records actual stream metadata and SHA256 values. The MKV SHA256 is `d582828ab83e23f5d6f784babe30eb6ffb274c8e108258e0fb54207b4c06da64`.

To regenerate in a new output directory, install fontTools **4.59.2** in an isolated tooling environment and run:

```sh
python3 scripts/generate-subtitle-fixture.py /path/to/new/external-fixture-directory
python3 scripts/verify-subtitle-fixture.py
```

Generation used FFmpeg 7.1.1-Jellyfin. The pure-Python fontTools wheel SHA256 is `8bd0f759020e87bb5d323e6283914d9bf4ae35a7307dafb2cbd1e379e720ad37`; the generator checks its version and refuses an existing output directory. Rebuilding with a different FFmpeg configuration may change encoded bytes, so verify the generated stream manifest and record a new checksum instead of assuming byte identity.

The PGS segment layout and BT.601 palette order were checked against [FFmpeg n7.1.1's decoder](https://github.com/FFmpeg/FFmpeg/blob/n7.1.1/libavcodec/pgssubdec.c). FFmpeg decoding verified all four tone frequencies, all six PGS colors, and the ASS bars/drawing. Those checks validate the fixture; Android rendering, audible track content, hardware/HDR, performance and the full-length NAS sample require separate evidence.

## ASS animation fixture

`ass-animation-fixture.mkv` reuses the owned video/audio/PGS/font generator and changes only the ASS content. It contains a rectangle changing red→blue, a white rectangle fading in/out, and the attached two-bar `I` moving horizontally. Two small white anchor shapes keep the complete subtitle bounding rectangle fixed throughout the animation, so bounds/order-based skipping cannot hide behind moving bounds. All dialogue events retain the same 0.5–11-second span.

SHA256: `c3d217efa640d64599e94e9cb81ee474387b916b51b74c2fda5a650d09eb3327`. Sources/track metadata/checksums and effect timing are recorded in `ass-animation-fixture.json`. No system font, third-party artwork, movie or user data is included.

```sh
python3 scripts/generate-ass-animation-fixture.py /path/to/new/external-fixture-directory
python3 scripts/verify-ass-animation-fixture.py
```

The independent FFmpeg/libass verifier reads the actual embedded ASS/font and checks red/blue pixels, half/full/half fade intensities, clearing, moving yellow-glyph centroid and unchanged overall bounds at five real media times. This establishes fixture correctness; Android animation, timing, performance and hardware acceptance are separate gates.
