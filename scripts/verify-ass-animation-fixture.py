#!/usr/bin/env python3
"""Independently render actual embedded ASS; not an Android acceptance claim."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def verify(asset):
    manifest = json.loads(asset.with_suffix(".json").read_text())
    assert hashlib.sha256(asset.read_bytes()).hexdigest() == manifest["sha256"][asset.name]
    # Reject filter delimiters instead of embedding an unescaped caller path.
    if any(character in str(asset) for character in "':,;\\[]"):
        raise ValueError("Fixture path contains unsupported FFmpeg filter delimiters")
    def frame(seconds):
        raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(asset),
            "-vf", f"subtitles={asset}:si=1", "-ss", str(seconds), "-frames:v", "1",
            "-pix_fmt", "rgb24", "-f", "rawvideo", "-"])
        assert len(raw) == 640 * 360 * 3
        return raw
    def pixel(raw, x, y):
        position = (y * 640 + x) * 3
        return tuple(raw[position:position + 3])
    def near(actual, expected, tolerance=15):
        assert all(abs(a - b) < tolerance for a, b in zip(actual, expected)), (actual, expected)
    def yellow_x(raw):
        xs = [x for y in range(170, 270) for x in range(640)
              if (lambda rgb: rgb[0] > 180 and rgb[1] > 180 and rgb[2] < 80)(pixel(raw, x, y))]
        assert len(xs) > 100, "The attached moving glyph did not render"
        return sum(xs) / len(xs)
    def bounds(raw):
        points = [position // 3 for position in range(0, len(raw), 3)
                  if max(raw[position:position + 3]) > 10]
        return (min(p % 640 for p in points), min(p // 640 for p in points),
                max(p % 640 for p in points), max(p // 640 for p in points))
    early, middle, moved, faded, cleared = [frame(seconds) for seconds in (1, 3, 5, 10, 11.5)]
    near(pixel(early, 90, 50), (255, 0, 0))
    near(pixel(middle, 90, 50), (0, 0, 255))
    near(pixel(early, 410, 70), (128, 128, 128))
    near(pixel(middle, 410, 70), (255, 255, 255))
    near(pixel(faded, 410, 70), (128, 128, 128))
    near(pixel(cleared, 90, 50), (0, 0, 0))
    near(pixel(cleared, 410, 70), (0, 0, 0))
    before, after = yellow_x(early), yellow_x(moved)
    assert after - before > 120, (before, after)
    assert len({bounds(raw) for raw in (early, middle, moved, faded)}) == 1, "The animation must retain fixed overall bounds"
    print(f"PASS independently decoded ASS colour, fade and attached-glyph motion: x={before:.1f}→{after:.1f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("asset", nargs="?", type=Path,
        default=Path(__file__).resolve().parents[1] / "android/app/src/androidTest/assets/media/ass-animation-fixture.mkv")
    verify(parser.parse_args().asset.resolve())
