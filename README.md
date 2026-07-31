<div align="center">

# FlixelGDX Video

[![CI](https://github.com/flixelgdx/flixelgdx-video/actions/workflows/ci_build.yml/badge.svg)](https://github.com/flixelgdx/flixelgdx-video/actions/workflows/ci_build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.flixelgdx/flixelgdx-video-core)](https://central.sonatype.com/artifact/org.flixelgdx/flixelgdx-video-core)
[![JitPack](https://jitpack.io/v/flixelgdx/flixelgdx-video.svg)](https://jitpack.io/#flixelgdx/flixelgdx-video)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Website](https://img.shields.io/badge/website-flixelgdx.org-blue)](https://flixelgdx.org)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)](https://adoptium.net/temurin/releases?version=17&os=any&arch=any)
[![Platforms](https://img.shields.io/badge/platforms-Desktop%20%7C%20Android%20%7C%20Web-brightgreen)](https://flixelgdx.org)

</div>

> [!TIP]
> This README is a quick-start. For the full guide (formats, quality options, streaming,
> platform quirks, and the complete API), read the official documentation at
> **[flixelgdx.org/docs/videos](https://flixelgdx.org/docs/videos)**.

**FlixelGDX Video** is the optional video playback extension for
[FlixelGDX](https://github.com/flixelgdx/flixelgdx). It lets you drop a video (a cutscene, an
intro, a background loop) straight into a `FlixelState` and treat it like any other game object:
it has the normal lifecycle, follows the state's draw order, and pauses and resumes with the game.

This is a separate repository so games only pull in the decoder they actually ship, and so the
framework itself stays lean. Nothing in the core framework depends on it; the video modules depend
on the framework, never the other way around.

---

## Modules

The extension is split into a platform-neutral API plus one backend per platform, so your build
only carries the code and natives for the platforms you target.

| Module | Purpose |
|--------|---------|
| **`flixelgdx-video-core`** | The platform-neutral API (`FlixelVideo`, `FlixelVideos`, `FlixelVideoFactory`, `FlixelVideoQuality`). Depends only on `flixelgdx-core`. |
| **`flixelgdx-video-lwjgl3`** | Desktop backend powered by [libvlc](https://www.videolan.org/vlc/libvlc.html), bridged through JNA. |
| **`flixelgdx-video-teavm`** | Web backend built on a hidden HTML video element the browser decodes, uploaded to the GPU each frame. |
| **`flixelgdx-video-android`** | Android backend that decodes with the platform `MediaPlayer` into a `SurfaceTexture`, then blits each frame into a normal texture so it draws through the regular batch. |
| **`flixelgdx-video-vlc-natives-*`** | Packaging-only modules that bundle the stripped libvlc natives for Windows, Linux, and macOS. Pulled in automatically by the desktop backend. |

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

**Desktop (`lwjgl3`) launcher module:**

```gradle
dependencies {
  implementation "org.flixelgdx:flixelgdx-video-lwjgl3:<flixelgdx-version>"
}
```

**Web (`teavm`) launcher module:**

```gradle
dependencies {
  implementation "org.flixelgdx:flixelgdx-video-teavm:<flixelgdx-version>"
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

## Usage

There are two steps: install the platform backend once in your launcher, then create and play
videos anywhere in your game.

### 1. Install the backend in your launcher

Each platform ships an installer you call once, before the game starts. This is the only
platform-specific code you write.

**Desktop:**

```java
public static void main(String[] args) {
  FlixelVlcVideoHandler.install();
  FlixelLwjgl3Launcher.launch(new MyGame());
}
```

**Web:**

```java
public static void main(String[] args) {
  FlixelTeaVMVideoHandler.install();
  FlixelTeaVMLauncher.launch(new MyGame());
}
```

**Android:**

```java
protected void onCreate(Bundle savedInstanceState) {
  super.onCreate(savedInstanceState);
  FlixelAndroidVideoHandler.install();
  FlixelAndroidLauncher.launch(new MyGame(), this);
}
```

### 2. Create and play a video

From then on, your game code is fully cross-platform. `FlixelVideos.create(...)` uses whichever
backend the launcher installed:

```java
FlixelVideo cutscene = FlixelVideos.create("videos/intro.mp4");
cutscene.setSize(Flixel.game.getWidth(), Flixel.game.getHeight());
cutscene.setLooped(false);
cutscene.onComplete.add(() -> Flixel.switchState(new MenuState()));
add(cutscene);
cutscene.play();
```

Because `FlixelVideo` extends `FlixelBasic`, it obeys state draw order just like a sprite: anything
added after it draws on top. All time values are in milliseconds. Call `destroy()` when the video
leaves the game for good to release the decoder and its frame texture.

---

## About the desktop natives

The desktop backend needs libvlc at runtime. Released artifacts bundle a stripped set of libvlc
natives for Windows, Linux, and macOS, so most games work out of the box with no VLC installation
required. If the bundled natives are missing (for example, a JitPack build) or fail to load,
`FlixelVlcDiscovery` falls back to a game-shipped `vlc/` folder or a system VLC install. If none of
those work, videos degrade gracefully to a never-ready state and the reason is logged, instead of
crashing the game.

---

## Contributing and building

- **[COMPILING.md](COMPILING.md)** - how to build and test the extension locally, including the
  recommended composite build against a local framework clone.
- **[CONTRIBUTING.md](CONTRIBUTING.md)** - contribution guidelines (shared with the main framework).
- **[PROJECT.md](PROJECT.md)** - the module layout and build system overview.

---

## License

FlixelGDX Video is released under the [MIT License](LICENSE).
