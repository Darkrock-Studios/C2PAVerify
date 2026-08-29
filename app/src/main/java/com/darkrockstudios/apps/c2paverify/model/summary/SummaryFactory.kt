package com.darkrockstudios.apps.c2paverify.model.summary

import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paManifestData
import com.darkrockstudios.apps.c2paverify.model.c2pa.ManifestValidationState
import com.darkrockstudios.apps.c2paverify.model.trust.TrustLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure domain logic that turns a parsed manifest + trust verdict into a [C2paSummary]. Lives in
 * `model/` (not a use case) because it is non-I/O and trivially unit-testable.
 */
object SummaryFactory {

	fun buildSummary(manifest: C2paManifestData?, trust: TrustLevel): C2paSummary {
		if (manifest == null) {
			return C2paSummary(
				status = OverallStatus.NO_MANIFEST,
				signerName = null,
				signerCommonName = null,
				claimGenerator = null,
				signedTime = null,
				ai = AiIndicator(isAiGenerated = false, sourceTypes = emptyList()),
				aiModified = AiModifiedIndicator(isAiModified = false, sourceTypes = emptyList()),
				capture = CaptureIndicator(isCameraCapture = false, sourceTypes = emptyList()),
				software = SoftwareCreatedIndicator(isSoftwareCreated = false, sourceTypes = emptyList()),
				enhanced = EnhancedIndicator(isEnhanced = false, sourceTypes = emptyList()),
				edited = EditedIndicator(isEdited = false, actions = emptyList()),
			)
		}

		val active = manifest.activeManifest
		val status = when {
			manifest.validationState == ManifestValidationState.INVALID ||
				manifest.integrityFailures.isNotEmpty() -> OverallStatus.TAMPERED_INVALID

			trust == TrustLevel.TRUSTED -> OverallStatus.SIGNED_TRUSTED
			else -> OverallStatus.SIGNED_UNTRUSTED
		}

		return C2paSummary(
			status = status,
			signerName = active?.signature?.issuer,
			signerCommonName = active?.signature?.commonName,
			claimGenerator = active?.claimGenerator,
			signedTime = active?.signature?.time,
			ai = detectAi(manifest),
			aiModified = detectAiModified(manifest),
			capture = detectCapture(manifest),
			software = detectSoftware(manifest),
			enhanced = detectEnhanced(manifest),
			edited = detectEdited(manifest),
			revoked = manifest.signerRevoked,
		)
	}

	/**
	 * Flags AI involvement from the **asset's own provenance chain** — `digitalSourceType` values that
	 * denote algorithmic media (IPTC: `trainedAlgorithmicMedia`) anywhere in the active manifest or its
	 * `parentOf` ancestors. This catches an asset that was AI-generated and then merely transcoded or
	 * enhanced, where only an earlier link in the chain carries the generation claim. Incorporated
	 * ingredients (`componentOf`) are *not* scanned: a normal photo that merely mixed in an
	 * AI-generated ingredient is not itself "AI-generated". See [chainSourceTypes].
	 */
	fun detectAi(manifest: C2paManifestData): AiIndicator {
		val sourceTypes = chainSourceTypes(manifest)
		val isAi = sourceTypes.any { it.segment() in GENERATED_SOURCE_TYPES }
		return AiIndicator(isAiGenerated = isAi, sourceTypes = sourceTypes)
	}

	/**
	 * Flags real content that was modified/composited with AI — IPTC
	 * `compositeWithTrainedAlgorithmicMedia` on the active claim (e.g. a camera photo with a
	 * generative-fill edit). Distinct from [detectAi] (wholly generated). Best-effort: tools that
	 * record only a software-agent name and no `digitalSourceType` won't be detected.
	 */
	fun detectAiModified(manifest: C2paManifestData): AiModifiedIndicator {
		val sourceTypes = chainSourceTypes(manifest)
		val isModified = sourceTypes.any { it.segment() in MODIFIED_SOURCE_TYPES }
		return AiModifiedIndicator(isAiModified = isModified, sourceTypes = sourceTypes)
	}

	/**
	 * Flags a capture-device origin from the asset's own claim — IPTC `digitalCapture` (a camera or
	 * scanner) or `computationalCapture` (computational photography). Scans the asset's own provenance
	 * chain, matching [detectAi]'s scope.
	 */
	fun detectCapture(manifest: C2paManifestData): CaptureIndicator {
		val sourceTypes = chainSourceTypes(manifest)
		val isCapture = sourceTypes.any { it.segment() in CAPTURE_SOURCE_TYPES }
		return CaptureIndicator(isCameraCapture = isCapture, sourceTypes = sourceTypes)
	}

	/**
	 * Flags software-authored media — IPTC `digitalCreation` ("created by a human using
	 * non-generative tools": digital painting, graphic design, a screenshot). Distinct from a camera
	 * capture and from AI generation. Scans the asset's own provenance chain.
	 */
	fun detectSoftware(manifest: C2paManifestData): SoftwareCreatedIndicator {
		val sourceTypes = chainSourceTypes(manifest)
		val isSoftware = sourceTypes.any { it.segment() in SOFTWARE_SOURCE_TYPES }
		return SoftwareCreatedIndicator(isSoftwareCreated = isSoftware, sourceTypes = sourceTypes)
	}

	/**
	 * Flags algorithmically enhanced media — IPTC `algorithmicallyEnhanced` (sharpening, noise
	 * reduction, upscaling). Computational processing, not generative AI. Scans the asset's own
	 * provenance chain.
	 */
	fun detectEnhanced(manifest: C2paManifestData): EnhancedIndicator {
		val sourceTypes = chainSourceTypes(manifest)
		val isEnhanced = sourceTypes.any { it.segment() in ENHANCED_SOURCE_TYPES }
		return EnhancedIndicator(isEnhanced = isEnhanced, sourceTypes = sourceTypes)
	}

	/**
	 * Flags ordinary edits after capture/creation, derived from the `c2pa.actions` codes across the
	 * asset's own provenance chain — crop, colour adjustments, retouch, etc. ([EDIT_ACTIONS]).
	 * Pure-creation actions like `c2pa.created`/`c2pa.opened` are ignored, so a freshly
	 * captured-and-signed photo is *not* flagged. These are human/software edits, not AI generation.
	 */
	fun detectEdited(manifest: C2paManifestData): EditedIndicator {
		val edits = chainActionCodes(manifest).filter { it.lowercase() in EDIT_ACTIONS }
		return EditedIndicator(isEdited = edits.isNotEmpty(), actions = edits)
	}

	/** Last path segment of an IPTC digitalSourceType URI, lower-cased. */
	private fun String.segment(): String = substringAfterLast('/').lowercase()

	// Wholly AI-generated (no real source material).
	private val GENERATED_SOURCE_TYPES = setOf("trainedalgorithmicmedia", "algorithmicmedia")

	// Real content modified/composited with AI.
	private val MODIFIED_SOURCE_TYPES = setOf("compositewithtrainedalgorithmicmedia")

	private val CAPTURE_SOURCE_TYPES = setOf("digitalcapture", "computationalcapture")

	// Authored in software with non-generative tools.
	private val SOFTWARE_SOURCE_TYPES = setOf("digitalcreation")

	// Algorithmically enhanced (sharpen / denoise / upscale), not generative.
	private val ENHANCED_SOURCE_TYPES = setOf("algorithmicallyenhanced")

	// c2pa.actions codes that mean the content was altered after it was first created.
	private val EDIT_ACTIONS = setOf(
		"c2pa.color_adjustments",
		"c2pa.cropped",
		"c2pa.drawing",
		"c2pa.edited",
		"c2pa.filtered",
		"c2pa.orientation",
		"c2pa.resized",
		"c2pa.placed",
		"c2pa.removed",
		"c2pa.spliced",
		"c2pa.painted",
		"c2pa.retouched",
		"c2pa.redacted",
		// Time-based edits, which only appear on video and audio.
		"c2pa.transcoded",
		"c2pa.dubbed",
		"c2pa.translated",
	)

	/**
	 * Every `digitalSourceType` declared anywhere in the asset's own provenance chain — the active
	 * manifest plus its `parentOf` ancestors (i.e. earlier versions of *this* asset). Incorporated
	 * ingredients (`componentOf` etc.) are deliberately excluded: they describe other assets that were
	 * merely mixed in, not what this asset itself is. See [C2paManifestData.manifestChain].
	 */
	private fun chainSourceTypes(manifest: C2paManifestData): List<String> = buildList {
		manifest.manifestChain.forEach { m ->
			m.assertions.forEach { a -> collectStringValues(a.data, "digitalSourceType", this) }
		}
	}.distinct()

	private fun chainActionCodes(manifest: C2paManifestData): List<String> = buildList {
		manifest.manifestChain.forEach { m ->
			m.assertions
				.filter { it.label.startsWith("c2pa.actions") }
				.forEach { a -> collectStringValues(a.data, "action", this) }
		}
	}.distinct()

	/** Recursively collects every string value stored under [key] anywhere within [element]. */
	private fun collectStringValues(element: JsonElement?, key: String, out: MutableList<String>) {
		when (element) {
			is JsonObject -> element.forEach { (k, value) ->
				if (k == key && value is JsonPrimitive && value.isString) {
					out.add(value.content)
				} else {
					collectStringValues(value, key, out)
				}
			}

			is JsonArray -> element.forEach { collectStringValues(it, key, out) }
			else -> Unit
		}
	}
}
