package com.darkrockstudios.apps.c2paverify.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darkrockstudios.apps.c2paverify.MainActivity
import com.darkrockstudios.apps.c2paverify.R
import com.darkrockstudios.apps.c2paverify.ui.deepdive.DeepDive
import com.darkrockstudios.apps.c2paverify.ui.picker.PickerExamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end UI smoke test driving the full app (real Koin DI + the real c2pa-android reader) over
 * the two bundled example images. Deterministic because the fixtures ship in the APK — no gallery
 * or share intent required.
 *
 * Covers: launch -> open a trusted example -> assert the trusted summary + signer -> open the
 * deep-dive -> deny the signer and watch the verdict flip to untrusted -> clear the override and
 * watch it flip back; plus the AI example surfacing the AI-generated indicator.
 */
@RunWith(AndroidJUnit4::class)
class C2paSmokeTest {

	@get:Rule
	val composeRule = createAndroidComposeRule<MainActivity>()

	private fun str(resId: Int): String = composeRule.activity.getString(resId)

	/** First launch may show the onboarding slideshow; dismiss it so the landing is reachable. */
	private fun dismissOnboardingIfPresent() {
		val skip = str(R.string.onboarding_skip)
		composeRule.waitUntil(timeoutMillis = 10_000) {
			nodesWithText(skip).isNotEmpty() || nodesWithTag(PickerExamples.TAG_TRUSTED).isNotEmpty()
		}
		if (nodesWithText(skip).isNotEmpty()) {
			composeRule.onNodeWithText(skip).performClick()
		}
		composeRule.waitUntil(timeoutMillis = 10_000) {
			nodesWithTag(PickerExamples.TAG_TRUSTED).isNotEmpty()
		}
	}

	private fun nodesWithText(text: String, substring: Boolean = false) =
		composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes()

	private fun nodesWithTag(tag: String) =
		composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()

	private fun awaitText(text: String, substring: Boolean = false, timeoutMillis: Long = 30_000) =
		composeRule.waitUntil(timeoutMillis) { nodesWithText(text, substring).isNotEmpty() }

	/**
	 * Reveals the deep-dive. On compact width it's a separate screen behind a "View details"
	 * button; on expanded width (tablet/foldable) it's already shown in the second pane.
	 */
	private fun openDeepDive() {
		val viewDetails = str(R.string.view_details)
		if (nodesWithText(viewDetails).isNotEmpty()) {
			composeRule.onNodeWithText(viewDetails).performClick()
		}
	}

	/** The deep-dive is a lazy list: a section below the fold composes only once scrolled to. */
	private fun scrollToDetail(text: String) {
		composeRule.onNodeWithTag(DeepDive.TAG_LIST).performScrollToNode(hasText(text))
	}

	@Test
	fun trustedExample_verifies_andSignerCanBeDeniedAndRestored() {
		dismissOnboardingIfPresent()

		// Open the bundled trusted (Google-signed) example.
		composeRule.onNodeWithTag(PickerExamples.TAG_TRUSTED).performClick()

		// The summary banner reports a trusted verification, with the signer carried through.
		awaitText(str(R.string.status_trusted))
		composeRule.onNodeWithText("Signed by", substring = true).assertExists()

		// Drill into the deep-dive (no-op when it's already beside the photo on large screens).
		openDeepDive()
		scrollToDetail(str(R.string.section_signature))
		awaitText(str(R.string.section_signature))

		// Denying the signer recomputes the verdict end-to-end (Room rule -> re-inspect).
		// The signature card holds its scroll across the re-inspection, so the verdict is read
		// in place.
		scrollToDetail(str(R.string.action_distrust_signer))
		composeRule.onNodeWithText(str(R.string.action_distrust_signer)).performClick()
		awaitText(str(R.string.trust_untrusted))

		// Clearing the override restores trust (and leaves persisted state clean for re-runs).
		composeRule.onNodeWithText(str(R.string.action_clear_override)).performClick()
		awaitText(str(R.string.trust_trusted))
	}

	@Test
	fun aiExample_isFlaggedAsAiGenerated() {
		dismissOnboardingIfPresent()

		composeRule.onNodeWithTag(PickerExamples.TAG_AI).performClick()

		// The asset's own claim is an AI composite, so the AI-generated chip must appear.
		awaitText(str(R.string.ai_badge))
	}
}
