#!/usr/bin/env python3
"""Inject actual pinned/patched VLC callbacks into a native state regression.

Run on a prepared external workspace. Produces a new external C source file;
does not modify the workspace, compile an SDK or touch an Android device.
"""
import argparse
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def generate(workspace, output, baseline=False, baseline_source=None):
    output = output.resolve()
    if output == ROOT or ROOT in output.parents:
        raise ValueError("Generated source must be outside the checkout")
    if output.exists():
        raise ValueError("Refusing an existing generated source")
    if baseline_source is not None and not baseline:
        raise ValueError("Explicit baseline source requires --baseline")
    source_path = baseline_source or workspace / "sdk/vlc/modules/video_output/android/display.c"
    source = source_path.read_text()
    boundaries = [
        ("static " + ("void" if baseline else "bool") + " SubpicturePrepare(vout_display_t *vd, subpicture_t *subpicture)\n{", "\nstatic picture_pool_t *Pool"),
        ("static void Prepare(vout_display_t *vd, picture_t *picture,\n                    subpicture_t *subpicture)\n{", "\nstatic void Display"),
        ("static void Display(vout_display_t *vd, picture_t *picture,\n                    subpicture_t *subpicture)\n{", "\nstatic void CopySourceAspect"),
    ]
    callbacks = []
    for start, end in boundaries:
        begin = source.index(start)
        callbacks.append(source[begin:source.index(end, begin)].rstrip())
    body = "\n\n".join(callbacks)
    template = (ROOT / "native/tests/subtitle-cache.c.in").read_text()
    assert template.count("/* NFB_ACTUAL_CALLBACKS */") == 1
    output.write_text(template.replace("/* NFB_ACTUAL_CALLBACKS */", body))
    print("Actual callback SHA256: " + hashlib.sha256(body.encode()).hexdigest())
    print(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workspace", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--baseline", action="store_true", help="Generate the original callbacks to prove regression detection")
    parser.add_argument("--baseline-source", type=Path, help="Upstream display.c retained by source preparation")
    args = parser.parse_args()
    generate(args.workspace, args.output, args.baseline, args.baseline_source)
