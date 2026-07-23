plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.trubus.tams"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.trubus.tams"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      val localKeystore = file("${rootDir}/debug.keystore")
      if (localKeystore.exists()) {
        storeFile = localKeystore
      } else {
        val userHome = System.getProperty("user.home")
        storeFile = file("$userHome/.android/debug.keystore")
      }
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // Was false, meaning proguard-rules.pro below was configured but never
      // actually applied -- the release APK shipped completely unshrunk and
      // unobfuscated. R8 shrinking commonly cuts APK size and method count
      // substantially, which matters directly for the low-end devices this
      // app targets. See the added keep rules in proguard-rules.pro.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

// NOTE: The Google "secrets-gradle-plugin" (which required a checked-in
// .env.example template file to exist) was removed here. It exists solely to
// inject a Google Maps Platform API key into the manifest/BuildConfig, but
// this app renders maps with osmdroid (an open-source library, no API key
// needed) -- grep across the whole module confirms nothing ever read a
// secrets-sourced value (no MAPS_API_KEY, no manifestPlaceholders, no custom
// BuildConfig field). It was leftover wiring from the original Android
// Studio "Google Maps Activity" template, from before the project switched
// to osmdroid, and was never actually functional.
//
// NOTE: Firebase (BOM/AI/App Check), Google Services plugin, navigation-compose,
// Coil, CameraX, Accompanist and DataStore were removed/never wired up here.
// None of them are referenced anywhere in the source (verified), there is no
// google-services.json in the project, and each one adds APK size plus (for
// Firebase in particular) extra ContentProvider-driven startup work -- pure
// overhead on the low-end devices this app targets. Re-add only if a real
// feature needs them.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.osmdroid.android)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // NOTE: src/test and src/androidTest previously contained only the
  // Android Studio "Example*Test" placeholders (no real test coverage) and
  // were removed, along with the JUnit/Espresso/Robolectric/Roborazzi
  // dependencies and the roborazzi plugin that existed solely to support
  // them. Re-add a test source set and its dependencies together the day
  // real tests are written.
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
