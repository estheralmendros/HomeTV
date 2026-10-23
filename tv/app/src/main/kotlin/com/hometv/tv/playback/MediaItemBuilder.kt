package com.hometv.tv.playback

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.hometv.tv.core.playback.MediaItemSpec

/**
 * [MediaItemSpec] → Media3 `MediaItem` (prd3 Playback Flow rules 2–3): the stream/HLS URL with its
 * MIME (container MIME for direct play, `application/x-mpegURL` for HLS), plus one
 * `SubtitleConfiguration` per cached WebVTT file (`Format.id` = server track id, so `ExoTrackMapper`
 * finds it; forced tracks carry `SELECTION_FLAG_FORCED`). Subtitles are parsed during extraction
 * (the Media3 ≥ 1.4 default); no legacy decoding.
 */
object MediaItemBuilder {
    fun build(spec: MediaItemSpec): MediaItem {
        val builder = MediaItem.Builder().setUri(spec.url)
        spec.mimeType?.let(builder::setMimeType)
        builder.setSubtitleConfigurations(
            spec.subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(sub.fileUri.toUri())
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    .setId(sub.trackId)
                    .setSelectionFlags(if (sub.forced) C.SELECTION_FLAG_FORCED else 0)
                    .build()
            },
        )
        return builder.build()
    }
}
