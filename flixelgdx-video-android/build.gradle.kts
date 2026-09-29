// Android backend for the FlixelGDX video extension. Only included when the Android SDK is
// available (see includeAndroid in settings.gradle.kts).

plugins {
  id("flixelgdx.android-library")
}

android {
  namespace = "org.flixelgdx.video"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(project(":flixelgdx-video-core"))
  implementation(libs.flixelgdx.android)
  implementation(libs.jetbrains.annotations)
}
