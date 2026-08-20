package com.darkrockstudios.apps.c2paverify.di

import androidx.room.Room
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import com.darkrockstudios.apps.c2paverify.datasource.db.C2paDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Platform singletons shared across layers: the Room database (and its DAOs), the JSON parser,
 * the Ktor client and the Coil image loader.
 *
 * The C2PA manifest schema is large and evolving, so [Json] is lenient and ignores unknown keys
 * for forward-compatibility.
 */
val appModule = module {
	single {
		Json {
			ignoreUnknownKeys = true
			isLenient = true
		}
	}

	single {
		Room.databaseBuilder(
			androidContext(),
			C2paDatabase::class.java,
			"c2pa.db",
		).build()
	}
	single { get<C2paDatabase>().userTrustDao() }

	single<HttpClient> { HttpClient(OkHttp) }

	/**
	 * One loader for both the viewer and the report renderer, so an asset that can be displayed can
	 * always be shared too. Registers the decoders the platform doesn't provide: SVG has none at all,
	 * and an animated GIF renders as a lone first frame until [AnimatedImageDecoder] is added.
	 */
	single {
		ImageLoader.Builder(androidContext())
			.components {
				add(SvgDecoder.Factory())
				add(AnimatedImageDecoder.Factory())
			}
			.build()
	}
}

/**
 * All Koin modules, ordered by layer. As components are added, register them in the matching
 * per-layer module (stateless = factory, stateful = single).
 */
val appModules = listOf(
	appModule,
	dataSourceModule,
	repositoryModule,
	serviceModule,
	useCaseModule,
	viewModelModule,
)
