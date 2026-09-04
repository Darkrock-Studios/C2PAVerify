package com.darkrockstudios.apps.c2paverify.ui.inspection

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darkrockstudios.apps.c2paverify.R
import com.darkrockstudios.apps.c2paverify.model.summary.C2paSummary
import com.darkrockstudios.apps.c2paverify.model.summary.ContentOrigin
import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus
import com.darkrockstudios.apps.c2paverify.model.summary.UnverifiableReason
import com.darkrockstudios.apps.c2paverify.model.summary.hasOriginSignal
import com.darkrockstudios.apps.c2paverify.model.summary.primaryOrigin
import com.darkrockstudios.apps.c2paverify.model.summary.secondaryOrigins

/**
 * Up-front summary of an inspection. The card leads with **what the content actually is** — its
 * content-origin headline (AI-generated, camera capture, …) drawn from the C2PA `digitalSourceType`
 * / actions — because that is what most people care about first. The signing/trust verdict is still
 * shown, but demoted to a secondary line beneath. When the asset declares no recognised origin
 * (or has no manifest at all), the headline falls back to the trust verdict.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryCard(
	summary: C2paSummary,
	modifier: Modifier = Modifier,
	onViewDetails: (() -> Unit)? = null,
) {
	val hasOrigin = summary.hasOriginSignal()
	val originVisuals = if (hasOrigin) originVisuals(summary.primaryOrigin()) else null
	val statusVisuals = statusVisuals(summary.status)
	val manifestPresent = summary.status != OverallStatus.NO_MANIFEST

	var infoChip by remember { mutableStateOf<ChipInfo?>(null) }
	infoChip?.let { chip -> ChipInfoDialog(chip, onDismiss = { infoChip = null }) }

	val container = originVisuals?.container ?: statusVisuals.container
	val onContainer = originVisuals?.onContainer ?: statusVisuals.onContainer
	// A slightly stronger tint, derived from the headline's own accent, so the hero band reads as
	// the most prominent part of the card and the details below sit on the plainer container.
	val heroAccent = originVisuals?.accent ?: trustTint(summary.status)
	val heroBackground = heroAccent.copy(alpha = 0.14f)

	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			// HERO: the content-origin headline (or the trust verdict, when there's no origin signal).
			// Full-bleed, tinted band so it stands out above the details.
			val heroInfo = originVisuals?.info
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.background(heroBackground)
					.then(if (heroInfo != null) Modifier.clickable { infoChip = heroInfo } else Modifier)
					.padding(16.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (originVisuals != null) {
					Icon(
						painter = painterResource(originVisuals.iconRes),
						contentDescription = null,
						modifier = Modifier.size(32.dp),
						tint = originVisuals.accent,
					)
				} else if (summary.status == OverallStatus.NO_MANIFEST) {
					// "Missing provenance" mark for the no-Content-Credentials case.
					Icon(
						painter = painterResource(R.drawable.ic_unknown),
						contentDescription = null,
						modifier = Modifier.size(32.dp),
					)
				} else {
					Icon(statusVisuals.icon, contentDescription = null, modifier = Modifier.size(32.dp))
				}
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = originVisuals?.label ?: statusVisuals.label,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
					)
					val tagline = originVisuals?.tagline ?: heroFallbackTagline(summary)
					if (tagline != null) {
						Text(
							text = tagline,
							style = MaterialTheme.typography.bodySmall,
							color = onContainer.copy(alpha = 0.8f),
						)
					}
				}
				if (heroInfo != null) {
					Icon(
						Icons.Filled.Info,
						contentDescription = stringResource(heroInfo.titleRes),
						modifier = Modifier.size(18.dp),
						tint = onContainer.copy(alpha = 0.7f),
					)
				}
			}

			Column(
				modifier = Modifier.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				// SECONDARY CHIPS: the remaining origin signals, plus a revoked-certificate alert.
				val secondary = summary.secondaryOrigins()
				if (secondary.isNotEmpty() || summary.revoked) {
					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						secondary.forEach { origin ->
							OriginChip(origin, onClick = { infoChip = chipInfoFor(origin) })
						}
						if (summary.revoked) {
							AssistChip(
								onClick = { infoChip = ChipInfo.REVOKED },
								label = { Text(stringResource(R.string.revoked_badge)) },
								leadingIcon = {
									Icon(
										painter = painterResource(ChipInfo.REVOKED.iconRes),
										contentDescription = null,
										modifier = Modifier.size(AssistChipDefaults.IconSize),
									)
								},
								colors = AssistChipDefaults.assistChipColors(
									labelColor = MaterialTheme.colorScheme.error,
									leadingIconContentColor = MaterialTheme.colorScheme.error,
								),
							)
						}
					}
				}

				// TRUST (demoted): a compact verdict line + signer details, beneath the origin headline.
				when {
					summary.status == OverallStatus.NO_MANIFEST -> Unit

					hasOrigin -> TrustLine(summary, onContainer)

					// No origin signal: the hero already showed the verdict, so just list the signer.
					else -> SignerLines(summary, onContainer)
				}

				if (onViewDetails != null) {
					TextButton(onClick = onViewDetails) {
						Icon(
							Icons.Filled.Search,
							contentDescription = null,
							modifier = Modifier.size(ButtonDefaults.IconSize),
						)
						Spacer(Modifier.size(ButtonDefaults.IconSpacing))
						Text(stringResource(R.string.view_details))
					}
				}
			}
		}
	}
}

/** The compact, demoted trust verdict: a small tinted icon + label, then the signer details. */
@Composable
private fun TrustLine(summary: C2paSummary, onContainer: Color) {
	val visuals = statusVisuals(summary.status)
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				visuals.icon,
				contentDescription = null,
				modifier = Modifier.size(18.dp),
				tint = trustTint(summary.status),
			)
			Text(
				text = visuals.label,
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.Medium,
				color = onContainer,
			)
		}
		SignerLines(summary, onContainer)
	}
}

@Composable
private fun SignerLines(summary: C2paSummary, onContainer: Color) {
	summary.signerName?.let {
		Text(
			text = stringResource(R.string.summary_signer, it),
			style = MaterialTheme.typography.bodySmall,
			color = onContainer.copy(alpha = 0.85f),
		)
	}
	summary.claimGenerator?.let {
		Text(
			text = stringResource(R.string.summary_generator, it),
			style = MaterialTheme.typography.bodySmall,
			color = onContainer.copy(alpha = 0.7f),
		)
	}
	summary.signedTime?.let {
		Text(
			text = stringResource(R.string.summary_signed_time, it),
			style = MaterialTheme.typography.bodySmall,
			color = onContainer.copy(alpha = 0.7f),
		)
	}
}

@Composable
private fun OriginChip(origin: ContentOrigin, onClick: () -> Unit) {
	val visuals = originVisuals(origin)
	AssistChip(
		onClick = onClick,
		label = { Text(visuals.label) },
		leadingIcon = {
			Icon(
				painter = painterResource(visuals.iconRes),
				contentDescription = null,
				modifier = Modifier.size(AssistChipDefaults.IconSize),
			)
		},
		colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = visuals.accent),
	)
}

/** Subtitle for the fallback (no-origin) hero: only a couple of verdicts need one. */
@Composable
private fun heroFallbackTagline(summary: C2paSummary): String? = when (summary.status) {
	OverallStatus.NO_MANIFEST -> stringResource(R.string.status_no_manifest_body)
	OverallStatus.UNVERIFIABLE -> unverifiableBody(summary)
	else -> null
}

/** Why we could not check integrity, phrased for the reader. */
@Composable
private fun unverifiableBody(summary: C2paSummary): String = when (summary.unverifiableReason) {
	UnverifiableReason.ASSET_TOO_LARGE -> stringResource(R.string.status_unverifiable_too_large)
	// BMFF_INDEXED_XPATH
	else -> stringResource(R.string.status_unverifiable_body)
}

/** Non-composable origin → explainer mapping, for use inside click handlers. */
private fun chipInfoFor(origin: ContentOrigin): ChipInfo = when (origin) {
	ContentOrigin.AI_GENERATED -> ChipInfo.AI
	ContentOrigin.AI_MODIFIED -> ChipInfo.AI_MODIFIED
	ContentOrigin.CAMERA_CAPTURE -> ChipInfo.CAPTURE
	ContentOrigin.SOFTWARE_CREATED -> ChipInfo.SOFTWARE
	ContentOrigin.ENHANCED -> ChipInfo.ENHANCED
	ContentOrigin.EDITED -> ChipInfo.EDITED
	ContentOrigin.UNKNOWN, ContentOrigin.NONE -> ChipInfo.AI
}

private data class OriginVisuals(
	val label: String,
	val tagline: String,
	val container: Color,
	val onContainer: Color,
	val accent: Color,
	val iconRes: Int,
	val info: ChipInfo,
)

/** Theme-aware identity (colour + icon + copy + explainer) for each concrete content origin. */
@Composable
private fun originVisuals(origin: ContentOrigin): OriginVisuals {
	val dark = isSystemInDarkTheme()
	return when (origin) {
		ContentOrigin.AI_GENERATED -> OriginVisuals(
			label = stringResource(R.string.ai_badge),
			tagline = stringResource(R.string.origin_ai_tagline),
			container = if (dark) Color(0xFF2A2140) else Color(0xFFEDE7FF),
			onContainer = if (dark) Color(0xFFD6C7FF) else Color(0xFF3A2275),
			accent = if (dark) Color(0xFFB39DFF) else Color(0xFF6A3FD0),
			iconRes = ChipInfo.AI.iconRes,
			info = ChipInfo.AI,
		)

		ContentOrigin.AI_MODIFIED -> OriginVisuals(
			label = stringResource(R.string.ai_modified_badge),
			tagline = stringResource(R.string.origin_ai_modified_tagline),
			container = if (dark) Color(0xFF2A2140) else Color(0xFFEDE7FF),
			onContainer = if (dark) Color(0xFFD6C7FF) else Color(0xFF3A2275),
			accent = if (dark) Color(0xFFB39DFF) else Color(0xFF6A3FD0),
			iconRes = ChipInfo.AI_MODIFIED.iconRes,
			info = ChipInfo.AI_MODIFIED,
		)

		ContentOrigin.CAMERA_CAPTURE -> OriginVisuals(
			label = stringResource(R.string.capture_badge),
			tagline = stringResource(R.string.origin_capture_tagline),
			container = if (dark) Color(0xFF13283F) else Color(0xFFDBEAFF),
			onContainer = if (dark) Color(0xFFAACBF5) else Color(0xFF0B3D80),
			accent = if (dark) Color(0xFF7FB5F0) else Color(0xFF1565C0),
			iconRes = ChipInfo.CAPTURE.iconRes,
			info = ChipInfo.CAPTURE,
		)

		ContentOrigin.SOFTWARE_CREATED -> OriginVisuals(
			label = stringResource(R.string.software_badge),
			tagline = stringResource(R.string.origin_software_tagline),
			container = if (dark) Color(0xFF0E312D) else Color(0xFFD3F1EC),
			onContainer = if (dark) Color(0xFF80E0D2) else Color(0xFF00564E),
			accent = if (dark) Color(0xFF57C9BA) else Color(0xFF00897B),
			iconRes = ChipInfo.SOFTWARE.iconRes,
			info = ChipInfo.SOFTWARE,
		)

		ContentOrigin.ENHANCED -> OriginVisuals(
			label = stringResource(R.string.enhanced_badge),
			tagline = stringResource(R.string.origin_enhanced_tagline),
			container = if (dark) Color(0xFF382414) else Color(0xFFFFE6D2),
			onContainer = if (dark) Color(0xFFF4B98D) else Color(0xFF8A3C09),
			accent = if (dark) Color(0xFFEFA06A) else Color(0xFFEF6C00),
			iconRes = ChipInfo.ENHANCED.iconRes,
			info = ChipInfo.ENHANCED,
		)

		ContentOrigin.EDITED -> OriginVisuals(
			label = stringResource(R.string.edited_badge),
			tagline = stringResource(R.string.origin_edited_tagline),
			container = if (dark) Color(0xFF1F2440) else Color(0xFFE2E5FF),
			onContainer = if (dark) Color(0xFFB7C0FF) else Color(0xFF2C3580),
			accent = if (dark) Color(0xFF93A0F0) else Color(0xFF3F51B5),
			iconRes = ChipInfo.EDITED.iconRes,
			info = ChipInfo.EDITED,
		)

		// hasOriginSignal() guards against these reaching originVisuals(); map them defensively.
		ContentOrigin.UNKNOWN, ContentOrigin.NONE -> OriginVisuals(
			label = stringResource(R.string.status_no_manifest),
			tagline = "",
			container = MaterialTheme.colorScheme.surfaceVariant,
			onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
			accent = MaterialTheme.colorScheme.onSurfaceVariant,
			iconRes = ChipInfo.AI.iconRes,
			info = ChipInfo.AI,
		)
	}
}

private data class StatusVisuals(
	val label: String,
	val container: Color,
	val onContainer: Color,
	val icon: ImageVector,
)

/** Semantic, theme-aware colours for the trust verdict (M3 has no standard success/warning role). */
@Composable
private fun statusVisuals(status: OverallStatus): StatusVisuals {
	val dark = isSystemInDarkTheme()
	return when (status) {
		OverallStatus.SIGNED_TRUSTED -> StatusVisuals(
			label = stringResource(R.string.status_trusted),
			container = if (dark) Color(0xFF14352A) else Color(0xFFD7F2E1),
			onContainer = if (dark) Color(0xFFA6F4C5) else Color(0xFF0B5132),
			icon = Icons.Filled.CheckCircle,
		)

		OverallStatus.SIGNED_UNTRUSTED -> StatusVisuals(
			label = stringResource(R.string.status_untrusted),
			container = if (dark) Color(0xFF3A3016) else Color(0xFFFFF0C2),
			onContainer = if (dark) Color(0xFFF6D77A) else Color(0xFF5A4500),
			icon = Icons.Filled.Warning,
		)

		OverallStatus.TAMPERED_INVALID -> StatusVisuals(
			label = stringResource(R.string.status_tampered),
			container = MaterialTheme.colorScheme.errorContainer,
			onContainer = MaterialTheme.colorScheme.onErrorContainer,
			icon = Icons.Filled.Warning,
		)

		// BMFF_INDEXED_XPATH
		OverallStatus.UNVERIFIABLE -> StatusVisuals(
			label = stringResource(R.string.status_unverifiable),
			container = if (dark) Color(0xFF3A3016) else Color(0xFFFFF0C2),
			onContainer = if (dark) Color(0xFFF6D77A) else Color(0xFF5A4500),
			icon = Icons.Filled.Info,
		)

		OverallStatus.NO_MANIFEST -> StatusVisuals(
			label = stringResource(R.string.status_no_manifest),
			container = MaterialTheme.colorScheme.surfaceVariant,
			onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
			icon = Icons.Filled.Info,
		)
	}
}

/** Strong, theme-aware tint for the small trust-verdict icon, legible on any origin-tinted card. */
@Composable
private fun trustTint(status: OverallStatus): Color {
	val dark = isSystemInDarkTheme()
	return when (status) {
		OverallStatus.SIGNED_TRUSTED -> if (dark) Color(0xFF7FE0A6) else Color(0xFF1E7A43)
		OverallStatus.SIGNED_UNTRUSTED -> if (dark) Color(0xFFE6C25A) else Color(0xFF9A6800)
		OverallStatus.TAMPERED_INVALID -> MaterialTheme.colorScheme.error
		// BMFF_INDEXED_XPATH
		OverallStatus.UNVERIFIABLE -> if (dark) Color(0xFFE6C25A) else Color(0xFF9A6800)
		OverallStatus.NO_MANIFEST -> MaterialTheme.colorScheme.onSurfaceVariant
	}
}

/** The tappable summary chips and the explanation each one shows. */
private enum class ChipInfo(val iconRes: Int, val titleRes: Int, val bodyRes: Int) {
	AI(R.drawable.ic_auto_awesome, R.string.ai_badge, R.string.ai_badge_explanation),
	AI_MODIFIED(R.drawable.ic_auto_fix, R.string.ai_modified_badge, R.string.ai_modified_explanation),
	CAPTURE(R.drawable.ic_camera, R.string.capture_badge, R.string.capture_badge_explanation),
	SOFTWARE(R.drawable.ic_brush, R.string.software_badge, R.string.software_badge_explanation),
	ENHANCED(R.drawable.ic_tune, R.string.enhanced_badge, R.string.enhanced_badge_explanation),
	EDITED(R.drawable.ic_edit, R.string.edited_badge, R.string.edited_badge_explanation),
	REVOKED(R.drawable.ic_block, R.string.revoked_badge, R.string.revoked_explanation),
}

@Composable
private fun ChipInfoDialog(chip: ChipInfo, onDismiss: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		icon = { Icon(painterResource(chip.iconRes), contentDescription = null) },
		title = { Text(stringResource(chip.titleRes)) },
		text = { Text(stringResource(chip.bodyRes)) },
		confirmButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
		},
	)
}
