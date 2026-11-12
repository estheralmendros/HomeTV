package com.hometv.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * Focus scrolling for a viewport that runs to the screen edges (decision C of
 * `specs/tv-improvements/idea.md`: no empty strip under the content): a focused child is brought
 * into the part of the viewport inside the safe area, [topPx] below its top and [bottomPx] above its
 * bottom, with [roomPx] more on each side (a focused card's scale). [base] decides where inside that
 * area (the platform spec: the TV pivot on a television, the minimal scroll elsewhere).
 */
@OptIn(ExperimentalFoundationApi::class)
class SafeAreaBringIntoViewSpec(
    private val topPx: Float,
    private val bottomPx: Float,
    private val roomPx: Float = 0f,
    private val base: BringIntoViewSpec = Minimal,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        base.calculateScrollDistance(offset - topPx - roomPx, size + 2 * roomPx, (containerSize - topPx - bottomPx).coerceAtLeast(0f))

    companion object {
        /** The default spec: the least scroll that shows the child. */
        val Minimal: BringIntoViewSpec = object : BringIntoViewSpec {}
    }
}
