package com.hometv.tv.core.playback

import com.hometv.tv.core.api.AudioTrack
import com.hometv.tv.core.api.ClientCaps
import com.hometv.tv.core.api.SubtitleTrack
import com.hometv.tv.core.util.Format
import com.hometv.tv.core.util.LanguageName
import com.hometv.tv.core.util.LanguageNames
import java.util.Locale

enum class PlaybackMode { DIRECT, HLS }

/** A track tag; `app` takes each text from strings.xml ("Forzados", "Imagen (%1$s)", "(convertido)"…). */
sealed interface TrackTag {
    data object Forced : TrackTag

    data object Sdh : TrackTag

    data object External : TrackTag

    data class Image(val codec: String) : TrackTag

    data object Default : TrackTag

    data object Converted : TrackTag

    data object Unavailable : TrackTag
}

/** Structured track label; `app` UiFormat joins language, codec, title and word tags with " · ". */
data class TrackLabel(val language: LanguageName, val codec: String?, val title: String?, val tags: List<TrackTag>)

enum class AudioSwitchRoute {
    /** `TrackSelectionOverride` in the current direct media, no server call. */
    LOCAL,

    /** A new `POST .../playback` with that `audio_stream_index`. */
    SERVER,
}

/**
 * Pure track rules: visible subtitles per mode, the default subtitle, structured labels
 * and the audio-switch route. No UI words: language names come from [LanguageNames], codecs are
 * technical tokens, every word is a [TrackTag] resolved by `app`.
 */
object TrackRules {
    private val SUBTITLE_CODEC_TOKENS = mapOf(
        "hdmv_pgs_subtitle" to "PGS",
        "pgssub" to "PGS",
        "dvd_subtitle" to "VobSub",
        "dvdsub" to "VobSub",
        "dvb_subtitle" to "DVB",
        "dvbsub" to "DVB",
    )

    /** HLS drops embedded image tracks (server remux `-sn`); direct mode shows text and image tracks. */
    fun visibleSubtitles(tracks: List<SubtitleTrack>, mode: PlaybackMode): List<SubtitleTrack> =
        if (mode == PlaybackMode.HLS) tracks.filter { it.isText } else tracks.filter { it.isText || it.isImage }

    /** A forced track in the selected audio track's language; else a `default=true` track; else Off (null). */
    fun defaultSubtitle(tracks: List<SubtitleTrack>, mode: PlaybackMode, audioLanguage: String?): SubtitleTrack? {
        val visible = visibleSubtitles(tracks, mode)
        val audio = languageKey(audioLanguage)
        if (audio != null) visible.firstOrNull { it.forced && languageKey(it.language) == audio }?.let { return it }
        return visible.firstOrNull { it.default }
    }

    /** "Inglés · EAC3 5.1 · Envolvente · Predeterminado" once resolved by `app`. */
    fun audioLabel(track: AudioTrack, converted: Boolean = false, unavailable: Boolean = false): TrackLabel {
        val codec = listOfNotNull(audioCodecToken(track.codec), Format.channels(track.channels)).joinToString(" ")
        val tags = buildList {
            if (track.default) add(TrackTag.Default)
            if (converted) add(TrackTag.Converted)
            if (unavailable) add(TrackTag.Unavailable)
        }
        return TrackLabel(LanguageNames.name(track.language), codec, track.title?.takeIf { it.isNotBlank() }, tags)
    }

    /** "Inglés · Forzados · Externo", "Inglés · Imagen (PGS)" once resolved by `app`. */
    fun subtitleLabel(track: SubtitleTrack, unavailable: Boolean = false): TrackLabel {
        val tags = buildList {
            if (track.forced) add(TrackTag.Forced)
            if (track.hearing_impaired) add(TrackTag.Sdh)
            if (track.isImage) add(TrackTag.Image(subtitleCodecToken(track.codec)))
            if (track.isExternal) add(TrackTag.External)
            if (unavailable) add(TrackTag.Unavailable)
        }
        return TrackLabel(LanguageNames.name(track.language), null, track.title?.takeIf { it.isNotBlank() }, tags)
    }

    /**
     * Direct mode and the track within the request's caps (codec and channels) → [AudioSwitchRoute.LOCAL];
     * anything else (direct outside caps, or HLS) → [AudioSwitchRoute.SERVER].
     */
    fun audioSwitchRoute(track: AudioTrack, mode: PlaybackMode, caps: ClientCaps): AudioSwitchRoute {
        if (mode == PlaybackMode.HLS) return AudioSwitchRoute.SERVER
        val codecOk = caps.audio_codecs.any { it.equals(track.codec, ignoreCase = true) }
        val channelsOk = (track.channels ?: 0) <= caps.max_audio_channels
        return if (codecOk && channelsOk) AudioSwitchRoute.LOCAL else AudioSwitchRoute.SERVER
    }

    /** `eac3` → "EAC3", `pcm_s16le` → "PCM" (the same upper-case tokens as the server `label`). */
    fun audioCodecToken(codec: String): String =
        if (codec.lowercase(Locale.ROOT).startsWith("pcm_")) "PCM" else codec.uppercase(Locale.ROOT)

    /** `hdmv_pgs_subtitle` → "PGS", `dvd_subtitle` → "VobSub", `dvb_subtitle` → "DVB", else upper case; null → "?". */
    fun subtitleCodecToken(codec: String?): String {
        if (codec.isNullOrBlank()) return "?"
        return SUBTITLE_CODEC_TOKENS[codec.lowercase(Locale.ROOT)] ?: codec.uppercase(Locale.ROOT)
    }

    /** Compares languages across 639-2/B, /T and 2-letter spellings. */
    private fun languageKey(code: String?): String? {
        val trimmed = code?.trim()?.lowercase(Locale.ROOT)
        if (trimmed.isNullOrEmpty() || trimmed == "und") return null
        return LanguageNames.toBcp47(trimmed) ?: trimmed
    }
}
