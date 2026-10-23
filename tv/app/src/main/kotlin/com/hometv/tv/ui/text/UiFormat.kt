package com.hometv.tv.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.hometv.tv.R
import com.hometv.tv.core.playback.MessageKey
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PreparingEstimate
import com.hometv.tv.core.playback.TrackLabel
import com.hometv.tv.core.playback.TrackTag
import com.hometv.tv.core.util.EpisodeCode
import com.hometv.tv.core.util.EpisodeTitle
import com.hometv.tv.core.util.Format
import com.hometv.tv.core.util.LanguageName
import com.hometv.tv.core.util.Runtime
import com.hometv.tv.core.util.SeasonTitle

/**
 * Turns core structured values and [UiText] into catalog text (prd3 §UI Language rules 5–6, §9.2).
 * Join rules: language, codec, title and word tags are joined with " · "; parenthesized tags
 * ("(convertido)", "(no disponible)") are appended with a plain space. `Runtime(h, 0)` prints "2 h",
 * `Runtime(0, m)` prints "42 min".
 */
class UiFormat(private val res: StringResolver) {
    fun text(text: UiText): String = when (text) {
        is UiText.Res -> res.string(text.id, *text.args.map(::arg).toTypedArray())
        is UiText.Plural -> res.plural(text.id, text.count, *text.args.map(::arg).toTypedArray())
        is UiText.Value -> arg(text.value)
        is UiText.Data -> text.text
        is UiText.Joined -> text.parts.map(::text).filter { it.isNotEmpty() }.joinToString(res.string(text.separator))
    }

    /** One argument as text. Numbers must already be es-ES strings ([Format]). */
    fun arg(value: Any): String = when (value) {
        is String -> value
        is UiText -> text(value)
        is Runtime -> runtime(value)
        is EpisodeCode -> episodeCode(value)
        is SeasonTitle -> seasonTitle(value)
        is EpisodeTitle -> episodeTitle(value)
        is LanguageName -> language(value)
        is TrackLabel -> trackLabel(value)
        is PreparingEstimate -> preparing(value)
        is PreparingText -> preparing(value.estimate, value.base)
        is Number -> throw IllegalArgumentException("Pre-format numbers with core Format (es-ES): $value")
        else -> throw IllegalArgumentException("Unsupported UiText argument: ${value::class.simpleName}")
    }

    fun runtime(runtime: Runtime): String = when {
        runtime.hours > 0 && runtime.minutes > 0 -> res.string(R.string.runtime_h_min, Format.count(runtime.hours), Format.count(runtime.minutes))
        runtime.hours > 0 -> res.string(R.string.runtime_h, Format.count(runtime.hours))
        else -> res.string(R.string.runtime_min, Format.count(runtime.minutes))
    }

    fun episodeCode(code: EpisodeCode): String = res.string(R.string.episode_code, code.season.toString(), code.episode.toString())

    fun seasonTitle(title: SeasonTitle): String = when (title) {
        is SeasonTitle.Named -> title.name
        is SeasonTitle.Numbered -> res.string(R.string.season_numbered, title.number.toString())
        SeasonTitle.Specials -> res.string(R.string.season_specials)
    }

    fun episodeTitle(title: EpisodeTitle): String = when (title) {
        is EpisodeTitle.Named -> title.title
        is EpisodeTitle.Numbered -> res.string(R.string.episode_numbered, title.number.toString())
    }

    fun language(name: LanguageName): String = when (name) {
        is LanguageName.Named -> name.name
        is LanguageName.Code -> name.code
        LanguageName.Unknown -> res.string(R.string.language_unknown)
    }

    fun tag(tag: TrackTag): String = when (tag) {
        TrackTag.Forced -> res.string(R.string.tag_forced)
        TrackTag.Sdh -> res.string(R.string.tag_sdh)
        TrackTag.External -> res.string(R.string.tag_external)
        is TrackTag.Image -> res.string(R.string.tag_image, tag.codec)
        TrackTag.Default -> res.string(R.string.tag_default)
        TrackTag.Converted -> res.string(R.string.tag_converted)
        TrackTag.Unavailable -> res.string(R.string.tag_unavailable)
    }

    fun trackLabel(label: TrackLabel): String {
        val suffixTags = label.tags.filter { it.isParenthesized }
        val words = listOf(language(label.language)) +
            listOfNotNull(label.codec?.takeIf { it.isNotBlank() }, label.title?.takeIf { it.isNotBlank() }) +
            label.tags.filterNot { it.isParenthesized }.map(::tag)
        val head = words.joinToString(res.string(R.string.separator_dot))
        return (listOf(head) + suffixTags.map(::tag)).joinToString(" ")
    }

    /** "Preparando el vídeo… quedan unos 3 min" (or the audio chip with [base] = `player_audio_preparing`). */
    fun preparing(estimate: PreparingEstimate, @StringRes base: Int = R.string.player_preparing): String {
        val head = res.string(base)
        val tail = when (estimate) {
            PreparingEstimate.None -> return head
            PreparingEstimate.LessThanAMinute -> res.string(R.string.less_than_a_minute_left)
            is PreparingEstimate.Minutes -> res.plural(R.plurals.minutes_left, estimate.minutes, Format.count(estimate.minutes))
        }
        return res.string(R.string.pair_space, head, tail)
    }

    fun message(key: MessageKey, args: List<String> = emptyList()): String = res.string(messageRes(key), *args.toTypedArray())

    private val TrackTag.isParenthesized: Boolean get() = this == TrackTag.Converted || this == TrackTag.Unavailable

    companion object {
        /** The catalog text of each core message key (`CONNECTION_ERROR` takes the server host as `%1$s`). */
        @StringRes
        fun messageRes(key: MessageKey): Int = when (key) {
            MessageKey.PLAYBACK_UNSUPPORTED -> R.string.player_unsupported
            MessageKey.REASON_VIDEO_CODEC_UNSUPPORTED -> R.string.reason_video_codec_unsupported
            MessageKey.REASON_NO_VIDEO_STREAM -> R.string.reason_no_video_stream
            MessageKey.REASON_HLS_NOT_SUPPORTED -> R.string.reason_hls_not_supported
            MessageKey.REASON_PROBE_FAILED -> R.string.reason_probe_failed
            MessageKey.REASON_DURATION_UNKNOWN -> R.string.reason_duration_unknown
            MessageKey.REASON_KEYFRAMES_TOO_SPARSE -> R.string.reason_keyframes_too_sparse
            MessageKey.REASON_UNKNOWN -> R.string.reason_unknown
            MessageKey.PREPARING_TIMEOUT -> R.string.player_preparing_timeout
            MessageKey.TOO_MANY_SESSIONS -> R.string.player_too_many_sessions
            MessageKey.HLS_DISK_FULL -> R.string.player_hls_disk_full
            MessageKey.SERVER_UNAVAILABLE -> R.string.player_server_unavailable
            MessageKey.KEYFRAME_INDEX_FAILED -> R.string.player_keyframe_index_failed
            MessageKey.FILE_MISSING -> R.string.player_file_missing
            MessageKey.ITEM_GONE -> R.string.item_gone
            MessageKey.HLS_FAILED -> R.string.player_hls_failed
            MessageKey.PLAYBACK_FAILED -> R.string.player_playback_failed
            MessageKey.UNEXPECTED_ERROR -> R.string.error_unexpected
            MessageKey.CONNECTION_ERROR -> R.string.error_connection
            MessageKey.TLS_ERROR -> R.string.setup_error_tls
            MessageKey.SIGN_IN_FAILED -> R.string.error_sign_in
            MessageKey.AUDIO_TRACK_UNPLAYABLE -> R.string.audio_track_unplayable
            MessageKey.AUDIO_SWITCH_FAILED -> R.string.audio_switch_failed
            MessageKey.AUDIO_PREPARING_TIMEOUT -> R.string.audio_preparing_timeout
        }

        @StringRes
        fun actionRes(action: PlayerAction): Int = when (action) {
            PlayerAction.RETRY -> R.string.action_retry
            PlayerAction.RETRY_FROM_HERE -> R.string.player_retry_from_here
            PlayerAction.TRY_OTHER_VERSION -> R.string.player_try_other_version
            PlayerAction.BACK -> R.string.action_back
        }

        /** TMDB's English show `status` → catalog text; unknown values are hidden (null). */
        @StringRes
        fun showStatusRes(status: String?): Int? = when (status?.trim()?.lowercase()) {
            "ended" -> R.string.show_status_ended
            "returning series" -> R.string.show_status_returning
            "canceled", "cancelled" -> R.string.show_status_canceled
            "in production" -> R.string.show_status_in_production
            "planned" -> R.string.show_status_planned
            "pilot" -> R.string.show_status_pilot
            else -> null
        }
    }
}

/** "Preparando el vídeo…" / "Preparando el audio…" ([base]) followed by the estimate, as a [UiText] argument. */
data class PreparingText(val estimate: PreparingEstimate, @param:StringRes val base: Int = R.string.player_preparing)

/** Provided by `HomeTvRoot` (es-ES resources). */
val LocalUiFormat = staticCompositionLocalOf<UiFormat> { error("UiFormat not provided") }

@Composable
@ReadOnlyComposable
fun UiText.resolve(): String = LocalUiFormat.current.text(this)
