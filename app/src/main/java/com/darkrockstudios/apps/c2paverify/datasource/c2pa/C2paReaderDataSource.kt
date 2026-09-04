package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.trust.TrustMaterial

/**
 * The KMP seam around the C2PA reader. Returns the reader's raw outputs as plain strings + cert
 * bytes — never c2pa library types — so nothing above the data layer imports `org.contentauth.*`.
 *
 * A future iOS app provides its own implementation of this interface (wired via Koin), keeping the
 * platform boundary at DI rather than the compiler.
 */
interface C2paReaderDataSource {
	/**
	 * Reads C2PA data from [image]. When [trust] is provided, the reader is configured to validate
	 * the signer against that trust material (so the result carries `signingCredential.trusted` /
	 * `.untrusted`). Returns [C2paRawRead.NoManifest] when the asset has no embedded manifest,
	 * [C2paRawRead.Manifest] when one is present (regardless of validity), or throws
	 * [C2paReadException] on an unexpected failure.
	 */
	suspend fun read(image: ImageSource, trust: TrustMaterial? = null): C2paRawRead
}

/** Raw, unparsed result of a C2PA read. */
sealed interface C2paRawRead {
	/** No C2PA manifest is embedded in the asset. */
	data object NoManifest : C2paRawRead

	/**
	 * A manifest store is present. [manifestJson] is `reader.json()`, [detailedJson] is
	 * `reader.detailedJson()` (heavier; may be null if not requested), and [certChainDer] is the
	 * signer's X.509 chain in DER form (may be empty if the library didn't expose it).
	 *
	 * [verificationIncomplete] means the reader reported a verdict without actually reading the
	 * asset, so any hash failure it reports is meaningless. See `AndroidC2paReaderDataSource`.
	 */
	data class Manifest(
		val manifestJson: String,
		val detailedJson: String?,
		val certChainDer: List<ByteArray>,
		val verificationIncomplete: Boolean = false,
	) : C2paRawRead
}

class C2paReadException(message: String, cause: Throwable? = null) : Exception(message, cause)
