package com.hometv.tv.playback

import android.content.ContextWrapper
import android.widget.FrameLayout
import com.hometv.tv.core.playback.MediaItemSpec
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.PlayerFailure
import com.hometv.tv.core.playback.PlayerState
import com.hometv.tv.core.playback.SideloadedSubtitle
import com.hometv.tv.core.playback.TrackCheckInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [VlcPlayerController] over a [FakeVlcPort] (prd2 FR-8). `post` queues blocks the test drains with [drain];
 * `FrameLayout(ContextWrapper(null))` works on the plain JVM thanks to `unitTests.isReturnDefaultValues`.
 */
class VlcPlayerControllerTest {
    private val port = FakeVlcPort()
    private var token: String? = "tok"
    private var factoryCalls = 0
    private var factory: () -> VlcPlayerPort = { port }
    private val logs = mutableListOf<String>()
    private val posted = mutableListOf<() -> Unit>()
    private val events = mutableListOf<String>()
    private val listener = object : PlayerController.Listener {
        override fun onTracksChanged(check: TrackCheckInput) {
            events += "tracks $check"
        }

        override fun onStateChanged(state: PlayerState) {
            events += "state $state"
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            events += "playing $isPlaying"
        }

        override fun onDurationChanged(durationMs: Long?) {
            events += "duration $durationMs"
        }

        override fun onFailure(failure: PlayerFailure) {
            events += "failure $failure"
        }
    }
    private val vlc = VlcPlayerController(
        portFactory = {
            factoryCalls++
            factory()
        },
        token = { token },
        log = { msg, e -> logs += "$msg ${e ?: ""}" },
        post = { posted += it },
    ).apply { setListener(listener) }

    private val layout = FrameLayout(ContextWrapper(null)).also { port.layoutNames[it] = "layout" }
    private val other = FrameLayout(ContextWrapper(null)).also { port.layoutNames[it] = "other" }

    private val vlcFailure = PlayerFailure(9001, engine = PlaybackEngine.VLC)
    private val failurePair = listOf("state IDLE", "failure $vlcFailure")

    private fun spec(url: String = "http://s/api/media-files/3/stream") = MediaItemSpec(
        url,
        null,
        subtitles = listOf(SideloadedSubtitle("s3", "file:///cache/subtitles/1/s3.vtt", "spa", null, false)),
        audioTrackIndices = listOf(1, 2),
        imageSubtitleIds = listOf("s2"),
        embeddedSubtitleIds = listOf("s1", "s2"),
    )

    private fun drain() {
        while (posted.isNotEmpty()) posted.removeAt(0)()
    }

    /** Attached, opened at [startMs] and playing (first `Playing` delivered, posted blocks drained). */
    private fun playing(startMs: Long = 0) {
        vlc.attach(layout)
        vlc.setMediaItem(spec(), startMs)
        vlc.prepare()
        vlc.play()
        port.emit(VlcEvent.Playing)
        drain()
    }

    @Test
    fun `AC-8_1 prepare opens the stream with the token, the start time and the slaves`() {
        vlc.attach(layout)
        vlc.setMediaItem(spec(), 600_000)
        assertTrue(port.opens.isEmpty(), "setMediaItem alone opens nothing")
        vlc.prepare()
        val open = port.opens.single()
        assertEquals("http://s/api/media-files/3/stream?token=tok", open.uri)
        assertTrue(":start-time=600.000" in open.options, "${open.options}")
        assertEquals(listOf("file:///cache/subtitles/1/s3.vtt"), open.slaves)
    }

    @Test
    fun `AC-8_1b the open waits for the surface, attach is idempotent and the token is read at open time`() {
        vlc.setMediaItem(spec(), 0)
        vlc.prepare()
        assertTrue(port.opens.isEmpty())
        assertEquals(PlayerState.BUFFERING, vlc.state)
        token = "tok2"
        vlc.attach(layout)
        assertEquals(listOf("attachViews layout", "setEventListener", "open"), port.commands())
        assertEquals("http://s/api/media-files/3/stream?token=tok2", port.opens.single().uri)
        port.clear()
        vlc.attach(layout)
        assertEquals(emptyList<String>(), port.calls)
        vlc.attach(other)
        assertEquals(listOf("detachViews", "attachViews other"), port.commands())
        port.clear()
        vlc.detach(layout)
        assertEquals(emptyList<String>(), port.calls, "layout is not the attached one")
        vlc.detach(other)
        assertEquals(listOf("detachViews"), port.commands())
        assertEquals(1, port.opens.size)
    }

    @Nested
    inner class CreationFailure {
        init {
            factory = { throw VlcUnavailableException(UnsatisfiedLinkError("no libvlc.so")) }
        }

        @Test
        fun `AC-8_1c a failing factory in prepare is posted, once, and again on every later prepare`() {
            vlc.setMediaItem(spec(), 0)
            vlc.prepare()
            assertEquals(emptyList<String>(), events, "nothing inline")
            drain()
            assertEquals(failurePair, events)
            assertEquals(1, logs.size)
            events.clear()
            vlc.prepare()
            assertEquals(1, factoryCalls)
            assertEquals(emptyList<String>(), events)
            assertEquals(1, posted.size)
            drain()
            assertEquals(failurePair, events)
            assertEquals(1, logs.size)
            assertEquals(PlayerState.IDLE, vlc.state)
        }

        @Test
        fun `AC-8_1c a failing factory at the first attach is posted too`() {
            vlc.attach(layout)
            assertEquals(emptyList<String>(), events)
            drain()
            assertEquals(failurePair, events)
        }

        @Test
        fun `AC-8_1c the failed controller ignores every command and a release drops the queued pair`() {
            vlc.setMediaItem(spec(), 0)
            vlc.prepare()
            drain()
            events.clear()
            vlc.play()
            vlc.pause()
            vlc.seekTo(5_000)
            vlc.attach(layout)
            vlc.detach(layout)
            vlc.stop()
            assertEquals(1, factoryCalls)
            assertTrue(posted.isEmpty())
            vlc.prepare()
            vlc.release()
            drain()
            assertEquals(emptyList<String>(), events)
        }
    }

    @Test
    fun `AC-8_1d background and return, attach first`() {
        playing(startMs = 100_000)
        port.clear()
        vlc.detach(layout)
        vlc.pause()
        vlc.stop()
        vlc.attach(layout)
        vlc.setMediaItem(spec(), 620_000)
        vlc.prepare()
        vlc.seekTo(620_000)
        vlc.pause()
        port.emit(VlcEvent.Playing)
        assertBackgroundReturn()
    }

    @Test
    fun `AC-8_1d background and return, attach last`() {
        playing(startMs = 100_000)
        port.clear()
        vlc.detach(layout)
        vlc.pause()
        vlc.stop()
        vlc.setMediaItem(spec(), 620_000)
        vlc.prepare()
        vlc.seekTo(620_000)
        vlc.pause()
        vlc.attach(layout)
        port.emit(VlcEvent.Playing)
        assertBackgroundReturn()
    }

    private fun assertBackgroundReturn() {
        val commands = port.commands()
        val detach = commands.indexOf("detachViews")
        val attach = commands.indexOf("attachViews layout")
        assertTrue(detach in 0 until attach, "$commands")
        assertEquals(1, commands.count { it == "open" })
        assertTrue(attach < commands.indexOf("open"), "$commands")
        val reopen = port.opens.last()
        assertTrue(":start-time=620.000" in reopen.options, "${reopen.options}")
        assertTrue(":start-paused" in reopen.options, "paused at open: no burst of audio")
        assertEquals("pause", commands.last(), "the first Playing pauses: $commands")
        assertFalse(commands.any { it.startsWith("setTime") }, "the start-time already is the position")
        assertFalse(vlc.isPlaying)
    }

    @Test
    fun `AC-8_2 pause before prepare - the first Playing pauses, never reports playing`() {
        vlc.attach(layout)
        vlc.setMediaItem(spec(), 0)
        vlc.pause()
        vlc.prepare()
        assertTrue(":start-paused" in port.opens.single().options)
        port.clear()
        port.emit(VlcEvent.Playing)
        drain()
        assertEquals(listOf("pause"), port.commands())
        assertFalse(vlc.isPlaying)
        assertEquals(PlayerState.READY, vlc.state)
        assertFalse("playing true" in events, "$events")
    }

    @Test
    fun `a playing open is not start-paused, and a start-paused input is resumed when play came meanwhile`() {
        vlc.attach(layout)
        vlc.setMediaItem(spec(), 0)
        vlc.play()
        vlc.prepare()
        assertFalse(":start-paused" in port.opens.single().options)
        // A paused open, then play() before libVLC applied the start pause: the first Paused resumes.
        vlc.pause()
        vlc.setMediaItem(spec(), 0)
        vlc.prepare()
        assertTrue(":start-paused" in port.opens.last().options)
        vlc.play()
        port.clear()
        port.emit(VlcEvent.Paused)
        assertEquals(listOf("play"), port.commands())
    }

    @Test
    fun `AC-8_3 recover reopens with the current token and seeks only when the target is more than 1 s off`() {
        playing(startMs = 600_000)
        port.emit(VlcEvent.TimeChanged(600_000))
        port.emit(VlcEvent.EncounteredError)
        token = "tok2"
        port.clear()
        vlc.prepare()
        vlc.seekTo(612_000)
        vlc.play()
        assertEquals("http://s/api/media-files/3/stream?token=tok2", port.opens.last().uri)
        assertTrue(":start-time=600.000" in port.opens.last().options)
        assertEquals(612_000, vlc.positionMs, "the seek bar doesn't jump back")
        port.emit(VlcEvent.Playing)
        assertTrue("setTime 612000" in port.commands(), "${port.commands()}")

        // Within 1 s of the start-time: no setTime.
        port.emit(VlcEvent.EncounteredError)
        port.clear()
        vlc.prepare()
        vlc.seekTo(612_500)
        vlc.play()
        port.emit(VlcEvent.Playing)
        assertFalse(port.commands().any { it.startsWith("setTime") }, "${port.commands()}")
    }

    @Test
    fun `AC-8_4 EncounteredError reports IDLE then the VLC failure, and no log carries the token`() {
        playing()
        events.clear()
        port.emit(VlcEvent.EncounteredError)
        assertEquals(listOf("state IDLE", "failure $vlcFailure"), events.filter { it.startsWith("state") || it.startsWith("failure") })
        assertEquals(PlayerState.IDLE, vlc.state)
        assertFalse(vlc.isPlaying)
        assertTrue(logs.none { "tok" in it }, "$logs")
    }

    @Test
    fun `AC-8_5 the audio wish is applied when the tracks arrive and the check is reported once`() {
        vlc.attach(layout)
        vlc.setMediaItem(spec(), 0)
        vlc.selectAudio(2)
        vlc.prepare()
        vlc.play()
        port.audioIds = listOf(10, 11)
        port.selectedAudio = 10
        port.emit(VlcEvent.TracksChanged)
        assertTrue("setAudioTrack 11" in port.commands(), "${port.commands()}")
        port.emit(VlcEvent.Playing)
        assertEquals(1, events.count { it.startsWith("tracks") })
        assertEquals("tracks ${TrackCheckInput(true, true, true, true, true, true)}", events.single { it.startsWith("tracks") })
        vlc.pause()
        port.emit(VlcEvent.Paused)
        vlc.play()
        port.emit(VlcEvent.Playing)
        assertEquals(1, events.count { it.startsWith("tracks") })
        assertEquals(1, port.commands().count { it.startsWith("setAudioTrack") })
    }

    @Test
    fun `AC-8_6 subtitles - Off disables the SPU, an image track gets its SPU id`() {
        playing()
        port.spuIds = listOf(3, 4, 5)
        port.selectedSpu = 3
        vlc.selectSubtitle(null)
        assertTrue("setSpuTrack -1" in port.commands(), "${port.commands()}")
        port.clear()
        vlc.selectSubtitle("s2")
        assertEquals(listOf("setSpuTrack 4"), port.commands().filter { it.startsWith("setSpuTrack") })
        // libVLC auto-selects another track later: the wish is applied again on the next track change.
        port.selectedSpu = 3
        port.clear()
        port.emit(VlcEvent.TracksChanged)
        assertEquals(listOf("setSpuTrack 4"), port.commands().filter { it.startsWith("setSpuTrack") })
    }

    @Test
    fun `AC-8_7 release - getters read the cache, IDLE and not playing, no port call`() {
        playing(startMs = 5_000)
        port.emit(VlcEvent.TimeChanged(7_000))
        assertTrue(vlc.isPlaying)
        vlc.release()
        assertEquals(listOf("setEventListener null", "detachViews", "release"), port.commands().takeLast(3))
        port.clear()
        assertEquals(7_000, vlc.positionMs)
        assertFalse(vlc.isPlaying)
        assertEquals(PlayerState.IDLE, vlc.state)
        assertEquals(7_000, vlc.bufferedPositionMs)
        vlc.play()
        vlc.seekTo(1)
        vlc.attach(other)
        vlc.prepare()
        drain()
        assertEquals(emptyList<String>(), port.calls)
    }

    @Test
    fun `AC-8_8 the length is the duration and an end near it is ENDED`() {
        playing()
        events.clear()
        port.emit(VlcEvent.LengthChanged(5_400_000))
        assertEquals(listOf("duration 5400000"), events)
        assertEquals(5_400_000, vlc.durationMs)
        port.emit(VlcEvent.TimeChanged(5_390_000))
        port.emit(VlcEvent.EndReached)
        assertTrue("state ENDED" in events, "$events")
        assertTrue(events.none { it.startsWith("failure") })
        assertEquals(PlayerState.ENDED, vlc.state)
        assertFalse(vlc.isPlaying)
    }

    @Test
    fun `AC-8_9 an early end is a dropped stream, an end with no known length is ENDED`() {
        playing()
        port.emit(VlcEvent.LengthChanged(5_400_000))
        port.emit(VlcEvent.TimeChanged(1_200_000))
        events.clear()
        port.emit(VlcEvent.EndReached)
        assertEquals(listOf("state IDLE", "failure $vlcFailure"), events.filter { it.startsWith("state") || it.startsWith("failure") })
        assertTrue(logs.any { "early end at 1200 of 5400" in it }, "$logs")

        val fresh = FakeVlcPort()
        val unknownLength = VlcPlayerController({ fresh }, { "tok" }, post = { posted += it })
        val seen = mutableListOf<PlayerState>()
        unknownLength.setListener(object : PlayerController.Listener by listener {
            override fun onStateChanged(state: PlayerState) {
                seen += state
            }
        })
        unknownLength.attach(layout)
        unknownLength.setMediaItem(spec(), 0)
        unknownLength.prepare()
        unknownLength.play()
        fresh.emit(VlcEvent.Playing)
        fresh.emit(VlcEvent.TimeChanged(1_200_000))
        fresh.emit(VlcEvent.EndReached)
        assertEquals(PlayerState.ENDED, seen.last())
    }

    @Nested
    inner class ReviewFixes {
        @Test
        fun `events of a replaced media are dropped after a reopen`() {
            playing(startMs = 100_000)
            port.emit(VlcEvent.TimeChanged(150_000))
            val old = port.listener!!
            vlc.setMediaItem(spec(), 150_000) // the subtitle rebuild reopens over the live media
            vlc.prepare()
            events.clear()
            old(VlcEvent.TimeChanged(0))
            old(VlcEvent.Stopped)
            old(VlcEvent.EncounteredError)
            drain()
            assertEquals(150_000, vlc.positionMs)
            assertTrue(events.none { it.startsWith("failure") || it == "state IDLE" }, "$events")
            assertEquals(PlayerState.BUFFERING, vlc.state)
            port.emit(VlcEvent.TimeChanged(151_000))
            assertEquals(151_000, vlc.positionMs, "the new media's events are applied")
        }

        @Test
        fun `a stopped media's late events are dropped`() {
            playing()
            val old = port.listener!!
            vlc.stop()
            old(VlcEvent.Playing)
            assertEquals(PlayerState.IDLE, vlc.state)
            assertFalse(vlc.isPlaying)
        }

        @Test
        fun `seekTo on a non-seekable media is ignored, the position is kept`() {
            playing()
            port.emit(VlcEvent.TimeChanged(30_000))
            port.seekable = false
            port.clear()
            vlc.seekTo(90_000)
            assertFalse(port.commands().any { it.startsWith("setTime") })
            assertEquals(30_000, vlc.positionMs)
            port.seekable = true
            vlc.seekTo(90_000)
            assertTrue("setTime 90000" in port.commands())
            assertEquals(90_000, vlc.positionMs)
        }

        @Test
        fun `seekTo after the end reopens at the target`() {
            playing()
            port.emit(VlcEvent.TimeChanged(5_399_000))
            port.emit(VlcEvent.EndReached)
            port.clear()
            vlc.seekTo(4_000_000)
            assertEquals(1, port.commands().count { it == "open" })
            assertTrue(":start-time=4000.000" in port.opens.last().options)
            assertEquals(4_000_000, vlc.positionMs)
            assertEquals(PlayerState.BUFFERING, vlc.state)
        }

        @Test
        fun `play after the end does not restart from the beginning`() {
            playing()
            port.emit(VlcEvent.EndReached)
            port.clear()
            vlc.play()
            assertEquals(emptyList<String>(), port.commands())
        }

        @Test
        fun `a seek before the first Playing is pending, applied at it, then seeks directly`() {
            vlc.attach(layout)
            vlc.setMediaItem(spec(), 0)
            vlc.prepare()
            vlc.play()
            port.clear()
            vlc.seekTo(300_000)
            port.emit(VlcEvent.TimeChanged(0)) // the start's time doesn't move the target back
            assertEquals(300_000, vlc.positionMs)
            assertFalse(port.commands().any { it.startsWith("setTime") })
            port.emit(VlcEvent.Playing)
            assertTrue("setTime 300000" in port.commands())
            port.clear()
            vlc.seekTo(400_000)
            assertEquals(listOf("setTime 400000"), port.commands().filter { it.startsWith("setTime") })
        }

        @Test
        fun `isPlaying is false while buffering, like ExoPlayer`() {
            playing()
            assertTrue(vlc.isPlaying)
            events.clear()
            port.emit(VlcEvent.Buffering(40f))
            assertEquals(PlayerState.BUFFERING, vlc.state)
            assertFalse(vlc.isPlaying)
            assertEquals(listOf("state BUFFERING", "playing false"), events)
            port.emit(VlcEvent.Buffering(100f))
            assertTrue(vlc.isPlaying)
            assertEquals(listOf("state BUFFERING", "playing false", "state READY", "playing true"), events)
        }

        @Test
        fun `command-caused changes are posted, never inline, and the getters change at once`() {
            playing()
            events.clear()
            vlc.pause()
            assertFalse(vlc.isPlaying)
            assertEquals(emptyList<String>(), events)
            drain()
            assertEquals(listOf("playing false"), events)
            events.clear()
            vlc.stop()
            assertEquals(PlayerState.IDLE, vlc.state)
            assertEquals(emptyList<String>(), events)
            drain()
            assertEquals(listOf("state IDLE"), events)
            events.clear()
            vlc.prepare()
            assertEquals(PlayerState.BUFFERING, vlc.state)
            assertEquals(emptyList<String>(), events)
            vlc.release()
            drain()
            assertEquals(emptyList<String>(), events, "dropped after release")
        }

        @Test
        fun `a posted state that an event already superseded is not reported stale`() {
            vlc.attach(layout)
            vlc.setMediaItem(spec(), 0)
            vlc.play()
            vlc.prepare() // BUFFERING, posted
            port.emit(VlcEvent.Playing) // READY, inline
            drain()
            assertEquals(listOf("state READY", "playing true"), events.filterNot { it.startsWith("tracks") })
            assertNull(events.firstOrNull { it == "state BUFFERING" })
        }
    }
}
