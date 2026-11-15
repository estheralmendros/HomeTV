package com.hometv.tv.core.playback

import com.hometv.tv.core.api.SubtitleStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtitleStylesTest {
    private val default = SubtitleLook.DEFAULT

    @Test
    fun `the default look is medium white outline black low and round-trips the wire defaults`() {
        assertEquals(
            SubtitleLook(SubtitleSize.MEDIUM, SubtitleColor.WHITE, SubtitleEdge.OUTLINE, SubtitleEdgeColor.BLACK, SubtitlePosition.LOW),
            default,
        )
        assertEquals(SubtitleStyle(), default.toWire())
        assertEquals(default, SubtitleLook.of(SubtitleStyle()))
    }

    @Test
    fun `of maps every wire value and an unknown value to that row's default`() {
        val look = SubtitleLook.of(SubtitleStyle("large", "orange", "shadow", "dark_gray", "high"))
        assertEquals(SubtitleLook(SubtitleSize.LARGE, SubtitleColor.ORANGE, SubtitleEdge.SHADOW, SubtitleEdgeColor.DARK_GRAY, SubtitlePosition.HIGH), look)
        assertEquals(SubtitleStyle("large", "orange", "shadow", "dark_gray", "high"), look.toWire())
        assertEquals(default, SubtitleLook.of(SubtitleStyle("huge", "red", "glow", "blue", "top")))
        SubtitleSize.entries.forEach { assertEquals(it, SubtitleLook.of(SubtitleStyle(size = it.wire)).size) }
        SubtitleColor.entries.forEach { assertEquals(it, SubtitleLook.of(SubtitleStyle(color = it.wire)).color) }
        SubtitleEdge.entries.forEach { assertEquals(it, SubtitleLook.of(SubtitleStyle(edge = it.wire)).edge) }
        SubtitleEdgeColor.entries.forEach { assertEquals(it, SubtitleLook.of(SubtitleStyle(edge_color = it.wire)).edgeColor) }
        SubtitlePosition.entries.forEach { assertEquals(it, SubtitleLook.of(SubtitleStyle(position = it.wire)).position) }
    }

    @Test
    fun `renderer values`() {
        assertEquals(listOf(0.8f, 1f, 1.3f), SubtitleSize.entries.map { it.factor })
        assertEquals(listOf(0xFFFFFF, 0xFFFF00, 0x00FF00, 0x00FFFF, 0xFF00FF, 0xFFA500), SubtitleColor.entries.map { it.rgb })
        assertEquals(listOf(0x000000, 0x404040, 0xFFFFFF), SubtitleEdgeColor.entries.map { it.rgb })
        assertEquals(listOf(0.08f, 0.14f, 0.20f), SubtitlePosition.entries.map { it.bottomFraction })
    }

    @Test
    fun `a stored clash is drawn with a black edge`() {
        val clash = default.copy(edgeColor = SubtitleEdgeColor.WHITE)
        assertEquals(SubtitleEdgeColor.BLACK, clash.effectiveEdgeColor)
        assertEquals(SubtitleEdgeColor.WHITE, clash.copy(color = SubtitleColor.YELLOW).effectiveEdgeColor)
        assertEquals(SubtitleEdgeColor.WHITE, clash.copy(edge = SubtitleEdge.NONE).effectiveEdgeColor)
        assertEquals(SubtitleEdgeColor.DARK_GRAY, default.copy(edgeColor = SubtitleEdgeColor.DARK_GRAY).effectiveEdgeColor)
    }

    @Test
    fun `edge colour row disabled without an edge, clash values disabled`() {
        assertTrue(SubtitleStyleEditor.isEnabled(default, StyleRow.EDGE_COLOR))
        assertFalse(SubtitleStyleEditor.isEnabled(default.copy(edge = SubtitleEdge.NONE), StyleRow.EDGE_COLOR))
        StyleRow.entries.filter { it != StyleRow.EDGE_COLOR }.forEach { assertTrue(SubtitleStyleEditor.isEnabled(default.copy(edge = SubtitleEdge.NONE), it)) }
        // White text: the white edge colour is disabled.
        assertTrue(SubtitleStyleEditor.isDisabled(default, StyleRow.EDGE_COLOR, SubtitleEdgeColor.WHITE))
        assertFalse(SubtitleStyleEditor.isDisabled(default, StyleRow.EDGE_COLOR, SubtitleEdgeColor.DARK_GRAY))
        // White edge colour with an edge: white text disabled; without an edge nothing clashes.
        val whiteEdge = default.copy(color = SubtitleColor.YELLOW, edgeColor = SubtitleEdgeColor.WHITE)
        assertTrue(SubtitleStyleEditor.isDisabled(whiteEdge, StyleRow.COLOR, SubtitleColor.WHITE))
        assertFalse(SubtitleStyleEditor.isDisabled(whiteEdge.copy(edge = SubtitleEdge.NONE), StyleRow.COLOR, SubtitleColor.WHITE))
        assertFalse(SubtitleStyleEditor.isDisabled(whiteEdge, StyleRow.SIZE, SubtitleSize.LARGE))
    }

    @Test
    fun `step moves one value, stops at the ends`() {
        assertEquals(SubtitleSize.LARGE, SubtitleStyleEditor.step(default, StyleRow.SIZE, forward = true).size)
        val large = default.copy(size = SubtitleSize.LARGE)
        assertEquals(large, SubtitleStyleEditor.step(large, StyleRow.SIZE, forward = true))
        assertEquals(default.copy(size = SubtitleSize.SMALL), SubtitleStyleEditor.step(default, StyleRow.SIZE, forward = false))
        assertEquals(SubtitlePosition.MIDDLE, SubtitleStyleEditor.step(default, StyleRow.POSITION, forward = true).position)
        assertEquals(default, SubtitleStyleEditor.step(default, StyleRow.POSITION, forward = false))
        assertEquals(SubtitleEdge.SHADOW, SubtitleStyleEditor.step(default, StyleRow.EDGE, forward = true).edge)
        assertEquals(SubtitleColor.YELLOW, SubtitleStyleEditor.step(default, StyleRow.COLOR, forward = true).color)
    }

    @Test
    fun `step skips the clash values and stops before a disabled last value`() {
        // White text, outline: Negro → Gris oscuro, then stop (Blanco skipped).
        val darkGray = SubtitleStyleEditor.step(default, StyleRow.EDGE_COLOR, forward = true)
        assertEquals(SubtitleEdgeColor.DARK_GRAY, darkGray.edgeColor)
        assertEquals(darkGray, SubtitleStyleEditor.step(darkGray, StyleRow.EDGE_COLOR, forward = true))
        // Yellow text, white edge: Left from Amarillo skips Blanco and stops.
        val yellowWhiteEdge = default.copy(color = SubtitleColor.YELLOW, edgeColor = SubtitleEdgeColor.WHITE)
        assertEquals(yellowWhiteEdge, SubtitleStyleEditor.step(yellowWhiteEdge, StyleRow.COLOR, forward = false))
        // A disabled row never changes.
        val none = default.copy(edge = SubtitleEdge.NONE)
        assertEquals(none, SubtitleStyleEditor.step(none, StyleRow.EDGE_COLOR, forward = true))
    }

    @Test
    fun `turning an edge on again with white text and a white stored edge colour sets black`() {
        val stored = default.copy(edge = SubtitleEdge.NONE, edgeColor = SubtitleEdgeColor.WHITE)
        // Edge order: outline, shadow, none; Left from none → shadow.
        val on = SubtitleStyleEditor.step(stored, StyleRow.EDGE, forward = false)
        assertEquals(SubtitleEdge.SHADOW, on.edge)
        assertEquals(SubtitleEdgeColor.BLACK, on.edgeColor)
        // Not white text: the edge colour is kept.
        val yellow = stored.copy(color = SubtitleColor.YELLOW)
        assertEquals(SubtitleEdgeColor.WHITE, SubtitleStyleEditor.step(yellow, StyleRow.EDGE, forward = false).edgeColor)
    }

    @Test
    fun `normalized fixes a stored clash`() {
        val clash = default.copy(edgeColor = SubtitleEdgeColor.WHITE)
        assertEquals(default, SubtitleStyleEditor.normalized(clash))
        assertEquals(default, SubtitleStyleEditor.normalized(default))
        val noEdge = clash.copy(edge = SubtitleEdge.NONE)
        assertEquals(noEdge, SubtitleStyleEditor.normalized(noEdge))
    }

    @Test
    fun `value lists per row and the end flags`() {
        assertEquals(SubtitleSize.entries, SubtitleStyleEditor.values(StyleRow.SIZE))
        assertEquals(SubtitleSize.MEDIUM, SubtitleStyleEditor.value(default, StyleRow.SIZE))
        assertEquals(SubtitleEdgeColor.BLACK, SubtitleStyleEditor.value(default, StyleRow.EDGE_COLOR))
        assertFalse(SubtitleStyleEditor.canStep(default, StyleRow.POSITION, forward = false))
        assertTrue(SubtitleStyleEditor.canStep(default, StyleRow.POSITION, forward = true))
        val darkGray = default.copy(edgeColor = SubtitleEdgeColor.DARK_GRAY)
        assertFalse(SubtitleStyleEditor.canStep(darkGray, StyleRow.EDGE_COLOR, forward = true))
    }
}
