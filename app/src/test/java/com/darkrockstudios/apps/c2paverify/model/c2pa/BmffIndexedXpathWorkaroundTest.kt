package com.darkrockstudios.apps.c2paverify.model.c2pa

import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus
import com.darkrockstudios.apps.c2paverify.model.summary.SummaryFactory
import com.darkrockstudios.apps.c2paverify.model.trust.TrustLevel
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BMFF_INDEXED_XPATH: delete this whole file together with `BmffIndexedXpathWorkaround.kt` once
 * c2pa-android ships a c2pa-rs carrying contentauth/c2pa-rs#2434.
 *
 * The fixtures mirror a real Pixel video capture (`Google C2PA SDK for Android`, a
 * `c2pa.hash.bmff.v3` assertion excluding the timed-metadata track via `/moov[1]/trak[3]`). The
 * asset's hash is genuinely correct (verified by recomputing it byte-for-byte against c2pa-rs's
 * own algorithm), but every released c2pa-rs silently drops the indexed exclusion and reports
 * `assertion.bmffHash.mismatch`.
 */
class BmffIndexedXpathWorkaroundTest {

	private val parser = C2paManifestParser(Json { ignoreUnknownKeys = true; isLenient = true })

	private fun fixture(name: String): String =
		requireNotNull(javaClass.classLoader?.getResourceAsStream("c2pa/captured/$name")) {
			"Missing fixture c2pa/captured/$name"
		}.bufferedReader().use { it.readText() }

	private fun pixelVideo(
		manifestJson: String = fixture("pixel-video-bmff.manifest.json"),
		detailedJson: String = fixture("pixel-video-bmff.detailed.json"),
	) = parser.parse(manifestJson, detailedJson)

	@Test
	fun `pixel video bmff mismatch is recognised as spurious`() {
		val data = pixelVideo()

		assertEquals(ManifestValidationState.INVALID, data.validationState)
		assertTrue(data.hasSpuriousBmffHashFailure)
	}

	@Test
	fun `spurious bmff failure is reported as unverifiable rather than tampered`() {
		val summary = SummaryFactory.buildSummary(pixelVideo(), TrustLevel.TRUSTED)

		assertEquals(OverallStatus.UNVERIFIABLE, summary.status)
	}

	@Test
	fun `bmff mismatch without an indexed exclusion stays tampered`() {
		// Same asset, but every exclusion is a plain path the library CAN evaluate, so a mismatch
		// here is real tampering and must keep its verdict.
		val detailed = fixture("pixel-video-bmff.detailed.json")
			.replace("/moov[1]/trak[3]", "/moov/trak")
		val data = pixelVideo(detailedJson = detailed)

		assertFalse(data.hasSpuriousBmffHashFailure)
		assertEquals(
			OverallStatus.TAMPERED_INVALID,
			SummaryFactory.buildSummary(data, TrustLevel.TRUSTED).status,
		)
	}

	@Test
	fun `any non-bmff failure keeps the tampered verdict`() {
		// A real signature break alongside the spurious hash failure must not be excused.
		val manifestJson = fixture("pixel-video-bmff.manifest.json").replace(
			""""code": "assertion.bmffHash.mismatch"""",
			""""code": "claimSignature.mismatch"""",
		)
		val data = pixelVideo(manifestJson = manifestJson)

		assertFalse(data.hasSpuriousBmffHashFailure)
		assertEquals(
			OverallStatus.TAMPERED_INVALID,
			SummaryFactory.buildSummary(data, TrustLevel.TRUSTED).status,
		)
	}

	@Test
	fun `an untrusted signer alongside the bmff mismatch still downgrades`() {
		// Regression: on a device with no trust list configured, a real Pixel video reports
		// signingCredential.untrusted next to the bogus hash mismatch. That is a statement about the
		// signer, not the content, and must not keep the asset pinned to "tampered".
		val manifestJson = fixture("pixel-video-bmff.manifest.json").replace(
			""""failure": [""",
			""""failure": [{ "code": "signingCredential.untrusted", "explanation": "untrusted" }, """,
		)
		val data = pixelVideo(manifestJson = manifestJson)

		assertTrue(data.hasSpuriousBmffHashFailure)
		assertEquals(
			OverallStatus.UNVERIFIABLE,
			SummaryFactory.buildSummary(data, TrustLevel.UNKNOWN).status,
		)
	}

	@Test
	fun `a clean manifest is never treated as spurious`() {
		val data = parser.parse(fixture("valid-C.manifest.json"), fixture("valid-C.detailed.json"))

		assertFalse(data.hasSpuriousBmffHashFailure)
	}

	@Test
	fun `a genuinely tampered asset keeps its verdict`() {
		val data = parser.parse(
			fixture("tampered-dat.manifest.json"),
			fixture("tampered-dat.detailed.json"),
		)

		assertFalse(data.hasSpuriousBmffHashFailure)
		assertEquals(
			OverallStatus.TAMPERED_INVALID,
			SummaryFactory.buildSummary(data, TrustLevel.TRUSTED).status,
		)
	}

	@Test
	fun `missing detailed json falls back to the tampered verdict`() {
		val data = parser.parse(fixture("pixel-video-bmff.manifest.json"))

		assertFalse(data.hasSpuriousBmffHashFailure)
	}

	@Test
	fun `malformed detailed json is not treated as spurious`() {
		assertFalse(hasIndexedBmffExclusion("{not json"))
		assertFalse(hasIndexedBmffExclusion("{}"))
	}
}
