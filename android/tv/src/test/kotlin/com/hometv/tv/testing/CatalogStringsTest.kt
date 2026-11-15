package com.hometv.tv.testing

import com.hometv.tv.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.hometv.tv.shared.R as SharedR

/** [CatalogStrings] reads the catalogs of every module in the app's test classpath (shared first, tv last). */
class CatalogStringsTest {
    @Test
    fun `a shared id resolves`() {
        assertEquals("Reintentar", CatalogStrings.string(SharedR.string.action_retry))
        assertEquals("action_retry", CatalogStrings.nameOf(SharedR.string.action_retry))
    }

    @Test
    fun `a shared plurals id resolves`() {
        assertEquals("1 episodio", CatalogStrings.plural(SharedR.plurals.episode_count, 1, "1"))
    }

    @Test
    fun `a tv id resolves`() {
        assertEquals("Conectar", CatalogStrings.string(R.string.setup_connect))
    }

    @Test
    fun `an unknown id fails with Unknown string id`() {
        val e = assertThrows(IllegalStateException::class.java) { CatalogStrings.nameOf(-1) }
        assertTrue(e.message.orEmpty().contains("Unknown string id"), e.message)
    }

    @Test
    fun `a missing property fails naming it`() {
        val e = assertThrows(IllegalStateException::class.java) { CatalogStrings.requiredProperty("hometv.nope") { null } }
        assertTrue(e.message.orEmpty().contains("hometv.nope"), e.message)
    }
}
