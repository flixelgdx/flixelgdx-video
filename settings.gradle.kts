/**
 * Root settings for the FlixelGDX Video multi-module build.
 *
 * <p>Declares the build-logic included build so convention plugins are available to all
 * subprojects, includes the sibling FlixelGDX framework as a composite build so the video modules
 * can resolve it from source during development, and centralizes repository declarations (including
 * where published FlixelGDX framework artifacts are resolved from).
 */

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://s01.oss.sonatype.org")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://s01.oss.sonatype.org")
    // mavenLocal() and jitpack.io let the video modules resolve the FlixelGDX framework
    // from a local publishToMavenLocal build or a GitHub branch/commit when a matching
    // release is not yet on Maven Central. When the composite build below is active it takes
    // priority over all of these. See COMPILING.md.
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://jitpack.io")
  }
}

rootProject.name = "flixelgdx-video"

// Build against the sibling FlixelGDX framework checkout when it is present, so a local framework
// change is picked up without republishing. When ../flixelgdx is absent (for example on CI) the
// framework is resolved from the repositories above instead.
if (file("../flixelgdx").isDirectory) {
  includeBuild("../flixelgdx")
}

include(
  "flixelgdx-video-core",
  "flixelgdx-video-vlc-natives-windows-amd64",
  "flixelgdx-video-vlc-natives-windows-aarch64",
  "flixelgdx-video-vlc-natives-linux-amd64",
  "flixelgdx-video-vlc-natives-linux-aarch64",
  "flixelgdx-video-vlc-natives-macos-universal",
  "flixelgdx-video-desktop",
  "flixelgdx-video-html5"
)
