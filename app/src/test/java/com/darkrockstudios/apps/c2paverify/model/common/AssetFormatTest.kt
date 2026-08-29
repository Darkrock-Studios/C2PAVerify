package com.darkrockstudios.apps.c2paverify.model.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFormatTest {

	/** The app's `minSdk`, where AVIF still has no platform decoder. */
	private val minSdk = 29

	@Test
	fun `mime type resolves to the reader token`() {
		assertEquals("image/jpeg", AssetFormats.fromMimeType("image/jpeg")?.token)
		assertEquals("image/png", AssetFormats.fromMimeType("image/png")?.token)
		assertEquals("image/heic", AssetFormats.fromMimeType("image/heic")?.token)
	}

	@Test
	fun `non-standard image-jpg is translated to the token the reader accepts`() {
		assertEquals("image/jpeg", AssetFormats.fromMimeType("image/jpg")?.token)
	}

	@Test
	fun `heif sequence types fall back to their still counterparts`() {
		assertEquals("image/heic", AssetFormats.fromMimeType("image/heic-sequence")?.token)
		assertEquals("image/heif", AssetFormats.fromMimeType("image/heif-sequence")?.token)
	}

	@Test
	fun `mime type matching ignores case and parameters`() {
		assertEquals("image/jpeg", AssetFormats.fromMimeType("IMAGE/JPEG")?.token)
		assertEquals("image/svg+xml", AssetFormats.fromMimeType("image/svg+xml; charset=utf-8")?.token)
	}

	@Test
	fun `generic and unknown mime types resolve to nothing`() {
		assertNull(AssetFormats.fromMimeType("application/octet-stream"))
		assertNull(AssetFormats.fromMimeType(null))
		assertNull(AssetFormats.fromMimeType(""))
	}

	@Test
	fun `pdf is not supported because the native library is built without it`() {
		assertNull(AssetFormats.fromMimeType("application/pdf"))
		assertNull(AssetFormats.fromFileName("scan.pdf"))
	}

	@Test
	fun `file extension resolves when no mime type is available`() {
		assertEquals("image/x-adobe-dng", AssetFormats.fromFileName("IMG_0042.DNG")?.token)
		assertEquals("image/tiff", AssetFormats.fromFileName("scan.tiff")?.token)
		assertEquals("application/x-c2pa-manifest-store", AssetFormats.fromFileName("photo.c2pa")?.token)
	}

	@Test
	fun `file names without a usable extension resolve to nothing`() {
		assertNull(AssetFormats.fromFileName("IMG_0042"))
		assertNull(AssetFormats.fromFileName(""))
		assertNull(AssetFormats.fromFileName(null))
	}

	@Test
	fun `headers identify the common still image containers`() {
		assertEquals("image/jpeg", AssetFormats.fromHeader(bytes(0xFF, 0xD8, 0xFF, 0xE0))?.token)
		assertEquals("image/png", AssetFormats.fromHeader(pngHeader())?.token)
		assertEquals("image/gif", AssetFormats.fromHeader(ascii("GIF89a"))?.token)
		assertEquals("image/webp", AssetFormats.fromHeader(riff("WEBP"))?.token)
		assertEquals("image/jxl", AssetFormats.fromHeader(bytes(0xFF, 0x0A, 0x00, 0x00))?.token)
	}

	@Test
	fun `tiff byte order marks cover the raw formats built on tiff`() {
		assertEquals("image/tiff", AssetFormats.fromHeader(bytes(0x49, 0x49, 0x2A, 0x00))?.token)
		assertEquals("image/tiff", AssetFormats.fromHeader(bytes(0x4D, 0x4D, 0x00, 0x2A))?.token)
	}

	@Test
	fun `bmff brands separate the variants sharing that container`() {
		assertEquals("image/heic", AssetFormats.fromHeader(ftyp("heic"))?.token)
		assertEquals("image/heif", AssetFormats.fromHeader(ftyp("mif1"))?.token)
		assertEquals("image/avif", AssetFormats.fromHeader(ftyp("avif"))?.token)
		assertEquals("video/quicktime", AssetFormats.fromHeader(ftyp("qt  "))?.token)
	}

	@Test
	fun `unrecognised and truncated headers resolve to nothing`() {
		assertNull(AssetFormats.fromHeader(bytes(0x00, 0x01, 0x02, 0x03)))
		assertNull(AssetFormats.fromHeader(bytes(0xFF)))
		assertNull(AssetFormats.fromHeader(ByteArray(0)))
		assertNull(AssetFormats.fromHeader(null))
	}

	@Test
	fun `header wins over a mime type that contradicts it`() {
		// The case that motivated sniffing: a null or defaulted MIME used to send every asset to the
		// JPEG parser, which reports a HEIF as corrupt rather than as unreadable.
		val resolved = AssetFormats.resolve(mimeType = "image/jpeg", header = ftyp("heic"))
		assertEquals("image/heic", resolved?.token)
	}

	@Test
	fun `mime type is used when the header is unavailable or unrecognised`() {
		assertEquals("image/tiff", AssetFormats.resolve(mimeType = "image/tiff")?.token)
		assertEquals(
			"image/tiff",
			AssetFormats.resolve(mimeType = "image/tiff", header = bytes(0x00, 0x01, 0x02, 0x03))?.token,
		)
	}

	@Test
	fun `file name is the last resort`() {
		assertEquals("image/x-sony-arw", AssetFormats.resolve(fileName = "DSC01234.arw")?.token)
		assertNull(AssetFormats.resolve(mimeType = "application/octet-stream", fileName = "mystery"))
	}

	@Test
	fun `a generic bmff brand defers to the declared type to tell audio from video`() {
		// "isom" is used by .mp4 and .m4a alike, so the container alone cannot separate them.
		assertEquals("audio/mp4", AssetFormats.resolve(mimeType = "audio/mp4", header = ftyp("isom"))?.token)
		assertEquals("video/mp4", AssetFormats.resolve(mimeType = "video/mp4", header = ftyp("isom"))?.token)
		assertEquals("video/mp4", AssetFormats.resolve(header = ftyp("isom"))?.token)
	}

	@Test
	fun `a specific bmff brand is not overridden by the declared type`() {
		assertEquals("image/heic", AssetFormats.resolve(mimeType = "video/mp4", header = ftyp("heic"))?.token)
	}

	@Test
	fun `a tiff byte-order mark defers to the declared type to name the raw format`() {
		// DNG, ARW and NEF are TIFF underneath, so the mark alone cannot say which of them it is.
		val bom = bytes(0x49, 0x49, 0x2A, 0x00)
		assertEquals(
			"image/x-adobe-dng",
			AssetFormats.resolve(mimeType = "image/x-adobe-dng", header = bom)?.token,
		)
		assertEquals("image/x-sony-arw", AssetFormats.resolve(fileName = "shot.arw", header = bom)?.token)
		assertEquals("image/tiff", AssetFormats.resolve(header = bom)?.token)
		assertEquals("image/tiff", AssetFormats.resolve(mimeType = "image/png", header = bom)?.token)
	}

	@Test
	fun `svg is recognised from its markup`() {
		assertEquals("image/svg+xml", AssetFormats.fromHeader(ascii("<?xml version=\"1.0\"?>"))?.token)
		assertEquals("image/svg+xml", AssetFormats.fromHeader(ascii("<svg xmlns=\"...\">"))?.token)
		assertEquals("image/svg+xml", AssetFormats.fromHeader(ascii("  \n<svg width=\"1\">"))?.token)
	}

	@Test
	fun `kind reflects what the asset is`() {
		assertEquals(AssetKind.IMAGE, AssetFormats.resolve(mimeType = "image/heic")?.kind)
		assertEquals(AssetKind.VIDEO, AssetFormats.resolve(mimeType = "video/quicktime")?.kind)
		assertEquals(AssetKind.AUDIO, AssetFormats.resolve(mimeType = "audio/flac")?.kind)
		assertEquals(AssetKind.MANIFEST, AssetFormats.resolve(fileName = "sidecar.c2pa")?.kind)
	}

	@Test
	fun `formats the app can decode are renderable`() {
		listOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif")
			.forEach { mime ->
				assertTrue(mime, requireNotNull(AssetFormats.fromMimeType(mime)).isRenderableBy(minSdk))
			}
		// Decodable only because coil-svg is registered, not because the platform can.
		assertTrue(requireNotNull(AssetFormats.fromMimeType("image/svg+xml")).isRenderableBy(minSdk))
	}

	@Test
	fun `formats nothing can decode are inspected without a preview`() {
		listOf("image/tiff", "image/x-adobe-dng", "image/x-sony-arw", "image/jxl", "video/mp4", "audio/flac")
			.forEach { mime ->
				assertFalse(mime, requireNotNull(AssetFormats.fromMimeType(mime)).isRenderableBy(minSdk))
			}
	}

	@Test
	fun `the bmff video family is playable and nothing else is`() {
		listOf("video/mp4", "video/x-m4v", "video/quicktime").forEach { mime ->
			assertTrue(mime, requireNotNull(AssetFormats.fromMimeType(mime)).isPlayable())
		}
		// AVI has no player here, and neither audio nor any still image is played at all.
		listOf("video/msvideo", "audio/mp4", "audio/flac", "image/jpeg", "image/avif")
			.forEach { mime ->
				assertFalse(mime, requireNotNull(AssetFormats.fromMimeType(mime)).isPlayable())
			}
	}

	@Test
	fun `time-based formats are streamed and stills are not`() {
		listOf("video/mp4", "video/msvideo", "audio/mp4", "audio/flac").forEach { mime ->
			assertTrue(mime, requireNotNull(AssetFormats.fromMimeType(mime)).isStreamed)
		}
		listOf("image/jpeg", "image/x-adobe-dng", "image/svg+xml").forEach { mime ->
			assertFalse(mime, requireNotNull(AssetFormats.fromMimeType(mime)).isStreamed)
		}
		assertFalse(requireNotNull(AssetFormats.fromFileName("sidecar.c2pa")).isStreamed)
	}

	@Test
	fun `a content source is identified from the header it carries`() {
		val source = ImageSource.Content(
			uri = "content://media/external/video/media/42",
			mimeType = "image/jpeg",
			header = ftyp("isom"),
		)
		// The pre-read head beats a declared type that contradicts it, exactly as raw bytes do.
		assertEquals("video/mp4", source.resolveFormat()?.token)
	}

	@Test
	fun `a content source without a header falls back to its declared type`() {
		val source = ImageSource.Content(
			uri = "content://media/external/video/media/42",
			mimeType = "video/quicktime",
			header = null,
		)
		assertEquals("video/quicktime", source.resolveFormat()?.token)
	}

	@Test
	fun `avif is renderable only once the platform gained a decoder for it`() {
		val avif = requireNotNull(AssetFormats.fromMimeType("image/avif"))
		assertFalse(avif.isRenderableBy(minSdk))
		assertFalse(avif.isRenderableBy(30))
		assertTrue(avif.isRenderableBy(31))
	}

	private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

	private fun ascii(text: String) = ByteArray(text.length) { text[it].code.toByte() }

	private fun pngHeader() = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

	/** A RIFF preamble: the tag, a size field, then the form type that says what it holds. */
	private fun riff(form: String) = ascii("RIFF") + ByteArray(4) + ascii(form)

	/** A BMFF `ftyp` box: a size field, the tag, then the major brand. */
	private fun ftyp(brand: String) = ByteArray(4) + ascii("ftyp") + ascii(brand)
}
