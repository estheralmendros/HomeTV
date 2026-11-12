package com.hometv.tv.core.playback

import com.hometv.tv.core.api.SubtitleStyle

// The profile's subtitle style (`specs/subtitles-improvements/prd.md` FR-6): wire values and
// renderer values only; `app` names every value through strings.xml.

enum class SubtitleSize(val wire: String, val factor: Float) {
    SMALL("small", 0.8f),
    MEDIUM("medium", 1f),
    LARGE("large", 1.3f),
}

enum class SubtitleColor(val wire: String, val rgb: Int) {
    WHITE("white", 0xFFFFFF),
    YELLOW("yellow", 0xFFFF00),
    GREEN("green", 0x00FF00),
    CYAN("cyan", 0x00FFFF),
    MAGENTA("magenta", 0xFF00FF),
    ORANGE("orange", 0xFFA500),
}

enum class SubtitleEdge(val wire: String) {
    OUTLINE("outline"),
    SHADOW("shadow"),
    NONE("none"),
}

enum class SubtitleEdgeColor(val wire: String, val rgb: Int) {
    BLACK("black", 0x000000),
    DARK_GRAY("dark_gray", 0x404040),
    WHITE("white", 0xFFFFFF),
}

/** [bottomFraction]: the subtitles' distance above the bottom, as a fraction of the picture height. */
enum class SubtitlePosition(val wire: String, val bottomFraction: Float) {
    LOW("low", 0.08f),
    MIDDLE("middle", 0.14f),
    HIGH("high", 0.20f),
}

data class SubtitleLook(
    val size: SubtitleSize,
    val color: SubtitleColor,
    val edge: SubtitleEdge,
    val edgeColor: SubtitleEdgeColor,
    val position: SubtitlePosition,
) {
    /** The Clash rule: white text with a white edge (and an edge) is drawn with black. */
    val effectiveEdgeColor: SubtitleEdgeColor
        get() = if (isClash) SubtitleEdgeColor.BLACK else edgeColor

    internal val isClash: Boolean
        get() = color == SubtitleColor.WHITE && edgeColor == SubtitleEdgeColor.WHITE && edge != SubtitleEdge.NONE

    fun toWire(): SubtitleStyle = SubtitleStyle(size.wire, color.wire, edge.wire, edgeColor.wire, position.wire)

    companion object {
        val DEFAULT = SubtitleLook(SubtitleSize.MEDIUM, SubtitleColor.WHITE, SubtitleEdge.OUTLINE, SubtitleEdgeColor.BLACK, SubtitlePosition.LOW)

        /** An unknown wire value (a newer server) → that row's default. */
        fun of(style: SubtitleStyle): SubtitleLook = SubtitleLook(
            size = SubtitleSize.entries.firstOrNull { it.wire == style.size } ?: DEFAULT.size,
            color = SubtitleColor.entries.firstOrNull { it.wire == style.color } ?: DEFAULT.color,
            edge = SubtitleEdge.entries.firstOrNull { it.wire == style.edge } ?: DEFAULT.edge,
            edgeColor = SubtitleEdgeColor.entries.firstOrNull { it.wire == style.edge_color } ?: DEFAULT.edgeColor,
            position = SubtitlePosition.entries.firstOrNull { it.wire == style.position } ?: DEFAULT.position,
        )
    }
}

/** The five rows of the style editor, top to bottom. */
enum class StyleRow { SIZE, COLOR, EDGE, EDGE_COLOR, POSITION }

/** The editor's rules (the TV `SubtitleStyleDialog`): disabled rows and values, Left/Right steps. */
object SubtitleStyleEditor {
    /** The row's values in display order. */
    fun values(row: StyleRow): List<Enum<*>> = when (row) {
        StyleRow.SIZE -> SubtitleSize.entries
        StyleRow.COLOR -> SubtitleColor.entries
        StyleRow.EDGE -> SubtitleEdge.entries
        StyleRow.EDGE_COLOR -> SubtitleEdgeColor.entries
        StyleRow.POSITION -> SubtitlePosition.entries
    }

    fun value(look: SubtitleLook, row: StyleRow): Enum<*> = when (row) {
        StyleRow.SIZE -> look.size
        StyleRow.COLOR -> look.color
        StyleRow.EDGE -> look.edge
        StyleRow.EDGE_COLOR -> look.edgeColor
        StyleRow.POSITION -> look.position
    }

    /** "Color del contorno" means nothing with "Sin contorno". */
    fun isEnabled(look: SubtitleLook, row: StyleRow): Boolean = row != StyleRow.EDGE_COLOR || look.edge != SubtitleEdge.NONE

    /** Clash values (prd.md Clash rule): white edge colour with white text; white text with a white edge colour and an edge. */
    fun isDisabled(look: SubtitleLook, row: StyleRow, value: Enum<*>): Boolean = when (row) {
        StyleRow.COLOR -> value == SubtitleColor.WHITE && look.edgeColor == SubtitleEdgeColor.WHITE && look.edge != SubtitleEdge.NONE
        StyleRow.EDGE_COLOR -> value == SubtitleEdgeColor.WHITE && look.color == SubtitleColor.WHITE
        else -> false
    }

    /**
     * The next enabled value in that direction (clash values skipped); the look unchanged at the ends or
     * on a disabled row. Turning an edge on with white text and a white stored edge colour sets black.
     */
    fun step(look: SubtitleLook, row: StyleRow, forward: Boolean): SubtitleLook {
        if (!isEnabled(look, row)) return look
        val values = values(row)
        val delta = if (forward) 1 else -1
        var i = values.indexOf(value(look, row)) + delta
        while (i in values.indices) {
            if (!isDisabled(look, row, values[i])) return withValueAt(look, row, i)
            i += delta
        }
        return look
    }

    fun canStep(look: SubtitleLook, row: StyleRow, forward: Boolean): Boolean = step(look, row, forward) != look

    /** A stored clash opens with a black edge colour, so the editor never starts on a disabled value. */
    fun normalized(look: SubtitleLook): SubtitleLook = if (look.isClash) look.copy(edgeColor = SubtitleEdgeColor.BLACK) else look

    /** [look] with the row's value at [index] of [values] (typed per row: no cast). */
    private fun withValueAt(look: SubtitleLook, row: StyleRow, index: Int): SubtitleLook = when (row) {
        StyleRow.SIZE -> look.copy(size = SubtitleSize.entries[index])
        StyleRow.COLOR -> look.copy(color = SubtitleColor.entries[index])
        StyleRow.EDGE -> normalized(look.copy(edge = SubtitleEdge.entries[index]))
        StyleRow.EDGE_COLOR -> look.copy(edgeColor = SubtitleEdgeColor.entries[index])
        StyleRow.POSITION -> look.copy(position = SubtitlePosition.entries[index])
    }
}
