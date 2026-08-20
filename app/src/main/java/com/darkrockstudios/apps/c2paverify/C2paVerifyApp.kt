package com.darkrockstudios.apps.c2paverify

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.darkrockstudios.apps.c2paverify.di.appModules
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin

class C2paVerifyApp : Application(), SingletonImageLoader.Factory, KoinComponent {
	override fun onCreate() {
		super.onCreate()

		Napier.base(DebugAntilog())

		startKoin {
			androidLogger()
			androidContext(this@C2paVerifyApp)
			modules(appModules)
		}
	}

	/** Points Coil's singleton at the Koin-built loader so every call site shares its decoders. */
	override fun newImageLoader(context: PlatformContext): ImageLoader = get()
}
