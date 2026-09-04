package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import androidx.test.platform.app.InstrumentationRegistry
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import com.darkrockstudios.apps.c2paverify.model.summary.SummaryFactory
import com.darkrockstudios.apps.c2paverify.model.trust.TrustMaterial
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Captures the REAL `reader.json()` / `reader.detailedJson()` output of the c2pa-android library
 * for each vendored sample image, writing the results to the app's external files dir so they can
 * be pulled off the device and turned into JVM unit-test fixtures. Also a smoke test that the
 * Android C2PA data source actually works on-device.
 *
 * Run on an emulator, then pull:
 *   adb shell run-as <app> ... / adb pull /sdcard/Android/data/<app>/files/c2pa-capture
 */
class C2paReaderCaptureTest {

	private val dataSource =
		AndroidC2paReaderDataSource(InstrumentationRegistry.getInstrumentation().targetContext)

	private val samples = listOf(
		"valid-C.jpg",
		"valid-multi-CAICAI.jpg",
		"invalid-sig.jpg",
		"tampered-dat.jpg",
		"no-manifest.jpg",
		"upstream/png_valid.png",
		"upstream/webp_valid.webp",
		"upstream/gif_valid.gif",
		"upstream/tiff_valid.tiff",
		"upstream/avif_valid.avif",
	)

	@Test
	fun captureRealReaderOutput() = runBlocking {
		// Sample assets live in the *test* APK (instrumentation context); write output into the
		// app-under-test's external files dir (target context).
		val testCtx = InstrumentationRegistry.getInstrumentation().context
		val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
		val outDir = File(appCtx.getExternalFilesDir(null), "c2pa-capture").apply { mkdirs() }

		val summary = StringBuilder()
		for (asset in samples) {
			val bytes = testCtx.assets.open("c2pa/$asset").use { it.readBytes() }
			val result = runCatching { dataSource.read(ImageSource.Bytes(bytes, mimeType = null)) }
			val base = asset.substringAfterLast('/').substringBeforeLast('.')
			result.onSuccess { read ->
				when (read) {
					is C2paRawRead.NoManifest -> {
						summary.appendLine("$asset -> NoManifest")
						File(outDir, "$base.NOMANIFEST.txt").writeText("NoManifest")
					}

					is C2paRawRead.Manifest -> {
						summary.appendLine("$asset -> Manifest (json=${read.manifestJson.length} chars, detailed=${read.detailedJson?.length ?: 0})")
						File(outDir, "$base.manifest.json").writeText(read.manifestJson)
						read.detailedJson?.let { File(outDir, "$base.detailed.json").writeText(it) }
					}
				}
			}.onFailure { e ->
				summary.appendLine("$asset -> ERROR ${e::class.simpleName}: ${e.message}")
				File(outDir, "$base.ERROR.txt").writeText("${e::class.qualifiedName}: ${e.message}\n${e.stackTraceToString()}")
			}
		}

		File(outDir, "_summary.txt").writeText(summary.toString())
		println("C2PA capture summary:\n$summary")
		println("C2PA capture written to: ${outDir.absolutePath}")
	}

	/**
	 * Every still-image format the reader claims to handle, signed and unsigned, identified from
	 * nothing but its own bytes. Each of these used to be handed to the JPEG parser by the old
	 * `image/jpeg` default, which reported a perfectly good PNG or TIFF as corrupt rather than as
	 * simply unsigned.
	 *
	 * The signed fixtures are asserted only to *parse*. Their test certificates expired in the
	 * 2000s, so today every one of them validates as Invalid whatever the format; the hard binding
	 * still matches. A verdict here would measure the clock and the trust list, not the parser.
	 */
	@Test
	fun everyStillImageFormatReachesItsParser() = runBlocking {
		val testCtx = InstrumentationRegistry.getInstrumentation().context
		listOf(
			Triple("upstream/png_valid.png", "image/png", true),
			Triple("upstream/no_c2pa_006.png", "image/png", false),
			Triple("upstream/webp_valid.webp", "image/webp", true),
			Triple("upstream/no_c2pa_040.webp", "image/webp", false),
			Triple("upstream/gif_valid.gif", "image/gif", true),
			Triple("upstream/no_c2pa_041.gif", "image/gif", false),
			Triple("upstream/tiff_valid.tiff", "image/tiff", true),
			Triple("upstream/no_c2pa_018.tiff", "image/tiff", false),
			Triple("upstream/avif_valid.avif", "image/avif", true),
			Triple("upstream/no_c2pa_008.avif", "image/avif", false),
			Triple("no-manifest.jpg", "image/jpeg", false),
		).forEach { (asset, expectedToken, signed) ->
			val bytes = testCtx.assets.open("c2pa/$asset").use { it.readBytes() }
			val source = ImageSource.Bytes(bytes, mimeType = null)

			assertEquals("$asset identified from its header", expectedToken, source.resolveFormat()?.token)

			val read = dataSource.read(source)
			if (signed) {
				assertTrue("$asset should yield a manifest, got $read", read is C2paRawRead.Manifest)
			} else {
				assertEquals("$asset should read cleanly as unsigned", C2paRawRead.NoManifest, read)
			}
		}
	}

	/**
	 * PDF reaches its parser and yields a manifest, identified from its own `%PDF-` header.
	 *
	 * Asserted on-device because it is the only place the claim can be settled: the handler is
	 * compiled into the native library (`pdf_io.rs`\'s "PDF write functionality" constant, lopdf and
	 * `application/pdf` are all in `libc2pa_c.so`), but only a real read proves the format token
	 * actually routes there. Writing is what c2pa-rs leaves unimplemented, which costs a verifier
	 * nothing. As with the still-image fixtures, only parsing is asserted: this file carries
	 * upstream\'s 2001-era test certificate, so its verdict measures the clock, not the parser.
	 */
	@Test
	fun pdfReachesItsParser() = runBlocking {
		val testCtx = InstrumentationRegistry.getInstrumentation().context
		val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
		val bytes = testCtx.assets.open("c2pa/upstream/pdf_valid.pdf").use { it.readBytes() }
		val source = ImageSource.Bytes(bytes, mimeType = null)

		assertEquals(
			"pdf_valid.pdf identified from its header",
			"application/pdf",
			source.resolveFormat()?.token,
		)

		val read = dataSource.read(source)
		assertTrue("pdf_valid.pdf should yield a manifest, got $read", read is C2paRawRead.Manifest)

		// Captured so the JVM suite can replay it as a fixture.
		val manifest = read as C2paRawRead.Manifest
		val outDir = File(appCtx.getExternalFilesDir(null), "c2pa-capture").apply { mkdirs() }
		File(outDir, "pdf_valid.manifest.json").writeText(manifest.manifestJson)
		manifest.detailedJson?.let { File(outDir, "pdf_valid.detailed.json").writeText(it) }
		println("PDF manifest captured: ${manifest.manifestJson.length} chars")
	}

	/**
	 * A PDF altered outside its hash exclusion reports a broken hard binding.
	 *
	 * Upstream publishes no tampered PDF, so one is made here: the document title in object 1 sits
	 * well before the exclusion (bytes 6191..7632, which cover the embedded manifest), and swapping a
	 * character keeps every offset and the xref intact, so the file still parses as a PDF and only
	 * the hash can object. Without this, nothing proves the PDF binding is actually *checked* rather
	 * than merely read.
	 */
	@Test
	fun pdfAlteredOutsideItsExclusionFailsTheHardBinding() = runBlocking {
		val testCtx = InstrumentationRegistry.getInstrumentation().context
		val bytes = testCtx.assets.open("c2pa/upstream/pdf_valid.pdf").use { it.readBytes() }

		val marker = "C2PA Test Doc".encodeToByteArray()
		val at = bytes.indexOfSlice(marker)
		assertTrue("title marker should be present to tamper with", at >= 0)
		assertTrue("the byte flipped must sit outside the hash exclusion", at < 6191)
		bytes[at] = 'X'.code.toByte()

		val read = dataSource.read(ImageSource.Bytes(bytes, mimeType = null))
		assertTrue("a tampered PDF should still parse, got $read", read is C2paRawRead.Manifest)
		assertTrue(
			"expected a data hash mismatch",
			(read as C2paRawRead.Manifest).manifestJson.contains("assertion.dataHash.mismatch"),
		)
	}

	/**
	 * Reads the conformant samples WITH the bundled official trust list configured, so we can
	 * confirm the reader reports signingCredential.trusted for a real trusted signer.
	 */
	@Test
	fun captureWithTrustList() = runBlocking {
		val testCtx = InstrumentationRegistry.getInstrumentation().context
		val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
		val outDir = File(appCtx.getExternalFilesDir(null), "c2pa-capture-trust").apply { mkdirs() }

		val anchorsPem = appCtx.assets.open("trust/c2pa-trust-anchors.pem")
			.use { it.readBytes().decodeToString() }
		val trust = TrustMaterial(trustAnchorsPem = anchorsPem)

		val summary = StringBuilder()
		listOf("conformant-a.jpg", "conformant-b.jpg", "valid-C.jpg").forEach { asset ->
			val bytes = testCtx.assets.open("c2pa/$asset").use { it.readBytes() }
			val base = asset.removeSuffix(".jpg")
			runCatching { dataSource.read(ImageSource.Bytes(bytes, "image/jpeg"), trust) }
				.onSuccess { read ->
					when (read) {
						is C2paRawRead.NoManifest -> summary.appendLine("$asset -> NoManifest")
						is C2paRawRead.Manifest -> {
							File(outDir, "$base.trust.json").writeText(read.manifestJson)
							val state = Regex("\"validation_state\"\\s*:\\s*\"(\\w+)\"")
								.find(read.manifestJson)?.groupValues?.get(1)
							val trusted = read.manifestJson.contains("signingCredential.trusted")
							val untrusted = read.manifestJson.contains("signingCredential.untrusted")
							summary.appendLine("$asset -> state=$state trusted=$trusted untrusted=$untrusted")
						}
					}
				}
				.onFailure { summary.appendLine("$asset -> ERROR ${it.message}") }
		}
		File(outDir, "_trust_summary.txt").writeText(summary.toString())
		println("C2PA trust capture:\n$summary")
	}

	/**
	 * Reads the bundled landing example (`assets/c2pa/examples/ai-generated.jpg` from the app's own
	 * assets) through the real reader + the app's parser/AI detector, to confirm it parses, its
	 * trust verdict, and whether it flags as AI per the asset's-own-claim rule.
	 */
	@Test
	fun verifyBundledAiExample() = runBlocking {
		val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
		val anchorsPem = appCtx.assets.open("trust/c2pa-trust-anchors.pem")
			.use { it.readBytes().decodeToString() }
		val trust = TrustMaterial(trustAnchorsPem = anchorsPem)
		val bytes = appCtx.assets.open("c2pa/examples/ai-generated.jpg").use { it.readBytes() }

		val out = StringBuilder()
		when (val read = dataSource.read(ImageSource.Bytes(bytes, "image/jpeg"), trust)) {
			is C2paRawRead.NoManifest -> out.appendLine("ai-generated.jpg -> NoManifest")
			is C2paRawRead.Manifest -> {
				val parser = C2paManifestParser(Json { ignoreUnknownKeys = true; isLenient = true })
				val data = parser.parse(read.manifestJson)
				val ai = SummaryFactory.detectAi(data)
				out.appendLine("validationState=${data.validationState}")
				out.appendLine("trusted=${data.signerTrusted} untrusted=${data.signerUntrusted}")
				out.appendLine("isAi=${ai.isAiGenerated}")
				out.appendLine("activeSourceTypes=${ai.sourceTypes}")
				out.appendLine("signer=${data.activeManifest?.signature?.issuer}")
				out.appendLine("generator=${data.activeManifest?.claimGenerator}")
			}
		}
		val outDir = File(appCtx.getExternalFilesDir(null), "c2pa-capture-trust").apply { mkdirs() }
		File(outDir, "_ai_example.txt").writeText(out.toString())
		println("AI EXAMPLE CHECK:\n$out")
	}
}

/** First index of [slice] in this array, or -1. Kotlin has no stdlib equivalent for ByteArray. */
private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
	if (slice.isEmpty() || slice.size > size) return -1
	outer@ for (i in 0..size - slice.size) {
		for (j in slice.indices) if (this[i + j] != slice[j]) continue@outer
		return i
	}
	return -1
}
