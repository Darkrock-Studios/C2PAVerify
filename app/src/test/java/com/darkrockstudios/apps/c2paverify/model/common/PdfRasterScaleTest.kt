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

	private fun assertFits(widthPt: Double, heightPt: Double, scale: Double, budget: Long) {
		assertTrue(
			"${widthPt}x$heightPt at $scale is ${pixels(widthPt, heightPt, scale)} px, over $budget",
			pixels(widthPt, heightPt, scale) <= budget,
		)
	}

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
		assertFits(612.0, 792.0, scale, 4_000_000)
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
			assertFits(w, h, pdfRasterScale(w, h, preferred = 8.0, budgetPixels = budget), budget)
		}
	}

	/**
	 * A page far too large for the budget used to escape it: the old MIN_SCALE floor was applied
	 * after the fit check and could raise the result back over the limit, so the renderer threw on
	 * exactly the malformed input the budget was meant to contain.
	 */
	@Test
	fun `a page too large for any sane scale is still brought under the budget`() {
		val budget = 4_000_000L
		listOf(
			300_000.0 to 300_000.0,
			1_000_000.0 to 1_000_000.0,
			14_400.0 to 14_400.0, // the spec's own maximum page size
		).forEach { (w, h) ->
			val scale = pdfRasterScale(w, h, preferred = 1.0, budgetPixels = budget)
			assertTrue("scale must stay positive, was $scale", scale > 0.0)
			assertFits(w, h, scale, budget)
		}
	}

	/**
	 * `/UserUnit` is folded into the dimensions by the caller, so the same page at UserUnit 2 is
	 * simply a bigger page here. It must come back at half the scale, since the renderer multiplies
	 * ours by the unit again.
	 */
	@Test
	fun `effective dimensions carrying UserUnit get a correspondingly smaller scale`() {
		val budget = 4_000_000L
		val plain = pdfRasterScale(612.0, 792.0, preferred = 8.0, budgetPixels = budget)
		val doubled = pdfRasterScale(612.0 * 2, 792.0 * 2, preferred = 8.0, budgetPixels = budget)

		assertEquals(plain / 2.0, doubled, 1e-3)
		assertFits(612.0 * 2, 792.0 * 2, doubled, budget)
	}

	@Test
	fun `a page with no usable size falls back rather than dividing by zero`() {
		assertEquals(1.0, pdfRasterScale(0.0, 792.0, preferred = 2.0, budgetPixels = 4_000_000), 1e-9)
		assertEquals(1.0, pdfRasterScale(612.0, 0.0, preferred = 2.0, budgetPixels = 4_000_000), 1e-9)
		assertEquals(1.0, pdfRasterScale(Double.NaN, 792.0, preferred = 2.0, budgetPixels = 4_000_000), 1e-9)
	}

	/** No raster fits a sub-pixel budget, so the renderer will refuse it whatever we return. */
	@Test
	fun `a budget no raster could meet falls back`() {
		assertEquals(1.0, pdfRasterScale(612.0, 792.0, preferred = 2.0, budgetPixels = 0), 1e-9)
		assertEquals(1.0, pdfRasterScale(612.0, 792.0, preferred = 2.0, budgetPixels = -1), 1e-9)
	}

	@Test
	fun `a nonsensical preferred scale falls back on what the budget affords`() {
		val budget = 4_000_000L
		listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
			val scale = pdfRasterScale(612.0, 792.0, preferred = bad, budgetPixels = budget)
			assertTrue("scale must stay positive for preferred=$bad, was $scale", scale > 0.0)
			assertFits(612.0, 792.0, scale, budget)
		}
	}
}
