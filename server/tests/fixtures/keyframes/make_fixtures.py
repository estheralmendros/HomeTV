"""Regenerate the stored keyframe-reader fixtures (run manually; needs ffmpeg + ffprobe).

    cd server && uv run python -m tests.fixtures.keyframes.make_fixtures

For each clip: generate it with ffmpeg lavfi, run the container reader through a recording
``read`` callable, and store only the byte ranges it read (zlib + base64) together with the
ffprobe packet-scan keyframe times (relative to ``format.start_time``). The unit tests replay
the reader over the stored ranges and compare with the ffprobe times (+-1 ms).
"""

import base64
import json
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

from hometv.playback.keyframe_readers import mkv_cue_times, mp4_sync_times, parse_packet_rows

HERE = Path(__file__).parent
SRC = ["-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=12"]
SINE = ["-f", "lavfi", "-i", "sine=frequency=440:duration=12"]
X264 = ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast"]

CLIPS: dict[str, tuple[list[str], int]] = {
    # name -> (ffmpeg args after the inputs, video stream index)
    "mkv_cues.mkv": ([*X264, "-g", "50", "-c:a", "aac", "-map", "0:v", "-map", "1:a"], 0),
    "mkv_audio_first.mkv": ([*X264, "-g", "30", "-c:a", "ac3", "-map", "1:a", "-map", "0:v"], 1),
    "vp9_opus.webm": (
        ["-c:v", "libvpx-vp9", "-g", "40", "-deadline", "realtime", "-c:a", "libopus"],
        0,
    ),
    "bframes_editlist.mp4": (
        [*X264.copy()[:-2], "-preset", "medium", "-g", "25", "-bf", "3", "-c:a", "aac"],
        0,
    ),
}


def ffprobe_times(path: Path, index: int) -> tuple[list[float], float]:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", str(index), "-show_entries",
         "packet=pts_time,dts_time,flags", "-of", "csv=p=0", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout  # fmt: skip
    start = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=start_time", "-of", "csv=p=0",
         str(path)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()  # fmt: skip
    return parse_packet_rows(out.splitlines()), float(start or 0)


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        for name, (args, index) in CLIPS.items():
            path = Path(tmp) / name
            subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *SRC, *SINE, *args,
                 str(path)],
                check=True,
            )  # fmt: skip
            data = path.read_bytes()
            ranges: list[tuple[int, int]] = []

            def read(offset: int, size: int, data: bytes = data, ranges=ranges) -> bytes:
                ranges.append((offset, size))
                return data[offset : offset + size]

            reader = mp4_sync_times if name.endswith(".mp4") else mkv_cue_times
            reader(read, len(data), index)
            times, start = ffprobe_times(path, index)
            fixture = {
                "container": name.rsplit(".", 1)[1],
                "video_index": index,
                "size": len(data),
                "start_seconds": start,
                "chunks": [
                    [o, base64.b64encode(zlib.compress(data[o : o + s], 9)).decode()]
                    for o, s in ranges
                ],
                "ffprobe_times": [round(t - start, 6) for t in times],
            }
            out = HERE / (name.replace(".", "_") + ".json")
            out.write_text(json.dumps(fixture, indent=1) + "\n")
            print(f"{out.name}: {len(ranges)} reads, {len(times)} keyframes", file=sys.stderr)


if __name__ == "__main__":
    main()
