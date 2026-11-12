package com.hometv.tv.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.playback.SubtitlePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * prd.md FR-6 "Posición" on ExoPlayer: Media3's WebVTT parser leaves `line:auto` cues at `line = -1` (the last
 * line), which `SubtitleView` draws at the very bottom whatever `setBottomPaddingFraction` says; placed cues
 * follow the padding (the look's position).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExoCuePlacementTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The cues of a server-made WebVTT (no cue settings), parsed by Media3 as the player does. */
    private fun vttCues(settings: String = ""): List<Cue> {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:03.000$settings\nLa fastidiamos.\n-Vaya.\n".toByteArray()
        val out = mutableListOf<Cue>()
        WebvttParser().parse(vtt, SubtitleParser.OutputOptions.allCues()) { out += it.cues }
        return out
    }

    /** The lowest pixel row with ink once [cues] are drawn by a 1920×1080 `SubtitleView` at [padding]. */
    private fun lowestInkRow(cues: List<Cue>, padding: Float): Int {
        val view = SubtitleView(context)
        view.setCues(cues)
        view.setBottomPaddingFraction(padding)
        view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1920, 1080)
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        for (y in 1079 downTo 0) for (x in 0 until 1920) if (bitmap.getPixel(x, y) ushr 24 != 0) return y
        return -1
    }

    @Test
    fun `an auto WebVTT cue is drawn at each position's height above the bottom`() {
        val placed = ExoCuePlacement.place(vttCues())
        val rows = SubtitlePosition.entries.map { lowestInkRow(placed, it.bottomFraction) }
        SubtitlePosition.entries.zip(rows).forEach { (position, row) ->
            // The text's bottom sits `bottomFraction` of the height above the bottom (± a descender/outline).
            val expected = 1080 * (1 - position.bottomFraction)
            assertTrue("$position: lowest ink row $row, expected ≈ $expected (rows $rows)", row in (expected - 30).toInt()..(expected + 4).toInt())
        }
    }

    @Test
    fun `cues the file positions and bitmap cues are left as they are`() {
        val positioned = vttCues(" line:0")
        assertSame(positioned, ExoCuePlacement.place(positioned))
        val bitmap = Cue.Builder().setBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)).setLine(-1f, Cue.LINE_TYPE_NUMBER).build()
        assertSame(bitmap, ExoCuePlacement.place(listOf(bitmap)).single())
    }

    @Test
    fun `the view's player hands placed cues to its listeners and in currentCues`() {
        val inner = CuesPlayer()
        val player = CuePlacementPlayer(inner)
        val seen = mutableListOf<List<Cue>>()
        var playingEvents = 0
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                seen += cueGroup.cues
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingEvents++
            }
        }
        player.addListener(listener)
        inner.emit(vttCues())
        assertEquals(Cue.DIMEN_UNSET, seen.single().single().line)
        assertEquals(Cue.DIMEN_UNSET, player.currentCues.cues.single().line)
        assertEquals("other events still reach the listener", 1, playingEvents)
        player.removeListener(listener)
        inner.emit(vttCues(" line:0"))
        assertEquals("no event after removeListener", 1, seen.size)
    }

    @Test
    fun `the Exo controller gives the PlayerView the placing player and takes it back`() {
        val exo = ExoPlayer.Builder(context).build()
        val controller = ExoPlayerController(exo)
        val view = PlayerView(context)
        controller.attach(view)
        val attached = view.player
        assertTrue("got $attached", attached is CuePlacementPlayer && attached.wrappedPlayer === exo)
        controller.attach(view)
        assertSame("re-attaching keeps the same player", attached, view.player)
        controller.detach(view)
        assertNull(view.player)
        exo.release()
    }

    /** A player whose cues the test sets (toggling play/pause each time, so a non-cue event fires too). */
    private class CuesPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var cues = CueGroup.EMPTY_TIME_ZERO
        private var playing = false

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_GET_TEXT, Player.COMMAND_PLAY_PAUSE).build())
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(listOf(MediaItemData.Builder("item").build()))
            .setPlaybackState(Player.STATE_READY)
            .setCurrentCues(cues)
            .build()

        fun emit(next: List<Cue>) {
            currentCues // the state is built lazily: build the previous one first, so the change is an event
            cues = CueGroup(next, 0)
            playing = !playing
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
