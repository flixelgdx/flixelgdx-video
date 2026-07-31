# Project Structure

FlixelGDX Video is the optional video playback extension for
[FlixelGDX](https://github.com/flixelgdx/flixelgdx). It is organized into multiple Gradle modules
that separate the platform-neutral video API from the platform-specific backends, so a game only
ships the decoder (and natives) it actually needs.

Every video module depends on the FlixelGDX framework; nothing in the framework depends on this
extension. That one-directional relationship is why the extension can live in its own repository.

## Modules

- **`flixelgdx-video-core`**: The heart of the extension. It holds the platform-neutral API
  (`FlixelVideo`, `FlixelVideos`, `FlixelVideoFactory`, `FlixelVideoQuality`, and the
  `FlixelUnavailableVideo` fallback). Depends only on `flixelgdx-core`. Every other module depends
  on this one.
- **`flixelgdx-video-lwjgl3`**: The desktop backend powered by
  [libvlc](https://www.videolan.org/vlc/libvlc.html). It bridges libvlc into the framework through
  JNA-registered JNI bindings and loads the native libraries provided by the
  `flixelgdx-video-vlc-natives-*` modules.
- **`flixelgdx-video-teavm`**: The web backend built on a hidden HTML video element, which the
  browser decodes; each frame is transferred GPU-to-GPU with `texImage2D`.
- **`flixelgdx-video-android`**: The Android backend that decodes with the platform `MediaPlayer`
  into a `SurfaceTexture` bound to a `GL_TEXTURE_EXTERNAL_OES` texture, then blits each frame into a
  normal framebuffer texture so videos draw through the regular batch and follow state draw order
  (added first draws under, added last draws over). Optional, and only included when the Android SDK
  is present (see [COMPILING.md](COMPILING.md)).
- **`flixelgdx-video-vlc-natives-windows-amd64`**, **`-linux-amd64`**, **`-macos-universal`**:
  Packaging-only modules. They carry no Java source; when the `packageVlcNatives` property is set,
  each one downloads, strips, and bundles the libvlc natives for its platform into a JAR. The
  desktop backend pulls them in as runtime dependencies.

## Build System

FlixelGDX Video uses **Gradle** with the modern Kotlin DSL, mirroring the framework.

### Key Files

- **`build.gradle.kts`**: The root aggregator. It applies IDE plugins and registers the aggregate
  `javadocAll` task; it holds no source of its own.
- **`settings.gradle.kts`**: Defines the modules included in the build, the repositories used to
  resolve the FlixelGDX framework, and the optional Android module.
- **`gradle.properties`**: Contains the project version, group ID, and the POM metadata used when
  publishing.
- **`gradle/libs.versions.toml`**: The version catalog. The `flixelgdx` version pins which framework
  release the video artifacts are built against.
- **`build-logic/`**: The included build that holds the shared convention plugins (`flixelgdx.*`)
  and `DownloadVlcNativesTask`, which fetches and strips the libvlc natives.

### Dependency Management

The video backends depend on the framework as external artifacts
(`org.flixelgdx:flixelgdx-core:<version>`, `flixelgdx-lwjgl3`, and so on), resolved from Maven
Central, a local `publishToMavenLocal` build, JitPack, or a Gradle composite build. See
[COMPILING.md](COMPILING.md) for how to point the build at a local framework clone during
development.

## GitHub Integration

Like the framework, this repository uses GitHub Actions for continuous integration
(`.github/workflows/ci_build.yml` verifies that every platform compiles and that Spotless and
Javadoc pass) and for publishing to Maven Central when a `v*` tag is pushed
(`.github/workflows/publish.yml`, which also packages the libvlc natives).
