package com.darkrockstudios.apps.c2paverify.ui.viewer

import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.Player
import kotlinx.coroutines.delay

/**
 * Plays [uri] in place of the still-image preview. The player reads the URI directly, the same way
 * the image path hands it to Coil, so nothing is copied to show it.
 *
 * Controls show on entry so the asset is visibly playable, then fade on their own; tapping brings
 * them back. They are drawn along the bottom edge of [modifier]'s bounds, so the caller keeps that
 * clear of the summary card.
 */
@OptIn(markerClass = [UnstableApi::class, ExperimentalApi::class])
@Composable
fun VideoPreview(
	uri: String,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	var position by rememberSaveable(uri) { mutableLongStateOf(0L) }
	var showControls by rememberSaveable(uri) { mutableStateOf(true) }

	LaunchedEffect(showControls) {
		if (showControls) {
			delay(CONTROLS_VISIBLE_MS)
			showControls = false
		}
	}

	val player = remember(uri) {
		ExoPlayer.Builder(context).build().apply {
			setMediaItem(MediaItem.fromUri(uri))
			// Read without observing: this runs in a composition scope, and the periodic save below
			// would otherwise invalidate the whole player subtree once a second.
			seekTo(Snapshot.withoutReadObservation { position })
			prepare()
		}
	}

	// The saved position has to be kept current rather than written on dispose: a rotation saves
	// composition state before the player is torn down, so a dispose-time write arrives too late.
	LaunchedEffect(player) {
		while (true) {
			delay(POSITION_SAVE_INTERVAL_MS)
			position = player.currentPosition
		}
	}

	// ON_STOP rather than a start effect: the start effect's teardown also runs on disposal, where it
	// would pause a player the DisposableEffect below has already released.
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

	DisposableEffect(player) {
		onDispose { player.release() }
	}

	Player(
		player = player,
		modifier = modifier.pointerInput(uri) {
			detectTapGestures { showControls = !showControls }
		},
		// A SurfaceView is punched through the window rather than drawn in it, and on API 34 it loses
		// its buffer when the composition around it changes, leaving the video black. A texture
		// surface is composited normally and survives that.
		surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
		showControls = showControls,
	)
}

private const val POSITION_SAVE_INTERVAL_MS = 1_000L

/** How long the transport controls stay up before fading, matching the usual player convention. */
private const val CONTROLS_VISIBLE_MS = 3_000L
