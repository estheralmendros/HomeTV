package com.hometv.tv.playback

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.SubtitleLook
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AndroidPlayback.create` runs at process start: it builds no LibVLC ("nothing is created here", prd2 FR-5) and the
 * display height (a mobile player turns landscape after launch) is read at each VLC port build, not at `create`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPlaybackTest {
    private class Instance(val options: List<String>)

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val created = mutableListOf<List<String>>()
    private val provider = LibVlcProvider<Instance>(listOf("arm64-v8a"), { opts -> created += opts; Instance(opts) }, { }, { _, _ -> })
    private var heightReads = 0

    private fun create() =
        AndroidPlayback.create(app, { "token" }, { _, _ -> }, { heightReads++; 1080 }, provider) { FakeVlcPort() }

    @Test
    fun `create builds no LibVLC and reads no display height`() {
        val playback = create()
        assertTrue(created.isEmpty())
        assertEquals(0, heightReads)
        assertTrue(playback.vlcAvailability.isAvailable())
        assertTrue("availability never creates LibVLC", created.isEmpty())
    }

    @Test
    fun `the display height is read at port build, not when the VLC controller is made`() {
        val playback = create()
        val controller = playback.controllers(PlaybackEngine.VLC, OkHttpClient()) { SubtitleLook.DEFAULT }
        assertEquals(0, heightReads)
        assertTrue(created.isEmpty())
        (controller as VlcPlayerController).attach(FrameLayout(app))
        assertEquals(1, heightReads)
        assertEquals(1, created.size)
    }
}
