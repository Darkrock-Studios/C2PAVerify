package com.darkrockstudios.apps.c2paverify.model.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [ImageSource.Content] carries a [ByteArray], so its equality is hand written. Getting it wrong is
 * silent: the inspection state would compare unequal to itself and re-run on every recomposition.
 */
class ImageSourceTest {

	@Test
	fun `content sources with the same uri, type and header are equal`() {
		val a = ImageSource.Content("content://asset/1", "video/mp4", byteArrayOf(1, 2, 3))
		val b = ImageSource.Content("content://asset/1", "video/mp4", byteArrayOf(1, 2, 3))
		assertEquals(a, b)
		assertEquals(a.hashCode(), b.hashCode())
	}

	@Test
	fun `content sources differing in any part are not equal`() {
		val base = ImageSource.Content("content://asset/1", "video/mp4", byteArrayOf(1, 2, 3))
		assertNotEquals(base, base.copy(uri = "content://asset/2"))
		assertNotEquals(base, base.copy(mimeType = "video/quicktime"))
		assertNotEquals(base, base.copy(header = byteArrayOf(1, 2, 4)))
	}

	@Test
	fun `a missing header compares equal to another missing header`() {
		val a = ImageSource.Content("content://asset/1", "video/mp4", header = null)
		val b = ImageSource.Content("content://asset/1", "video/mp4", header = null)
		assertEquals(a, b)
		assertEquals(a.hashCode(), b.hashCode())
		assertNotEquals(a, a.copy(header = byteArrayOf()))
	}
}
