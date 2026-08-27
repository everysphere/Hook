package com.tomfricks.hook.ui.components

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * A silent, looping demo clip from `res/raw` that fills whatever it is given.
 *
 * Every video in the app is one of these: an explanation playing under a
 * heading, never something the user drives. So there are no controls, no sound
 * and no end — and no letterbox either. ZOOM is the video equivalent of
 * [androidx.compose.ui.layout.ContentScale.Crop]: the clip is scaled until both
 * sides cover the frame and the overhang is clipped, so a rounded frame around
 * it never shows a gap between the two.
 *
 * @param playing false parks the clip on its current frame. Callers pass the
 *   composable's own visibility here — an off-screen pager page or a scrim that
 *   has been dismissed should not keep a decoder running.
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(
    @RawRes video: Int,
    modifier: Modifier = Modifier,
    playing: Boolean = true
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(video) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(
                MediaItem.fromUri("android.resource://${context.packageName}/$video".toUri())
            )
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }

    // A backgrounded app must not keep decoding frames, and neither must a clip
    // the caller has parked; both are just "don't play right now".
    DisposableEffect(player, lifecycleOwner, playing) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.playWhenReady = playing
                Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                else -> Unit
            }
        }
        player.playWhenReady = playing &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    )
}
