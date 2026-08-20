package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import com.darkrockstudios.apps.c2paverify.model.common.AssetFormat
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import com.darkrockstudios.apps.c2paverify.model.trust.TrustMaterial
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.contentauth.c2pa.C2PAContext
import org.contentauth.c2pa.C2PASettings
import org.contentauth.c2pa.DataStream
import org.contentauth.c2pa.FileStream
import org.contentauth.c2pa.Reader
import org.contentauth.c2pa.Stream
import org.contentauth.c2pa.settings.C2PASettingsDefinition
import org.contentauth.c2pa.settings.TrustSettings
import org.contentauth.c2pa.settings.VerifySettings
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The ONLY file that touches the `org.contentauth.c2pa` library. All blocking JNI calls run on
 * [Dispatchers.IO]; library exceptions and the no-manifest case are mapped to [C2paRawRead] /
 * [C2paReadException] so nothing above the data layer sees c2pa types.
 *
 * When [TrustMaterial] is supplied, the reader is built from [C2PASettings] with trust
 * verification enabled, so the manifest JSON carries `signingCredential.trusted` / `.untrusted`.
 */
class AndroidC2paReaderDataSource : C2paReaderDataSource {

	override suspend fun read(image: ImageSource, trust: TrustMaterial?): C2paRawRead =
		withContext(Dispatchers.IO) {
			val format = requireFormat(image)
			buildStream(image).use { stream ->
				try {
					openAndExtract(format.token, stream, trust)
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
						throw C2paReadException("Failed to read C2PA data: ${e.message}", e)
					}
				}
			}
		}

	private fun openAndExtract(format: String, stream: Stream, trust: TrustMaterial?): C2paRawRead =
		if (trust != null && trust.hasAnchors) {
			// Settings may be freed once the context is built; the context must outlive the reader.
			val settings = C2PASettings.fromDefinition(buildDefinition(trust))
			val context = try {
				C2PAContext.fromSettings(settings)
			} finally {
				settings.close()
			}
			context.use { ctx ->
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

	private fun buildStream(image: ImageSource): Stream = when (image) {
		is ImageSource.Bytes -> DataStream(image.bytes)
		is ImageSource.Path -> FileStream(File(image.path), FileStream.Mode.READ)
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
