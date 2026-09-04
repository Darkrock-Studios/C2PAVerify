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
 * A page is described in points, so a caller that wants to fill a viewport derives [preferred] from
 * it. Pure arithmetic, kept out of the Android layers so it can be tested without a device.
 */
fun pdfRasterScale(
	widthPt: Double,
	heightPt: Double,
	preferred: Double,
	budgetPixels: Long,
): Double {
	if (widthPt <= 0.0 || heightPt <= 0.0 || budgetPixels <= 0L) return FALLBACK_SCALE
	val affordable = sqrt(budgetPixels.toDouble() / (widthPt * heightPt))
	// The renderer rounds each axis up independently, so a scale that exactly spends the budget can
	// still land a pixel over it once both are ceilinged. Step down until it genuinely fits.
	var scale = min(preferred, affordable)
	while (scale > MIN_SCALE && ceil(widthPt * scale) * ceil(heightPt * scale) > budgetPixels) {
		scale *= BACKOFF
	}
	return scale.coerceAtLeast(MIN_SCALE)
}

/** Used when a page reports no usable size; the renderer's own default. */
private const val FALLBACK_SCALE = 1.0

/** Floor on the result, so a page too large for any budget still renders something legible-ish. */
private const val MIN_SCALE = 0.01

private const val BACKOFF = 0.999
