package com.hometv.tv.core.playback

import com.hometv.tv.core.api.AudioChoice
import com.hometv.tv.core.api.AudioTrack
import com.hometv.tv.core.api.SubtitleChoice
import com.hometv.tv.core.api.SubtitleTrack
import com.hometv.tv.core.api.TrackChoice
import com.hometv.tv.core.util.LanguageNames
import java.util.Locale

/** Both parts of a matched [TrackChoice] for one file: `null` audio = the file default, `null` subtitle = Off. */
data class TrackMatch(val audioStreamIndex: Int?, val subtitleId: String?)

/**
 * The remembered track choice's matching rule (`specs/subtitles-improvements/prd.md` FR-3..FR-5a),
 * pure. The same rule as the web's `player/trackChoice.ts`; both run `shared/track-choice-cases.json`.
 */
object TrackChoiceRules {
    /** The server's limit for a stored title (FR-9); longer titles are cut. */
    private const val MAX_TITLE = 1000

    /** FR-3: empty → null; region/script dropped; `und` stays `und`; else the BCP 47 code or the code. */
    fun languageKey(code: String?): String? {
        val lower = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val primary = lower.split('-', '_').first()
        if (primary.isEmpty()) return null
        if (primary == "und") return "und"
        return LanguageNames.toBcp47(primary) ?: primary
    }

    fun sameLanguage(a: String?, b: String?): Boolean {
        val key = languageKey(a)
        return key != null && key == languageKey(b)
    }

    /** The file default: the `default`-flagged audio track, else the first. */
    fun defaultAudio(tracks: List<AudioTrack>): AudioTrack? = tracks.firstOrNull { it.default } ?: tracks.firstOrNull()

    /** FR-4: the index to send as `audio_stream_index`; null = the file default. */
    fun matchAudio(choice: AudioChoice?, tracks: List<AudioTrack>): Int? {
        if (choice == null || tracks.isEmpty()) return null
        val candidates = tracks.filter { sameLanguage(it.language, choice.language) }
        if (candidates.isEmpty()) return null
        val fileDefault = defaultAudio(tracks)
        val chosen = candidates.firstOrNull { sameTitle(it.title, choice.title) && it.channels == choice.channels }
            ?: candidates.firstOrNull { it === fileDefault }
            ?: candidates.first()
        return if (chosen === fileDefault) null else chosen.index
    }

    /** FR-5 (TV): the subtitle id to select at a file's first decision; null = Off. */
    fun matchSubtitle(choice: SubtitleChoice?, tracks: List<SubtitleTrack>, mode: PlaybackMode, audioLanguage: String?): String? {
        val fallback = { TrackRules.defaultSubtitle(tracks, mode, audioLanguage)?.id }
        if (choice == null) return fallback()
        if (choice.mode == MODE_OFF) return null
        val candidates = TrackRules.visibleSubtitles(tracks, mode).filter { sameLanguage(it.language, choice.language) }
        if (candidates.isEmpty()) return fallback()
        val exact = candidates.filter { it.kind == choice.kind && sameFlags(it, choice) && it.source == choice.source }
        (exact.firstOrNull { sameTitle(it.title, choice.title) } ?: exact.firstOrNull())?.let { return it.id }
        val sameKind = candidates.filter { it.kind == choice.kind }
        val pool = sameKind.ifEmpty { candidates.filter { it.isText } + candidates.filterNot { it.isText } }
        val pick = pool.firstOrNull { sameFlags(it, choice) }
            ?: pool.firstOrNull { it.forced == choice.forced }
            ?: pool.firstOrNull { !it.forced && !it.hearing_impaired }
            ?: pool.firstOrNull()
        return pick?.id
    }

    /** Both parts (the case table); the subtitle default uses the matched (else the default) audio's language. */
    fun match(choice: TrackChoice?, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>, mode: PlaybackMode): TrackMatch {
        val audioIndex = matchAudio(choice?.audio, audio)
        val audioTrack = audio.firstOrNull { it.index == audioIndex } ?: defaultAudio(audio)
        return TrackMatch(audioIndex, matchSubtitle(choice?.subtitles, subtitles, mode, audioTrack?.language))
    }

    /** FR-5a: blank language → `und`; channels outside 1..32 → null (the server would answer 422); long titles cut. */
    fun audioChoiceOf(track: AudioTrack): AudioChoice =
        AudioChoice(pickLanguage(track.language), pickTitle(track.title), track.channels?.takeIf { it in 1..32 })

    /** FR-5a: null (Desactivados) → `off`; a track → its traits. */
    fun subtitleChoiceOf(track: SubtitleTrack?): SubtitleChoice {
        if (track == null) return SubtitleChoice(mode = MODE_OFF)
        return SubtitleChoice(
            mode = MODE_TRACK,
            language = pickLanguage(track.language),
            title = pickTitle(track.title),
            forced = track.forced,
            hearing_impaired = track.hearing_impaired,
            source = track.source,
            kind = track.kind,
        )
    }

    fun withAudio(choice: TrackChoice?, audio: AudioChoice): TrackChoice = TrackChoice(audio, choice?.subtitles)

    fun withSubtitles(choice: TrackChoice?, subtitles: SubtitleChoice): TrackChoice = TrackChoice(choice?.audio, subtitles)

    /** prd.md FR-5a: `a != null && a == b` (data-class equality, null channels equal null). */
    fun sameAudioChoice(a: AudioChoice?, b: AudioChoice?): Boolean = a != null && a == b

    /** Part-wise: a part picked in this session wins over the fetched one. */
    fun effective(fetched: TrackChoice?, session: TrackChoice?): TrackChoice? {
        if (session == null) return fetched
        return TrackChoice(session.audio ?: fetched?.audio, session.subtitles ?: fetched?.subtitles)
    }

    private const val MODE_OFF = "off"
    private const val MODE_TRACK = "track"

    private fun normalTitle(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() }

    private fun sameTitle(a: String?, b: String?): Boolean = normalTitle(a) == normalTitle(b)

    private fun sameFlags(track: SubtitleTrack, choice: SubtitleChoice): Boolean =
        track.forced == choice.forced && track.hearing_impaired == choice.hearing_impaired

    private fun pickLanguage(language: String?): String = language?.trim()?.takeIf { it.isNotEmpty() } ?: "und"

    private fun pickTitle(title: String?): String? = title?.take(MAX_TITLE)
}
