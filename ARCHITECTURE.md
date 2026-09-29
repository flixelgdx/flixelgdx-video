# Project Structure

FlixelGDX Video is the optional video playback extension for
[FlixelGDX](https://github.com/flixelgdx/flixelgdx). It is organized into multiple Gradle modules
that separate the platform-neutral video API from the platform-specific backends, so a game only
ships the decoder (and natives) it actually needs.

Every video module depends on the FlixelGDX framework; nothing in the framework depends on this
extension. That one-directional relationship is why the extension can live in its own repository.

## Modules

- **`flixelgdx-video-core`**: The heart of the extension. It holds the platform-neutral API and
  splits it in two on purpose:
  - `FlixelVideo` is the final, game-facing sprite. Your game adds it to a state like any other
    object. It wraps a player and adds the game-side behavior: signals (such as `onComplete`),
    automatic pause and resume on window focus, sizing, `screenCenter()`, and drawing.
  - `FlixelVideoPlayer` is the interface each backend implements. A player decodes the video and
    owns the texture that holds the current frame, and `FlixelVideo` draws whatever texture the
    player returns.

  `FlixelVideos` is the entry point (`FlixelVideos.create(...)`), and `FlixelVideoFactory` is the
  service contract a backend registers (`createPlayer(...)`). `FlixelUnavailableVideoPlayer` is the
  fallback returned when a video cannot be opened, so a missing file never crashes the game.
  `FlixelVideoQuality` holds the quality presets. `FlixelVideoCpuFrame` is an optional helper for
  backends that decode into CPU pixels; it keeps one reusable image and texture and rewrites the
  pixels each frame. Backends that never touch CPU pixels do not use it. Core depends only on
  `flixelgdx-core`; every other module depends on core.
- **`flixelgdx-video-desktop`**: The desktop backend powered by
  [libvlc](https://www.videolan.org/vlc/libvlc.html). `FlixelVlcPlayer` bridges libvlc into the
  framework through JNA, and `FlixelDesktopVideoHandler` registers the factory. The native libraries
  come from the `flixelgdx-video-vlc-natives-*` modules.
- **`flixelgdx-video-html5`**: The web backend. `FlixelHtml5Player` drives a hidden HTML video
  element that the browser decodes.
- **`flixelgdx-video-android`**: The Android backend. `FlixelMediaPlayerPlayer` decodes with the
  platform `MediaPlayer`, and `FlixelAndroidVideoHandler.install(Context)` registers the factory.
  It needs no native library. This module is only part of the build when the `includeAndroid` flag
  is set (see [COMPILING.md](COMPILING.md)).
- **`flixelgdx-video-vlc-natives-windows-amd64`**, **`-windows-aarch64`**, **`-linux-amd64`**,
  **`-linux-aarch64`**, **`-macos-universal`**: Packaging-only modules. They carry no Java source;
  when the `packageVlcNatives` property is set, each one downloads, strips, and bundles the libvlc
  natives for its platform and architecture into a JAR. The desktop backend pulls them in as runtime
  dependencies, and `FlixelVlcDiscovery` loads the folder matching the current OS and CPU
  architecture.

## Frame Paths

Every backend gets a decoded frame into a `FlixelTexture` that `FlixelVideo` can draw, but each
platform does it differently, so the work stays as close to the GPU as the platform allows. In every
case the per-frame path allocates nothing.

- **Desktop (CPU copy):** libvlc decodes on its own threads and writes RGBA pixels into a buffer
  through its video callbacks. On the render thread, `FlixelVlcPlayer.update(...)` uploads those
  pixels with `FlixelVideoCpuFrame`, which keeps one image and one texture and rewrites the texture
  when a new frame is ready. The libvlc threads are kept attached to the JVM (a JNA callback thread
  initializer with `detach=false`), because attaching and detaching a thread on every callback would
  allocate on every frame.
- **HTML5 (DOM element upload):** The browser decodes the hidden video element. The player uploads
  the element directly to its own WebGL texture, so no pixels are read back into Java. At lower
  quality presets, the frame is first drawn onto a reused offscreen canvas at the smaller size, and
  the canvas is uploaded instead.
- **Android (SurfaceTexture, OES blit, render target):** `MediaPlayer` renders into a
  `SurfaceTexture`, which exposes each frame as an external OpenGL (OES) texture. The framework batch
  can only draw normal 2D textures, so on the render thread the player draws the OES texture once per
  new frame into a framework render target. That render target's texture is the frame the batch
  draws. The quality preset scales the render target.

## Build System

FlixelGDX Video uses **Gradle** with the modern Kotlin DSL, mirroring the framework.

### Key Files

- **`build.gradle.kts`**: The root aggregator. It applies IDE plugins and registers the aggregate
  `javadocAll` task; it holds no source of its own.
- **`settings.gradle.kts`**: Defines the modules included in the build and the repositories used to
  resolve the FlixelGDX framework. It also reads the `includeAndroid` flag (from `-PincludeAndroid=true`
  or from `local.properties`) and only includes `flixelgdx-video-android` when it is on.
- **`gradle.properties`**: Contains the group ID and the POM metadata used when publishing. The
  project version is not stored here; it comes from git tags.
- **`gradle/libs.versions.toml`**: The version catalog. The `flixelgdx` version pins which framework
  release the video artifacts are built against.
- **`example.local.properties`**: A template for `local.properties`, which holds the Android SDK path
  and the `includeAndroid` flag.
- **`build-logic/`**: The included build that holds the shared convention plugins (`flixelgdx.*`),
  including `flixelgdx.android-library` for the Android module, and `DownloadVlcNativesTask`, which
  fetches and strips the libvlc natives.

### Dependency Management

The video backends depend on the framework as external artifacts
(`org.flixelgdx:flixelgdx-core:<version>`, `flixelgdx-desktop`, `flixelgdx-html5`,
`flixelgdx-android`), resolved from Maven Central, a local `publishToMavenLocal` build, or JitPack.
To build against framework source instead, attach it manually with
`--include-build ../flixelgdx`; nothing does this automatically. The Android backend uses the
framework's GLES Android backend, which may be newer than the version pinned in the catalog, so
building it during development usually needs that flag. See [COMPILING.md](COMPILING.md).

## GitHub Integration

Like the framework, this repository uses GitHub Actions for continuous integration
(`.github/workflows/ci_build.yml`) and for publishing to Maven Central when a `v*` tag is pushed
(`.github/workflows/publish.yml`, which also packages the libvlc natives). The CI workflow runs a
Spotless formatting check, a Javadoc check, and a build matrix that compiles the desktop backend on
Linux, Windows, and macOS, the Android backend on Linux (with an Android SDK set up and
`-PincludeAndroid=true`), and the web backend.
