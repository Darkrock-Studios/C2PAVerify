package com.darkrockstudios.apps.c2paverify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.darkrockstudios.apps.c2paverify.ui.C2paVerifyApp
import com.darkrockstudios.apps.c2paverify.ui.theme.C2PAVerifyTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

	/** The latest image shared into the app via ACTION_SEND(_MULTIPLE). */
	private val sharedImage = MutableStateFlow<String?>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		handleShareIntent(intent)
		setContent {
			C2PAVerifyTheme {
				C2paVerifyApp(sharedImage = sharedImage)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleShareIntent(intent)
	}

	private fun handleShareIntent(intent: Intent?) {
		val type = intent?.type
		if (type == null || !(type.startsWith("image/") || type.startsWith("video/"))) return
		val uri: Uri? = when (intent.action) {
			Intent.ACTION_SEND ->
				IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
			// Multi-image share: inspect the first for now (a pager is a later enhancement).
			Intent.ACTION_SEND_MULTIPLE ->
				IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
					?.firstOrNull()
			else -> null
		}
		if (uri != null) sharedImage.value = uri.toString()
	}
}
