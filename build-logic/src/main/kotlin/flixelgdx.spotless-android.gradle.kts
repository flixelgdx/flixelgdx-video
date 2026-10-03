plugins {
  id("com.diffplug.spotless")
}

spotless {
  java {
    target("flixelgdx-video-android/src/**/*.java")
    eclipse("4.33").configFile("${rootDir}/gradle/spotless/eclipse-formatter.xml")
    importOrder("com", "org", "io", "java", "javax", "jdk", "", "\\#")
    removeUnusedImports()
    endWithNewline()
    trimTrailingWhitespace()
  }
}
