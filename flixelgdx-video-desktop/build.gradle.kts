// Desktop backend for the FlixelGDX video extension. Bridges libvlc into the framework through
// JNA-registered JNI bindings. The libvlc native libraries are provided at runtime by the
// flixelgdx-video-vlc-natives modules, which FlixelVlcDiscovery extracts from the classpath on
// first use.

plugins {
  id("flixelgdx.java-library")
}

dependencies {
  api(project(":flixelgdx-video-core"))
  api(libs.jna)

  implementation(libs.flixelgdx.desktop)
  implementation(libs.jetbrains.annotations)

  runtimeOnly(project(":flixelgdx-video-vlc-natives-windows-amd64"))
  runtimeOnly(project(":flixelgdx-video-vlc-natives-windows-aarch64"))
  runtimeOnly(project(":flixelgdx-video-vlc-natives-linux-amd64"))
  runtimeOnly(project(":flixelgdx-video-vlc-natives-linux-aarch64"))
  runtimeOnly(project(":flixelgdx-video-vlc-natives-macos-universal"))

  compileOnly(libs.graalvm.nativeimage)
}
