package com.hometv.tv.playback

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.core.playback.SubtitleLook
import kotlin.math.max
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The video surface of the active engine, shared by the TV and mobile players; [controller] is read inside the block
 * keyed on [engine] (see below).
 */
@Composable
fun VideoSurface(controller: PlayerController?, engine: PlaybackEngine?, keepScreenOn: Boolean, look: SubtitleLook, overlayClearance: Float) {
    val probe = LocalVideoSurfaceProbe.current
    // The controller is created lazily at the first attach and is not observable: it is read inside a block
    // keyed on the observable engine, so null → engine (and every engine switch) disposes the old view and
    // creates the right one with the controller that exists at that moment.
    key(engine) {
        when (engine) {
            null -> Box(Modifier.fillMaxSize().testTag("player_video")) // no controller yet
            PlaybackEngine.EXO -> ExoSurface(controller as? ExoPlayerController, keepScreenOn, look, overlayClearance) { probe(engine, controller) }
            PlaybackEngine.VLC -> VlcSurface(controller as? VlcPlayerController, keepScreenOn) { probe(engine, controller) }
        }
    }
}

/**
 * ExoPlayer's `PlayerView` (own controller disabled). Its `SubtitleView` always draws the profile's [look]
 * (`ExoSubtitleStyle`; the device caption settings are ignored): a draft change restyles live; the bottom padding
 * is the look's position, raised above the overlay's bottom block while it shows ([overlayClearance]). The
 * surface recomposes on every Player state change, so the style and the padding are set only when they changed
 * (each `SubtitleView` setter re-lays out its cues).
 */
@OptIn(UnstableApi::class)
@Composable
private fun ExoSurface(exo: ExoPlayerController?, keepScreenOn: Boolean, look: SubtitleLook, overlayClearance: Float, onBind: () -> Unit) {
    val captionProbe = LocalExoCaptionProbe.current
    val applied = remember { AppliedCaption() }
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            onBind()
            exo?.attach(view)
            view.keepScreenOn = keepScreenOn
            val spec = ExoSubtitleStyle.of(look)
            val padding = max(spec.bottomPaddingFraction, overlayClearance)
            view.subtitleView?.let { subtitles ->
                if (spec != applied.spec) {
                    subtitles.setStyle(spec.toCaptionStyle())
                    subtitles.setFractionalTextSize(spec.textSizeFraction)
                    applied.spec = spec
                }
                if (padding != applied.padding) {
                    subtitles.setBottomPaddingFraction(padding)
                    applied.padding = padding
                }
                captionProbe(ExoCaptionApplied(spec, padding, overlayClearance))
            }
        },
        onRelease = { view -> exo?.detach(view) },
        modifier = Modifier.fillMaxSize().testTag("player_video"),
    )
}

/**
 * libVLC's `VLCVideoLayout` (subtitles drawn by libVLC, styled by `VlcOptions`). With no [VlcPlayerController]
 * (a fake in tests) a plain `Box`: no `VLCVideoLayout` is ever built on Robolectric. Mirrors VLC for Android's
 * player: `detach` at `ON_STOP` (the `SurfaceView`'s surface is destroyed while stopped) and `attach` at
 * `ON_START`; `attach` is idempotent and also starts the controller's deferred open of the first media.
 */
@Composable
private fun VlcSurface(vlc: VlcPlayerController?, keepScreenOn: Boolean, onBind: () -> Unit) {
    if (vlc == null) {
        SideEffect(onBind)
        Box(Modifier.fillMaxSize().testTag("player_video"))
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A plain holder, not Compose state: the factory fills it without a second recomposition or an effect re-key.
    val layoutHolder = remember { arrayOfNulls<VLCVideoLayout>(1) }
    DisposableEffect(vlc, lifecycle) {
        // addObserver replays ON_START when the screen is already started: harmless, attach is idempotent.
        val observer = LifecycleEventObserver { _, event ->
            val view = layoutHolder[0] ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> vlc.detach(view)
                Lifecycle.Event.ON_START -> vlc.attach(view)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { context ->
            VLCVideoLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }.also { layoutHolder[0] = it }
        },
        update = { view ->
            onBind()
            // A recomposition while stopped must not hand libVLC a destroyed surface: ON_START attaches.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vlc.attach(view)
            view.keepScreenOn = keepScreenOn
        },
        onRelease = { view ->
            vlc.detach(view)
            if (layoutHolder[0] === view) layoutHolder[0] = null
        },
        modifier = Modifier.fillMaxSize().testTag("player_video"),
    )
}

/**
 * Test seam: called from the video surface's `update` (the fake-only `Box` of the VLC surface: a `SideEffect`)
 * with the engine and the controller it binds (the real `attach` is unchanged). Production never provides it.
 */
val LocalVideoSurfaceProbe: ProvidableCompositionLocal<(PlaybackEngine?, PlayerController?) -> Unit> = staticCompositionLocalOf<(PlaybackEngine?, PlayerController?) -> Unit> { { _, _ -> } }

/** What the Exo surface's `SubtitleView` was last set to (the look, the bottom padding) and the overlay clearance then. */
data class ExoCaptionApplied(val spec: ExoCaptionSpec, val bottomPaddingFraction: Float, val overlayClearance: Float)

/** The last values set on the `SubtitleView` (null / NaN: nothing set yet on this view). */
private class AppliedCaption(var spec: ExoCaptionSpec? = null, var padding: Float = Float.NaN)

/** Test seam: what the Exo surface applied to its `SubtitleView` after each `update`. Production never provides it. */
val LocalExoCaptionProbe: ProvidableCompositionLocal<(ExoCaptionApplied) -> Unit> = staticCompositionLocalOf<(ExoCaptionApplied) -> Unit> { {} }
