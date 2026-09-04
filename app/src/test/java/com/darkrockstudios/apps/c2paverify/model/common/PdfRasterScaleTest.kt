package com.darkrockstudios.apps.c2paverify.model.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * The renderer rejects an over-budget raster rather than shrinking it, so every scale this returns
 * has to fit under the budget once both axes are rounded up. A regression here is a preview that
 * silently fails to draw, which is what happened when the budget was passed straight through.
 */
class PdfRasterScaleTest {

	private fun pixels(widthPt: Double, heightPt: Double, scale: Double): Double =
		ceil(widthPt * scale) * ceil(heightPt * scale)

	@Test
	fun `preferred scale is kept when it fits the budget`() {
		// US Letter at 2x is 1224x1584 = 1.9M, well under budget.
		assertEquals(2.0, pdfRasterScale(612.0, 792.0, preferred = 2.0, budgetPixels = 4_000_000), 1e-9)
	}

	@Test
	fun `over-budget request is cut back to fit`() {
		// The case that shipped a spinner: a tall viewport asked for ~3.4x of a Letter page.
		val scale = pdfRasterScale(612.0, 792.0, preferred = 3.4, budgetPixels = 4_000_000)

		assertTrue("should have been reduced from 3.4, was $scale", scale < 3.4)
		assertTrue("must fit the budget", pixels(612.0, 792.0, scale) <= 4_000_000)
	}

	@Test
	fun `result fits the budget across a range of page shapes`() {
		val budget = 4_000_000L
		listOf(
			612.0 to 792.0,   // Letter
			595.0 to 842.0,   // A4
			792.0 to 792.0,   // square
			2384.0 to 3370.0, // A0 poster
			1440.0 to 90.0,   // banner
		).forEach { (w, h) ->
			val scale = pdfRasterScale(w, h, preferred = 8.0, budgetPixels = budget)
			assertTrue(
				"${w}x$h at $scale is ${pixels(w, h, scale)} px, over budget",
				pixels(w, h, scale) <= budget,
			)
		}
	}

	@Test
	fun `a page with no usable size falls back rather than dividing by zero`() {
		assertEquals(1.0, pdfRasterScale(0.0, 792.0, preferred = 2.0, budgetPixels = 4_000_000), 1e-9)
		assertEquals(1.0, pdfRasterScale(612.0, 0.0, preferred = 2.0, budgetPixels = 4_000_000), 1e-9)
		assertEquals(1.0, pdfRasterScale(612.0, 792.0, preferred = 2.0, budgetPixels = 0), 1e-9)
	}
}
