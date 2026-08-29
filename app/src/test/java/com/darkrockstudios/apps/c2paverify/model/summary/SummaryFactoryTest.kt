package com.darkrockstudios.apps.c2paverify.model.summary

import com.darkrockstudios.apps.c2paverify.model.trust.TrustLevel
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryFactoryTest {

	private val parser = C2paManifestParser(Json { ignoreUnknownKeys = true; isLenient = true })

	private fun fixture(name: String): String =
		requireNotNull(javaClass.classLoader?.getResourceAsStream("c2pa/captured/$name")) {
			"Missing fixture $name"
		}.bufferedReader().use { it.readText() }

	@Test
	fun `null manifest is NO_MANIFEST`() {
		val summary = SummaryFactory.buildSummary(manifest = null, trust = TrustLevel.UNKNOWN)
		assertEquals(OverallStatus.NO_MANIFEST, summary.status)
		assertFalse(summary.manifestPresent)
	}

	@Test
	fun `valid manifest maps to trusted or untrusted by trust level`() {
		val data = parser.parse(fixture("valid-C.manifest.json"))
		assertEquals(
			OverallStatus.SIGNED_UNTRUSTED,
			SummaryFactory.buildSummary(data, TrustLevel.UNTRUSTED).status,
		)
		assertEquals(
			OverallStatus.SIGNED_TRUSTED,
			SummaryFactory.buildSummary(data, TrustLevel.TRUSTED).status,
		)
	}

	@Test
	fun `integrity failure is TAMPERED_INVALID even if trusted`() {
		val data = parser.parse(fixture("invalid-sig.manifest.json"))
		assertEquals(
			OverallStatus.TAMPERED_INVALID,
			SummaryFactory.buildSummary(data, TrustLevel.TRUSTED).status,
		)
	}

	@Test
	fun `signer info is carried into the summary`() {
		val data = parser.parse(fixture("valid-C.manifest.json"))
		val summary = SummaryFactory.buildSummary(data, TrustLevel.UNTRUSTED)
		assertEquals("C2PA Test Signing Cert", summary.signerName)
		assertEquals("C2PA Signer", summary.signerCommonName)
	}

	@Test
	fun `detectAi flags trainedAlgorithmicMedia`() {
		val aiJson = """
			{
			  "active_manifest": "m1",
			  "manifests": {
			    "m1": {
			      "assertions": [
			        { "label": "c2pa.actions.v2", "data": { "actions": [
			          { "action": "c2pa.created",
			            "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia" }
			        ] } }
			      ]
			    }
			  },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val ai = SummaryFactory.detectAi(parser.parse(aiJson))
		assertTrue(ai.isAiGenerated)
		assertTrue(ai.sourceTypes.any { it.contains("trainedAlgorithmicMedia") })
	}

	@Test
	fun `detectAi is false for an ordinary edited image`() {
		val data = parser.parse(fixture("valid-multi-CAICAI.manifest.json"))
		assertFalse(SummaryFactory.detectAi(data).isAiGenerated)
	}

	@Test
	fun `composite-with-AI is AI-modified, not AI-generated`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.edited",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/compositeWithTrainedAlgorithmicMedia" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val data = parser.parse(json)
		assertFalse(SummaryFactory.detectAi(data).isAiGenerated)
		assertTrue(SummaryFactory.detectAiModified(data).isAiModified)
	}

	@Test
	fun `fully AI-generated is not flagged as AI-modified`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val data = parser.parse(json)
		assertTrue(SummaryFactory.detectAi(data).isAiGenerated)
		assertFalse(SummaryFactory.detectAiModified(data).isAiModified)
	}

	@Test
	fun `detectCapture flags a digitalCapture camera origin`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val capture = SummaryFactory.detectCapture(parser.parse(json))
		assertTrue(capture.isCameraCapture)
	}

	@Test
	fun `detectCapture is false for an AI-generated image`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		assertFalse(SummaryFactory.detectCapture(parser.parse(json)).isCameraCapture)
	}

	@Test
	fun `detectSoftware flags a digitalCreation origin`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCreation" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val data = parser.parse(json)
		assertTrue(SummaryFactory.detectSoftware(data).isSoftwareCreated)
		assertFalse(SummaryFactory.detectAi(data).isAiGenerated)
		assertFalse(SummaryFactory.detectCapture(data).isCameraCapture)
	}

	@Test
	fun `detectEnhanced flags an algorithmicallyEnhanced origin`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.edited",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicallyEnhanced" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		assertTrue(SummaryFactory.detectEnhanced(parser.parse(json)).isEnhanced)
	}

	@Test
	fun `detectEdited flags edit actions but not a plain capture`() {
		val edited = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created" },
			      { "action": "c2pa.color_adjustments" },
			      { "action": "c2pa.cropped" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val editedResult = SummaryFactory.detectEdited(parser.parse(edited))
		assertTrue(editedResult.isEdited)
		assertTrue(editedResult.actions.contains("c2pa.color_adjustments"))

		val captureOnly = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		assertFalse(SummaryFactory.detectEdited(parser.parse(captureOnly)).isEdited)
	}

	@Test
	fun `detectEdited flags the time-based edits that only video and audio carry`() {
		val transcoded = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created" },
			      { "action": "c2pa.transcoded" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val result = SummaryFactory.detectEdited(parser.parse(transcoded))
		assertTrue(result.isEdited)
		assertTrue(result.actions.contains("c2pa.transcoded"))
	}

	@Test
	fun `primaryOrigin prefers AI over a co-present capture, edits drop to secondary`() {
		val json = """
			{
			  "active_manifest": "m1",
			  "manifests": { "m1": { "assertions": [
			    { "label": "c2pa.actions.v2", "data": { "actions": [
			      { "action": "c2pa.created",
			        "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture" },
			      { "action": "c2pa.color_adjustments" }
			    ] } }
			  ] } },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val summary = SummaryFactory.buildSummary(parser.parse(json), TrustLevel.TRUSTED)
		assertEquals(ContentOrigin.CAMERA_CAPTURE, summary.primaryOrigin())
		assertTrue(summary.hasOriginSignal())
		// Camera is the headline; the edit shows as a secondary chip.
		assertTrue(summary.secondaryOrigins().contains(ContentOrigin.EDITED))
		assertFalse(summary.secondaryOrigins().contains(ContentOrigin.CAMERA_CAPTURE))
	}

	@Test
	fun `revoked certificate is detected and surfaced as untrusted`() {
		val json = """
			{ "active_manifest":"m1","manifests":{"m1":{"signature_info":{"issuer":"X"}}},
			  "validation_state":"Valid",
			  "validation_results":{"activeManifest":{"failure":[
			    {"code":"signingCredential.ocsp.revoked","explanation":"revoked"}]}} }
		""".trimIndent()
		val data = parser.parse(json)
		assertTrue(data.signerRevoked)
		val summary = SummaryFactory.buildSummary(data, TrustLevel.UNTRUSTED)
		assertTrue(summary.revoked)
		assertEquals(OverallStatus.SIGNED_UNTRUSTED, summary.status)
	}

	@Test
	fun `AI generation in a parent manifest wins the headline over a later transcode-enhance`() {
		// The asset was fully AI-generated, then transcoded; the active manifest only records the
		// transcode (algorithmicallyEnhanced) and points back to the AI root via a parentOf ingredient.
		// The headline must still lead with AI-generated, with Enhanced demoted to a secondary chip.
		val json = """
			{
			  "active_manifest": "active",
			  "manifests": {
			    "active": {
			      "ingredients": [ { "relationship": "parentOf", "active_manifest": "root" } ],
			      "assertions": [
			        { "label": "c2pa.actions.v2", "data": { "actions": [
			          { "action": "c2pa.transcoded",
			            "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicallyEnhanced" }
			        ] } }
			      ]
			    },
			    "root": {
			      "assertions": [
			        { "label": "c2pa.actions.v2", "data": { "actions": [
			          { "action": "c2pa.created",
			            "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia" }
			        ] } }
			      ]
			    }
			  },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		val summary = SummaryFactory.buildSummary(parser.parse(json), TrustLevel.TRUSTED)
		assertTrue(summary.ai.isAiGenerated)
		assertEquals(ContentOrigin.AI_GENERATED, summary.primaryOrigin())
		assertTrue(summary.secondaryOrigins().contains(ContentOrigin.ENHANCED))
	}

	@Test
	fun `detectAi ignores AI only in ingredient provenance, not the asset's own claim`() {
		// Active manifest is a plain capture; only an *ingredient* manifest is AI-generated.
		// The asset itself is not AI-generated, so the headline flag must stay false.
		val json = """
			{
			  "active_manifest": "active",
			  "manifests": {
			    "active": {
			      "assertions": [
			        { "label": "c2pa.actions.v2", "data": { "actions": [
			          { "action": "c2pa.created",
			            "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture" }
			        ] } }
			      ]
			    },
			    "ingredient": {
			      "assertions": [
			        { "label": "c2pa.actions.v2", "data": { "actions": [
			          { "action": "c2pa.created",
			            "digitalSourceType": "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia" }
			        ] } }
			      ]
			    }
			  },
			  "validation_state": "Valid"
			}
		""".trimIndent()
		assertFalse(SummaryFactory.detectAi(parser.parse(json)).isAiGenerated)
	}
}
