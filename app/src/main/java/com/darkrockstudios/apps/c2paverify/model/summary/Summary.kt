package com.darkrockstudios.apps.c2paverify.model.summary

import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paManifestData
import com.darkrockstudios.apps.c2paverify.model.common.AssetFormat

/** High-level verdict shown on the summary card. */
enum class OverallStatus {
	/** No embedded C2PA manifest. */
	NO_MANIFEST,

	/** Manifest valid and signer trusted. */
	SIGNED_TRUSTED,

	/** Manifest valid but signer not on a trust list. */
	SIGNED_UNTRUSTED,

	/** Manifest present but failed integrity/signature validation. */
	TAMPERED_INVALID,
}

/** Whether the asset declares AI involvement, derived from `digitalSourceType` assertions. */
data class AiIndicator(
	val isAiGenerated: Boolean,
	/** The raw `digitalSourceType` values found (IPTC URIs), for display/debugging. */
	val sourceTypes: List<String>,
)

/**
 * Whether the asset's own claim says real content was modified/composited with AI — IPTC
 * `compositeWithTrainedAlgorithmicMedia` — as opposed to being wholly AI-generated ([AiIndicator])
 * or untouched.
 */
data class AiModifiedIndicator(
	val isAiModified: Boolean,
	val sourceTypes: List<String>,
)

/**
 * Whether the asset's own claim says it was captured by a device (camera/scanner) — IPTC
 * `digitalCapture` or `computationalCapture` — as opposed to being created or edited in software.
 */
data class CaptureIndicator(
	val isCameraCapture: Boolean,
	val sourceTypes: List<String>,
)

/**
 * Whether the asset's own claim says it was authored in software using non-generative tools — IPTC
 * `digitalCreation` (e.g. digital painting, graphic design, a screenshot) — as opposed to a camera
 * capture or AI generation.
 */
data class SoftwareCreatedIndicator(
	val isSoftwareCreated: Boolean,
	val sourceTypes: List<String>,
)

/**
 * Whether the asset's own claim says it was algorithmically enhanced — IPTC `algorithmicallyEnhanced`
 * (e.g. sharpening, noise reduction, upscaling). Computational processing, not generative AI.
 */
data class EnhancedIndicator(
	val isEnhanced: Boolean,
	val sourceTypes: List<String>,
)

/**
 * Whether the asset was edited after it was originally captured/created, derived from non-trivial
 * `c2pa.actions` codes (crop, colour adjustments, retouch, …). Ordinary human/software edits — not
 * AI generation, which is covered by [AiIndicator]/[AiModifiedIndicator].
 */
data class EditedIndicator(
	val isEdited: Boolean,
	/** The edit action codes found (e.g. `c2pa.color_adjustments`). */
	val actions: List<String>,
)

/** The up-front summary of an inspection. */
data class C2paSummary(
	val status: OverallStatus,
	val signerName: String?,
	val signerCommonName: String?,
	val claimGenerator: String?,
	val signedTime: String?,
	val ai: AiIndicator,
	val aiModified: AiModifiedIndicator = AiModifiedIndicator(isAiModified = false, sourceTypes = emptyList()),
	val capture: CaptureIndicator = CaptureIndicator(isCameraCapture = false, sourceTypes = emptyList()),
	val software: SoftwareCreatedIndicator = SoftwareCreatedIndicator(isSoftwareCreated = false, sourceTypes = emptyList()),
	val enhanced: EnhancedIndicator = EnhancedIndicator(isEnhanced = false, sourceTypes = emptyList()),
	val edited: EditedIndicator = EditedIndicator(isEdited = false, actions = emptyList()),
	/** The signer's certificate was reported revoked (via a stapled OCSP response). */
	val revoked: Boolean = false,
) {
	val manifestPresent: Boolean get() = status != OverallStatus.NO_MANIFEST
}

/**
 * The single most salient content-origin classification for an asset — the headline shown on the
 * summary card. The highest-priority signal wins; any remaining signals surface as secondary chips.
 *
 * [NONE] means no manifest at all; [UNKNOWN] means the asset is signed but declares no recognised
 * origin signal (so the card falls back to leading with the trust verdict).
 */
enum class ContentOrigin {
	AI_GENERATED,
	AI_MODIFIED,
	CAMERA_CAPTURE,
	SOFTWARE_CREATED,
	ENHANCED,
	EDITED,
	UNKNOWN,
	NONE,
}

/** Picks the headline origin in priority order (most-to-least defining of what the asset *is*). */
fun C2paSummary.primaryOrigin(): ContentOrigin = when {
	status == OverallStatus.NO_MANIFEST -> ContentOrigin.NONE
	ai.isAiGenerated -> ContentOrigin.AI_GENERATED
	aiModified.isAiModified -> ContentOrigin.AI_MODIFIED
	capture.isCameraCapture -> ContentOrigin.CAMERA_CAPTURE
	software.isSoftwareCreated -> ContentOrigin.SOFTWARE_CREATED
	enhanced.isEnhanced -> ContentOrigin.ENHANCED
	edited.isEdited -> ContentOrigin.EDITED
	else -> ContentOrigin.UNKNOWN
}

/** True when the asset carries a concrete origin signal (i.e. [primaryOrigin] is neither UNKNOWN nor NONE). */
fun C2paSummary.hasOriginSignal(): Boolean = when (primaryOrigin()) {
	ContentOrigin.UNKNOWN, ContentOrigin.NONE -> false
	else -> true
}

/**
 * The origin signals to show as secondary chips: every detected origin except the [primaryOrigin].
 * The generic "Edited" signal is suppressed when the asset is AI-generated or AI-modified (those
 * already imply modification), to avoid redundant noise.
 */
fun C2paSummary.secondaryOrigins(): List<ContentOrigin> {
	val primary = primaryOrigin()
	val aiInvolved = ai.isAiGenerated || aiModified.isAiModified
	return buildList {
		if (ai.isAiGenerated) add(ContentOrigin.AI_GENERATED)
		if (aiModified.isAiModified) add(ContentOrigin.AI_MODIFIED)
		if (capture.isCameraCapture) add(ContentOrigin.CAMERA_CAPTURE)
		if (software.isSoftwareCreated) add(ContentOrigin.SOFTWARE_CREATED)
		if (enhanced.isEnhanced) add(ContentOrigin.ENHANCED)
		if (edited.isEdited && !aiInvolved) add(ContentOrigin.EDITED)
	}.filter { it != primary }
}

/** Result of inspecting a photo: the headline [summary] plus the full [manifest] (if any). */
data class InspectionResult(
	val summary: C2paSummary,
	val manifest: C2paManifestData?,
	/** Whether the user has an explicit allow/deny rule for this signer. */
	val signerHasOverride: Boolean = false,
	/** How the asset was identified, or null when nothing could identify it. */
	val format: AssetFormat? = null,
)
