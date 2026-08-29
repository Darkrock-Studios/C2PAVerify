package com.darkrockstudios.apps.c2paverify.datasource.image

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.darkrockstudios.apps.c2paverify.model.common.AssetFormats
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.isStreamed
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * Turns a URI into an [ImageSource] the C2PA reader can consume, on [Dispatchers.IO].
 * Android-specific; isolates `android.*`/URI handling out of the KMP-clean upper layers.
 *
 * Video and audio become an [ImageSource.Content] naming the URI plus its leading bytes, so the
 * asset is streamed when it is read rather than held: one is routinely hundreds of megabytes and is
 * re-read on every share and on every trust-rule change, which a heap copy cannot survive. Stills
 * stay on the whole-file path, which every content provider can serve whether or not its descriptor
 * can be seeked. Bundled examples use the `file:///android_asset/...` scheme (the same one Coil
 * understands for display); they are small, known-good and read whole through the
 * [android.content.res.AssetManager].
 *
 * ## Why this fights so hard for the ORIGINAL bytes
 * C2PA's `c2pa.hash.data` hard-binding hashes the whole JPEG (minus its own manifest), so the EXIF
 * is covered. When an app reads a photo through a MediaProvider-backed URI (the Photo Picker, the
 * `com.android.providers.media.documents` SAF root, or a direct `content://media/...` URI) WITHOUT
 * `ACCESS_MEDIA_LOCATION`, MediaProvider zeroes the GPS-location EXIF in place, mutating the bytes
 * and breaking the hash (spurious `assertion.dataHash.mismatch`). Granting `ACCESS_MEDIA_LOCATION`
 * is what actually stops the redaction: once held, even a plain read of a SAF media-doc URI returns
 * the original bytes. [MediaStore.setRequireOriginal] is additionally needed for a direct
 * `content://media/...` URI (the share path), so the URI is rewritten through it where possible and
 * every later open goes to the rewritten URI. Non-media URIs (ExternalStorageProvider, `file://`)
 * are never redacted.
 */
class AssetSourceDataSource(private val context: Context) {

	suspend fun read(uriString: String): ImageSource = withContext(Dispatchers.IO) {
		if (uriString.startsWith(ASSET_URI_PREFIX)) {
			val assetPath = uriString.removePrefix(ASSET_URI_PREFIX)
			val bytes = context.assets.open(assetPath).use { it.readBytes() }
			ImageSource.Bytes(bytes = bytes, mimeType = AssetFormats.fromFileName(assetPath)?.token)
		} else {
			readContent(uriString)
		}
	}

	/**
	 * Opens [uriString] once and lets its leading bytes decide whether the asset is streamed later or
	 * read here and now. The header is pushed back, so the whole-file read that may follow starts
	 * where it would have.
	 */
	private fun readContent(uriString: String): ImageSource {
		val resolver = context.contentResolver
		val uri = uriString.toUri()
		val mimeType = resolver.getType(uri)
		val (readUri, stream) = open(resolver, uri)
		return stream.use { input ->
			val pushback = PushbackInputStream(input, AssetFormats.HEADER_BYTES)
			val header = pushback.readHeader()
			pushback.unread(header)
			val format = AssetFormats.resolve(mimeType, uri.lastPathSegment, header)
			// Nothing identified it, so the reader will refuse it on name alone and its bytes are
			// never wanted. The picker accepts every video type, including containers this app cannot
			// identify (Matroska, WebM), and pulling a gigabyte of one into the heap would crash long
			// before anything got to refuse it.
			if (format == null || format.isStreamed) {
				streamedSource(readUri, mimeType, header, pushback)
			} else {
				ImageSource.Bytes(bytes = pushback.readBytes(), mimeType = mimeType)
			}
		}
	}

	/**
	 * Names [uri] for later streaming, or a local copy of it when the provider will not serve one.
	 *
	 * Cloud storage (Drive, Dropbox, and cloud-only items in Photos) has no file to hand out and
	 * serves a document down a pipe instead, which is forward-only. BMFF hard binding is nothing but
	 * seeking, so those are copied to the cache first. The copy is addressed by URI like any other
	 * source, so it carries its sniffed header and every reader of it stays unchanged.
	 */
	private fun streamedSource(
		uri: Uri,
		mimeType: String?,
		header: ByteArray,
		input: InputStream,
	): ImageSource {
		val readable = if (isSeekable(uri)) uri else Uri.fromFile(copyToCache(input))
		return ImageSource.Content(uri = readable.toString(), mimeType = mimeType, header = header)
	}

	/** Whether [uri] opens as something the reader can seek within, rather than a pipe. */
	private fun isSeekable(uri: Uri): Boolean = runCatching {
		context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize >= 0 }
	}.onFailure {
		Napier.d(tag = TAG) { "Could not probe $uri for seeking (${it.message}); copying it" }
	}.getOrNull() ?: false

	/**
	 * Writes [input] into the cache and returns the file.
	 *
	 * Written fresh every time rather than reused: the bytes behind a remote document can change
	 * between reads, and this app must never report a verdict for content it did not just read.
	 */
	private fun copyToCache(input: InputStream): File {
		val dir = File(context.cacheDir, STREAMED_DIR)
		dir.mkdirs()
		dir.listFiles()?.forEach { it.delete() }
		val file = File(dir, STREAMED_FILE)
		try {
			file.outputStream().use { input.copyTo(it) }
		} catch (e: IOException) {
			file.delete()
			throw e
		}
		return file
	}

	/**
	 * Opens [uri], preferring the un-redacted rewrite of it, and reports which URI actually opened so
	 * a streamed source names the one every later read should use.
	 */
	private fun open(resolver: ContentResolver, uri: Uri): Pair<Uri, InputStream> {
		originalUri(uri)?.let { original ->
			runCatching { resolver.openInputStream(original) }
				.onFailure { Napier.d(tag = TAG) { "Original unavailable (${it.message}); falling back" } }
				.getOrNull()
				?.let { return original to it }
		}
		val stream = resolver.openInputStream(uri)
			?: throw IOException("Unable to open stream for $uri")
		return uri to stream
	}

	/** The leading [AssetFormats.HEADER_BYTES], which is all the format sniffer needs. */
	private fun InputStream.readHeader(): ByteArray {
		val buffer = ByteArray(AssetFormats.HEADER_BYTES)
		var filled = 0
		while (filled < buffer.size) {
			val read = read(buffer, filled, buffer.size - filled)
			if (read <= 0) break
			filled += read
		}
		return buffer.copyOf(filled)
	}

	/**
	 * Rewrites [uri] into one [MediaStore.setRequireOriginal] accepts, or null when it does not
	 * apply. Only direct MediaStore URIs qualify (the share path); the Photo Picker's
	 * `content://media/picker/...` URIs reject `setRequireOriginal` and SAF document URIs aren't
	 * MediaStore URIs. For those the caller falls back to the URI as given, which is already
	 * un-redacted once `ACCESS_MEDIA_LOCATION` is granted.
	 */
	private fun originalUri(uri: Uri): Uri? = when (uri.authority) {
		MediaStore.AUTHORITY ->
			uri.takeIf { it.pathSegments.firstOrNull() != PICKER_PATH }
				?.let { runCatching { MediaStore.setRequireOriginal(it) }.getOrNull() }

		else -> null
	}

	private companion object {
		const val STREAMED_DIR = "streamed"
		const val STREAMED_FILE = "asset"
		const val ASSET_URI_PREFIX = "file:///android_asset/"
		const val TAG = "AssetSource"
		const val PICKER_PATH = "picker" // content://media/picker/... rejects setRequireOriginal
	}
}
