/**
 * Root aggregator for the FlixelGDX Video multi-module build.
 *
 * <p>The root project holds no source itself; it only applies IDE plugins and registers the
 * aggregate Javadoc task. All subproject setup lives in the convention plugins under
 * build-logic/src/main/kotlin/.
 */

plugins {
  eclipse
  idea
  // These plugins must be declared in the root classpath scope to prevent classloader conflicts
  // when convention plugins apply them to sibling subprojects. None of these are applied to the
  // root project itself; subprojects apply them via the flixelgdx.* convention plugins.
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.vanniktech) apply false
}

val groupId: String by project

group = groupId
version = gitVersion()

eclipse.project.name = "flixelgdx-video-parent"

fun gitVersion(): String = try {
  val proc = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
    .directory(rootDir)
    .start()
  proc.waitFor()
  proc.inputStream.bufferedReader().readText().trim().removePrefix("v").ifEmpty { "unspecified" }
} catch (_: Exception) {
  "unspecified"
}

idea {
  module {
    outputDir = file("build/classes/java/main")
    testOutputDir = file("build/classes/java/test")
  }
}

tasks.register("javadocAll") {
  group = "verification"
  description = "Runs Javadoc (with doclint) on all published Java library modules."
  dependsOn(
    ":flixelgdx-video-core:javadoc",
    ":flixelgdx-video-desktop:javadoc",
    ":flixelgdx-video-html5:javadoc"
  )
}
