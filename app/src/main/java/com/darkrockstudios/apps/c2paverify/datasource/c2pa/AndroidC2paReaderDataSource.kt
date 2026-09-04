package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import com.darkrockstudios.apps.c2paverify.model.common.AssetFormat
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import com.darkrockstudios.apps.c2paverify.model.trust.TrustMaterial
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.contentauth.c2pa.C2PAContext
import org.contentauth.c2pa.C2PASettings
import org.contentauth.c2pa.CallbackStream
import org.contentauth.c2pa.DataStream
import org.contentauth.c2pa.FileStream
import org.contentauth.c2pa.Reader
import org.contentauth.c2pa.Stream
import org.contentauth.c2pa.settings.C2PASettingsDefinition
import org.contentauth.c2pa.settings.TrustSettings
import org.contentauth.c2pa.settings.VerifySettings
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * The ONLY file that touches the `org.contentauth.c2pa` library. All blocking JNI calls run on
 * [Dispatchers.IO]; library exceptions and the no-manifest case are mapped to [C2paRawRead] /
 * [C2paReadException] so nothing above the data layer sees c2pa types.
 *
 * When [TrustMaterial] is supplied, the reader is built from [C2PASettings] with trust
 * verification enabled, so the manifest JSON carries `signingCredential.trusted` / `.untrusted`.
 */
class AndroidC2paReaderDataSource(private val context: Context) : C2paReaderDataSource {

	override suspend fun read(image: ImageSource, trust: TrustMaterial?): C2paRawRead =
		withContext(Dispatchers.IO) {
			val format = requireFormat(image)
			buildStream(image).use { source ->
				val read = try {
					openAndExtract(format.token, source.stream, trust)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					// The "no embedded manifest" case is normal (most photos). The library surfaces it
					// inconsistently: a typed C2PAError on the no-trust path, but a plain RuntimeException
					// ("ManifestNotFound: no JUMBF data found") on the trust path — so match on the message
					// for any exception type rather than the class.
					if (indicatesNoManifest(e.message)) {
						Napier.d(tag = TAG) { "No C2PA manifest present: ${e.message}" }
						C2paRawRead.NoManifest
					} else {
						// Carries the cause's own words and nothing else: every presenter of this already
						// says that reading the C2PA data is what failed, and prefixing it here spelled
						// that out twice on screen.
						throw C2paReadException(e.message ?: "The file could not be parsed", e)
					}
				}
				// A read that failed part way through looks to the reader like a shorter file, which
				// for a hard binding comes back as a hash mismatch. Reporting that as a verdict would
				// accuse an intact asset of being tampered with, so the I/O failure wins.
				source.readFailure?.let {
					throw C2paReadException("Reading the file failed part way through", it)
				}
				source.markCoverage(read)
			}
		}

	private fun openAndExtract(format: String, stream: Stream, trust: TrustMaterial?): C2paRawRead =
		if (trust != null && trust.hasAnchors) {
			// Settings may be freed once the context is built; the context must outlive the reader.
			val settings = C2PASettings.fromDefinition(buildDefinition(trust))
			val c2paContext = try {
				C2PAContext.fromSettings(settings)
			} finally {
				settings.close()
			}
			c2paContext.use { ctx ->
				Reader.fromContext(ctx).withStream(format, stream).use { extract(it) }
			}
		} else {
			Reader.fromStream(format, stream).use { extract(it) }
		}

	private fun extract(reader: Reader): C2paRawRead.Manifest {
		val manifestJson = reader.json()
		val detailedJson = runCatching { reader.detailedJson() }
			.onFailure { Napier.w(tag = TAG, throwable = it) { "detailedJson() failed" } }
			.getOrNull()
		return C2paRawRead.Manifest(
			manifestJson = manifestJson,
			detailedJson = detailedJson,
			certChainDer = emptyList(),
		)
	}

	private fun buildDefinition(trust: TrustMaterial) = C2PASettingsDefinition(
		trust = TrustSettings(
			verifyTrustList = true,
			trustAnchors = trust.trustAnchorsPem,
			allowedList = trust.allowedListPem,
			trustConfig = trust.trustConfig,
		),
		verify = VerifySettings(verifyTrust = true, verifyTimestampTrust = true),
	)

	private fun buildStream(image: ImageSource): AssetStream = when (image) {
		is ImageSource.Bytes -> AssetStream(backing = null, stream = DataStream(image.bytes))

		is ImageSource.Path -> AssetStream(
			backing = null,
			stream = FileStream(File(image.path), FileStream.Mode.READ),
		)

		is ImageSource.Content -> contentStream(image.uri)
	}

	/**
	 * A seekable stream over [uriString] that never materialises the asset.
	 *
	 * A provider that hands back a pipe rather than a real file is refused here rather than half way
	 * through a parse: BMFF hard binding is nothing but seeking, so a forward-only descriptor cannot
	 * serve the reader at all.
	 */
	private fun contentStream(uriString: String): AssetStream {
		val descriptor = context.contentResolver.openFileDescriptor(uriString.toUri(), "r")
			?: throw C2paReadException("Unable to open $uriString")
		val size = descriptor.statSize
		if (size < 0) {
			descriptor.close()
			throw C2paReadException("This file cannot be read from where it is stored")
		}
		// AutoCloseInputStream owns the descriptor, so closing the stream closes its channel and the
		// descriptor exactly once. Closing the two separately would risk closing a file number that
		// had already been released and reused.
		val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
		return try {
			val channel = input.channel
			val stream = AssetStream(backing = input, assetSize = size)
			val window = AssetWindow(start = 0L, length = size) { buffer, count, position ->
				runCatching { channel.read(ByteBuffer.wrap(buffer, 0, count), position) }
					.onFailure {
						Napier.w(tag = TAG, throwable = it) { "Read failed at $position" }
						stream.readFailure = it
					}
					.getOrDefault(0)
			}
			stream.also {
				it.window = window
				it.stream = CallbackStream(reader = window::read, seeker = window::seek)
			}
		} catch (e: Exception) {
			input.close()
			throw C2paReadException("Unable to read $uriString", e)
		}
	}

	/**
	 * A reader [stream] together with the platform handle it reads through, so both are released by
	 * one [close] whether the reader finished or threw.
	 */
	private class AssetStream(
		private val backing: Closeable?,
		stream: Stream? = null,
		private val assetSize: Long = -1L,
	) : Closeable {

		lateinit var stream: Stream

		/** Set for a windowed source, so how much of the asset was served can be checked afterwards. */
		var window: AssetWindow? = null

		/** Set when the platform failed to serve a read, so a short read is not read as the file's end. */
		@Volatile
		var readFailure: Throwable? = null

		init {
			stream?.let { this.stream = it }
		}

		/**
		 * Flags a verdict the reader reached without actually reading the asset.
		 *
		 * c2pa-android's JNI glue allocates a ByteArray the size of whatever the core asks for, and
		 * the core asks for a whole contiguous hash range at once. For a large video that throws
		 * OutOfMemoryError inside the native call, the glue swallows it, and the failed read comes
		 * back as `assertion.bmffHash.mismatch`. Unlike [readFailure], nothing reaches our read
		 * callback at all, so the only evidence is how little was served.
		 * Tracked upstream as contentauth/c2pa-android#133.
		 */
		fun markCoverage(read: C2paRawRead): C2paRawRead {
			val served = window?.bytesRead ?: return read
			if (read !is C2paRawRead.Manifest || assetSize <= 0) return read
			val incomplete = served < assetSize / MIN_READ_COVERAGE_DIVISOR
			if (incomplete) {
				Napier.w(tag = TAG) { "Read $served of $assetSize bytes; hash results are not trustworthy" }
			}
			return read.copy(verificationIncomplete = incomplete)
		}

		override fun close() {
			try {
				if (::stream.isInitialized) stream.close()
			} finally {
				backing?.close()
			}
		}
	}

	/**
	 * Identifies [image] for the reader, refusing to guess when nothing does.
	 *
	 * The reader picks a parser by exact token match, so an asset it has no parser for has to be
	 * reported as unreadable. Defaulting to JPEG instead made it report a HEIF or a TIFF as
	 * *corrupt*, which reads as a failed verification rather than an unsupported file.
	 */
	private fun requireFormat(image: ImageSource): AssetFormat =
		image.resolveFormat() ?: throw C2paReadException(
			"Unsupported file type" + (image.mimeType?.let { " ($it)" } ?: ""),
		)

	private companion object {
		const val TAG = "C2paReader"

		/**
		 * A real verification reads all but the excluded boxes. Half the asset is a deliberately
		 * loose floor: well above any plausible exclusion set, well below a genuine full read.
		 */
		const val MIN_READ_COVERAGE_DIVISOR = 2
	}
}

/**
 * Best-effort detection of the "asset has no embedded manifest" case from an exception [message].
 * The native layer surfaces it inconsistently — a typed [C2PAError] on the no-trust path, a plain
 * `RuntimeException` on the trust path — but always with text mentioning a missing manifest/JUMBF
 * (e.g. `ManifestNotFound: no JUMBF data found`), so match on the message text rather than the type.
 */
private fun indicatesNoManifest(message: String?): Boolean {
	val compact = message?.lowercase()?.replace(" ", "")?.replace("_", "") ?: return false
	return listOf(
		"manifestnotfound",
		"jumbfnotfound",
		"nomanifest",
		"noclaim",
		"claimmissing",
		"noembedded",
	).any { it in compact }
}
