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
  `FlixelUnavailableVideo` fallback). It also owns the shared frame path: a backend decodes each
  frame into a `FlixelImage` and hands it to `FlixelVideo.updateFrame(...)`, which keeps one
  `FlixelTexture` alive and scrubs its pixels. Because that upload rides on the portable graphics
  interface, every backend reuses it. Depends only on `flixelgdx-core`; every other module depends
  on this one.
- **`flixelgdx-video-desktop`**: The desktop backend powered by
  [libvlc](https://www.videolan.org/vlc/libvlc.html). It bridges libvlc into the framework through
  JNA-registered JNI bindings, decodes frames into memory through libvlc's video callbacks, and
  loads the native libraries provided by the `flixelgdx-video-vlc-natives-*` modules.
- **`flixelgdx-video-html5`**: The web backend built on a hidden HTML video element, which the
  browser decodes; each frame is drawn onto an offscreen canvas, read back as RGBA, and handed to
  core.
- **`flixelgdx-video-vlc-natives-windows-amd64`**, **`-windows-aarch64`**, **`-linux-amd64`**,
  **`-linux-aarch64`**, **`-macos-universal`**: Packaging-only modules. They carry no Java source;
  when the `packageVlcNatives` property is set, each one downloads, strips, and bundles the libvlc
  natives for its platform and architecture into a JAR. The desktop backend pulls them in as runtime
  dependencies, and `FlixelVlcDiscovery` loads the folder matching the current OS and CPU
  architecture.

## Build System

FlixelGDX Video uses **Gradle** with the modern Kotlin DSL, mirroring the framework.

### Key Files

- **`build.gradle.kts`**: The root aggregator. It applies IDE plugins and registers the aggregate
  `javadocAll` task; it holds no source of its own.
- **`settings.gradle.kts`**: Defines the modules included in the build, the repositories used to
  resolve the FlixelGDX framework, and the composite build against a sibling `../flixelgdx` checkout
  when one is present.
- **`gradle.properties`**: Contains the project version, group ID, and the POM metadata used when
  publishing.
- **`gradle/libs.versions.toml`**: The version catalog. The `flixelgdx` version pins which framework
  release the video artifacts are built against.
- **`build-logic/`**: The included build that holds the shared convention plugins (`flixelgdx.*`)
  and `DownloadVlcNativesTask`, which fetches and strips the libvlc natives.

### Dependency Management

The video backends depend on the framework as external artifacts
(`org.flixelgdx:flixelgdx-core:<version>`, `flixelgdx-desktop`, `flixelgdx-html5`), resolved from
Maven Central, a local `publishToMavenLocal` build, or JitPack. During development the build also
includes a sibling `../flixelgdx` checkout as a Gradle composite build when it is present, so
framework changes are picked up from source without republishing. See [COMPILING.md](COMPILING.md).

## GitHub Integration

Like the framework, this repository uses GitHub Actions for continuous integration
(`.github/workflows/ci_build.yml` verifies that every platform compiles and that Spotless and
Javadoc pass) and for publishing to Maven Central when a `v*` tag is pushed
(`.github/workflows/publish.yml`, which also packages the libvlc natives).
