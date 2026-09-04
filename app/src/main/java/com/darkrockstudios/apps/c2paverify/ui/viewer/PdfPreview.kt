package com.darkrockstudios.apps.c2paverify.ui.viewer

import android.content.Context
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
import com.darkrockstudios.apps.c2paverify.model.common.ASSET_URI_PREFIX
import com.darkrockstudios.apps.c2paverify.model.common.pdfRasterScale
import io.github.aakira.napier.Napier
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.PdfPage
import io.github.yuroyami.kitepdf.nativerenderer.AndroidPdfBitmapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
	var unopenable by remember(uri) { mutableStateOf(false) }
	var pageFailed by remember(uri) { mutableStateOf(false) }
	var pageIndex by rememberSaveable(uri) { mutableIntStateOf(0) }
	var viewport by remember { mutableStateOf(IntSize.Zero) }
	var page by remember(uri) { mutableStateOf<Bitmap?>(null) }

	// Rasterising is a blocking call that cancellation cannot interrupt, so a superseded render runs
	// to completion whatever we do. This lock keeps the *next* one from starting alongside it, which
	// is what otherwise stacks several multi-megabyte bitmaps during a fold or a rotation.
	val renderLock = remember { Mutex() }

	LaunchedEffect(uri) {
		val opened = withContext(Dispatchers.IO) {
			runCatching {
				// An encrypted document parses but cannot be drawn, so it belongs with the failures
				// rather than showing a page of blanks.
				context.readAsset(uri)?.let { PdfDocument.openOrNull(it) }?.takeIf { !it.isEncrypted }
			}.onFailure {
				Napier.w(tag = TAG, throwable = it) { "Could not open the PDF" }
			}.getOrNull()
		}
		document = opened
		unopenable = opened == null
	}

	val doc = document
	LaunchedEffect(doc, pageIndex, viewport) {
		if (doc == null || viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
		// Let the size settle before doing any work: onSizeChanged fires repeatedly through a fold,
		// a rotation or a pane resize, and each render is expensive enough to be worth not starting.
		delay(RENDER_SETTLE_MS)
		page = null
		pageFailed = false
		val rendered = renderLock.withLock {
			withContext(Dispatchers.Default) {
				runCatching {
					val target = doc.pages[pageIndex]
					AndroidPdfBitmapRenderer.renderToBitmap(
						target,
						scaleFor(target, viewport),
						Color.WHITE,
						MAX_PIXELS,
					)
				}.onFailure {
					Napier.w(tag = TAG, throwable = it) { "Could not render PDF page $pageIndex" }
				}.getOrNull()
			}
		}
		page = rendered
		pageFailed = rendered == null
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
			unopenable -> Unavailable(R.string.pdf_unreadable_title, R.string.pdf_unreadable_body)
			pageFailed -> Unavailable(R.string.pdf_page_failed_title, R.string.pdf_page_failed_body)
			bitmap != null -> Image(
				bitmap = bitmap.asImageBitmap(),
				contentDescription = stringResource(R.string.selected_asset),
				// Inside, not Fit, to match what setContentLocation above tells Telephoto the content
				// is. Fit would upscale a raster smaller than the viewport while Telephoto still
				// measured pan and zoom against the smaller unscaled rectangle.
				contentScale = ContentScale.Inside,
				modifier = Modifier
					.fillMaxSize()
					.zoomable(state = zoomableState, gestures = EnabledZoomGestures.ZoomAndPan),
			)

			else -> CircularProgressIndicator()
		}

		// Deliberately not gated on the page having rendered: a page that will not draw is exactly
		// when these are needed, to get back to one that did.
		val pageCount = doc?.pageCount ?: 0
		if (!unopenable && pageCount > 1) {
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
 * The whole document, however it is addressed.
 *
 * The bundled samples use [ASSET_URI_PREFIX], which is not a filesystem path and which
 * `ContentResolver` cannot open, so it has to go through `AssetManager` the way
 * `AssetSourceDataSource` does.
 */
private fun Context.readAsset(uri: String): ByteArray? =
	if (uri.startsWith(ASSET_URI_PREFIX)) {
		assets.open(uri.removePrefix(ASSET_URI_PREFIX)).use { it.readBytes() }
	} else {
		contentResolver.openInputStream(uri.toUri())?.use { it.readBytes() }
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

/** Stands in for content that parsed for provenance but cannot be drawn. */
@Composable
private fun Unavailable(titleRes: Int, bodyRes: Int, modifier: Modifier = Modifier) {
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
			text = stringResource(titleRes),
			style = MaterialTheme.typography.titleMedium,
			textAlign = TextAlign.Center,
		)
		Text(
			text = stringResource(bodyRes),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}

/**
 * Points-to-pixels scale for [page] drawn into [viewport].
 *
 * The renderer rasterises at `scale * UserUnit`, so both the fit and the pixel budget are worked out
 * against dimensions with that unit already folded in. Asks for [SHARPNESS] times the size the page
 * is displayed at, so text survives a zoom in without a re-render, and lets [pdfRasterScale] cut
 * that back to whatever [MAX_PIXELS] affords.
 */
private fun scaleFor(page: PdfPage, viewport: IntSize): Double {
	val unit = page.userUnit.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
	val widthPt = page.displayWidth * unit
	val heightPt = page.displayHeight * unit
	if (widthPt <= 0.0 || heightPt <= 0.0) return 1.0
	val fit = minOf(viewport.width / widthPt, viewport.height / heightPt)
	return pdfRasterScale(widthPt, heightPt, preferred = fit * SHARPNESS, budgetPixels = MAX_PIXELS)
}

private const val TAG = "PdfPreview"
private const val SHARPNESS = 2.0
private const val MAX_PIXELS = 4_000_000L

/** How long a viewport change has to hold still before it is worth rasterising for. */
private const val RENDER_SETTLE_MS = 120L
