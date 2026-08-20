package com.darkrockstudios.apps.c2paverify.di

import com.darkrockstudios.apps.c2paverify.datasource.c2pa.AndroidC2paReaderDataSource
import com.darkrockstudios.apps.c2paverify.datasource.c2pa.C2paReaderDataSource
import com.darkrockstudios.apps.c2paverify.datasource.image.ImageBytesDataSource
import com.darkrockstudios.apps.c2paverify.datasource.report.ReportRendererDataSource
import com.darkrockstudios.apps.c2paverify.datasource.settings.PreferencesDataSource
import com.darkrockstudios.apps.c2paverify.datasource.trustlist.TrustAnchorParser
import com.darkrockstudios.apps.c2paverify.datasource.trustlist.TrustListAssetDataSource
import com.darkrockstudios.apps.c2paverify.datasource.trustlist.TrustListCacheDataSource
import com.darkrockstudios.apps.c2paverify.datasource.trustlist.TrustListRemoteDataSource
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestParser
import com.darkrockstudios.apps.c2paverify.repository.C2paManifestRepository
import com.darkrockstudios.apps.c2paverify.repository.ImageRepository
import com.darkrockstudios.apps.c2paverify.repository.ReportRepository
import com.darkrockstudios.apps.c2paverify.repository.SettingsRepository
import com.darkrockstudios.apps.c2paverify.repository.TrustListRepository
import com.darkrockstudios.apps.c2paverify.repository.UserTrustRepository
import com.darkrockstudios.apps.c2paverify.service.TrustEvaluationService
import com.darkrockstudios.apps.c2paverify.ui.inspection.InspectionViewModel
import com.darkrockstudios.apps.c2paverify.ui.onboarding.OnboardingViewModel
import com.darkrockstudios.apps.c2paverify.ui.trust.TrustManagementViewModel
import com.darkrockstudios.apps.c2paverify.usecase.inspect.InspectPhotoUseCase
import com.darkrockstudios.apps.c2paverify.usecase.settings.MarkOnboardingSeenUseCase
import com.darkrockstudios.apps.c2paverify.usecase.settings.ObserveOnboardingSeenUseCase
import com.darkrockstudios.apps.c2paverify.usecase.share.ShareReportUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.ClearAuthorityRuleUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.GetTrustListUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.ObserveUserTrustRulesUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.RefreshTrustListUseCase
import com.darkrockstudios.apps.c2paverify.usecase.trust.SetAuthorityRuleUseCase
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * One Koin module per architectural layer. The DI scope mirrors statefulness:
 *  - stateless items (data sources, use cases) are registered as `factory`
 *  - stateful items (repositories, services) are registered as `single`
 *
 * Components are added here as each build-order step lands.
 */

// LAYER 1 — data sources (stateless → factory)
val dataSourceModule = module {
	factoryOf(::AndroidC2paReaderDataSource) bind C2paReaderDataSource::class
	factory { C2paManifestParser(get()) }
	factory { ImageBytesDataSource(androidContext()) }
	factory { TrustListAssetDataSource(androidContext()) }
	factory { TrustListCacheDataSource(androidContext()) }
	factoryOf(::TrustListRemoteDataSource)
	factoryOf(::TrustAnchorParser)
	factory { ReportRendererDataSource(androidContext(), get()) }
	factory { PreferencesDataSource(androidContext()) }
}

// LAYER 2 — repositories (stateful → single)
val repositoryModule = module {
	singleOf(::C2paManifestRepository)
	singleOf(::ImageRepository)
	singleOf(::ReportRepository)
	singleOf(::SettingsRepository)
	singleOf(::TrustListRepository)
	singleOf(::UserTrustRepository)
}

// LAYER 3 — services (stateful → single)
val serviceModule = module {
	singleOf(::TrustEvaluationService)
}

// LAYER 4 — use cases (stateless → factory)
val useCaseModule = module {
	factoryOf(::InspectPhotoUseCase)
	factoryOf(::ShareReportUseCase)
	factoryOf(::GetTrustListUseCase)
	factoryOf(::RefreshTrustListUseCase)
	factoryOf(::ObserveUserTrustRulesUseCase)
	factoryOf(::SetAuthorityRuleUseCase)
	factoryOf(::ClearAuthorityRuleUseCase)
	factoryOf(::ObserveOnboardingSeenUseCase)
	factoryOf(::MarkOnboardingSeenUseCase)
}

// Presentation — ViewModels
val viewModelModule = module {
	viewModelOf(::InspectionViewModel)
	viewModelOf(::TrustManagementViewModel)
	viewModelOf(::OnboardingViewModel)
}
