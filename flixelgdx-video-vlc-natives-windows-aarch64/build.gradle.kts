plugins {
  id("flixelgdx.java-library")
}

val vlcVersionString = libs.versions.vlc.get()

val vlcDownloads = listOf(
  mapOf(
    "name" to "vlc-${vlcVersionString}-winarm64.zip",
    "url" to "https://download.videolan.org/pub/videolan/vlc/${vlcVersionString}/winarm64/vlc-${vlcVersionString}-winarm64.zip",
    "sha256" to "9c0917dc521ffc8ce30e70bca7f6c9dc8fec80909d763e75cd976351dee8db0b"
  )
)

val vlcPluginBlocklist = listOf(
  "gui", "lua", "control", "services_discovery", "visualization",
  "mux", "stream_out", "access_output", "meta_engine", "keystore", "logger"
)

val vlcDownloadDir = layout.buildDirectory.dir("vlc-downloads")
val vlcNativesDir = layout.buildDirectory.dir("vlc-natives")

if ((findProperty("packageVlcNatives") ?: "false") == "true") {
  val downloadVlcNatives = tasks.register<DownloadVlcNativesTask>("downloadVlcNatives") {
    group = "flixelgdx"
    description = "Downloads libvlc $vlcVersionString Windows ARM64 natives for JAR packaging."
    vlcVersion.set(vlcVersionString)
    platformDir.set("windows-aarch64")
    downloadSpecs.set(vlcDownloads)
    pluginBlocklist.set(vlcPluginBlocklist)
    downloadCacheDir.set(vlcDownloadDir)
    nativesDir.set(vlcNativesDir)
  }

  tasks.processResources {
    dependsOn(downloadVlcNatives)
    from(vlcNativesDir) {
      into("org/flixelgdx/video/natives")
      exclude("**/sdk/**")
    }
  }
}
