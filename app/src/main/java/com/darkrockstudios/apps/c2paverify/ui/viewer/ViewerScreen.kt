package com.darkrockstudios.apps.c2paverify.ui.viewer

import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darkrockstudios.apps.c2paverify.R
import com.darkrockstudios.apps.c2paverify.model.common.AssetFormat
import com.darkrockstudios.apps.c2paverify.model.common.AssetFormats
import com.darkrockstudios.apps.c2paverify.model.common.isPlayable
import com.darkrockstudios.apps.c2paverify.model.common.isRenderableBy
import com.darkrockstudios.apps.c2paverify.model.share.ReportBadge
import com.darkrockstudios.apps.c2paverify.model.share.ReportBadgeStyle
import com.darkrockstudios.apps.c2paverify.model.share.ReportOverlay
import com.darkrockstudios.apps.c2paverify.model.share.toneFor
import com.darkrockstudios.apps.c2paverify.model.summary.C2paSummary
import com.darkrockstudios.apps.c2paverify.model.summary.ContentOrigin
import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus
import com.darkrockstudios.apps.c2paverify.model.summary.UnverifiableReason
import com.darkrockstudios.apps.c2paverify.model.summary.hasOriginSignal
import com.darkrockstudios.apps.c2paverify.model.summary.primaryOrigin
import com.darkrockstudios.apps.c2paverify.model.summary.secondaryOrigins
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionUiState
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionViewModel
import com.darkrockstudios.apps.c2paverify.ui.inspection.SummaryCard
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * Displays the selected/shared photo (Telephoto + Coil 3 zoom/pan/subsampling) and overlays the
 * C2PA summary card once inspection completes. Once loaded, a Share action renders the photo with a
 * verification overlay and hands it to the system share sheet.
 */
/** Gap between the summary card and the bottom of the window. */
private val OverlayBottomPadding = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
	imageUri: String?,
	onBack: () -> Unit,
	onOpenDetails: (() -> Unit)?,
	viewModel: InspectionViewModel = koinViewModel(),
) {
	LaunchedEffect(imageUri) {
		if (imageUri != null) viewModel.inspect(imageUri)
	}
	val state by viewModel.state.collectAsStateWithLifecycle()
	val sharing by viewModel.sharing.collectAsStateWithLifecycle()

	val context = LocalContext.current
	val chooserTitle = stringResource(R.string.share_report_chooser)
	val snackbarHostState = remember { SnackbarHostState() }
	val shareFailedMessage = stringResource(R.string.share_report_failed)
	LaunchedEffect(Unit) {
		viewModel.shareFailures.collect { snackbarHostState.showSnackbar(shareFailedMessage) }
	}
	LaunchedEffect(Unit) {
		viewModel.shareRequests.collect { uriString ->
			val send = Intent(Intent.ACTION_SEND).apply {
				type = "image/png"
				putExtra(Intent.EXTRA_STREAM, uriString.toUri())
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			context.startActivity(Intent.createChooser(send, chooserTitle))
		}
	}

	val loaded = state as? InspectionUiState.Loaded
	val overlay = loaded?.let { reportOverlayFor(it.result.summary) }

	// Until inspection identifies the asset, the file name is the only hint available; without it a
	// video would take the image branch first and show an empty frame. A content URI usually carries
	// no extension, in which case the asset is assumed decodable so a photo never flashes the
	// placeholder. Formats the platform can't decode also can't be drawn into a shareable report.
	val guessedFormat = remember(imageUri) {
		imageUri?.let { AssetFormats.fromFileName(it.substringBefore('?')) }
	}
	val assetFormat = loaded?.result?.format ?: guessedFormat
	val canPreview = assetFormat?.isRenderableBy(Build.VERSION.SDK_INT)
		?: (state !is InspectionUiState.Error)
	val canPlay = assetFormat?.isPlayable() == true

	// While the photo is zoomed in (inspecting), slide the summary card down to a peek so it's out
	// of the way; bring it back when the photo returns to its fit/unzoomed state.
	val zoomState = rememberZoomableImageState()
	val peeking by remember {
		derivedStateOf { (zoomState.zoomableState.zoomFraction ?: 0f) > 0.02f }
	}
	val peekProgress by animateFloatAsState(if (peeking) 1f else 0f, label = "summaryPeek")

	// The player draws its own transport controls along its bottom edge, which is where the summary
	// card sits. Reserving the card's footprint keeps the two apart, so the verdict stays readable
	// and the seek bar stays reachable without either having to move.
	var summaryHeight by remember { mutableStateOf(0.dp) }
	val summaryFootprint = summaryHeight +
		OverlayBottomPadding +
		WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

	Scaffold(
		snackbarHost = { SnackbarHost(snackbarHostState) },
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.viewer_title)) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.back),
						)
					}
				},
				actions = {
					if (sharing) {
						CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp).size(24.dp))
					} else if (overlay != null && (canPreview || canPlay)) {
						IconButton(onClick = { viewModel.shareReport(overlay) }) {
							Icon(Icons.Filled.Share, stringResource(R.string.share_report))
						}
					}
				},
			)
		},
	) { innerPadding ->
		// innerPadding is intentionally not applied: the photo fills the screen edge-to-edge (under
		// the system bars). The chrome stays clear of the bars on its own — the top bar via the
		// Scaffold, and the summary card via navigationBarsPadding() below (which is why we must NOT
		// consume the nav-bar inset here, or the card would drop onto the gesture bar).
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			when {
				imageUri == null -> Text(
					text = stringResource(R.string.no_image),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				canPlay -> VideoPreview(
					uri = imageUri,
					modifier = Modifier.fillMaxSize().padding(bottom = summaryFootprint),
				)

				canPreview -> ZoomableAsyncImage(
					model = imageUri,
					contentDescription = stringResource(R.string.selected_asset),
					state = zoomState,
					modifier = Modifier.fillMaxSize(),
				)

				else -> PreviewUnavailable(assetFormat)
			}

			InspectionOverlay(
				state = state,
				onOpenDetails = onOpenDetails,
				peekFraction = { peekProgress },
				onHeightChanged = { summaryHeight = it },
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.navigationBarsPadding()
					.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = OverlayBottomPadding)
					.widthIn(max = 520.dp),
			)
		}
	}
}

/**
 * Stands in for the asset when the platform has no decoder for it. Reading provenance never needed
 * the pixels, so the summary card below still carries a real verdict.
 */
@Composable
private fun PreviewUnavailable(format: AssetFormat?, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.padding(32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = Icons.Filled.Info,
			contentDescription = null,
			modifier = Modifier.size(64.dp),
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(R.string.preview_unavailable_title),
			style = MaterialTheme.typography.titleMedium,
			textAlign = TextAlign.Center,
		)
		Text(
			text = stringResource(R.string.preview_unavailable_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
		format?.let {
			Text(
				text = it.token,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun InspectionOverlay(
	state: InspectionUiState,
	onOpenDetails: (() -> Unit)?,
	peekFraction: () -> Float,
	onHeightChanged: (Dp) -> Unit,
	modifier: Modifier = Modifier,
) {
	when (state) {
		InspectionUiState.Idle -> Unit
		InspectionUiState.Loading -> CircularProgressIndicator(modifier = modifier)
		is InspectionUiState.Loaded -> {
			// Slide the card down by (its height − a small peek) as the photo zooms in. The peek
			// fraction is read inside offset { } (layout phase) so zoom changes don't recompose.
			val density = LocalDensity.current
			val peekVisiblePx = with(density) { 28.dp.toPx() }
			var cardHeight by remember { mutableIntStateOf(0) }
			SummaryCard(
				summary = state.result.summary,
				onViewDetails = onOpenDetails?.takeIf { state.result.manifest != null },
				modifier = modifier
					.onSizeChanged {
						cardHeight = it.height
						onHeightChanged(with(density) { it.height.toDp() })
					}
					.offset {
						val hideBy = (cardHeight - peekVisiblePx).coerceAtLeast(0f)
						IntOffset(x = 0, y = (peekFraction() * hideBy).roundToInt())
					},
			)
		}

		is InspectionUiState.Error -> Card(
			modifier = modifier.fillMaxWidth(),
			colors = CardDefaults.cardColors(
				containerColor = MaterialTheme.colorScheme.errorContainer,
				contentColor = MaterialTheme.colorScheme.onErrorContainer,
			),
		) {
			Text(
				text = stringResource(R.string.inspect_error, state.message),
				modifier = Modifier.padding(16.dp),
				style = MaterialTheme.typography.bodyMedium,
			)
		}
	}
}

/**
 * Resolves a [C2paSummary] into a localised [ReportOverlay] for the renderer. Mirrors the on-screen
 * summary card: the headline leads with the content origin (falling back to the trust verdict when
 * there's none), the remaining origins become secondary pills, and the verdict is demoted to a line.
 */
/** Why we could not check integrity, phrased for the reader. */
@Composable
private fun unverifiableTagline(summary: C2paSummary): String = when (summary.unverifiableReason) {
	UnverifiableReason.ASSET_TOO_LARGE -> stringResource(R.string.status_unverifiable_too_large)
	// BMFF_INDEXED_XPATH
	else -> stringResource(R.string.status_unverifiable_body)
}

@Composable
private fun reportOverlayFor(summary: C2paSummary): ReportOverlay {
	val statusLabel = when (summary.status) {
		OverallStatus.SIGNED_TRUSTED -> stringResource(R.string.status_trusted)
		OverallStatus.SIGNED_UNTRUSTED -> stringResource(R.string.status_untrusted)
		OverallStatus.TAMPERED_INVALID -> stringResource(R.string.status_tampered)
		// BMFF_INDEXED_XPATH
		OverallStatus.UNVERIFIABLE -> stringResource(R.string.status_unverifiable)
		OverallStatus.NO_MANIFEST -> stringResource(R.string.status_no_manifest)
	}
	val hasOrigin = summary.hasOriginSignal()
	val manifestPresent = summary.status != OverallStatus.NO_MANIFEST
	val origin = if (hasOrigin) originReport(summary.primaryOrigin()) else null

	val headline = origin?.label ?: statusLabel
	val tagline = origin?.tagline
		?: stringResource(R.string.status_no_manifest_body).takeIf { summary.status == OverallStatus.NO_MANIFEST }
		?: unverifiableTagline(summary).takeIf { summary.status == OverallStatus.UNVERIFIABLE }

	// Secondary pills: the same set the card shows (primary excluded, "Edited" suppressed under AI).
	val badges = buildList {
		summary.secondaryOrigins().forEach { add(reportBadge(it)) }
		if (summary.revoked) add(ReportBadge(stringResource(R.string.revoked_badge), ReportBadgeStyle.ALERT))
	}

	val details = buildList {
		if (manifestPresent) {
			summary.signerName?.let { add(stringResource(R.string.summary_signer, it)) }
			summary.claimGenerator?.let { add(stringResource(R.string.summary_generator, it)) }
			summary.signedTime?.let { add(stringResource(R.string.summary_signed_time, it)) }
		}
	}

	return ReportOverlay(
		headline = headline,
		tagline = tagline,
		headlineStyle = origin?.style,
		tone = toneFor(summary.status),
		badges = badges,
		// Demoted verdict line only when an origin led the hero; otherwise the hero already is it.
		trustLabel = statusLabel.takeIf { hasOrigin && manifestPresent },
		details = details,
		watermark = stringResource(R.string.report_watermark),
	)
}

/** Headline label + tagline + accent style for a primary content origin on the report. */
private class OriginReport(val label: String, val tagline: String, val style: ReportBadgeStyle)

@Composable
private fun originReport(origin: ContentOrigin): OriginReport? = when (origin) {
	ContentOrigin.AI_GENERATED -> OriginReport(
		stringResource(R.string.ai_badge), stringResource(R.string.origin_ai_tagline), ReportBadgeStyle.AI,
	)

	ContentOrigin.AI_MODIFIED -> OriginReport(
		stringResource(R.string.ai_modified_badge), stringResource(R.string.origin_ai_modified_tagline), ReportBadgeStyle.AI,
	)

	ContentOrigin.CAMERA_CAPTURE -> OriginReport(
		stringResource(R.string.capture_badge), stringResource(R.string.origin_capture_tagline), ReportBadgeStyle.CAPTURE,
	)

	ContentOrigin.SOFTWARE_CREATED -> OriginReport(
		stringResource(R.string.software_badge), stringResource(R.string.origin_software_tagline), ReportBadgeStyle.SOFTWARE,
	)

	ContentOrigin.ENHANCED -> OriginReport(
		stringResource(R.string.enhanced_badge), stringResource(R.string.origin_enhanced_tagline), ReportBadgeStyle.ENHANCED,
	)

	ContentOrigin.EDITED -> OriginReport(
		stringResource(R.string.edited_badge), stringResource(R.string.origin_edited_tagline), ReportBadgeStyle.EDITED,
	)

	ContentOrigin.UNKNOWN, ContentOrigin.NONE -> null
}

/** Secondary content-origin pill for the report. */
@Composable
private fun reportBadge(origin: ContentOrigin): ReportBadge = when (origin) {
	ContentOrigin.AI_GENERATED -> ReportBadge(stringResource(R.string.ai_badge), ReportBadgeStyle.AI)
	ContentOrigin.AI_MODIFIED -> ReportBadge(stringResource(R.string.ai_modified_badge), ReportBadgeStyle.AI)
	ContentOrigin.CAMERA_CAPTURE -> ReportBadge(stringResource(R.string.capture_badge), ReportBadgeStyle.CAPTURE)
	ContentOrigin.SOFTWARE_CREATED -> ReportBadge(stringResource(R.string.software_badge), ReportBadgeStyle.SOFTWARE)
	ContentOrigin.ENHANCED -> ReportBadge(stringResource(R.string.enhanced_badge), ReportBadgeStyle.ENHANCED)
	ContentOrigin.EDITED -> ReportBadge(stringResource(R.string.edited_badge), ReportBadgeStyle.EDITED)
	ContentOrigin.UNKNOWN, ContentOrigin.NONE -> ReportBadge(stringResource(R.string.edited_badge), ReportBadgeStyle.EDITED)
}
