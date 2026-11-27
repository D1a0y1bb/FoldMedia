import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(::load)
  }
}

android {
  namespace = "com.foldmedia.app"
  compileSdk = 37
  buildToolsVersion = "37.0.0"

  defaultConfig {
    applicationId = "com.foldmedia.app"
    minSdk = 33
    targetSdk = 36
    versionCode = 3
    versionName = "1.0.2"
  }

  signingConfigs {
    if (keystorePropertiesFile.exists()) {
      create("release") {
        val storePath = keystoreProperties.getProperty("storeFile").orEmpty()
        if (storePath.isNotBlank()) {
          storeFile = file(storePath)
        }
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "x86_64")
      isUniversalApk = true
    }
  }

  buildTypes {
    release {
      if (keystorePropertiesFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      }
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }

  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android & Lifecycle
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Foldable & Adaptive Windowing
  implementation(libs.androidx.window)

  // Compose & Miuix (Xiaomi HyperOS Design System)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.miuix.ui)
  implementation(libs.miuix.icons)
  implementation(libs.miuix.blur)
  implementation(libs.miuix.glass)
  implementation(libs.miuix.material)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Networking, Serialization & Image Loading
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)

  // On-device Media Engines (yt-dlp + ffmpeg)
  implementation(libs.youtubedl.library)
  implementation(libs.youtubedl.ffmpeg)

  // Unit & Instrumented Tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
