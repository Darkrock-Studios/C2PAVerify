package com.darkrockstudios.apps.c2paverify.model.c2pa

import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus
import com.darkrockstudios.apps.c2paverify.model.summary.SummaryFactory
import com.darkrockstudios.apps.c2paverify.model.summary.UnverifiableReason
import com.darkrockstudios.apps.c2paverify.model.trust.TrustLevel
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An asset the reader never actually read must not be called tampered.
 *
 * c2pa-android's JNI layer allocates a buffer the size of the whole hash range; for a large video
 * that throws `OutOfMemoryError`, the glue swallows it, and the failed read surfaces as a hash
 * mismatch (contentauth/c2pa-android#133). `AndroidC2paReaderDataSource` catches this by measuring
 * how much of the asset its stream actually served; these tests cover what the domain layer does
 * with that signal.
 */
class IncompleteVerificationTest {

	private val parser = C2paManifestParser(Json { ignoreUnknownKeys = true; isLenient = true })

	private fun fixture(name: String): String =
		requireNotNull(javaClass.classLoader?.getResourceAsStream("c2pa/captured/$name")) {
			"Missing fixture c2pa/captured/$name"
		}.bufferedReader().use { it.readText() }

	/** The BMFF fixture without its indexed exclusion, so only the incomplete-read signal is in play. */
	private fun hashFailure(verificationIncomplete: Boolean) = parser.parse(
		manifestJson = fixture("pixel-video-bmff.manifest.json"),
		detailedJson = fixture("pixel-video-bmff.detailed.json").replace("/moov[1]/trak[3]", "/moov/trak"),
		verificationIncomplete = verificationIncomplete,
	)

	@Test
	fun `a hash failure from an unread asset is reported as unverifiable`() {
		val data = hashFailure(verificationIncomplete = true)

		assertTrue(data.hashFailuresAreUnreliable)
		val summary = SummaryFactory.buildSummary(data, TrustLevel.UNKNOWN)
		assertEquals(OverallStatus.UNVERIFIABLE, summary.status)
		assertEquals(UnverifiableReason.ASSET_TOO_LARGE, summary.unverifiableReason)
	}

	@Test
	fun `the same failure from a fully read asset stays tampered`() {
		val data = hashFailure(verificationIncomplete = false)

		assertFalse(data.hashFailuresAreUnreliable)
		assertEquals(
			OverallStatus.TAMPERED_INVALID,
			SummaryFactory.buildSummary(data, TrustLevel.UNKNOWN).status,
		)
	}

	@Test
	fun `an incomplete read does not excuse a broken signature`() {
		// If the signature itself failed, an incomplete read is not the explanation; stay tampered.
		val manifestJson = fixture("pixel-video-bmff.manifest.json")
			.replace(""""code": "assertion.bmffHash.mismatch"""", """"code": "claimSignature.mismatch"""")
		val data = parser.parse(manifestJson, null, verificationIncomplete = true)

		assertFalse(data.hashFailuresAreUnreliable)
		assertEquals(
			OverallStatus.TAMPERED_INVALID,
			SummaryFactory.buildSummary(data, TrustLevel.UNKNOWN).status,
		)
	}

	@Test
	fun `a bmff hash mismatch now counts as an integrity failure`() {
		val data = parser.parse(fixture("pixel-video-bmff.manifest.json"))

		assertTrue(data.integrityFailures.any { it.code == "assertion.bmffHash.mismatch" })
		assertTrue(data.onlyHashFailures)
	}
}
