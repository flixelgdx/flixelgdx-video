plugins {
  id("flixelgdx.vlc-natives")
}

val vlcVersionString = libs.versions.vlc.get()

vlcNatives {
  vlcVersion.set(vlcVersionString)
  platformDir.set("macos-universal")
  downloads.set(listOf(
    mapOf(
      "name" to "vlc-${vlcVersionString}-universal.dmg",
      "url" to "https://download.videolan.org/pub/videolan/vlc/${vlcVersionString}/macosx/vlc-${vlcVersionString}-universal.dmg",
      "sha256" to "56ee657c3aaf5c71b4ab7d6e4f4a77f6eca54633e0bf42a93b8116eb1d1f6ec9"
    )
  ))
}
