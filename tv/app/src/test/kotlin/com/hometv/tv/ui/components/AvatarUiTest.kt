package com.hometv.tv.ui.components

import com.hometv.tv.core.api.Avatar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The one avatar rule (TopNav, profile picker, ratings block, Actividad): first letter, `/api/info` colour. */
class AvatarUiTest {
    private val avatars = listOf(Avatar("avatar-01", "#E50914"), Avatar("avatar-02", "#1DB954"))

    @Test
    fun `initial is the trimmed first letter upper-cased, colour from the avatar list`() {
        assertEquals(AvatarUi("E", "#1DB954"), AvatarUi.of("  esther", "avatar-02", avatars))
        assertEquals(AvatarUi("Á", null), AvatarUi.of("álvaro", "avatar-09", avatars))
        assertEquals(AvatarUi("", null), AvatarUi.of("   ", "avatar-01", null))
        assertEquals("#E50914", AvatarUi.color("avatar-01", avatars))
        assertEquals("K", AvatarUi.initial("kids"))
    }
}
