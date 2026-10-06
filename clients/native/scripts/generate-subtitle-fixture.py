#!/usr/bin/env python3
"""Generate owned subtitle/audio/attachment data; no film or system font is copied.

Requires FFmpeg 7.1.1 and fontTools 4.59.2. Output is a new external directory.
PGS segment layout follows the FFmpeg n7.1.1 decoder, not an invented API.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


COLORS = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0), (0, 255, 255), (255, 0, 255)]


def segment(pts, kind, payload=b""):
    return b"PG" + struct.pack(">IIBH", pts, pts, kind, len(payload)) + payload


def pgs(rgb):
    # CCIR BT.601 limited-range palette order is Y, Cr, Cb, Alpha.
    r, g, b = rgb
    y = round(16 + .257 * r + .504 * g + .098 * b)
    cr = round(128 + .439 * r - .368 * g - .071 * b)
    cb = round(128 - .148 * r - .291 * g + .439 * b)
    width, height = 80, 30
    rle = (bytes([1]) * width + b"\x00\x00") * height
    object_data = struct.pack(">HBB", 1, 0, 0xC0) + (4 + len(rle)).to_bytes(3, "big") + struct.pack(">HH", width, height) + rle
    palette = bytes([0, 0, 0, 16, 128, 128, 0, 1, y, cr, cb, 255])
    window = bytes([1, 0]) + struct.pack(">HHHH", 0, 0, 640, 360)
    output = bytearray()
    for second in range(1, 11):
        pts = second * 90_000
        composition = struct.pack(">HHBHBBBB", 640, 360, 0x10, second, 0x80, 0, 0, 1)
        composition += struct.pack(">HBBHH", 1, 0, 0, 200, 160)
        output += segment(pts, 0x16, composition) + segment(pts, 0x17, window)
        output += segment(pts, 0x14, palette) + segment(pts, 0x15, object_data) + segment(pts, 0x80)
    clear = struct.pack(">HHBHBBBB", 640, 360, 0x10, 11, 0, 0, 0, 0)
    output += segment(11 * 90_000, 0x16, clear) + segment(11 * 90_000, 0x80)
    return bytes(output)


def font(path):
    import fontTools
    from fontTools.fontBuilder import FontBuilder
    from fontTools.pens.ttGlyphPen import TTGlyphPen
    if fontTools.__version__ != "4.59.2":
        raise ValueError("Use the locked fontTools 4.59.2")
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder([".notdef", "space", "I"])
    builder.setupCharacterMap({32: "space", 73: "I"})
    glyphs = {}
    for name in [".notdef", "space", "I"]:
        pen = TTGlyphPen(None)
        if name == "I":
            # Two disconnected bars distinguish this attachment from a system I.
            for x in (100, 550):
                pen.moveTo((x, 0)); pen.lineTo((x + 150, 0))
                pen.lineTo((x + 150, 700)); pen.lineTo((x, 700)); pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (900, 0) for name in glyphs})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": "NfbFixtureBars", "styleName": "Regular",
        "uniqueFontIdentifier": "NFB-owned-fixture-bars-1", "fullName": "NfbFixtureBars Regular",
        "psName": "NfbFixtureBars-Regular", "version": "Version 1.0",
        "copyright": "Project-owned generated test glyphs; GPL-3.0-or-later"})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost(); builder.setupMaxp()
    builder.font.recalcTimestamp = False
    builder.font["head"].created = builder.font["head"].modified = 2082844800
    builder.save(path)


ASS = """[Script Info]
ScriptType: v4.00+
PlayResX: 640
PlayResY: 360
WrapStyle: 2
ScaledBorderAndShadow: yes
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Bars,NfbFixtureBars,60,&H000000FF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\\an7\\pos(80,40)}I
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\\an7\\pos(300,60)\\1c&HFF0000&\\p1}m 0 0 l 80 0 80 40 0 40{\\p0}
"""


def generate(output):
    output.mkdir(parents=True, exist_ok=False)
    (output / "text.srt").write_text("1\n00:00:00,500 --> 00:00:11,000\nNATIVE TEXT\n字幕测试\n", encoding="utf-8")
    (output / "style.ass").write_text(ASS, encoding="utf-8")
    font(output / "NfbFixtureBars.ttf")
    for index, rgb in enumerate(COLORS, 1):
        (output / f"pgs{index}.sup").write_bytes(pgs(rgb))
    command = ["ffmpeg", "-hide_banner", "-nostdin", "-f", "lavfi", "-i", "color=c=black:size=640x360:rate=15:duration=12"]
    for tone in (220, 440, 660, 880):
        command += ["-f", "lavfi", "-i", f"sine=frequency={tone}:sample_rate=48000:duration=12"]
    for name in ["text.srt", "style.ass"] + [f"pgs{i}.sup" for i in range(1, 7)]:
        command += ["-i", str(output / name)]
    for index in range(13):
        command += ["-map", str(index)]
    command += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "30", "-pix_fmt", "yuv420p", "-g", "15",
        "-c:a:0", "truehd", "-strict:a:0", "-2", "-ac:a:0", "2", "-c:a:1", "aac", "-b:a:1", "48k",
        "-c:a:2", "flac", "-c:a:3", "libopus", "-b:a:3", "48k", "-c:s", "copy",
        "-disposition:a:0", "default", "-metadata:s:a:0", "title=NFB TrueHD 220Hz",
        "-metadata:s:a:1", "title=NFB AAC 440Hz", "-metadata:s:a:2", "title=NFB FLAC 660Hz", "-metadata:s:a:3", "title=NFB Opus 880Hz",
        "-metadata:s:s:0", "title=NFB Text", "-metadata:s:s:1", "title=NFB ASS Attachment", "-disposition:s:0", "0"]
    for index in range(1, 4):
        command += [f"-disposition:a:{index}", "0"]
    for index in range(1, 8):
        command += [f"-disposition:s:{index}", "0"]
    for index in range(6):
        command += [f"-metadata:s:s:{index + 2}", f"title=NFB PGS{index + 1}"]
    command += ["-attach", str(output / "NfbFixtureBars.ttf"), "-metadata:s:t:0", "mimetype=application/x-truetype-font",
        "-metadata:s:t:0", "filename=NfbFixtureBars.ttf", "-t", "12", str(output / "subtitle-fixture.mkv")]
    subprocess.run(command, check=True)
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(output / "subtitle-fixture.mkv")]))
    kinds = [s.get("codec_name") for s in probe["streams"]]
    if kinds.count("hdmv_pgs_subtitle") != 6 or not {"truehd", "aac", "flac", "opus", "ass", "subrip"}.issubset(kinds):
        raise ValueError("Generated media lacks the expected tracks")
    manifest = {"ownedFixture": True, "notFullSampleAcceptance": True, "fontTools": "4.59.2",
        "ffmpeg": subprocess.check_output(["ffmpeg", "-version"], text=True).splitlines()[0],
        "tracks": [{"codec": s.get("codec_name"), "title": s.get("tags", {}).get("title"), "default": s.get("disposition", {}).get("default")} for s in probe["streams"]],
        "pgsRgb": COLORS, "sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir() if p.is_file()}}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(output / "subtitle-fixture.mkv")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    generate(parser.parse_args().output.resolve())
