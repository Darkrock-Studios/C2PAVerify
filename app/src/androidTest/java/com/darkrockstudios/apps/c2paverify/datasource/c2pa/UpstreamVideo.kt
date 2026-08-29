package com.darkrockstudios.apps.c2paverify.datasource.c2pa

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only C2PA-signed MP4 upstream publishes, fetched rather than vendored.
 *
 * It is 15 MB, which is a permanent cost to the repo for one test file. It is cached in the app's
 * files dir, so only the first run after an install needs the network; a test run reinstalls the
 * app, so that is roughly once per invocation.
 */
object UpstreamVideo {

	private const val NAME = "truepic-20230212-zoetrope.mp4"
	private const val BYTES = 15_456_823L
	private const val URL =
		"https://raw.githubusercontent.com/c2pa-org/public-testfiles/main/" +
			"legacy/1.4/video/mp4/$NAME"

	/**
	 * Downloads the fixture if it is not already cached. Skips the calling test rather than failing
	 * it when the network is unavailable, so an offline run does not report red on something it never
	 * exercised.
	 */
	fun file(context: Context): File {
		val cached = File(context.filesDir, "fixtures/$NAME")
		if (cached.exists() && cached.length() == BYTES) return cached
		cached.parentFile?.mkdirs()
		try {
			val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
				connectTimeout = 30_000
				readTimeout = 30_000
			}
			try {
				connection.inputStream.use { input ->
					cached.outputStream().use { output -> input.copyTo(output) }
				}
			} finally {
				connection.disconnect()
			}
		} catch (e: IOException) {
			cached.delete()
			assumeNoException("Could not fetch $URL; skipping", e)
		}
		assertEquals("fetched fixture is the wrong size", BYTES, cached.length())
		return cached
	}
}
