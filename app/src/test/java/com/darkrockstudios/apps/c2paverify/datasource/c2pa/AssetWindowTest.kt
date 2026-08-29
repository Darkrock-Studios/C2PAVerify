package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import org.contentauth.c2pa.SeekMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The seek arithmetic behind video verification. A mistake here does not crash: it makes the C2PA
 * reader walk the wrong bytes and report a perfectly good file as corrupt, so it is pinned off
 * device where every branch is reachable.
 */
class AssetWindowTest {

	private val source = ByteArray(64) { it.toByte() }

	@Test
	fun `seek reports the resulting absolute position, not the offset it was given`() {
		val window = windowOver(source)
		assertEquals(10L, window.seek(10L, SeekMode.START))
		assertEquals(14L, window.seek(4L, SeekMode.CURRENT))
		assertEquals(64L, window.seek(0L, SeekMode.END))
	}

	@Test
	fun `seeking from the end walks backwards from the length`() {
		val window = windowOver(source)
		assertEquals(56L, window.seek(-8L, SeekMode.END))
		assertArrayEquals(byteArrayOf(56, 57, 58, 59), window.readBytes(4))
	}

	@Test
	fun `successive relative seeks compose`() {
		val window = windowOver(source)
		window.seek(4L, SeekMode.CURRENT)
		window.seek(4L, SeekMode.CURRENT)
		assertEquals(12L, window.seek(4L, SeekMode.CURRENT))
	}

	@Test
	fun `reading advances the position`() {
		val window = windowOver(source)
		assertArrayEquals(byteArrayOf(0, 1, 2, 3), window.readBytes(4))
		assertArrayEquals(byteArrayOf(4, 5, 6, 7), window.readBytes(4))
		assertEquals(8L, window.seek(0L, SeekMode.CURRENT))
	}

	@Test
	fun `a window over a slice is addressed from its own start and stops at its own end`() {
		val window = windowOver(source, start = 16, length = 8)
		assertArrayEquals(byteArrayOf(16, 17, 18, 19), window.readBytes(4))
		// Asking for more than the window holds yields only the remainder, never the bytes beyond it.
		assertArrayEquals(byteArrayOf(20, 21, 22, 23), window.readBytes(32))
		assertEquals(0, window.read(ByteArray(8), 8))
	}

	@Test
	fun `the end of the window reads as zero rather than as a negative count`() {
		val window = windowOver(source)
		window.seek(0L, SeekMode.END)
		assertEquals(0, window.read(ByteArray(8), 8))
	}

	@Test
	fun `seeking past the end is allowed and leaves nothing to read`() {
		val window = windowOver(source)
		assertEquals(1_000L, window.seek(1_000L, SeekMode.START))
		assertEquals(0, window.read(ByteArray(8), 8))
	}

	@Test
	fun `seeking before the start clamps rather than going negative`() {
		val window = windowOver(source)
		assertEquals(0L, window.seek(-1_000L, SeekMode.START))
		assertArrayEquals(byteArrayOf(0, 1), window.readBytes(2))
	}

	@Test
	fun `a read never overruns the buffer it was given`() {
		val window = windowOver(source)
		val buffer = ByteArray(4)
		assertEquals(4, window.read(buffer, 32))
		assertArrayEquals(byteArrayOf(0, 1, 2, 3), buffer)
	}

	@Test
	fun `a failing source reports a short read instead of throwing`() {
		val window = AssetWindow(start = 0L, length = 16L) { _, _, _ -> 0 }
		assertEquals(0, window.read(ByteArray(8), 8))
	}

	private fun windowOver(
		bytes: ByteArray,
		start: Int = 0,
		length: Int = bytes.size - start,
	) = AssetWindow(start.toLong(), length.toLong()) { buffer, count, position ->
		val from = position.toInt()
		val available = minOf(count, bytes.size - from).coerceAtLeast(0)
		bytes.copyInto(buffer, 0, from, from + available)
		available
	}

	/** Reads exactly what the window returns for one [read] call. */
	private fun AssetWindow.readBytes(count: Int): ByteArray {
		val buffer = ByteArray(count)
		val read = read(buffer, count)
		return buffer.copyOf(read)
	}
}
