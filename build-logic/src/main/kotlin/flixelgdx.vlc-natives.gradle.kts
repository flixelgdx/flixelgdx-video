/**
 * Convention plugin for VLC natives JAR modules.
 *
 * <p>Applies {@code flixelgdx.java-library} and wires the {@link DownloadVlcNativesTask}
 * when {@code -PpackageVlcNatives=true} is set. Each applying module only needs to declare its
 * VLC version, platform identifier, and download specs via the {@code vlcNatives} extension.
 */

plugins {
  id("flixelgdx.java-library")
}

val vlcNatives: VlcNativesExtension = extensions.create<VlcNativesExtension>("vlcNatives")
vlcNatives.excludeSdk.convention(false)

val vlcPluginBlocklist = listOf(
  "gui", "lua", "control", "services_discovery", "visualization",
  "mux", "stream_out", "access_output", "meta_engine", "keystore", "logger"
)

val vlcDownloadDir = layout.buildDirectory.dir("vlc-downloads")
val vlcNativesDir = layout.buildDirectory.dir("vlc-natives")

if ((findProperty("packageVlcNatives") ?: "false") == "true") {
  val downloadVlcNatives = tasks.register<DownloadVlcNativesTask>("downloadVlcNatives") {
    group = "flixelgdx"
    val versionStr = vlcNatives.vlcVersion.get()
    val platformStr = vlcNatives.platformDir.get()
    description = "Downloads libvlc $versionStr $platformStr natives for JAR packaging."
    vlcVersion.set(versionStr)
    platformDir.set(platformStr)
    downloadSpecs.set(vlcNatives.downloads.get())
    pluginBlocklist.set(vlcPluginBlocklist)
    downloadCacheDir.set(vlcDownloadDir)
    nativesDir.set(vlcNativesDir)
  }

  tasks.named<ProcessResources>("processResources") {
    dependsOn(downloadVlcNatives)
    from(vlcNativesDir) {
      into("org/flixelgdx/video/natives")
      if (vlcNatives.excludeSdk.get()) {
        exclude("**/sdk/**")
      }
    }
  }
}
