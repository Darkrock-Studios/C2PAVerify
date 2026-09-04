package com.darkrockstudios.apps.c2paverify.model.common

/**
 * Scheme the bundled sample assets are addressed by. Not a filesystem path and not something
 * Android's `ContentResolver` can open: it has to be read through `AssetManager`. Coil understands
 * it natively, which is why the image preview never had to care.
 */
const val ASSET_URI_PREFIX = "file:///android_asset/"

/**
 * A platform-neutral handle to an asset to be inspected, either its bytes or a way to reach them.
 * Kept free of `android.*` types so the domain/repository/usecase layers stay KMP-clean; the Android
 * data sources translate a content URI into one of these.
 */
sealed interface ImageSource {
	/** The type the platform declared for this asset, if it declared one at all. */
	val mimeType: String?

	/** Raw, already-loaded image bytes. */
	data class Bytes(val bytes: ByteArray, override val mimeType: String?) : ImageSource {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Bytes) return false
			return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
		}

		override fun hashCode(): Int = 31 * (mimeType?.hashCode() ?: 0) + bytes.contentHashCode()
	}

	/** An absolute file path on the local filesystem. */
	data class Path(val path: String, override val mimeType: String?) : ImageSource

	/**
	 * A platform URI the data layer can reopen on demand, carrying [header] (the leading bytes) so
	 * the asset can still be identified without this layer doing any I/O.
	 *
	 * The asset itself is never held in memory, which is what makes video of any length inspectable.
	 */
	data class Content(
		val uri: String,
		override val mimeType: String?,
		val header: ByteArray?,
	) : ImageSource {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Content) return false
			return uri == other.uri && mimeType == other.mimeType && header.contentEquals(other.header)
		}

		override fun hashCode(): Int {
			var result = uri.hashCode()
			result = 31 * result + (mimeType?.hashCode() ?: 0)
			result = 31 * result + header.contentHashCode()
			return result
		}
	}
}
