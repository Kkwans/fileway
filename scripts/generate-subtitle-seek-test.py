#!/usr/bin/env python3
"""Extract locked VLC seek callbacks into a controlled native regression."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def generate(source, output):
    output = output.resolve()
    if output == ROOT or ROOT in output.parents or output.exists():
        raise ValueError("Output must be a new file outside the checkout")
    text = source.read_text()
    callbacks = []
    for start, end in [
        ("SegmentSeeker::tracks_seekpoint_t\nSegmentSeeker::find_greatest_seekpoints_in_range", "\nSegmentSeeker::Seekpoint\n"),
        ("SegmentSeeker::Seekpoint\nSegmentSeeker::get_first_seekpoint_around", "\nSegmentSeeker::seekpoint_pair_t\n"),
        ("SegmentSeeker::tracks_seekpoint_t\nSegmentSeeker::get_seekpoints(", "\nvoid\nSegmentSeeker::index_range"),
    ]:
        begin = text.index(start)
        callbacks.append(text[begin:text.index(end, begin)])
    template = (ROOT / "native/tests/subtitle-seek.cpp.in").read_text()
    assert template.count("/* NFB_ACTUAL_CALLBACKS */") == 1
    output.write_text(template.replace("/* NFB_ACTUAL_CALLBACKS */", "\n".join(callbacks)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    generate(args.source, args.output)
