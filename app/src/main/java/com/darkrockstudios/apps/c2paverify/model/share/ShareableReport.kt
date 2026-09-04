package com.darkrockstudios.apps.c2paverify.model.share

import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus

/** Colour family for the status accent drawn on a shareable report. */
enum class ReportTone { TRUSTED, UNTRUSTED, INVALID, NEUTRAL }

/** Visual emphasis for a report badge pill (drives its colour/glyph). */
enum class ReportBadgeStyle { AI, CAPTURE, SOFTWARE, ENHANCED, EDITED, ALERT }

/** One indicator pill on the report, e.g. "AI-generated" or "Revoked certificate". */
data class ReportBadge(val label: String, val style: ReportBadgeStyle)

/**
 * Everything the (Android) report renderer needs to draw the verification overlay onto a photo,
 * expressed as already-localised, platform-neutral values. The UI layer resolves string resources
 * and builds this; the renderer only paints. Keeping it pure leaves the upper layers KMP-clean.
 *
 * The panel leads with the content **origin** (what the photo is — AI-generated, camera capture, …)
 * and demotes the signing/trust verdict to a secondary line, mirroring the on-screen summary card.
 * When the asset declares no recognised origin, the headline falls back to the trust verdict
 * ([headlineStyle] is then null and [trustLabel] is null, since the hero already is the verdict).
 */
data class ReportOverlay(
	/** Hero headline: the primary content-origin label, or the trust verdict when there's no origin. */
	val headline: String,
	/** Short subtitle under the headline (origin tagline, or no-manifest body). May be null. */
	val tagline: String?,
	/** Non-null → origin hero, painted in this badge style's accent; null → trust-verdict hero. */
	val headlineStyle: ReportBadgeStyle?,
	/** Trust verdict tone, used for the small status dot. */
	val tone: ReportTone,
	/** Secondary content-origin pills + a revoked-certificate alert. May be empty. */
	val badges: List<ReportBadge>,
	/** Demoted trust verdict line beneath an origin hero; null when the hero already is the verdict. */
	val trustLabel: String?,
	/** Signer / generator / signed-time lines, in display order. May be empty. */
	val details: List<String>,
	/** Brand mark, e.g. "Verified with C2PAViewer". */
	val watermark: String,
)

/** Maps the inspection verdict to a report accent tone. */
fun toneFor(status: OverallStatus): ReportTone = when (status) {
	OverallStatus.SIGNED_TRUSTED -> ReportTone.TRUSTED
	OverallStatus.SIGNED_UNTRUSTED -> ReportTone.UNTRUSTED
	OverallStatus.TAMPERED_INVALID -> ReportTone.INVALID
	// BMFF_INDEXED_XPATH
	OverallStatus.UNVERIFIABLE -> ReportTone.NEUTRAL
	OverallStatus.NO_MANIFEST -> ReportTone.NEUTRAL
}
