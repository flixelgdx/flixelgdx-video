plugins {
  id("flixelgdx.vlc-natives")
}

val vlcVersionString = libs.versions.vlc.get()

vlcNatives {
  vlcVersion.set(vlcVersionString)
  platformDir.set("linux-amd64")
  downloads.set(listOf(
    mapOf(
      "name" to "libvlc5_${vlcVersionString}-0+deb12u1_amd64.deb",
      "url" to "https://deb.debian.org/debian/pool/main/v/vlc/libvlc5_${vlcVersionString}-0%2Bdeb12u1_amd64.deb",
      "sha256" to "07ad5c61dc41acf29c485224accf457b7632e68a910eee21badf30213e6ab359"
    ),
    mapOf(
      "name" to "libvlccore9_${vlcVersionString}-0+deb12u1_amd64.deb",
      "url" to "https://deb.debian.org/debian/pool/main/v/vlc/libvlccore9_${vlcVersionString}-0%2Bdeb12u1_amd64.deb",
      "sha256" to "a3114b86450777e4cbbd4620419b74d189367e5f5026286cc74c39c9e759bfa7"
    ),
    mapOf(
      "name" to "vlc-plugin-base_${vlcVersionString}-0+deb12u1_amd64.deb",
      "url" to "https://deb.debian.org/debian/pool/main/v/vlc/vlc-plugin-base_${vlcVersionString}-0%2Bdeb12u1_amd64.deb",
      "sha256" to "38b953a2a6355c5ba75e3e5d2015100d793fbf5a00211aaa78b2d705a9547cb1"
    ),
    mapOf(
      "name" to "vlc-plugin-video-output_${vlcVersionString}-0+deb12u1_amd64.deb",
      "url" to "https://deb.debian.org/debian/pool/main/v/vlc/vlc-plugin-video-output_${vlcVersionString}-0%2Bdeb12u1_amd64.deb",
      "sha256" to "de1d62487161efc62305b6e84cd364e71f109125d601401ee824d3291813e6e2"
    )
  ))
}
