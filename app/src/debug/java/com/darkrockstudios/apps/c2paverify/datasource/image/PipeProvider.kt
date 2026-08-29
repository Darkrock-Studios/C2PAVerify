package com.darkrockstudios.apps.c2paverify.datasource.image

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Serves a file down a pipe, the way cloud storage providers serve a document they hold remotely.
 *
 * A pipe cannot be seeked and reports no size, which is the condition the app has to copy its way
 * out of. Nothing on an emulator does this on its own, so this supplies it.
 *
 * Debug builds only, and declared in the debug manifest rather than the test one so that it runs in
 * the app's process with the app's classpath. The last path segment names a file in the cache dir.
 */
class PipeProvider : ContentProvider() {

	override fun onCreate() = true

	override fun getType(uri: Uri): String = "video/mp4"

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
		val source = File(requireNotNull(context).cacheDir, requireNotNull(uri.lastPathSegment))
		return openPipeHelper(uri, "video/mp4", null, source) { output, _, _, _, file ->
			// A reader that stops early leaves the write end broken, which is ordinary rather than
			// exceptional: it is what happens whenever a consumer only wants the head of a document.
			runCatching {
				FileOutputStream(output.fileDescriptor).use { sink ->
					requireNotNull(file).inputStream().use { it.copyTo(sink) }
				}
			}
			Unit
		}
	}

	override fun query(
		uri: Uri,
		projection: Array<out String>?,
		selection: String?,
		selectionArgs: Array<out String>?,
		sortOrder: String?,
	): Cursor? = null

	override fun insert(uri: Uri, values: ContentValues?): Uri? = null

	override fun update(
		uri: Uri,
		values: ContentValues?,
		selection: String?,
		selectionArgs: Array<out String>?,
	): Int = 0

	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

	companion object {
		const val AUTHORITY = "com.darkrockstudios.apps.c2paverify.debug.pipe"

		/** A URI serving [name] from the app's cache dir over a pipe. */
		fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
	}
}
