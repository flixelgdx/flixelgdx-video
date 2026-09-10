plugins {
  id("flixelgdx.vlc-natives")
}

val vlcVersionString = libs.versions.vlc.get()

vlcNatives {
  vlcVersion.set(vlcVersionString)
  platformDir.set("windows-aarch64")
  excludeSdk.set(true)
  downloads.set(listOf(
    mapOf(
      "name" to "vlc-${vlcVersionString}-winarm64.zip",
      "url" to "https://download.videolan.org/pub/videolan/vlc/${vlcVersionString}/winarm64/vlc-${vlcVersionString}-winarm64.zip",
      "sha256" to "9c0917dc521ffc8ce30e70bca7f6c9dc8fec80909d763e75cd976351dee8db0b"
    )
  ))
}
