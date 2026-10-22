import pytest

from hometv.probe import ProbeError, probe

pytestmark = pytest.mark.integration


async def test_real_probe_h264_aac(clips_dir, ffprobe_bin):
    result = await probe(clips_dir / "h264_aac.mp4", ffprobe_path=ffprobe_bin)
    assert result.container == "mp4"
    assert result.video is not None
    assert result.video.codec == "h264"
    assert (result.video.width, result.video.height) == (320, 240)
    assert result.video.frame_rate == 25.0
    assert result.audio[0].codec == "aac"
    assert result.duration_seconds == pytest.approx(20.0, abs=0.2)


async def test_real_probe_invalid_file(tmp_path, ffprobe_bin):
    bad = tmp_path / "bad.mkv"
    bad.write_bytes(b"not a video")
    with pytest.raises(ProbeError):
        await probe(bad, ffprobe_path=ffprobe_bin)
