"""``procs.effective_argv``: the priority prefixes shrink to the tools that exist."""

import pytest

from hometv.playback import procs


@pytest.fixture
def tools(monkeypatch):
    present: set[str] = set()
    monkeypatch.setattr(procs, "_available", lambda binary: binary in present)
    return present


def test_idle_prefix_keeps_what_exists(tools):
    argv = [*procs.IDLE_PREFIX, "ffprobe", "x"]
    tools.update({"nice", "ionice"})
    assert procs.effective_argv(argv) == argv
    tools.discard("ionice")
    assert procs.effective_argv(argv) == ["nice", "-n", "10", "ffprobe", "x"]
    tools.clear()
    assert procs.effective_argv(argv) == ["ffprobe", "x"]


def test_transcode_prefix_dropped_when_nice_is_missing(tools):
    argv = [*procs.TRANSCODE_PREFIX, "ffmpeg", "-i", "x"]
    assert procs.TRANSCODE_PREFIX == ("nice", "-n", "10")
    tools.add("nice")
    assert procs.effective_argv(argv) == argv
    tools.clear()
    assert procs.effective_argv(argv) == ["ffmpeg", "-i", "x"]


def test_transcode_prefix_never_adds_ionice(tools):
    tools.update({"nice", "ionice"})
    argv = [*procs.TRANSCODE_PREFIX, "ffmpeg"]
    assert procs.effective_argv(argv) == argv


def test_plain_argv_is_untouched(tools):
    assert procs.effective_argv(["ffmpeg", "-i", "x"]) == ["ffmpeg", "-i", "x"]
