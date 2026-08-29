package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import androidx.test.platform.app.InstrumentationRegistry
import com.darkrockstudios.apps.c2paverify.datasource.image.AssetSourceDataSource
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Proves MP4 verification end to end on a device: the asset is identified from its own bytes, read
 * through the descriptor stream without being loaded, and yields the same manifest a whole-file read
 * does.
 *
 * The fixture is fetched rather than vendored. It is 15 MB, which is a permanent cost to the repo
 * for one test file, and it is the only C2PA-signed MP4 upstream publishes. It is cached in the
 * app's files dir, so only the first run on a device needs the network.
 */
class VideoStreamTest {

	private val appContext = InstrumentationRegistry.getInstrumentation().targetContext
	private val dataSource = AndroidC2paReaderDataSource(appContext)

	/**
	 * The seek arithmetic is the risk this test exists for. BMFF hard binding walks the box tree, so
	 * any error in it changes what the reader hashes; an identical manifest from both paths is the
	 * strongest available statement that the streamed read is byte-for-byte the whole-file read.
	 */
	@Test
	fun streamedVideoReadsIdenticallyToAWholeFileRead() = runBlocking {
		val file = fixture()
		val bytes = file.readBytes()

		val whole = dataSource.read(ImageSource.Bytes(bytes, mimeType = null))
		val streamed = dataSource.read(
			ImageSource.Content(
				uri = "file://${file.absolutePath}",
				mimeType = "video/mp4",
				header = bytes.copyOf(32),
			),
		)

		assertTrue("expected a manifest, got $whole", whole is C2paRawRead.Manifest)
		assertEquals(
			(whole as C2paRawRead.Manifest).manifestJson,
			(streamed as C2paRawRead.Manifest).manifestJson,
		)
	}

	@Test
	fun mp4IsIdentifiedFromItsOwnBytesAndReachesTheBmffParser() = runBlocking {
		val file = fixture()
		val source = ImageSource.Bytes(file.readBytes(), mimeType = null)

		assertEquals("video/mp4", source.resolveFormat()?.token)

		val read = dataSource.read(source)
		assertTrue("expected a manifest, got $read", read is C2paRawRead.Manifest)
		// The hard binding video carries, and the reason the stream has to be seekable at all.
		assertTrue((read as C2paRawRead.Manifest).manifestJson.contains("c2pa.hash.bmff"))
	}

	/** A picked video is handed to the reader as a stream, never loaded into the heap. */
	@Test
	fun aVideoLoadsAsAStreamedSourceWhileAPhotoDoesNot() = runBlocking {
		val assetSource = AssetSourceDataSource(appContext)
		val video = assetSource.read("file://${fixture().absolutePath}")
		assertTrue("expected a streamed source, got $video", video is ImageSource.Content)
		assertEquals("video/mp4", video.resolveFormat()?.token)

		val photo = assetSource.read("file:///android_asset/c2pa/examples/trusted.jpg")
		assertTrue("expected bytes, got $photo", photo is ImageSource.Bytes)
	}

	/**
	 * Downloads the upstream fixture once per device. Skips rather than fails when the network is
	 * unavailable, so an offline run does not report a red test it never actually exercised.
	 */
	private fun fixture(): File {
		val cached = File(appContext.filesDir, "fixtures/$FIXTURE_NAME")
		if (cached.exists() && cached.length() == FIXTURE_BYTES) return cached
		cached.parentFile?.mkdirs()
		try {
			val connection = (URL(FIXTURE_URL).openConnection() as HttpURLConnection).apply {
				connectTimeout = 30_000
				readTimeout = 30_000
			}
			try {
				connection.inputStream.use { input ->
					cached.outputStream().use { output -> input.copyTo(output) }
				}
			} finally {
				connection.disconnect()
			}
		} catch (e: Exception) {
			cached.delete()
			assumeNoException("Could not fetch $FIXTURE_URL; skipping the MP4 tests", e)
		}
		assertEquals("fetched fixture is the wrong size", FIXTURE_BYTES, cached.length())
		return cached
	}

	private companion object {
		const val FIXTURE_NAME = "truepic-20230212-zoetrope.mp4"
		const val FIXTURE_BYTES = 15_456_823L
		const val FIXTURE_URL =
			"https://raw.githubusercontent.com/c2pa-org/public-testfiles/main/" +
				"legacy/1.4/video/mp4/$FIXTURE_NAME"
	}
}
