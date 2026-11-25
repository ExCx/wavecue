#!/usr/bin/env python3
"""Build the deterministic audio fixtures used by the instrumented tests.

The fixtures are small, regenerable and have known ground truth, which is what
lets the tests assert exact timecodes instead of "something appeared".

    python3 scripts/qa/make_fixtures.py --out build/qa-fixtures

Output:
    01 Opening.wav   32.000 s, 48 kHz, 16-bit stereo
                     silence at 0.000-5.000, 12.000-17.000 and 27.000-32.000
                     tone elsewhere (so edge silence is excluded by default)
    02 Middle.wav    10.000 s continuous tone
    03 Finale.wav     3.000 s continuous tone (short enough to auto-advance)
    04 Codec Test.mp3 5.256 s MPEG-2.5 Layer III, 8 kHz mono, CC0 silence
    readme.txt, artwork.jpg, 05 Not Supported.m4a   must not be listed
"""

from __future__ import annotations

import argparse
import base64
import math
import shutil
import struct
import sys
from array import array
from pathlib import Path

# 48 kHz keeps every fixture boundary an exact multiple of the 64-frame
# analysis bin, so silence regions land on whole timecodes.
SAMPLE_RATE = 48_000
CHANNELS = 2
BITS = 16
AMPLITUDE = 0.125  # -18 dBFS
FULL_SCALE = 32767

HERE = Path(__file__).resolve().parent
COMMITTED = HERE / "fixtures"

# 1x1 white JPEG, used only to prove non-audio files are filtered out.
JPEG_1PX = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0"
    "aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAA"
    "AAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=="
)


def _wav_header(frames: int) -> bytes:
    data_bytes = frames * CHANNELS * (BITS // 8)
    block_align = CHANNELS * (BITS // 8)
    byte_rate = SAMPLE_RATE * block_align
    return b"".join(
        (
            b"RIFF",
            struct.pack("<I", 36 + data_bytes),
            b"WAVE",
            b"fmt ",
            struct.pack("<IHHIIHH", 16, 1, CHANNELS, SAMPLE_RATE, byte_rate, block_align, BITS),
            b"data",
            struct.pack("<I", data_bytes),
        )
    )


def _tone(frames: int, left_hz: float, right_hz: float) -> array:
    samples = array("h")
    step_l = 2.0 * math.pi * left_hz / SAMPLE_RATE
    step_r = 2.0 * math.pi * right_hz / SAMPLE_RATE
    peak = int(AMPLITUDE * FULL_SCALE)
    for frame in range(frames):
        samples.append(int(peak * math.sin(step_l * frame)))
        samples.append(int(peak * math.sin(step_r * frame)))
    return samples


def _silent(frames: int) -> array:
    return array("h", bytes(frames * CHANNELS * (BITS // 8)))


def write_wav(path: Path, silent_ranges: list[tuple[float, float]], seconds: float) -> None:
    """Write a stereo tone with hard-edged silent spans (no fades, so the bin
    boundaries land exactly on the asserted timecodes)."""
    total = int(round(seconds * SAMPLE_RATE))
    samples = _tone(total, 440.0, 660.0)
    for start_s, end_s in silent_ranges:
        start = int(round(start_s * SAMPLE_RATE))
        end = min(int(round(end_s * SAMPLE_RATE)), total)
        samples[start * CHANNELS : end * CHANNELS] = _silent(end - start)
    if sys.byteorder != "little":
        samples.byteswap()
    with path.open("wb") as handle:
        handle.write(_wav_header(total))
        handle.write(samples.tobytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="build/qa-fixtures", type=Path)
    args = parser.parse_args()

    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.iterdir():
        if stale.is_file():
            stale.unlink()

    write_wav(out / "01 Opening.wav", [(0.0, 5.0), (12.0, 17.0), (27.0, 32.0)], 32.0)
    write_wav(out / "02 Middle.wav", [], 10.0)
    write_wav(out / "03 Finale.wav", [], 3.0)

    mp3 = COMMITTED / "04 Codec Test.mp3"
    if not mp3.exists():
        print(f"missing committed MP3 fixture: {mp3}", file=sys.stderr)
        return 1
    shutil.copyfile(mp3, out / mp3.name)

    (out / "readme.txt").write_text("Plain text must never be listed by WaveCue.\n")
    (out / "artwork.jpg").write_bytes(JPEG_1PX)
    (out / "05 Not Supported.m4a").write_bytes(b"\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00")

    for item in sorted(out.iterdir()):
        print(f"{item.stat().st_size:>9}  {item.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
