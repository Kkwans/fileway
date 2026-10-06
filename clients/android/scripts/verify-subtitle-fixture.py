#!/usr/bin/env python3
"""Validate the generated asset with an independent decoder, not Android claims."""
import cmath
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess


def peak_frequency(samples, sample_rate=48000):
    size = 8192
    assert len(samples) >= size
    spectrum = [complex(samples[i] * (.5 - .5 * math.cos(2 * math.pi * i / (size - 1)))) for i in range(size)]
    target = 0
    for index in range(1, size):
        bit = size >> 1
        while target & bit:
            target ^= bit
            bit >>= 1
        target ^= bit
        if index < target:
            spectrum[index], spectrum[target] = spectrum[target], spectrum[index]
    length = 2
    while length <= size:
        root = cmath.exp(-2j * math.pi / length)
        for start in range(0, size, length):
            weight = 1
            for index in range(length // 2):
                first = spectrum[start + index]
                second = spectrum[start + index + length // 2] * weight
                spectrum[start + index] = first + second
                spectrum[start + index + length // 2] = first - second
                weight *= root
        length *= 2
    peak = max(range(1, size // 2), key=lambda index: abs(spectrum[index]))
    return peak * sample_rate / size


def verify():
    asset = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/media/subtitle-fixture.mkv"
    manifest = json.loads(asset.with_suffix(".json").read_text())
    assert hashlib.sha256(asset.read_bytes()).hexdigest() == manifest["sha256"][asset.name]
    for index, expected in enumerate((220, 440, 660, 880)):
        raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(asset), "-map", f"0:a:{index}",
            "-ss", "0.25", "-t", "0.25", "-ac", "1", "-ar", "48000", "-f", "s16le", "-"])
        samples = struct.unpack("<" + "h" * (len(raw) // 2), raw)
        frequency = peak_frequency(samples)
        rms = math.sqrt(sum(value * value for value in samples) / len(samples))
        assert abs(frequency - expected) < 6 and rms > 100, (index, frequency, rms)
        print(f"Decoded audio {index}: {frequency:.2f} Hz, RMS {rms:.1f}", flush=True)
    for index, expected in enumerate(manifest["pgsRgb"]):
        raw = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(asset), "-filter_complex",
            f"[0:v:0][0:s:{index + 2}]overlay[v]", "-map", "[v]", "-ss", "3", "-frames:v", "1", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"])
        assert len(raw) == 640 * 360 * 3
        actual = list(raw[(170 * 640 + 210) * 3:(170 * 640 + 210) * 3 + 3])
        assert all(abs(first - second) < 12 for first, second in zip(actual, expected)), (index, actual)
        print(f"Decoded PGS {index + 1}: RGB {actual}", flush=True)


if __name__ == "__main__":
    verify()
