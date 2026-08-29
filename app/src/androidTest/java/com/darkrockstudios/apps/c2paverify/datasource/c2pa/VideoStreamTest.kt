package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import androidx.test.platform.app.InstrumentationRegistry
import com.darkrockstudios.apps.c2paverify.datasource.image.AssetSourceDataSource
import com.darkrockstudios.apps.c2paverify.datasource.image.PipeProvider
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
	 * A provider that will not hand back a seekable file still gets verified, by way of a local copy.
	 *
	 * This is what cloud storage does: Drive and Dropbox hold the document remotely and serve it down
	 * a pipe. A pipe cannot be seeked and BMFF hard binding is nothing but seeking, so without the
	 * copy the reader cannot touch it at all.
	 */
	@Test
	fun aVideoServedOverAPipeIsCopiedAndVerified() = runBlocking {
		val fixture = fixture()
		val staged = File(appContext.cacheDir, "piped.mp4")
		fixture.copyTo(staged, overwrite = true)
		val pipeUri = PipeProvider.uriFor(staged.name)

		// The premise: this URI genuinely has no file behind it.
		val size = appContext.contentResolver.openFileDescriptor(pipeUri, "r")
			?.use { it.statSize }
		assertEquals("the provider must serve a pipe for this test to mean anything", -1L, size)

		val source = AssetSourceDataSource(appContext).read(pipeUri.toString())
		assertTrue("expected a streamed source, got $source", source is ImageSource.Content)
		assertEquals("video/mp4", source.resolveFormat()?.token)

		val piped = dataSource.read(source)
		val direct = dataSource.read(ImageSource.Bytes(fixture.readBytes(), mimeType = null))
		assertTrue("expected a manifest, got $piped", piped is C2paRawRead.Manifest)
		assertEquals(
			(direct as C2paRawRead.Manifest).manifestJson,
			(piped as C2paRawRead.Manifest).manifestJson,
		)
		staged.delete()
		Unit
	}

	/**
	 * A container the app cannot name is still streamed rather than read whole. It is refused by the
	 * reader either way, but a gigabyte of Matroska pulled into the heap first would crash before
	 * anything got the chance to refuse it.
	 */
	@Test
	fun anUnidentifiableVideoIsStreamedRatherThanLoaded() = runBlocking {
		val file = File(appContext.cacheDir, "unidentifiable.mkv").apply {
			// An EBML header, which the format sniffer has no entry for.
			writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(64))
		}
		val source = AssetSourceDataSource(appContext).read("file://${file.absolutePath}")

		assertTrue("expected a streamed source, got $source", source is ImageSource.Content)
		assertNull("the app must not claim to identify it", source.resolveFormat())
		assertThrows(C2paReadException::class.java) { runBlocking { dataSource.read(source) } }
		file.delete()
		Unit
	}

	private fun fixture(): File = UpstreamVideo.file(appContext)
}
