package com.darkrockstudios.apps.c2paverify.ui.viewer

import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import kotlinx.coroutines.delay

/**
 * Plays [uri] in place of the still-image preview. The player reads the URI directly, the same way
 * the image path hands it to Coil, so nothing is copied to show it.
 *
 * Controls are hoisted: they overlap the summary card at the bottom of the viewer, so the caller
 * owns [showControls] and moves the card out of their way while they are up.
 */
@OptIn(markerClass = [UnstableApi::class, ExperimentalApi::class])
@Composable
fun VideoPreview(
	uri: String,
	showControls: Boolean,
	onToggleControls: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	var position by rememberSaveable(uri) { mutableLongStateOf(0L) }

	val player = remember(uri) {
		ExoPlayer.Builder(context).build().apply {
			setMediaItem(MediaItem.fromUri(uri))
			seekTo(position)
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

	LifecycleStartEffect(player) {
		onStopOrDispose { player.pause() }
	}

	DisposableEffect(player) {
		onDispose { player.release() }
	}

	Player(
		player = player,
		modifier = modifier.pointerInput(Unit) {
			detectTapGestures { onToggleControls() }
		},
		showControls = showControls,
	)
}

private const val POSITION_SAVE_INTERVAL_MS = 1_000L
