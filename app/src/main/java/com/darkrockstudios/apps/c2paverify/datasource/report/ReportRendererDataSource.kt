package com.darkrockstudios.apps.c2paverify.datasource.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.toBitmap
import com.darkrockstudios.apps.c2paverify.model.common.AssetKind
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.pdfRasterScale
import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import com.darkrockstudios.apps.c2paverify.model.share.ReportBadgeStyle
import com.darkrockstudios.apps.c2paverify.model.share.ReportOverlay
import com.darkrockstudios.apps.c2paverify.model.share.ReportTone
import io.github.aakira.napier.Napier
import io.github.yuroyami.kitepdf.PdfDocument
import io.github.yuroyami.kitepdf.nativerenderer.AndroidPdfBitmapRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Renders a shareable "verification report": the inspected photo with a [ReportOverlay] panel
 * painted across the bottom (status accent, signer/generator details, an AI badge and a brand
 * mark). Writes a PNG into the app's cache and hands back a `content://` [FileProvider] URI string
 * the share sheet can read.
 *
 * Android-only (`android.graphics` + `FileProvider`); isolates that out of the KMP-clean layers.
 * All decoding/encoding runs on [Dispatchers.IO].
 */
class ReportRendererDataSource(
	private val context: Context,
	private val imageLoader: ImageLoader,
) {

	suspend fun render(image: ImageSource, overlay: ReportOverlay): String = withContext(Dispatchers.IO) {
		val photo = decodeScaled(image) ?: throw IOException("Unable to decode image for report")
		val canvasBitmap = try {
			drawReport(photo, overlay)
		} finally {
			photo.recycle()
		}
		val uri = try {
			writeToCache(canvasBitmap)
		} finally {
			canvasBitmap.recycle()
		}
		uri
	}

	/**
	 * Decodes through the shared Coil loader rather than [BitmapFactory], so whatever the viewer can
	 * display can also be drawn into a report. SVG has no BitmapFactory path at all, and letting the
	 * two diverge is what made Share fail on assets that were visibly on screen.
	 *
	 * [MAX_EDGE_PX] is a ceiling, not a target. Coil infers [Precision.EXACT] whenever a size is set
	 * without a view to scale the result, which upscales anything smaller than the request;
	 * [Precision.INEXACT] is what keeps the size an OOM guard and leaves small assets at their own
	 * resolution.
	 */
	private suspend fun decodeScaled(image: ImageSource): Bitmap? {
		val kind = image.resolveFormat()?.kind
		if (kind == AssetKind.VIDEO) return posterFrame(image)
		if (kind == AssetKind.DOCUMENT) return firstPage(image)
		val data: Any = when (image) {
			is ImageSource.Bytes -> image.bytes
			is ImageSource.Path -> File(image.path)
			is ImageSource.Content -> image.uri
		}
		val request = ImageRequest.Builder(context)
			.data(data)
			.size(MAX_EDGE_PX, MAX_EDGE_PX)
			.precision(Precision.INEXACT)
			.build()
		return (imageLoader.execute(request) as? SuccessResult)
			?.image
			?.toBitmap()
			?.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
	}

	/**
	 * Page one, standing in for the document the way the poster frame does for a video.
	 *
	 * Rendered through the same KitePDF rasterizer the viewer draws with, so the report shows what
	 * was on screen. A page is described in points, so [MAX_EDGE_PX] becomes the scale that would put
	 * its long edge on that ceiling, which [pdfRasterScale] then cuts back to fit the pixel budget.
	 */
	private fun firstPage(image: ImageSource): Bitmap? = runCatching {
		val bytes = when (image) {
			is ImageSource.Bytes -> image.bytes
			is ImageSource.Path -> File(image.path).readBytes()
			is ImageSource.Content -> context.contentResolver.openInputStream(image.uri.toUri())
				?.use { it.readBytes() }
		} ?: return null
		val doc = PdfDocument.openOrNull(bytes)?.takeIf { !it.isEncrypted } ?: return null
		val page = doc.pages.firstOrNull() ?: return null
		// The renderer rasterises at scale * UserUnit, so the budget is worked out against dimensions
		// with that unit already folded in; a /UserUnit 2 page would otherwise ask for four times the
		// pixels budgeted here and be refused outright.
		val unit = page.userUnit.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
		val widthPt = page.displayWidth * unit
		val heightPt = page.displayHeight * unit
		val longestEdgePt = maxOf(widthPt, heightPt)
		val scale = pdfRasterScale(
			widthPt = widthPt,
			heightPt = heightPt,
			preferred = if (longestEdgePt > 0) MAX_EDGE_PX / longestEdgePt else 1.0,
			budgetPixels = MAX_REPORT_PIXELS,
		)
		AndroidPdfBitmapRenderer.renderToBitmap(page, scale, Color.WHITE, MAX_REPORT_PIXELS)
	}.onFailure {
		Napier.w(tag = TAG, throwable = it) { "Unable to render the first PDF page" }
	}.getOrNull()

	/**
	 * The frame a video opens on, standing in for the asset the way the photo does for a still. A
	 * video has no single image to report on, and the first frame is the one the viewer was looking
	 * at before they hit share.
	 *
	 * As on the image path, [MAX_EDGE_PX] is a ceiling rather than a target: asking the retriever for
	 * a scaled frame larger than the source gets one, so anything already within the ceiling is taken
	 * at its own resolution.
	 */
	private fun posterFrame(image: ImageSource): Bitmap? {
		val retriever = MediaMetadataRetriever()
		return try {
			when (image) {
				is ImageSource.Content -> retriever.setDataSource(context, image.uri.toUri())
				is ImageSource.Path -> retriever.setDataSource(image.path)
				is ImageSource.Bytes -> return null
			}
			val frame = retriever.scaledDownFrame() ?: return null
			frame.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
				.also { if (it !== frame) frame.recycle() }
		} catch (e: IOException) {
			noFrame(e)
		} catch (e: IllegalArgumentException) {
			noFrame(e)
		} catch (e: IllegalStateException) {
			noFrame(e)
		} finally {
			retriever.release()
		}
	}

	/**
	 * Reports that no frame could be taken. Anything the retriever throws beyond this is caught by
	 * the share action itself, which already degrades to "couldn't build a report image".
	 */
	private fun noFrame(cause: Throwable): Bitmap? {
		Napier.w(tag = TAG, throwable = cause) { "Unable to extract a poster frame" }
		return null
	}

	/** The opening frame, shrunk to fit [MAX_EDGE_PX] only if it exceeds it. */
	private fun MediaMetadataRetriever.scaledDownFrame(): Bitmap? {
		val rotation = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
		val rawWidth = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
		val rawHeight = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
		// A frame comes back already rotated, so a quarter turn swaps the dimensions it reports.
		val quarterTurned = rotation == 90 || rotation == 270
		val width = if (quarterTurned) rawHeight else rawWidth
		val height = if (quarterTurned) rawWidth else rawHeight

		// Without dimensions the ceiling is all there is to go on: an unscaled 8K frame is ~140 MB,
		// and this bitmap is copied twice more before the report is written.
		if (width == null || height == null) {
			return getScaledFrameAtTime(
				0L,
				MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
				MAX_EDGE_PX,
				MAX_EDGE_PX,
			)
		}
		val longestEdge = maxOf(width, height)
		if (longestEdge <= MAX_EDGE_PX) {
			return getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
		}
		val scale = MAX_EDGE_PX.toFloat() / longestEdge
		return getScaledFrameAtTime(
			0L,
			MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
			(width * scale).roundToInt().coerceAtLeast(1),
			(height * scale).roundToInt().coerceAtLeast(1),
		)
	}

	private fun MediaMetadataRetriever.metadataInt(key: Int): Int? =
		extractMetadata(key)?.toIntOrNull()

	private fun drawReport(photo: Bitmap, overlay: ReportOverlay): Bitmap {
		val width = photo.width
		val height = photo.height
		val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(out)
		canvas.drawBitmap(photo, 0f, 0f, null)

		// Scale every dimension off the short edge so the panel reads the same on any resolution.
		val unit = minOf(width, height) / 28f
		val margin = unit
		val padding = unit * 1.1f
		val titleSize = unit * 1.7f
		val bodySize = unit * 1.15f
		val markSize = unit * 0.95f
		val lineGap = bodySize * 0.55f
		val sectionGap = bodySize * 0.8f

		val titlePaint = textPaint(titleSize, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
		val taglinePaint = textPaint(bodySize, Typeface.DEFAULT).apply { alpha = 200 }
		val bodyPaint = textPaint(bodySize, Typeface.DEFAULT).apply { alpha = 230 }
		val trustPaint = textPaint(bodySize, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
		val markPaint = textPaint(markSize, Typeface.DEFAULT).apply { alpha = 150 }
		val badgePaint = textPaint(bodySize, Typeface.create(Typeface.DEFAULT, Typeface.BOLD))

		val contentWidth = width - 2 * margin - 2 * padding

		// The hero accent: the origin badge colour, or the trust tone when the verdict leads.
		val accentColor = overlay.headlineStyle?.let { badgeColor(it) } ?: toneColor(overlay.tone)

		// Hero headline (+ tagline), wrapped to fit beside the accent dot.
		val dotR = titleSize * 0.32f
		val headlineInset = dotR * 2 + padding * 0.5f
		val headlineLines = wrap(overlay.headline, titlePaint, contentWidth - headlineInset)
		val taglineLines = overlay.tagline?.let { wrap(it, taglinePaint, contentWidth) }.orEmpty()

		// Secondary pills, laid into wrapping rows up front so we can size the panel.
		val pillHeight = bodySize * 2.1f
		val pillGap = padding * 0.5f
		val pills = overlay.badges.map { badge ->
			val text = "${badgeGlyph(badge.style)} ${badge.label}"
			BadgePill(badge.style, text, badgePaint.measureText(text) + padding * 1.6f)
		}
		val badgeRows = flowIntoRows(pills, contentWidth, pillGap)

		// Demoted trust line + signer details.
		val trustDotR = bodySize * 0.34f
		val trustInset = trustDotR * 2 + padding * 0.4f
		val trustLines = overlay.trustLabel?.let { wrap(it, trustPaint, contentWidth - trustInset) }.orEmpty()
		val detailLines = overlay.details.flatMap { wrap(it, bodyPaint, contentWidth) }

		// Measure the panel: hero + (badges) + (trust + details) + watermark.
		val titleLineH = lineHeight(titlePaint)
		val bodyLineH = lineHeight(bodyPaint)
		val trustLineH = lineHeight(trustPaint)
		val markLineH = lineHeight(markPaint)

		val heroContentH = headlineLines.size * (titleLineH + lineGap) +
			taglineLines.size * (bodyLineH + lineGap)

		var contentH = 0f
		contentH += heroContentH
		if (badgeRows.isNotEmpty()) contentH += sectionGap + badgeRows.size * (pillHeight + lineGap)
		if (trustLines.isNotEmpty() || detailLines.isNotEmpty()) contentH += sectionGap
		contentH += trustLines.size * (trustLineH + lineGap)
		contentH += detailLines.size * (bodyLineH + lineGap)
		contentH += sectionGap + markLineH

		val panelHeight = padding * 2 + contentH
		val panel = RectF(margin, height - margin - panelHeight, width - margin, height - margin)
		val corner = unit * 0.8f
		canvas.drawRoundRect(panel, corner, corner, fillPaint(0xE6101418.toInt()))

		// Hero band: a tinted strip behind the headline so it leads the panel, echoing the in-app card.
		val heroBand = RectF(panel.left, panel.top, panel.right, panel.top + padding + heroContentH + sectionGap * 0.5f)
		fillTopRoundRect(canvas, heroBand, corner, fillPaint(withAlpha(accentColor, HERO_BAND_ALPHA)))

		val x = panel.left + padding
		var y = panel.top + padding

		// Hero: accent dot + bold headline.
		headlineLines.forEachIndexed { i, line ->
			if (i == 0) canvas.drawCircle(x + dotR, y + titleLineH / 2f, dotR, fillPaint(accentColor))
			canvas.drawText(line, x + headlineInset, y + titlePaint.textSize * 0.82f, titlePaint)
			y += titleLineH + lineGap
		}
		for (line in taglineLines) {
			canvas.drawText(line, x + headlineInset, y + taglinePaint.textSize * 0.82f, taglinePaint)
			y += bodyLineH + lineGap
		}

		// Secondary pills.
		if (badgeRows.isNotEmpty()) {
			y += sectionGap
			for (row in badgeRows) {
				var bx = x
				for (pill in row) {
					val rect = RectF(bx, y, bx + pill.width, y + pillHeight)
					canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, fillPaint(badgeColor(pill.style)))
					canvas.drawText(pill.text, rect.left + padding * 0.8f, rect.centerY() + badgePaint.textSize * 0.35f, badgePaint)
					bx += pill.width + pillGap
				}
				y += pillHeight + lineGap
			}
		}

		// Demoted trust verdict + signer details.
		if (trustLines.isNotEmpty() || detailLines.isNotEmpty()) y += sectionGap
		trustLines.forEachIndexed { i, line ->
			if (i == 0) canvas.drawCircle(x + trustDotR, y + trustLineH / 2f, trustDotR, fillPaint(toneColor(overlay.tone)))
			canvas.drawText(line, x + trustInset, y + trustPaint.textSize * 0.82f, trustPaint)
			y += trustLineH + lineGap
		}
		for (line in detailLines) {
			canvas.drawText(line, x, y + bodyPaint.textSize * 0.82f, bodyPaint)
			y += bodyLineH + lineGap
		}

		// Brand mark, bottom-right of the panel.
		val markWidth = markPaint.measureText(overlay.watermark)
		canvas.drawText(
			overlay.watermark,
			panel.right - padding - markWidth,
			panel.bottom - padding,
			markPaint,
		)
		return out
	}

	private fun writeToCache(bitmap: Bitmap): String {
		val dir = File(context.cacheDir, REPORT_DIR).apply { mkdirs() }
		val file = File(dir, REPORT_FILE)
		file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
		val authority = "${context.packageName}$FILEPROVIDER_SUFFIX"
		return FileProvider.getUriForFile(context, authority, file).toString()
	}

	private fun textPaint(size: Float, typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textSize = size
		this.typeface = typeface
	}

	private fun fillPaint(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = argb
	}

	/** Replaces the alpha byte of an opaque ARGB colour, for tinted fills. */
	private fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0x00FFFFFF)

	/** Fills a rect whose top corners are rounded by [radius] and bottom corners are square. */
	private fun fillTopRoundRect(canvas: Canvas, rect: RectF, radius: Float, paint: Paint) {
		val path = Path().apply {
			addRoundRect(
				rect,
				floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
				Path.Direction.CW,
			)
		}
		canvas.drawPath(path, paint)
	}

	private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

	/** Greedy word-wrap so long signer names don't overflow the panel. */
	private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
		if (paint.measureText(text) <= maxWidth) return listOf(text)
		val out = mutableListOf<String>()
		val current = StringBuilder()
		for (word in text.split(' ')) {
			val candidate = if (current.isEmpty()) word else "$current $word"
			if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
				current.clear().append(candidate)
			} else {
				out.add(current.toString())
				current.clear().append(word)
			}
		}
		if (current.isNotEmpty()) out.add(current.toString())
		return out
	}

	private fun toneColor(tone: ReportTone): Int = when (tone) {
		ReportTone.TRUSTED -> 0xFF2E7D32.toInt()
		ReportTone.UNTRUSTED -> 0xFFF9A825.toInt()
		ReportTone.INVALID -> 0xFFE53935.toInt()
		ReportTone.NEUTRAL -> 0xFF9E9E9E.toInt()
	}

	/** A measured indicator pill ready to draw. */
	private data class BadgePill(val style: ReportBadgeStyle, val text: String, val width: Float)

	/** Greedy left-to-right flow of pills into rows no wider than [maxWidth]. */
	private fun flowIntoRows(pills: List<BadgePill>, maxWidth: Float, gap: Float): List<List<BadgePill>> {
		val rows = mutableListOf<MutableList<BadgePill>>()
		var rowWidth = 0f
		for (pill in pills) {
			if (rows.isEmpty() || rowWidth + pill.width > maxWidth) {
				rows.add(mutableListOf())
				rowWidth = 0f
			}
			rows.last().add(pill)
			rowWidth += pill.width + gap
		}
		return rows
	}

	private fun badgeGlyph(style: ReportBadgeStyle): String = when (style) {
		ReportBadgeStyle.AI -> "✨"
		ReportBadgeStyle.CAPTURE -> "📷"
		ReportBadgeStyle.SOFTWARE -> "🖌"
		ReportBadgeStyle.ENHANCED -> "🪄"
		ReportBadgeStyle.EDITED -> "✏️"
		ReportBadgeStyle.ALERT -> "⛔"
	}

	private fun badgeColor(style: ReportBadgeStyle): Int = when (style) {
		ReportBadgeStyle.AI -> AI_COLOR
		ReportBadgeStyle.CAPTURE -> CAPTURE_COLOR
		ReportBadgeStyle.SOFTWARE -> SOFTWARE_COLOR
		ReportBadgeStyle.ENHANCED -> ENHANCED_COLOR
		ReportBadgeStyle.EDITED -> EDITED_COLOR
		ReportBadgeStyle.ALERT -> ALERT_COLOR
	}

	private companion object {
		const val TAG = "ReportRenderer"
		const val MAX_EDGE_PX = 2048

		/** Backstop for a page whose aspect ratio would blow past [MAX_EDGE_PX] in the other axis. */
		const val MAX_REPORT_PIXELS = 4_000_000L
		// Tint strength (~20% over the dark panel) for the hero band behind the headline.
		const val HERO_BAND_ALPHA = 0x33
		const val REPORT_DIR = "reports"
		const val REPORT_FILE = "c2pa-report.png"
		const val FILEPROVIDER_SUFFIX = ".fileprovider"
		val AI_COLOR = 0xFF7C4DFF.toInt()
		val CAPTURE_COLOR = 0xFF1565C0.toInt()
		val SOFTWARE_COLOR = 0xFF00897B.toInt()
		val ENHANCED_COLOR = 0xFFEF6C00.toInt()
		val EDITED_COLOR = 0xFF5C6BC0.toInt()
		val ALERT_COLOR = 0xFFD32F2F.toInt()
	}
}
