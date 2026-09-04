package com.darkrockstudios.apps.c2paverify.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.darkrockstudios.apps.c2paverify.R
import com.darkrockstudios.apps.c2paverify.model.common.pdfRasterScale
import io.github.aakira.napier.Napier
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.nativerenderer.AndroidPdfBitmapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * Draws [uri] a page at a time, in place of the still-image preview.
 *
 * Pages are rasterised by KitePDF and handed to the same [ZoomableState] the image path uses, so a
 * PDF zooms and pans exactly like a photo and drives the summary card's peek the same way. That
 * shared state is why this renders into Telephoto rather than using KitePDF's own viewer.
 *
 * Unlike the video and image paths, which stream or let Coil manage memory, the whole document is
 * held: KitePDF parses from a byte array. PDFs are the one accepted format small enough for that,
 * and the C2PA read has already loaded the same bytes for its hard binding.
 */
@Composable
fun PdfPreview(
	uri: String,
	zoomableState: ZoomableState,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	var document by remember(uri) { mutableStateOf<PdfDocument?>(null) }
	var unreadable by remember(uri) { mutableStateOf(false) }
	var pageIndex by rememberSaveable(uri) { mutableIntStateOf(0) }
	var viewport by remember { mutableStateOf(IntSize.Zero) }
	var page by remember(uri) { mutableStateOf<Bitmap?>(null) }

	LaunchedEffect(uri) {
		val opened = withContext(Dispatchers.IO) {
			runCatching {
				val bytes = context.contentResolver.openInputStream(uri.toUri())
					?.use { it.readBytes() }
					?: return@runCatching null
				// An encrypted document parses but cannot be drawn, so it belongs with the failures
				// rather than showing a page of blanks.
				PdfDocument.openOrNull(bytes)?.takeIf { !it.isEncrypted }
			}.onFailure {
				Napier.w(tag = TAG, throwable = it) { "Could not open the PDF" }
			}.getOrNull()
		}
		document = opened
		unreadable = opened == null
	}

	val doc = document
	LaunchedEffect(doc, pageIndex, viewport) {
		if (doc == null || viewport == IntSize.Zero) return@LaunchedEffect
		val rendered = withContext(Dispatchers.Default) {
			runCatching {
				val target = doc.pages[pageIndex]
				AndroidPdfBitmapRenderer.renderToBitmap(
					target,
					scaleFor(target.displayWidth, target.displayHeight, viewport),
					Color.WHITE,
					MAX_PIXELS,
				)
			}.onFailure {
				Napier.w(tag = TAG, throwable = it) { "Could not render PDF page $pageIndex" }
			}.getOrNull()
		}
		page = rendered
		unreadable = rendered == null
		if (rendered != null) {
			zoomableState.setContentLocation(
				ZoomableContentLocation.scaledInsideAndCenterAligned(
					Size(rendered.width.toFloat(), rendered.height.toFloat()),
				),
			)
		}
	}

	Box(
		modifier = modifier.onSizeChanged { viewport = it },
		contentAlignment = Alignment.Center,
	) {
		val bitmap = page
		when {
			unreadable -> Unreadable()
			bitmap != null -> Image(
				bitmap = bitmap.asImageBitmap(),
				contentDescription = stringResource(R.string.selected_asset),
				contentScale = ContentScale.Fit,
				modifier = Modifier
					.fillMaxSize()
					.zoomable(state = zoomableState, gestures = EnabledZoomGestures.ZoomAndPan),
			)

			else -> CircularProgressIndicator()
		}

		val pageCount = doc?.pageCount ?: 0
		if (bitmap != null && pageCount > 1) {
			PageControls(
				pageIndex = pageIndex,
				pageCount = pageCount,
				onPage = { pageIndex = it },
				modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
			)
		}
	}
}

/**
 * Page navigation, shown only for a document that has more than one page.
 *
 * Deliberately buttons rather than a swipe pager: [zoomable] claims horizontal drags for panning,
 * and a pager underneath it would fight for the same gesture.
 */
@Composable
private fun PageControls(
	pageIndex: Int,
	pageCount: Int,
	onPage: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier,
		shape = MaterialTheme.shapes.extraLarge,
		color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
		tonalElevation = 3.dp,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			modifier = Modifier.padding(horizontal = 4.dp),
		) {
			IconButton(
				onClick = { onPage(pageIndex - 1) },
				enabled = pageIndex > 0,
			) {
				Icon(
					imageVector = Icons.AutoMirrored.Filled.ArrowBack,
					contentDescription = stringResource(R.string.pdf_previous_page),
				)
			}
			Text(
				text = stringResource(R.string.pdf_page_indicator, pageIndex + 1, pageCount),
				style = MaterialTheme.typography.labelLarge,
			)
			IconButton(
				onClick = { onPage(pageIndex + 1) },
				enabled = pageIndex < pageCount - 1,
			) {
				Icon(
					imageVector = Icons.AutoMirrored.Filled.ArrowForward,
					contentDescription = stringResource(R.string.pdf_next_page),
				)
			}
		}
	}
}

/** Stands in for a document that parsed for provenance but cannot be drawn. */
@Composable
private fun Unreadable(modifier: Modifier = Modifier) {
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
			text = stringResource(R.string.pdf_unreadable_title),
			style = MaterialTheme.typography.titleMedium,
			textAlign = TextAlign.Center,
		)
		Text(
			text = stringResource(R.string.pdf_unreadable_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}

/**
 * Points-to-pixels scale for a page drawn into [viewport].
 *
 * Asks for [SHARPNESS] times the size it is displayed at, so text survives a zoom in without a
 * re-render, and lets [pdfRasterScale] cut that back to whatever [MAX_PIXELS] affords.
 */
private fun scaleFor(widthPt: Double, heightPt: Double, viewport: IntSize): Double {
	if (widthPt <= 0.0 || heightPt <= 0.0) return 1.0
	val fit = minOf(viewport.width / widthPt, viewport.height / heightPt)
	return pdfRasterScale(widthPt, heightPt, preferred = fit * SHARPNESS, budgetPixels = MAX_PIXELS)
}

private const val TAG = "PdfPreview"
private const val SHARPNESS = 2.0
private const val MAX_PIXELS = 4_000_000L
