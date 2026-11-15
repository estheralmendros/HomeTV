package com.hometv.tv.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.FakePlayerController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Player screen's Media3 `MediaSession` (prd2 FR-10.2), one per screen, for either engine. A session id still
 * registered in the process makes `MediaSession.Builder.build()` throw, so building a probe session with the same
 * id proves the old one was released.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerMediaSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val player = ControllerSessionPlayer({ FakePlayerController() }, { null }, { false })
    private val sessions = mutableListOf<PlayerMediaSession>()

    @After
    fun tearDown() {
        sessions.forEach { runCatching { it.release() } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** True when no live session holds [id] (a probe session with that id can be built). */
    private fun released(id: String): Boolean {
        val probe = runCatching { MediaSession.Builder(context, player).setId(id).build() }.getOrNull() ?: return false
        probe.release()
        return true
    }

    @Test
    fun twoSessionsGetDistinctIds() {
        val first = PlayerMediaSession(context, player).also(sessions::add)
        val second = PlayerMediaSession(context, ControllerSessionPlayer({ null }, { null }, { false })).also(sessions::add)
        assertNotEquals(first.id, second.id)
        assertEquals("the first session is live", false, released(first.id))
    }

    @Test
    fun releaseReleasesTheSession() {
        val session = PlayerMediaSession(context, player)
        val id = session.id
        assertEquals(false, released(id))
        session.release()
        assertEquals(true, released(id))
    }
}
