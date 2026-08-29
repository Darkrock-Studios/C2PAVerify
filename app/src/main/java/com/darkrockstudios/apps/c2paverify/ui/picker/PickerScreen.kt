package com.darkrockstudios.apps.c2paverify.ui.picker

import android.Manifest.permission.ACCESS_MEDIA_LOCATION
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.darkrockstudios.apps.c2paverify.R

/** A bundled sample the user can open to see the app work without picking their own photo. */
private data class ExampleImage(
	val assetUri: String,
	val titleRes: Int,
	val subtitleRes: Int,
	val testTag: String,
)

/** Stable identifiers for the bundled examples, shared with the E2E smoke test. */
object PickerExamples {
	const val TRUSTED_ASSET = "file:///android_asset/c2pa/examples/trusted.jpg"
	const val AI_ASSET = "file:///android_asset/c2pa/examples/ai-generated.jpg"
	const val TAG_TRUSTED = "example_trusted"
	const val TAG_AI = "example_ai"
}

private val exampleImages = listOf(
	ExampleImage(
		assetUri = PickerExamples.TRUSTED_ASSET,
		titleRes = R.string.example_trusted_title,
		subtitleRes = R.string.example_trusted_subtitle,
		testTag = PickerExamples.TAG_TRUSTED,
	),
	ExampleImage(
		assetUri = PickerExamples.AI_ASSET,
		titleRes = R.string.example_ai_title,
		subtitleRes = R.string.example_ai_subtitle,
		testTag = PickerExamples.TAG_AI,
	),
)

/**
 * Landing screen: an informative empty state, a document picker for images and video, and a couple
 * of bundled example images so the app can be tried without a C2PA asset on hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
	onImagePicked: (String) -> Unit,
	onOpenTrust: () -> Unit,
	onShowOnboarding: () -> Unit,
	onShowStudio: () -> Unit,
) {
	// Deliberately ACTION_OPEN_DOCUMENT (SAF), NOT the Photo Picker: the picker only exposes
	// pre-redacted picker:// URIs. SAF can still hand back a media-backed URI that MediaProvider
	// redacts, so AssetSourceDataSource reaches the original via ACCESS_MEDIA_LOCATION +
	// setRequireOriginal, which is why we request that permission before opening the picker.
	val openDocument = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri -> if (uri != null) onImagePicked(uri.toString()) }

	// Request ACCESS_MEDIA_LOCATION (needed to read un-redacted GPS EXIF so the C2PA hash stays
	// intact), then open the document picker regardless of the user's choice.
	val requestMediaLocation = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { openDocument.launch(arrayOf("image/*", "video/*")) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.app_name)) },
				actions = {
					IconButton(onClick = onShowOnboarding) {
						Icon(
							imageVector = Icons.Filled.Info,
							contentDescription = stringResource(R.string.onboarding_show),
						)
					}
					IconButton(onClick = onOpenTrust) {
						Icon(
							painter = painterResource(R.drawable.ic_shield),
							contentDescription = stringResource(R.string.trust_manage),
						)
					}
				},
			)
		},
	) { innerPadding ->
		// Centred, width-capped body so it doesn't stretch awkwardly wide on tablets.
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.verticalScroll(rememberScrollState()),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
		) {
			Column(
				modifier = Modifier
					.widthIn(max = BodyMaxWidth)
					.fillMaxWidth()
					.padding(horizontal = 24.dp, vertical = 32.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				Icon(
					imageVector = Icons.Default.Search,
					contentDescription = null,
					modifier = Modifier.size(72.dp),
					tint = MaterialTheme.colorScheme.primary,
				)
				Text(
					text = stringResource(R.string.empty_title),
					style = MaterialTheme.typography.headlineSmall,
					textAlign = TextAlign.Center,
				)
				Text(
					text = stringResource(R.string.empty_body),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
				Button(
					onClick = { requestMediaLocation.launch(ACCESS_MEDIA_LOCATION) },
				) {
					Text(stringResource(R.string.pick_photo))
				}

				Text(
					text = stringResource(R.string.example_section_title),
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
				)
				exampleImages.forEach { example ->
					ExampleCard(
						example = example,
						onClick = { onImagePicked(example.assetUri) },
					)
				}

				TextButton(onClick = onShowStudio, modifier = Modifier.padding(top = 16.dp)) {
					Text(
						text = stringResource(R.string.about_studio_button),
						style = MaterialTheme.typography.bodySmall,
					)
				}
			}
		}
	}
}

/** The landing body is capped to this width so it stays readable on large screens. */
private val BodyMaxWidth = 560.dp

@Composable
private fun ExampleCard(
	example: ExampleImage,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Card(
		onClick = onClick,
		modifier = modifier.fillMaxWidth().testTag(example.testTag),
	) {
		Row(
			modifier = Modifier.padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			AsyncImage(
				model = example.assetUri,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.size(64.dp)
					.clip(RoundedCornerShape(8.dp)),
			)
			Column(Modifier.weight(1f)) {
				Text(stringResource(example.titleRes), style = MaterialTheme.typography.bodyLarge)
				Text(
					stringResource(example.subtitleRes),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}
