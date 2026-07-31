plugins {
  id("flixelgdx.java-library")
}

dependencies {
  api(project(":flixelgdx-video-core"))
  api(libs.flixelgdx.teavm)
  implementation(libs.jetbrains.annotations)
}
