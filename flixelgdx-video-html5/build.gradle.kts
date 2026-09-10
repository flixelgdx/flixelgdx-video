// Web backend for the FlixelGDX video extension. Decodes video through a hidden HTML video element
// and reads each frame back into a FlixelImage that core uploads. The TeaVM plugin is applied so the
// browser interop (org.teavm.jso) is on the compile classpath, exactly as the framework's html5
// backend does it.

plugins {
  id("flixelgdx.java-library")
  alias(libs.plugins.teavm)
}

dependencies {
  api(project(":flixelgdx-video-core"))
  api(libs.flixelgdx.html5)
  implementation(libs.jetbrains.annotations)
}
