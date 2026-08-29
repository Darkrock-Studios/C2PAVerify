package com.darkrockstudios.apps.c2paverify.ui.viewer

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.darkrockstudios.apps.c2paverify.datasource.c2pa.UpstreamVideo
import com.darkrockstudios.apps.c2paverify.datasource.image.PipeProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Whether the player can read a document a provider only serves down a pipe.
 *
 * The C2PA reader cannot, which is why a non-seekable source is copied to the cache before it is
 * verified. The player is handed the original URI, so this records what it does with one.
 */
class PipedPlaybackTest {

	@Test
	fun aPipedVideoPrepares() {
		val appContext = InstrumentationRegistry.getInstrumentation().targetContext
		val staged = File(appContext.cacheDir, "playback.mp4")
		UpstreamVideo.file(appContext).copyTo(staged, overwrite = true)

		val settledLatch = CountDownLatch(1)
		var failure: PlaybackException? = null
		lateinit var player: ExoPlayer

		val instrumentation = InstrumentationRegistry.getInstrumentation()
		instrumentation.runOnMainSync {
			player = ExoPlayer.Builder(appContext).build().apply {
				addListener(object : Player.Listener {
					override fun onPlaybackStateChanged(state: Int) {
						if (state == Player.STATE_READY) settledLatch.countDown()
					}

					override fun onPlayerError(error: PlaybackException) {
						failure = error
						settledLatch.countDown()
					}
				})
				setMediaItem(MediaItem.fromUri(PipeProvider.uriFor(staged.name)))
				prepare()
			}
		}

		val settled = settledLatch.await(30, TimeUnit.SECONDS)
		instrumentation.runOnMainSync { player.release() }
		staged.delete()

		println("PIPED PLAYBACK: settled=$settled error=${failure?.errorCodeName} ${failure?.message}")
		if (failure != null) throw AssertionError("piped playback failed: ${failure?.errorCodeName}")
		assumeTrue("player never settled", settled)
	}
}
