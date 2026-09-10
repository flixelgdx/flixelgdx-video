plugins {
  id("flixelgdx.java-library")
}

val vlcVersionString = libs.versions.vlc.get()

val vlcDownloads = listOf(
  mapOf(
    "name" to "libvlc5_${vlcVersionString}-0+deb12u1_arm64.deb",
    "url" to "https://deb.debian.org/debian/pool/main/v/vlc/libvlc5_${vlcVersionString}-0%2Bdeb12u1_arm64.deb",
    "sha256" to "0975b601999553dfe541216c92131d3cf66eaeee7bcc11f3ea89b776ebebf446"
  ),
  mapOf(
    "name" to "libvlccore9_${vlcVersionString}-0+deb12u1_arm64.deb",
    "url" to "https://deb.debian.org/debian/pool/main/v/vlc/libvlccore9_${vlcVersionString}-0%2Bdeb12u1_arm64.deb",
    "sha256" to "72ed72b65fad6c202e3824e51a1576c7b4747cb5732fb6c29aec85aa7887f4a1"
  ),
  mapOf(
    "name" to "vlc-plugin-base_${vlcVersionString}-0+deb12u1_arm64.deb",
    "url" to "https://deb.debian.org/debian/pool/main/v/vlc/vlc-plugin-base_${vlcVersionString}-0%2Bdeb12u1_arm64.deb",
    "sha256" to "7dd90880b8f30eea1a3b871dfaa10695e1291e359bbaab2fb3ced712c218fa8c"
  ),
  mapOf(
    "name" to "vlc-plugin-video-output_${vlcVersionString}-0+deb12u1_arm64.deb",
    "url" to "https://deb.debian.org/debian/pool/main/v/vlc/vlc-plugin-video-output_${vlcVersionString}-0%2Bdeb12u1_arm64.deb",
    "sha256" to "11bb52c4f4c84cf6087fa3328fba9df0b6442fe13a7f19425059ecdbf6557e1a"
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
    description = "Downloads libvlc $vlcVersionString Linux ARM64 natives for JAR packaging."
    vlcVersion.set(vlcVersionString)
    platformDir.set("linux-aarch64")
    downloadSpecs.set(vlcDownloads)
    pluginBlocklist.set(vlcPluginBlocklist)
    downloadCacheDir.set(vlcDownloadDir)
    nativesDir.set(vlcNativesDir)
  }

  tasks.processResources {
    dependsOn(downloadVlcNatives)
    from(vlcNativesDir) {
      into("org/flixelgdx/video/natives")
    }
  }
}
