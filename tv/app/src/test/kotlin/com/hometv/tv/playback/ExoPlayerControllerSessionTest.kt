package com.hometv.tv.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Headset and Bluetooth play/pause: the Player's Media3 `MediaSession` lives while a `PlayerView`
 * shows the player (Robolectric, a real ExoPlayer on the main looper). A session id still registered in the
 * process makes `MediaSession.Builder.build()` throw, so building a probe session with the same id proves
 * the old one was released.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExoPlayerControllerSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val exo = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()

    /** Calls on the ExoPlayer, in order, with the controller's session id at the time of each `release`. */
    private val calls = mutableListOf<String>()
    private lateinit var controller: ExoPlayerController

    /** The ExoPlayer seen by the controller: delegates to [exo] and logs `release()`. */
    private val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
        if (method.name == "release") calls += "player.release (session ${controller.sessionId ?: "none"})"
        try {
            method.invoke(exo, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    } as ExoPlayer

    private val view = PlayerView(context)

    init {
        controller = ExoPlayerController(player)
    }

    @After
    fun tearDown() {
        runCatching { exo.release() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** True when no live session holds [id] (a probe session with that id can be built). */
    private fun released(id: String): Boolean {
        val probe = runCatching { MediaSession.Builder(context, exo).setId(id).build() }.getOrNull() ?: return false
        probe.release()
        return true
    }

    @Test
    fun attachCreatesOneSessionAndASecondAttachIsANoOp() {
        assertNull(controller.sessionId)
        controller.attach(view)
        val id = controller.sessionId
        assertNotNull(id)
        assertSame("the view shows the player", player, view.player)
        controller.attach(view)
        assertEquals("a second attach keeps the session", id, controller.sessionId)
        assertEquals("the session is live", false, released(id!!))
    }

    @Test
    fun detachReleasesTheSessionAndAReattachGetsANewId() {
        controller.attach(view)
        val first = controller.sessionId!!
        controller.detach(view)
        assertNull(controller.sessionId)
        assertNull(view.player)
        assertEquals("detach released the session", true, released(first))
        controller.attach(view)
        val second = controller.sessionId
        assertNotNull(second)
        assertNotEquals(first, second)
    }

    @Test
    fun releaseReleasesTheSessionBeforeThePlayer() {
        controller.attach(view)
        val id = controller.sessionId!!
        controller.release()
        assertEquals(listOf("player.release (session none)"), calls)
        assertEquals(true, released(id))
    }
}
