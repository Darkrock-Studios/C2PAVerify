package com.darkrockstudios.apps.c2paverify.repository

import com.darkrockstudios.apps.c2paverify.datasource.c2pa.C2paRawRead
import com.darkrockstudios.apps.c2paverify.datasource.c2pa.C2paReaderDataSource
import com.darkrockstudios.apps.c2paverify.model.c2pa.C2paManifestData
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.trust.TrustMaterial

/**
 * Reads and parses the C2PA manifest store for an image. Combines the [C2paReaderDataSource]
 * (data layer) with the pure [C2paManifestParser]; stays KMP-clean (no `android.*`).
 */
class C2paManifestRepository(
	private val reader: C2paReaderDataSource,
	private val parser: C2paManifestParser,
) {
	sealed interface ManifestResult {
		/** The asset carries no embedded C2PA manifest. */
		data object NoManifest : ManifestResult

		/** A manifest store is present (validity is described within [data]). */
		data class Present(val data: C2paManifestData) : ManifestResult
	}

	suspend fun inspect(image: ImageSource, trust: TrustMaterial? = null): ManifestResult =
		when (val raw = reader.read(image, trust)) {
			is C2paRawRead.NoManifest -> ManifestResult.NoManifest
			is C2paRawRead.Manifest -> ManifestResult.Present(
				parser.parse(raw.manifestJson, raw.detailedJson, raw.verificationIncomplete),
			)
		}
}
