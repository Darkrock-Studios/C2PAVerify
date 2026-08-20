package com.darkrockstudios.apps.c2paverify.model.common

/**
 * A platform-neutral handle to image bytes to be inspected. Kept free of `android.*` types so the
 * domain/repository/usecase layers stay KMP-clean; the Android data sources translate a content
 * URI into one of these.
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
}
