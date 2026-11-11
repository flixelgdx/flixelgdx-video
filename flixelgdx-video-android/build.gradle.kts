// Android backend for the FlixelGDX video extension. Only included when the Android SDK is
// available (see includeAndroid in settings.gradle.kts).

plugins {
  id("flixelgdx.android-library")
}

android {
  namespace = "org.flixelgdx.video"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

dependencies {
  api(project(":flixelgdx-video-core"))
  implementation(libs.flixelgdx.android)
  implementation(libs.flixelgdx.gpu)
  implementation(libs.jwebgpu.api)
  implementation(libs.jetbrains.annotations)
}
