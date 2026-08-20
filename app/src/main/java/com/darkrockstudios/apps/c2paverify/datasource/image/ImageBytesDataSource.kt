package com.darkrockstudios.apps.c2paverify.datasource.image

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.darkrockstudios.apps.c2paverify.model.common.AssetFormats
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Reads image bytes from a content URI via [Context.getContentResolver], on [Dispatchers.IO].
 * Bundled examples use the `file:///android_asset/...` scheme (the same one Coil understands for
 * display) and are read through the [android.content.res.AssetManager]. Android-specific; isolates
 * `android.*`/URI handling out of the KMP-clean upper layers.
 *
 * ## Why this fights so hard for the ORIGINAL bytes
 * C2PA's `c2pa.hash.data` hard-binding hashes the whole JPEG (minus its own manifest), so the EXIF
 * is covered. When an app reads a photo through a MediaProvider-backed URI (the Photo Picker, the
 * `com.android.providers.media.documents` SAF root, or a direct `content://media/...` URI) WITHOUT
 * `ACCESS_MEDIA_LOCATION`, MediaProvider zeroes the GPS-location EXIF in place — mutating the bytes
 * and breaking the hash (spurious `assertion.dataHash.mismatch`). Granting `ACCESS_MEDIA_LOCATION`
 * is what actually stops the redaction: once held, even a plain read of a SAF media-doc URI returns
 * the original bytes. [MediaStore.setRequireOriginal] is additionally needed for a direct
 * `content://media/...` URI (the share path), so we try it first and fall back to a plain read.
 * Non-media URIs (ExternalStorageProvider, `file://`) are never redacted.
 */
class ImageBytesDataSource(private val context: Context) {

	suspend fun read(uriString: String): ImageSource.Bytes = withContext(Dispatchers.IO) {
		if (uriString.startsWith(ASSET_URI_PREFIX)) {
			val assetPath = uriString.removePrefix(ASSET_URI_PREFIX)
			val bytes = context.assets.open(assetPath).use { it.readBytes() }
			ImageSource.Bytes(bytes = bytes, mimeType = AssetFormats.fromFileName(assetPath)?.token)
		} else {
			val uri = uriString.toUri()
			val resolver = context.contentResolver
			val mimeType = resolver.getType(uri)
			val bytes = readOriginalBytes(resolver, uri)
				?: resolver.openInputStream(uri)?.use { it.readBytes() }
				?: throw IOException("Unable to open image stream for $uriString")
			ImageSource.Bytes(bytes = bytes, mimeType = mimeType)
		}
	}

	/**
	 * Best-effort read of the ORIGINAL, un-redacted bytes for a MediaProvider-backed URI. Returns
	 * null when the URI isn't media-backed (so a plain read is already original) or when the original
	 * can't be obtained (e.g. `ACCESS_MEDIA_LOCATION` not granted) — the caller then falls back to a
	 * plain read. Never throws.
	 */
	private fun readOriginalBytes(resolver: ContentResolver, uri: Uri): ByteArray? = runCatching {
		val mediaUri = toMediaUri(uri) ?: return null
		val original = MediaStore.setRequireOriginal(mediaUri)
		resolver.openInputStream(original)?.use { it.readBytes() }
	}.onFailure {
		Napier.d(tag = TAG) { "Original read unavailable (${it.message}); falling back to plain read" }
	}.getOrNull()

	/**
	 * Maps [uri] to a `content://media/...` URI that [MediaStore.setRequireOriginal] accepts, or null
	 * otherwise. Only direct MediaStore URIs qualify (the share path); the Photo Picker's
	 * `content://media/picker/...` URIs reject `setRequireOriginal` and SAF document URIs aren't
	 * MediaStore URIs — for those the caller falls back to a plain read, which is already un-redacted
	 * once `ACCESS_MEDIA_LOCATION` is granted.
	 */
	private fun toMediaUri(uri: Uri): Uri? = when (uri.authority) {
		MediaStore.AUTHORITY -> uri.takeIf { it.pathSegments.firstOrNull() != PICKER_PATH }
		else -> null
	}

	private companion object {
		const val ASSET_URI_PREFIX = "file:///android_asset/"
		const val TAG = "ImageBytes"
		const val PICKER_PATH = "picker" // content://media/picker/... — rejects setRequireOriginal
	}
}
