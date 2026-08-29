package com.darkrockstudios.apps.c2paverify.usecase.inspect

import com.darkrockstudios.apps.c2paverify.datasource.c2pa.C2paRawRead
import com.darkrockstudios.apps.c2paverify.datasource.c2pa.C2paReaderDataSource
import com.darkrockstudios.apps.c2paverify.model.common.ImageSource
import com.darkrockstudios.apps.c2paverify.model.summary.OverallStatus
import com.darkrockstudios.apps.c2paverify.model.trust.TrustMaterial
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestParser
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestRepository
import com.darkrockstudios.apps.c2paverify.repository.ImageRepository
import com.darkrockstudios.apps.c2paverify.repository.TrustListRepository
import com.darkrockstudios.apps.c2paverify.repository.UserTrustRepository
import com.darkrockstudios.apps.c2paverify.service.TrustEvaluationService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the full read→parse→summarize pipeline with a fake C2PA reader feeding real captured
 * JSON, and a mocked [ImageRepository] (so no Android Context is needed).
 */
class InspectPhotoUseCaseTest {

	private val parser = C2paManifestParser(Json { ignoreUnknownKeys = true; isLenient = true })

	private fun fixture(name: String): String =
		requireNotNull(javaClass.classLoader?.getResourceAsStream("c2pa/captured/$name")) {
			"Missing fixture $name"
		}.bufferedReader().use { it.readText() }

	private fun useCaseReturning(
		raw: C2paRawRead,
		source: ImageSource = ImageSource.Bytes(ByteArray(0), "image/jpeg"),
	): InspectPhotoUseCase {
		val reader = object : C2paReaderDataSource {
			override suspend fun read(image: ImageSource, trust: TrustMaterial?): C2paRawRead = raw
		}
		val imageRepo = mockk<ImageRepository> {
			coEvery { load(any()) } returns source
		}
		val trustListRepo = mockk<TrustListRepository> {
			coEvery { current(any()) } returns TrustMaterial("-----BEGIN CERTIFICATE-----\nx\n-----END CERTIFICATE-----")
		}
		val userTrust = mockk<UserTrustRepository> {
			coEvery { ruleFor(any()) } returns null
			coEvery { blockedAnchorSubjects() } returns emptySet()
		}
		return InspectPhotoUseCase(
			imageRepo,
			C2paManifestRepository(reader, parser),
			trustListRepo,
			TrustEvaluationService(userTrust),
			userTrust,
		)
	}

	@Test
	fun `valid untrusted image summarizes as SIGNED_UNTRUSTED`() = runTest {
		val raw = C2paRawRead.Manifest(fixture("valid-C.manifest.json"), null, emptyList())
		val result = useCaseReturning(raw).invoke("content://x")
		assertEquals(OverallStatus.SIGNED_UNTRUSTED, result.summary.status)
		assertNotNull(result.manifest)
	}

	@Test
	fun `invalid signature summarizes as TAMPERED_INVALID`() = runTest {
		val raw = C2paRawRead.Manifest(fixture("invalid-sig.manifest.json"), null, emptyList())
		val result = useCaseReturning(raw).invoke("content://x")
		assertEquals(OverallStatus.TAMPERED_INVALID, result.summary.status)
	}

	@Test
	fun `no manifest summarizes as NO_MANIFEST`() = runTest {
		val result = useCaseReturning(C2paRawRead.NoManifest).invoke("content://x")
		assertEquals(OverallStatus.NO_MANIFEST, result.summary.status)
		assertNull(result.manifest)
	}

	@Test
	fun `a streamed source still carries its format to the UI`() = runTest {
		val video = ImageSource.Content(
			uri = "content://media/external/video/media/42",
			mimeType = "video/mp4",
			header = ByteArray(4) + "ftyp".toByteArray() + "isom".toByteArray(),
		)
		val result = useCaseReturning(C2paRawRead.NoManifest, source = video).invoke("content://x")
		assertEquals("video/mp4", result.format?.token)
	}
}
