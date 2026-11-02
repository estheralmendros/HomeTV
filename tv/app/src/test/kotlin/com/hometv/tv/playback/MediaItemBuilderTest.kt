package com.hometv.tv.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.SideloadedSubtitle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** [MediaItemSpec] → Media3 `MediaItem`. */
@RunWith(AndroidJUnit4::class)
class MediaItemBuilderTest {
    @Test
    fun `direct play uses the container MIME and one subtitle configuration per cached VTT`() {
        val item = MediaItemBuilder.build(
            MediaItemSpec(
                url = "https://hometv.ts.net/api/media-files/91/stream",
                mimeType = "video/x-matroska",
                subtitles = listOf(
                    SideloadedSubtitle("emb-3", "file:///data/cache/subtitles/91/emb-3.vtt", "spa", null, forced = false),
                    SideloadedSubtitle("ext-1", "file:///data/cache/subtitles/91/ext-1.vtt", "eng", "Forced", forced = true),
                ),
            ),
        )
        val config = checkNotNull(item.localConfiguration)
        assertEquals("https://hometv.ts.net/api/media-files/91/stream", config.uri.toString())
        assertEquals(MimeTypes.VIDEO_MATROSKA, config.mimeType)
        assertEquals(listOf("emb-3", "ext-1"), config.subtitleConfigurations.map { it.id })
        assertEquals(listOf(MimeTypes.TEXT_VTT, MimeTypes.TEXT_VTT), config.subtitleConfigurations.map { it.mimeType })
        assertEquals("file", config.subtitleConfigurations.first().uri.scheme)
        assertEquals("/data/cache/subtitles/91/emb-3.vtt", config.subtitleConfigurations.first().uri.path)
        assertEquals(listOf("spa", "eng"), config.subtitleConfigurations.map { it.language })
        assertEquals(listOf(0, C.SELECTION_FLAG_FORCED), config.subtitleConfigurations.map { it.selectionFlags })
    }

    @Test
    fun `HLS uses the m3u8 MIME`() {
        val item = MediaItemBuilder.build(MediaItemSpec("https://hometv.ts.net/api/hls/S1/index.m3u8", MimeTypes.APPLICATION_M3U8))
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        assertEquals(0, item.localConfiguration?.subtitleConfigurations?.size)
    }
}
