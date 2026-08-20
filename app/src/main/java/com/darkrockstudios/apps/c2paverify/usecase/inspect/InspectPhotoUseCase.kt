package com.darkrockstudios.apps.c2paverify.usecase.inspect

import com.darkrockstudios.apps.c2paverify.model.common.resolveFormat
import com.darkrockstudios.apps.c2paverify.model.summary.InspectionResult
import com.darkrockstudios.apps.c2paverify.model.summary.SummaryFactory
import com.darkrockstudios.apps.c2paverify.model.trust.TrustLevel
import com.darkrockstudios.apps.c2paverify.model.trust.authorityKeyOf
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestRepository
import com.darkrockstudios.apps.c2paverify.repository.ImageRepository
import com.darkrockstudios.apps.c2paverify.repository.TrustListRepository
import com.darkrockstudios.apps.c2paverify.repository.UserTrustRepository
import com.darkrockstudios.apps.c2paverify.service.TrustEvaluationService

/**
 * Orchestrates a full inspection: load image bytes, read+parse the C2PA manifest against the
 * active trust list, derive the trust level (c2pa verdict + user allow/deny), and assemble the
 * summary. Stateless use case combining repositories + the trust service + the pure SummaryFactory.
 */
class InspectPhotoUseCase(
	private val imageRepository: ImageRepository,
	private val manifestRepository: C2paManifestRepository,
	private val trustListRepository: TrustListRepository,
	private val trustEvaluationService: TrustEvaluationService,
	private val userTrustRepository: UserTrustRepository,
) {
	suspend operator fun invoke(imageUri: String): InspectionResult {
		val image = imageRepository.load(imageUri)
		val format = image.resolveFormat()
		// Honour the user's dis-allowed CAs by dropping them from the trust anchors.
		val trust = trustListRepository.current(userTrustRepository.blockedAnchorSubjects())
		return when (val result = manifestRepository.inspect(image, trust)) {
			is C2paManifestRepository.ManifestResult.NoManifest ->
				InspectionResult(
					summary = SummaryFactory.buildSummary(manifest = null, trust = TrustLevel.UNKNOWN),
					manifest = null,
					format = format,
				)

			is C2paManifestRepository.ManifestResult.Present -> {
				val level = trustEvaluationService.evaluate(result.data)
				val signature = result.data.activeManifest?.signature
				val key = authorityKeyOf(signature?.issuer, signature?.certSerialNumber)
				val hasOverride = key != null && userTrustRepository.ruleFor(key) != null
				InspectionResult(
					summary = SummaryFactory.buildSummary(result.data, level),
					manifest = result.data,
					signerHasOverride = hasOverride,
					format = format,
				)
			}
		}
	}
}
