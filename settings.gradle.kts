/**
 * Root settings for the FlixelGDX Video multi-module build.
 *
 * <p>Declares the build-logic included build so convention plugins are available to all
 * subprojects, and centralizes repository declarations, including where the published FlixelGDX
 * framework artifacts (`org.flixelgdx:flixelgdx-core`, `flixelgdx-desktop`, `flixelgdx-html5`) are
 * resolved from. A contributor who wants to build against a local framework checkout instead can do
 * so without editing this file, for example by passing `--include-build ../flixelgdx` on the command
 * line.
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
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://jitpack.io")
  }
}

rootProject.name = "flixelgdx-video"

include(
  "flixelgdx-video-core",
  "flixelgdx-video-desktop",
  "flixelgdx-video-html5",
  "flixelgdx-video-vlc-natives-windows-amd64",
  "flixelgdx-video-vlc-natives-windows-aarch64",
  "flixelgdx-video-vlc-natives-linux-amd64",
  "flixelgdx-video-vlc-natives-linux-aarch64",
  "flixelgdx-video-vlc-natives-macos-universal"
)
