# Owned media fixture

`fixture.mkv` is a 12-second 320×180 H.264/AAC test pattern and generated sine tone. It contains no third-party film, credentials or personal data. It tests actual libVLC decode, duration, seek and resume through the Go HTTP lease. It is not evidence for HDR, HEVC, PGS, ASS or the required full-length sample.

Generated locally with FFmpeg (the generated asset is project-owned and distributed under the repository license):

```sh
ffmpeg -f lavfi -i 'testsrc2=size=320x180:rate=15:duration=12' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=12' \
  -c:v libx264 -preset veryfast -crf 30 -pix_fmt yuv420p -g 15 \
  -c:a aac -b:a 48k -shortest fixture.mkv
```
