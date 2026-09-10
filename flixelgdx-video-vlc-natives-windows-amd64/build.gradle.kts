plugins {
  id("flixelgdx.vlc-natives")
}

val vlcVersionString = libs.versions.vlc.get()

vlcNatives {
  vlcVersion.set(vlcVersionString)
  platformDir.set("windows-amd64")
  excludeSdk.set(true)
  downloads.set(listOf(
    mapOf(
      "name" to "vlc-${vlcVersionString}-win64.zip",
      "url" to "https://download.videolan.org/pub/videolan/vlc/${vlcVersionString}/win64/vlc-${vlcVersionString}-win64.zip",
      "sha256" to "992d19dbd0b8a7cde9167d2f7780b1ef6f92acc8a71acfa736101a21f35181e1"
    )
  ))
}
