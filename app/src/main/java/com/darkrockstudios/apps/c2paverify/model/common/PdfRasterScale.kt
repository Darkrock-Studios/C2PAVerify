package com.darkrockstudios.apps.c2paverify.model.common

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Points-to-pixels scale for rasterising a PDF page, never exceeding [budgetPixels].
 *
 * The renderer treats its pixel budget as a limit to *reject*, not one to scale down to: ask it for
 * a raster a pixel over and it throws rather than shrinking. So the budget has to be honoured here,
 * before the ask, which is the whole reason this is a shared function and not a line at each call
 * site. [preferred] is what the caller would like; the return value is that or less.
 *
 * [widthPt] and [heightPt] must be the page's *effective* dimensions, with `/UserUnit` already
 * folded in. The renderer rasterises at `scale * userUnit`, so a caller that passes the raw page
 * size would under-count a `/UserUnit 2` page by a factor of four and hand back a scale that trips
 * the very limit this exists to respect.
 *
 * Pure arithmetic, kept out of the Android layers so it can be tested without a device.
 */
fun pdfRasterScale(
	widthPt: Double,
	heightPt: Double,
	preferred: Double,
	budgetPixels: Long,
): Double {
	if (!widthPt.isFinite() || !heightPt.isFinite() || widthPt <= 0.0 || heightPt <= 0.0) {
		return FALLBACK_SCALE
	}
	// A budget below one pixel cannot be met by any raster, since each axis ceilings to at least 1.
	// The renderer rejects such a budget outright, so there is nothing better to return than this.
	if (budgetPixels < 1L) return FALLBACK_SCALE

	val affordable = sqrt(budgetPixels.toDouble() / (widthPt * heightPt))
	val wanted = if (preferred.isFinite() && preferred > 0.0) preferred else affordable
	var scale = min(wanted, affordable)
	// The renderer rounds each axis up independently, so a scale that exactly spends the budget can
	// still land a pixel over it once both are ceilinged. Step down until it genuinely fits. This
	// always terminates: shrinking far enough puts both axes at 1px, and 1x1 fits any budget >= 1.
	while (scale > 0.0 && ceil(widthPt * scale) * ceil(heightPt * scale) > budgetPixels) {
		scale *= BACKOFF
	}
	return scale
}

/** Used when a page reports no usable size, or a budget no raster could meet; the renderer's own default. */
private const val FALLBACK_SCALE = 1.0

private const val BACKOFF = 0.999
