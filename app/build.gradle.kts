import com.android.build.api.variant.FilterConfiguration.FilterType.ABI

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
}

// Per-ABI offset added to the base versionCode so each split APK carries a unique code,
// ordered so 64-bit ABIs outrank their 32-bit counterparts on multi-ABI devices.
val abiVersionCodeOffsets = mapOf(
	"armeabi-v7a" to 1,
	"x86" to 2,
	"x86_64" to 3,
	"arm64-v8a" to 4,
)

// Set -PplayBundle=true for the Play AAB build (see fastlane deploy lane). Play does its own
// per-device splitting and prefers uncompressed native libs, so the AAB disables ABI splits and
// native-lib compression. (Splits + resource shrinking are also incompatible with bundleRelease:
// https://issuetracker.google.com/402800800.) The default builds the standalone split APKs for
// GitHub/IzzyOnDroid, where per-ABI splitting + compression keep each APK under 30 MB.
val playBundle = providers.gradleProperty("playBundle").orNull?.toBoolean() ?: false

android {
	namespace = "com.darkrockstudios.apps.c2paverify"
	compileSdk {
		version = release(37)
	}

	defaultConfig {
		applicationId = "com.darkrockstudios.apps.c2paverify"
		minSdk = 29
		targetSdk = 36
		versionCode = libs.versions.app.versioncode.get().toInt()
		versionName = libs.versions.app.version.get()

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	// Split into one APK per ABI: a universal APK bundling all four would exceed the
	// 30 MB per-APK ceiling of the IzzyOnDroid F-Droid repo (libc2pa_c.so is ~21 MB each).
	splits {
		abi {
			isEnable = !playBundle
			reset()
			include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
			isUniversalApk = false
		}
	}

	// CI signs release builds from a keystore passed via env vars (see .github/workflows/release.yml).
	// Locally these are unset, so release builds stay unsigned and debug builds are unaffected.
	val releaseKeystore = System.getenv("KEYSTORE_FILE")

	signingConfigs {
		if (releaseKeystore != null) {
			create("release") {
				storeFile = file(releaseKeystore)
				storePassword = System.getenv("KEYSTORE_PASSWORD")
				keyAlias = System.getenv("KEY_ALIAS")
				keyPassword = System.getenv("KEY_PASSWORD")
			}
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
			if (releaseKeystore != null) {
				signingConfig = signingConfigs.getByName("release")
			}
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	buildFeatures {
		compose = true
		// VERSION_NAME feeds the Cairn about screen.
		buildConfig = true
	}
	packaging {
		jniLibs {
			// Compress native libs for the standalone split APKs to clear the 30 MB ceiling;
			// the Play AAB ships them uncompressed (Play prefers extractNativeLibs=false).
			useLegacyPackaging = !playBundle
		}
		resources {
			excludes += setOf(
				"/META-INF/{AL2.0,LGPL2.1}",
				"META-INF/LICENSE.md",
				"META-INF/LICENSE-notice.md",
				"META-INF/LICENSE",
				"META-INF/LICENSE.txt",
				"META-INF/NOTICE.md",
				"META-INF/NOTICE",
				"META-INF/NOTICE.txt",
			)
		}
	}
}

androidComponents {
	onVariants { variant ->
		variant.outputs.forEach { output ->
			val abi = output.filters.find { it.filterType == ABI }?.identifier
			val offset = abiVersionCodeOffsets[abi] ?: return@forEach
			val baseCode = output.versionCode.get() ?: return@forEach
			output.versionCode.set(offset * 1000 + baseCode)
		}
	}
}

kotlin {
	compilerOptions {
		jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
	}
}

ksp {
	arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
	// Compose / AndroidX UI
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material.icons.core)
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)

	// Adaptive multi-pane + navigation
	implementation(libs.androidx.adaptive)
	implementation(libs.androidx.adaptive.layout)
	implementation(libs.androidx.adaptive.navigation)
	implementation(libs.androidx.navigation.compose)

	// DI (Koin)
	implementation(platform(libs.koin.bom))
	implementation(libs.koin.android)
	implementation(libs.koin.androidx.compose)

	// Coroutines & serialization
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.kotlinx.serialization.json)

	// HTTP (Ktor)
	implementation(libs.ktor.client.core)
	implementation(libs.ktor.client.okhttp)
	implementation(libs.ktor.client.content.negotiation)
	implementation(libs.ktor.serialization.kotlinx.json)

	// Image loading & zoom
	implementation(libs.coil.compose)
	implementation(libs.coil.network.ktor3)
	// Decoders the platform lacks: SVG has no native decoder at all, and animated GIF needs one
	// registered explicitly or Coil renders only the first frame.
	implementation(libs.coil.svg)
	implementation(libs.coil.gif)
	implementation(libs.telephoto.zoomable.image.coil3)

	// Video playback. media3-ui-compose-material3 supplies the Player composable (surface, transport
	// controls, seek bar); the ui-compose module beneath it carries PlayerSurface.
	implementation(libs.media3.exoplayer)
	implementation(libs.media3.ui.compose)
	implementation(libs.media3.ui.compose.material3)

	// C2PA reading/verification (Android-only native lib via JitPack; needs JNA aar).
	// c2pa-android transitively pulls the plain jna *jar*; on Android we need the *aar* (which
	// carries the native .so libs), so exclude the transitive jar to avoid duplicate classes.
	implementation(libs.c2pa.android) {
		exclude(group = "net.java.dev.jna", module = "jna")
	}
	implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

	// Storage & logging
	implementation(libs.androidx.room.runtime)
	implementation(libs.androidx.room.ktx)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.napier)

	// Dark Rock Studios about screen
	implementation(libs.cairn)

	// Unit tests
	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.turbine)
	testImplementation(libs.mockk)
	testImplementation(libs.koin.test)
	testImplementation(libs.koin.test.junit4)
	testImplementation(libs.konsist)
	testImplementation(libs.androidx.room.testing)

	// Instrumented / E2E tests
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.mockk.android)
	debugImplementation(libs.androidx.compose.ui.test.manifest)
	debugImplementation(libs.androidx.compose.ui.tooling)
}