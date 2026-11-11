<div align="center">

# FlixelGDX Video

[![CI](https://github.com/flixelgdx/flixelgdx-video/actions/workflows/ci_build.yml/badge.svg)](https://github.com/flixelgdx/flixelgdx-video/actions/workflows/ci_build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.flixelgdx/flixelgdx-video-core)](https://central.sonatype.com/artifact/org.flixelgdx/flixelgdx-video-core)
[![JitPack](https://jitpack.io/v/flixelgdx/flixelgdx-video.svg)](https://jitpack.io/#flixelgdx/flixelgdx-video)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![FlixelGDX 0.7.2](https://img.shields.io/badge/FlixelGDX-0.7.2-red)](https://kotlinlang.org/)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange)](https://adoptium.net/temurin/releases?version=25&os=any&arch=any)
[![Platforms](https://img.shields.io/badge/platforms-Desktop%20%7C%20Web%20%7C%20Android-brightgreen)](https://flixelgdx.org)

</div>

FlixelGDX Video is a simplistic, robust and cross-platform video extension for the Java game framework [FlixelGDX](https://github.com/flixelgdx/flixelgdx).
It's the perfect tool to seamlessly play video files directly inside your game for things like cutscenes, backgrounds animations, and so much more.

> [!TIP]
> This README is a quick-start. For the full guide (formats, quality options, streaming,
> platform quirks, and the complete API), read the official documentation at
> **[flixelgdx.org/docs/videos](https://flixelgdx.org/docs/videos)**.

---

## Modules

The extension is split into a platform-neutral API plus one backend per platform, so your build
only carries the code and natives for the platforms you target.

| Module                              | Purpose                                                                                                                                                                                                                                                                                              |
|-------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`flixelgdx-video-core`**          | The platform-neutral API (`FlixelVideo`, `FlixelVideoPlayer`, `FlixelVideos`, `FlixelVideoFactory`, `FlixelVideoQuality`). `FlixelVideo` is the sprite your game uses; each backend supplies a `FlixelVideoPlayer` that decodes frames and owns the frame texture. Depends only on `flixelgdx-core`. |
| **`flixelgdx-video-desktop`**       | Desktop backend powered by [libvlc](https://www.videolan.org/vlc/libvlc.html), bridged through JNA. Frames are decoded into memory and copied into a texture.                                                                                                                                        |
| **`flixelgdx-video-html5`**         | Web backend built on a hidden HTML video element the browser decodes. The element is copied straight to a WebGPU texture with `copyExternalImageToTexture`, with no CPU readback.                                                                                                                    |
| **`flixelgdx-video-android`**       | Android backend built on the platform `MediaPlayer`. Frames are decoded as YUV planes, uploaded to WebGPU textures, and converted to RGBA on the GPU into a render target. Needs Android 8.0 (API 26) or newer and no native library of its own.                                                     |
| **`flixelgdx-video-vlc-natives-*`** | Packaging-only modules that bundle the stripped libvlc natives for Windows and Linux (x86-64 and ARM64) and macOS (universal). Pulled in automatically by the desktop backend.                                                                                                                       |

---

## Installation

The video artifacts are versioned in lockstep with the framework release they target. 
Add the backend for each platform module in your game, plus the core API in your shared
`core` module.

**Shared `core` module** (so your game code can reference `FlixelVideo` and `FlixelVideos`):

```gradle
dependencies {
  implementation "org.flixelgdx:flixelgdx-video-core:<flixelgdx-version>"
}
```

**Desktop launcher module:**

```gradle
dependencies {
  implementation "org.flixelgdx:flixelgdx-video-desktop:<flixelgdx-version>"
}
```

**Web launcher module:**

```gradle
dependencies {
  implementation "org.flixelgdx:flixelgdx-video-html5:<flixelgdx-version>"
}
```

**Android launcher module:**

```gradle
dependencies {
  implementation "org.flixelgdx:flixelgdx-video-android:<flixelgdx-version>"
}
```

Each backend depends on `flixelgdx-video-core`, so pulling in a backend also brings the API with it.

---

## How do I use it?

Configuring the video extension only takes a single line of code you add in your platform module.

**Desktop:**

```java
public static void main(String[] args) {
  FlixelDesktopVideoHandler.install();
  FlixelDesktopLauncher.launch(new MyGame());
}
```

**HTML5:**

```java
public static void main(String[] args) {
  FlixelHtml5VideoHandler.install();
  FlixelHtml5Launcher.launch(new MyGame());
}
```

**Android:**

```java
public class MyActivity extends Activity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    FlixelAndroidVideoHandler.install(this);
    FlixelAndroidLauncher.launch(this, new MyGame());
  }
}
```

> [!NOTE]
> On Android, videos in your `assets` folder must be stored uncompressed in the APK. The build tools
> already do this for `.mp4`, `.m4v`, `.3gp`, `.mkv`, and `.webm`. For any other extension, such as
> `.mov`, add it to the no-compress list in your Android module's `build.gradle.kts`:
>
> ```kotlin
> android {
>   androidResources {
>     noCompress += "mov"
>   }
> }
> ```

After that, you can immediately start using cross-platform videos in your game. To create a video, you simply call
`FlixelVideos.create(...)`, pass a `FlixelFile` into it, configure it and add it to your state.

```java
public class PlayState extends FlixelState {

  private FlixelVideo cutscene;
    
  @Override
  public void create() {
    cutscene = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
    cutscene.setSize(1280, 720);
    cutscene.screenCenter();
    cutscene.setLooped(false);
    cutscene.onComplete.add(data -> Flixel.switchState(() -> new MenuState()));
    add(cutscene);
    cutscene.play();
  }

  @Override
  public void destroy() {
    // Destroy the video when you're done using it to release the texture and decoder.
    cutscene.destroy();
  }
}
```

### Debugging videos

Videos work with the FlixelGDX debugger app just like sprites. While your game runs in debug mode, a video shows
up in the scene tree, draws its box in the viewport, and can be clicked, dragged, and focused with "Go to". Selecting
one opens a **Video** section in the inspector with its position, size, angle, and playback state. You can edit most
of it live, for example to seek, pause, change the rate or volume, or switch the quality preset by typing a name such
as `HALF`. Nothing has to be set up, and release builds are not affected.

> [!NOTE]
> `setPosition(...)`, `setSize(...)`, and `setScrollFactor(...)` no longer return the video, because `FlixelVideo`
> now implements the framework's `FlixelPositional` interface. Call them as separate statements instead of
> chaining them.

---

## Contributing and building

- **[COMPILING.md](COMPILING.md)** - how to build and test the extension locally, including the
  building against a local framework clone with `--include-build` / `includeBuild(...)`.
- **[CONTRIBUTING.md](CONTRIBUTING.md)** - contribution guidelines (shared with the main framework).
- **[ARCHITECTURE.md](ARCHITECTURE.md)** - the module layout and build system overview.

---

## License

FlixelGDX Video is released under the [MIT License](LICENSE).
