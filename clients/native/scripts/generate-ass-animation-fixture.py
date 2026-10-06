#!/usr/bin/env python3
"""Generate owned ASS colour/motion/fade media using the existing fixture tools."""
import argparse
import importlib.util
import json
from pathlib import Path


def generate(output):
    spec = importlib.util.spec_from_file_location("owned_fixture", Path(__file__).with_name("generate-subtitle-fixture.py"))
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    header = fixture.ASS.split("Dialogue:", 1)[0]
    fixture.ASS = header + r"""Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(80,40)\1c&H0000FF&\t(1000,2000,\1c&HFF0000&)\p1}m 0 0 l 80 0 80 40 0 40{\p0}
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(400,60)\1c&HFFFFFF&\fad(1000,2000)\p1}m 0 0 l 80 0 80 40 0 40{\p0}
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\move(20,180,200,180,0,4000)\1c&H00FFFF&}I
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(10,10)\1c&HFFFFFF&\p1}m 0 0 l 4 0 4 4 0 4{\p0}
Dialogue: 0,0:00:00.50,0:00:11.00,Bars,,0,0,0,,{\an7\pos(620,330)\1c&HFFFFFF&\p1}m 0 0 l 4 0 4 4 0 4{\p0}
"""
    fixture.generate(output)
    original = output / "subtitle-fixture.mkv"
    original.rename(output / "ass-animation-fixture.mkv")
    manifest = json.loads((output / "manifest.json").read_text())
    manifest["sha256"]["ass-animation-fixture.mkv"] = manifest["sha256"].pop("subtitle-fixture.mkv")
    manifest["assAnimation"] = {
        "fixedBoundsAnchors": [[10, 10, 4, 4], [620, 330, 4, 4]],
        "colour": {"position": [80, 40], "size": [80, 40], "redBeforeSeconds": 1.5, "blueAfterSeconds": 2.5},
        "fade": {"position": [400, 60], "size": [80, 40], "fadeInMs": 1000, "fadeOutMs": 2000, "endSeconds": 11},
        "movement": {"font": "NfbFixtureBars", "glyph": "I", "from": [20, 180], "to": [200, 180], "durationMs": 4000},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(output / "ass-animation-fixture.mkv")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    generate(parser.parse_args().output.resolve())
