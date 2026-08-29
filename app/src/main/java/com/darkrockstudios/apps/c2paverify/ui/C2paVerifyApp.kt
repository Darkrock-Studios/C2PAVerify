package com.darkrockstudios.apps.c2paverify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.darkrockstudios.apps.c2paverify.BuildConfig
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionScaffold
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionViewModel
import com.darkrockstudios.apps.c2paverify.ui.navigation.Landing
import com.darkrockstudios.apps.c2paverify.ui.navigation.Trust
import com.darkrockstudios.apps.c2paverify.ui.navigation.Viewer
import com.darkrockstudios.apps.c2paverify.ui.onboarding.OnboardingScreen
import com.darkrockstudios.apps.c2paverify.ui.onboarding.OnboardingViewModel
import com.darkrockstudios.apps.c2paverify.ui.picker.PickerScreen
import com.darkrockstudios.apps.c2paverify.ui.trust.TrustManagementScreen
import com.darkrockstudios.cairn.CairnAboutOverlay
import com.darkrockstudios.cairn.CairnAppId
import com.darkrockstudios.cairn.CairnConfig
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel

/**
 * Root composable. Hosts navigation between the landing/picker and the photo viewer, and routes
 * images shared into the app (via [sharedImage]) straight to the viewer.
 */
@Composable
fun C2paVerifyApp(sharedImage: StateFlow<String?>) {
	val navController = rememberNavController()
	// One inspection VM shared by the viewer and the deep-dive (activity-scoped owner).
	val inspectionViewModel: InspectionViewModel = koinViewModel()
	val onboardingViewModel: OnboardingViewModel = koinViewModel()
	var currentImage by rememberSaveable { mutableStateOf<String?>(null) }

	val shared by sharedImage.collectAsStateWithLifecycle()
	LaunchedEffect(shared) {
		val uri = shared ?: return@LaunchedEffect
		currentImage = uri
		navController.navigate(Viewer) { launchSingleTop = true }
	}

	val showOnboarding by onboardingViewModel.showOnboarding.collectAsStateWithLifecycle()
	var studioVisible by rememberSaveable { mutableStateOf(false) }

	Box(modifier = Modifier.fillMaxSize()) {
		NavHost(navController = navController, startDestination = Landing) {
			composable<Landing> {
				PickerScreen(
					onImagePicked = { uri ->
						currentImage = uri
						navController.navigate(Viewer)
					},
					onOpenTrust = { navController.navigate(Trust) },
					onShowOnboarding = onboardingViewModel::replay,
					onShowStudio = { studioVisible = true },
				)
			}
			composable<Viewer> {
				InspectionScaffold(
					imageUri = currentImage,
					viewModel = inspectionViewModel,
					onExit = { navController.popBackStack() },
				)
			}
			composable<Trust> {
				TrustManagementScreen(onBack = { navController.popBackStack() })
			}
		}

		// One-time intro slideshow, drawn above everything until seen (or while replayed).
		if (showOnboarding == true) {
			OnboardingScreen(onFinish = onboardingViewModel::finish)
		}

		// Last child of the Box: Cairn draws over our own pixels and claims system back.
		CairnAboutOverlay(
			visible = studioVisible,
			config = CairnConfig(
				currentAppId = CairnAppId.C2paVerify,
				versionName = BuildConfig.VERSION_NAME,
			),
			onDismissed = { studioVisible = false },
		)
	}
}
