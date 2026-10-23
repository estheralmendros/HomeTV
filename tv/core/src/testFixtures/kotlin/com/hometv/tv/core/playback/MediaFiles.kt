package com.hometv.tv.core.playback

import com.hometv.tv.core.api.AudioTrack
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.SubtitleTrack
import com.hometv.tv.core.api.VideoInfo

/** Test factories in the prd.md shapes. */
object F {
    val H264 = VideoInfo("h264", 1920, 1080)
    val HEVC = VideoInfo("hevc", 3840, 2160, hdr = "hdr10")

    fun file(
        id: Long,
        video: VideoInfo? = H264,
        duration: Double? = 7200.0,
        part: Int? = null,
        label: String = "1080p H264 · AAC 2.0",
        probeError: String? = null,
        size: Long = 1_000_000_000L - id,
        fileName: String = "file$id.mkv",
        container: String = "mkv",
        audio: List<AudioTrack> = listOf(audio(1)),
    ) = MediaFile(
        id = id, file_name = fileName, size_bytes = size, container = container, duration_seconds = duration,
        label = label, video = video, audio_tracks = audio, subtitle_tracks = emptyList(), part_number = part,
        probe_error = probeError,
    )

    fun hevc(id: Long, duration: Double? = 7200.0, part: Int? = null, label: String = "2160p HEVC HDR10 · EAC3 5.1") =
        file(id, video = HEVC, duration = duration, part = part, label = label)

    fun part(id: Long, n: Int, duration: Double? = 3000.0, video: VideoInfo? = H264, label: String = "1080p H264 · AAC 2.0", probeError: String? = null) =
        file(id, video = video, duration = duration, part = n, label = label, probeError = probeError)

    fun progress(fileId: Long?, position: Double = 1000.0, duration: Double? = 7200.0, watched: Boolean = false) =
        Progress(position_seconds = position, duration_seconds = duration, watched = watched, media_file_id = fileId)

    fun audio(index: Int, codec: String = "aac", channels: Int? = 2, language: String? = "eng", title: String? = null, default: Boolean = false) =
        AudioTrack(index = index, codec = codec, channels = channels, language = language, title = title, default = default)

    fun sub(
        id: String,
        language: String? = "eng",
        kind: String = "text",
        source: String = "embedded",
        codec: String? = if (kind == "image") "hdmv_pgs_subtitle" else "subrip",
        forced: Boolean = false,
        sdh: Boolean = false,
        default: Boolean = false,
        title: String? = null,
    ) = SubtitleTrack(
        id = id, source = source, kind = kind, codec = codec, language = language, title = title, forced = forced,
        hearing_impaired = sdh, default = default, url = if (kind == "text") "/api/media-files/91/subtitles/$id.vtt" else null,
    )

    /** Chromecast-like caps without HEVC. */
    val caps = ClientCaps(listOf("mp4", "mkv", "webm", "ts"), listOf("h264", "vp9"), listOf("aac", "ac3", "eac3"), 6, true, true)
    val hevcCaps = caps.copy(video_codecs = listOf("h264", "hevc", "vp9"))
}
