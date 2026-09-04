package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import org.contentauth.c2pa.SeekMode

/**
 * Read and seek arithmetic over the window `[start, start + length)` of a seekable byte source.
 *
 * BMFF hard binding walks the box tree and skips over `mdat`, so the C2PA reader needs real random
 * access rather than a forward-only stream. Position is tracked here and every read is positional,
 * so nothing sharing the underlying open file can move this window and this window moves nothing
 * else.
 *
 * Deliberately holds no `org.contentauth.c2pa.Stream`: that class loads the native library in its
 * static initializer, so keeping the arithmetic out of the stream hierarchy is what lets it be
 * tested off device. The stream is assembled around it at the call site.
 */
internal class AssetWindow(
	private val start: Long,
	private val length: Long,
	private val readAt: (buffer: ByteArray, count: Int, position: Long) -> Int,
) {
	private var position: Long = 0

	/**
	 * Total bytes actually handed to the reader.
	 *
	 * A hard binding covers everything but a few excluded boxes, so a genuine verification reads
	 * nearly the whole window. Far less than that means the reader gave a verdict without looking
	 * at the asset, which is what happens when the native layer cannot allocate its read buffer.
	 */
	var bytesRead: Long = 0L
		private set

	/**
	 * Reads up to [maxLength] bytes into [buffer], returning the count and 0 at the end of the
	 * window.
	 *
	 * Never returns a negative count, and reports an I/O failure as a short read rather than
	 * throwing. This runs inside a JNI upcall from the native reader, where an exception may go
	 * unchecked and leave the reader acting on a garbage return; a short read makes it report a
	 * truncated asset instead, which is a clean parse failure.
	 */
	fun read(buffer: ByteArray, maxLength: Int): Int {
		val available = (length - position).coerceAtLeast(0L)
		val wanted = minOf(maxLength.toLong(), available, buffer.size.toLong()).toInt()
		if (wanted <= 0) return 0
		val read = readAt(buffer, wanted, start + position)
		if (read <= 0) return 0
		position += read
		bytesRead += read
		return read
	}

	/**
	 * Moves to [offset] relative to [mode] and returns the resulting ABSOLUTE position within the
	 * window, which is what the reader expects back rather than the offset it asked for.
	 *
	 * Seeking past the end is allowed and leaves the next read returning 0, matching a file. A seek
	 * before the start is clamped rather than thrown, for the same JNI reason [read] returns 0.
	 */
	fun seek(offset: Long, mode: SeekMode): Long {
		val target = when (mode) {
			SeekMode.START -> offset
			SeekMode.CURRENT -> position + offset
			SeekMode.END -> length + offset
		}
		position = target.coerceAtLeast(0L)
		return position
	}
}
