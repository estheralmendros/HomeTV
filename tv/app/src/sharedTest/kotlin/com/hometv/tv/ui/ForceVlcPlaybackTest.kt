package com.hometv.tv.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.PlaybackResponse
import com.hometv.tv.core.playback.F
import com.hometv.tv.testing.UiTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ajustes' "Usar siempre VLC (experimental)" sends the next film to libVLC; switched off, ExoPlayer again (prd3 AC-13.4). */
@RunWith(AndroidJUnit4::class)
class ForceVlcPlaybackTest : UiTestBase() {
    override val vlcAvailable: Boolean = true

    /** From a Home card: Up → Inicio, five tabs right → Ajustes, OK, two rows down → the switch, OK, Back → the Ajustes tab. */
    private fun toggleForceVlcInSettings() {
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
        press(Key.DirectionRight, times = 5)
        assertFocused("tab_SETTINGS")
        press(Key.DirectionCenter)
        assertFocused("settings_change_server")
        press(Key.DirectionDown, times = 2)
        assertFocused("settings_force_vlc")
        press(Key.DirectionCenter)
        back()
        assertFocused("tab_SETTINGS")
    }

    /**
     * From the Ajustes tab: Down lands on the "Seguir viendo" card under it (the episode), one Left → Dune (an
     * H.264 MKV, the first card), OK → Player.
     */
    private fun playDune() {
        press(Key.DirectionDown)
        assertFocused("card_continue:episode:203")
        press(Key.DirectionLeft)
        assertFocused("card_continue:film:12")
        press(Key.DirectionCenter)
        node("player").assertIsDisplayed()
    }

    /** One Back leaves the Player, focus back on Dune's card. */
    private fun leavePlayer() {
        back()
        assertTrue(absent("player"))
        assertFocused("card_continue:film:12")
    }

    @Test
    fun theSwitchSendsTheNextFilmToLibVlcAndOffBackToExoPlayer() {
        useLibrary()
        api.onMe = { com.hometv.tv.core.api.Me(5, "Esther", "avatar-05", is_admin = true) }
        api.onFilm = { id -> FilmDetail(id = id, title = "Dune", media_files = listOf(F.file(1))) }
        api.onPlayback = { _, _ -> PlaybackResponse("direct", url = "/api/media-files/1/stream", audio_stream_index = 1, duration_seconds = 9360.0) }
        launch()

        toggleForceVlcInSettings()
        assertTrue(enginePrefs.forceVlc.value)
        playDune()
        onVlcPlayer { ready() }

        assertFalse("VLC caps: no HLS", api.playbackBodies.single().second.client_caps.hls)
        assertTrue(vlcPlayer.log.any { it.startsWith("setMediaItem") })
        assertTrue("play" in vlcPlayer.log)
        assertTrue("the EXO fake receives nothing", player.log.isEmpty())
        assertEquals(0, playerBuilds)

        leavePlayer()
        toggleForceVlcInSettings()
        assertFalse(enginePrefs.forceVlc.value)
        val vlcCalls = vlcPlayer.log.size
        playDune()
        onPlayer { ready() }

        assertTrue("ExoPlayer caps ask for HLS", api.playbackBodies.last().second.client_caps.hls)
        assertTrue(player.log.any { it.startsWith("setMediaItem") })
        assertTrue("play" in player.log)
        assertEquals("the VLC fake receives nothing new", vlcCalls, vlcPlayer.log.size)
    }
}
