package com.darkrockstudios.apps.c2paverify.repository

import com.darkrockstudios.apps.c2paverify.datasource.image.AssetSourceDataSource
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource

/**
 * Provides the asset to be inspected as a platform-neutral [ImageSource]. Thin seam over
 * [AssetSourceDataSource]; keeps `android.*`/URI handling in the data layer. (Coil/Telephoto and
 * the video player load the URI directly in the UI; this path is for feeding the C2PA reader.)
 */
class ImageRepository(private val assetSource: AssetSourceDataSource) {
	suspend fun load(uriString: String): ImageSource = assetSource.read(uriString)
}
