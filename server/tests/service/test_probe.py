import json
import sys
from pathlib import Path

import pytest

from hometv.probe import ProbeError, probe, run_subprocess

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "ffprobe"


class FakeRunner:
    def __init__(self, returncode=0, stdout=b"", stderr=b"", exc=None):
        self.calls = []
        self.returncode, self.stdout, self.stderr, self.exc = returncode, stdout, stderr, exc

    async def __call__(self, args, timeout):
        self.calls.append((list(args), timeout))
        if self.exc:
            raise self.exc
        return self.returncode, self.stdout, self.stderr


async def test_probe_invokes_ffprobe_with_argument_list_and_timeout():
    payload = (FIXTURES / "mp4_h264_aac.json").read_bytes()
    runner = FakeRunner(stdout=payload)
    result = await probe("/media/x.mp4", ffprobe_path="/opt/ffprobe", runner=runner)
    assert result.container == "mp4"
    args, timeout = runner.calls[0]
    assert args == [
        "/opt/ffprobe",
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        "/media/x.mp4",
    ]
    assert timeout == 60.0


async def test_probe_nonzero_exit_raises_with_last_stderr_line():
    runner = FakeRunner(returncode=1, stderr=b"warn\n/media/x.mkv: Invalid data found\n")
    with pytest.raises(ProbeError, match="Invalid data found"):
        await probe("/media/x.mkv", runner=runner)


async def test_probe_invalid_json_raises():
    with pytest.raises(ProbeError, match="invalid ffprobe output"):
        await probe("/media/x.mkv", runner=FakeRunner(stdout=b"not json"))


async def test_probe_timeout_raises():
    with pytest.raises(ProbeError, match="timed out"):
        await probe("/media/x.mkv", runner=FakeRunner(exc=TimeoutError()))


async def test_probe_missing_binary_raises():
    with pytest.raises(ProbeError, match="not found"):
        await probe("/media/x.mkv", runner=FakeRunner(exc=FileNotFoundError("ffprobe")))


async def test_probe_without_format_raises():
    with pytest.raises(ProbeError):
        await probe("/media/x.mkv", runner=FakeRunner(stdout=json.dumps({"streams": []}).encode()))


# --- run_subprocess uses create_subprocess_exec (no shell) ---------------------------------


async def test_run_subprocess_captures_output():
    code, out, err = await run_subprocess(
        [sys.executable, "-c", "import sys; print('out'); print('err', file=sys.stderr)"], 10
    )
    assert code == 0
    assert out.strip() == b"out"
    assert err.strip() == b"err"


async def test_run_subprocess_does_not_use_a_shell():
    code, out, _ = await run_subprocess(
        [sys.executable, "-c", "import sys; print(sys.argv[1])", "$HOME; echo pwned"], 10
    )
    assert code == 0
    assert out.strip() == b"$HOME; echo pwned"


async def test_run_subprocess_timeout_kills_process():
    with pytest.raises(TimeoutError):
        await run_subprocess([sys.executable, "-c", "import time; time.sleep(30)"], 0.3)


async def test_run_subprocess_missing_binary():
    with pytest.raises(FileNotFoundError):
        await run_subprocess(["/nonexistent/ffprobe-binary"], 1)
