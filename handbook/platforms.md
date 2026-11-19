# Platform Notes: Formats, Desktop, Web, and Android

Your game code is the same on every platform, but each platform decodes video its own way. This guide covers which
file format to pick and what is different about desktop, the web, and Android.

## Table of contents

1. [Choosing a file format](#choosing-a-file-format)
2. [Desktop](#desktop)
3. [Web](#web)
4. [Android](#android)
5. [Check your understanding](#check-your-understanding)

## Choosing a file format

Each platform decodes with a different engine, so the set of formats they share is what matters:

| Platform | Decoder                          | What it plays                                                |
|----------|----------------------------------|--------------------------------------------------------------|
| Desktop  | libvlc                           | Nearly every common format.                                  |
| Web      | The browser's built-in decoder   | Whatever the player's browser supports.                      |
| Android  | The platform `MediaPlayer`       | The formats Android supports, which can vary by device.      |

**For a game that ships on several platforms, use MP4 files with H.264 video and AAC audio.** It is the one combination
every desktop, browser, and phone plays. Keep the resolution no larger than you draw it (a 4K file in a 720p game only
costs decode time), and test each platform you ship on.

## Desktop

### The decoder and its native libraries

The desktop module decodes with [libvlc](https://www.videolan.org/vlc/libvlc.html). Its native libraries for Windows
and Linux (x86-64 and ARM64) and macOS (one universal build) come with the desktop module as runtime dependencies, so
there is nothing to install for most players.

When the first video is created, the extension looks for a working libvlc in this order and uses the first one that
actually starts up:

1. The folder named by the `flixel.video.vlc.path` system property, if you set it. That folder must hold the libvlc
   library and its `plugins` folder. If it does not work, no other place is tried.
2. A `vlc/` folder in the game's working directory, for games that ship their own copy of VLC.
3. The bundled natives, extracted once to a cache folder in the player's user directory.
4. A system-wide VLC installation.

On Linux, steps 3 and 4 are swapped: a system VLC is tried before the bundled copy, because the distribution's own
VLC plugins are built against the codec libraries that are actually installed on that system. If a Linux player sees
no video, installing VLC and the distribution's VLC plugin packages (for example `vlc-plugin-base`) is the usual fix.

### When no decoder is found

If none of those places has a working libvlc, the game still runs. Every video becomes a stand-in that is never ready,
and the reason is logged once, along with how to fix it. After the first failure, the search is not repeated for the
rest of the session. See [When a video cannot play](playing-videos.md#when-a-video-cannot-play).

To check which libvlc was loaded, read the log: the first video logs the libvlc version and the path it came from. For a
diagnostics screen, `FlixelDesktopVideoHandler.getLibVlcVersion()` returns the version string.

### Videos inside a packaged JAR

libvlc can only open real files. A video packed inside a JAR (for example an internal asset in a released build) is
copied to a temporary file the first time a video is created from it, and the temporary file is deleted when the game
exits. Nothing is required from you, but very large videos take a moment to copy.

### Native images

Desktop builds compiled to a GraalVM native image work without extra setup; the desktop module carries the
configuration that libvlc's bindings need.

## Web

### How it works

The browser decodes the video with a hidden video element that is never added to the page, and each new frame is
copied straight into a GPU texture. Because of that, the video is drawn in the game's own draw order, like any sprite.

### Where files are loaded from

A relative path such as `Flixel.files.internal("videos/intro.mp4")` is loaded from `assets/videos/intro.mp4` next to
the game's page, which is where the web packaging puts your assets. A path that is already a full URL (it contains
`://`) or starts with `/` is loaded as is.

Videos are requested in cross-origin mode, so a video hosted on another domain only plays if that server allows
cross-origin requests. Serving videos alongside the game avoids the issue entirely.

### Autoplay rules

Browsers do not let a page play sound until the player has interacted with it. If a video with sound is started before
the first click, tap, or key press, the browser blocks it, and the video starts automatically on the player's next
pointer or key press. A "click to start" title screen before your first video hides this nicely.

### Formats

Browser support varies. MP4 with H.264 video and AAC audio plays in every modern browser.

## Android

### Installing

The Android handler needs a `Context`, so pass your activity: `FlixelAndroidVideoHandler.install(this)`. The module
needs Android 8.0 (API 26) or newer and no extra native libraries, because the decoder ships with Android.

### Uncompressed assets

Videos in your APK's `assets` folder are opened directly from inside the APK, which only works for files stored
**uncompressed**. The Android build tools already store these extensions uncompressed: `.mp4`, `.m4v`, `.3gp`,
`.3gpp`, `.3g2`, `.3gpp2`, `.mkv`, and `.webm`.

Any other extension, such as `.mov`, is compressed by default and cannot be opened. Either convert the video to one
of the listed formats or add its extension to the no-compress list in your Android module:

```kotlin
// android/build.gradle.kts
android {
  androidResources {
    noCompress += "mov"
  }
}
```

If a video fails to open for this reason, the log says so and names the extension to add. Files on disk (opened with
`Flixel.files.local(...)`, `external(...)`, or `absolute(...)`) have no such limit.

### Rotated phone recordings

Phone cameras often save a video sideways and tag it with a rotation. On Android, that tag is ignored, so such a video
is drawn sideways. Re-encode it upright, or turn it with the video's `angle`.

### Loading starts on the first update

On Android, a video starts loading the first time it is updated. Calls like `play()`, `setTime(...)`, and
`setRate(...)` made before then are remembered and applied once loading finishes, so you can call them right after
creating the video. Just make sure the video is added to a state so it gets updated.

## Check your understanding

1. You need one video file that plays everywhere your game ships. Which format do you choose?
2. A web build's intro video does not start until the player presses a key. What is going on?
3. A `.mov` video works on desktop but never becomes ready on Android. What is the fix?

(Answers: MP4 with H.264 video and AAC audio; the browser blocked playback with sound before the first interaction, and
the video started on the key press; convert it to MP4, or add `noCompress += "mov"` to the Android module's build
file.)

**Next:** [Cookbook](cookbook.md)
