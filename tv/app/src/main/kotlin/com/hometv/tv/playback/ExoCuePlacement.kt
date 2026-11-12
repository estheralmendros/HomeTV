package com.hometv.tv.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * "Posición" on ExoPlayer (`specs/subtitles-improvements/prd.md` FR-6). Media3's WebVTT parser turns a cue
 * left at `line:auto` (every server-made WebVTT cue) into `line = -1` (`LINE_TYPE_NUMBER`: the last line), and
 * `SubtitleView` applies `setBottomPaddingFraction` only to cues whose line is unset: such a cue is drawn at the
 * very bottom at every position. [place] unsets that line, so the cue sits the look's fraction above the bottom.
 * Cues the file positions (any other line) and bitmap cues keep their own placement.
 */
@UnstableApi
object ExoCuePlacement {
    fun place(cues: List<Cue>): List<Cue> =
        if (cues.none(::isBottomLine)) cues else cues.map { if (isBottomLine(it)) it.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).build() else it }

    fun place(group: CueGroup): CueGroup {
        val placed = place(group.cues)
        return if (placed === group.cues) group else CueGroup(placed, group.presentationTimeUs)
    }

    private fun isBottomLine(cue: Cue) = cue.bitmap == null && cue.lineType == Cue.LINE_TYPE_NUMBER && cue.line == -1f
}

/**
 * The player `PlayerView` sees (`ExoPlayerController.attach`): ExoPlayer with its cues passed through
 * [ExoCuePlacement] (listeners' `onCues` and `currentCues`), everything else forwarded.
 */
@UnstableApi
class CuePlacementPlayer(player: Player) : ForwardingPlayer(player) {
    override fun getCurrentCues(): CueGroup = ExoCuePlacement.place(super.getCurrentCues())

    override fun addListener(listener: Player.Listener) = super.addListener(placing(listener))

    override fun removeListener(listener: Player.Listener) = super.removeListener(placing(listener))

    /**
     * [listener] with its `onCues` placed. Every `Player.Listener` method is a Java default method, which Kotlin's
     * `by` delegation does not forward, so a proxy forwards each call (also methods a later Media3 adds). Two
     * proxies of the same [listener] are equal, so [removeListener] finds the one [addListener] registered.
     */
    private fun placing(listener: Player.Listener): Player.Listener =
        Proxy.newProxyInstance(Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java), PlacingHandler(listener)) as Player.Listener

    private class PlacingHandler(val listener: Player.Listener) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            return when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> arguments[0]?.let(::handlerOf)?.listener == listener
                    "hashCode" -> listener.hashCode()
                    else -> "CuePlacement($listener)"
                }
                // Matched by type, not by name (R8 renames library methods): the only one-argument listener
                // methods taking a CueGroup or a List are the two `onCues`.
                method.parameterTypes.singleOrNull().let { it == CueGroup::class.java || it == List::class.java } -> forward(method, placed(arguments[0]))
                else -> forward(method, *arguments)
            }
        }

        private fun placed(cues: Any?): Any? = when (cues) {
            is CueGroup -> ExoCuePlacement.place(cues)
            is List<*> -> ExoCuePlacement.place(cues.filterIsInstance<Cue>())
            else -> cues
        }

        private fun forward(method: Method, vararg arguments: Any?): Any? =
            try {
                method.invoke(listener, *arguments)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        private fun handlerOf(other: Any): PlacingHandler? =
            if (Proxy.isProxyClass(other.javaClass)) Proxy.getInvocationHandler(other) as? PlacingHandler else null
    }
}
