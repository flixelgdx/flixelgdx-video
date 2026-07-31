import java.util.Properties

/**
 * Root settings for the FlixelGDX Video multi-module build.
 *
 * <p>Declares the build-logic included build so convention plugins are available to all
 * subprojects, centralizes repository declarations (including where the FlixelGDX framework
 * artifacts are resolved from), and conditionally includes the Android module when the Android
 * SDK is present.
 */

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
    maven("https://s01.oss.sonatype.org")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// The Android module is optional so the extension can be built without an Android SDK.
// Enable via: -PincludeAndroid=true (CI / one-off) or includeAndroid=true in local.properties (gitignored).
val includeAndroidFromCli = startParameter.projectProperties["includeAndroid"] == "true"
val includeAndroidFromLocal = run {
  val f = File(settingsDir, "local.properties")
  if (f.exists()) {
    val props = Properties()
    f.inputStream().use(props::load)
    props.getProperty("includeAndroid", "false") == "true"
  } else {
    false
  }
}
val includeAndroid = includeAndroidFromCli || includeAndroidFromLocal
gradle.extra["includeAndroid"] = includeAndroid

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
    maven("https://s01.oss.sonatype.org")
    // mavenLocal() and jitpack.io let the video modules resolve the FlixelGDX framework
    // from a local publishToMavenLocal build or a GitHub branch/commit when a matching
    // release is not yet on Maven Central. See COMPILING.md for the composite build path.
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://jitpack.io")
  }
}

rootProject.name = "flixelgdx-video"

include(
  "flixelgdx-video-core",
  "flixelgdx-video-vlc-natives-windows-amd64",
  "flixelgdx-video-vlc-natives-linux-amd64",
  "flixelgdx-video-vlc-natives-macos-universal",
  "flixelgdx-video-lwjgl3",
  "flixelgdx-video-teavm"
)

if (includeAndroid) {
  include("flixelgdx-video-android")
}
