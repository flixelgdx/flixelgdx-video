# Compiling & Testing

FlixelGDX Video is a library extension, not a standalone game, so it cannot be run by itself. It
also is not fully self-contained: every video module depends on the FlixelGDX framework
(`org.flixelgdx:flixelgdx-core`, `flixelgdx-lwjgl3`, and so on). Testing your changes therefore has
two parts: getting the extension to build against a framework, and then consuming your local
extension from a separate test game.

This guide focuses on what is specific to this repository. For the full environment setup (installing
JDK 17 with Eclipse Temurin, Git, the Android SDK, IDE configuration, and platform troubleshooting),
follow the framework's guide, which applies here unchanged:
**[flixelgdx/flixelgdx -> COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/develop/COMPILING.md)**.

---

## Table of contents

1. [Prerequisites](#prerequisites)
2. [Getting the source](#getting-the-source)
3. [How this extension depends on the framework](#how-this-extension-depends-on-the-framework)
4. [Building the extension](#building-the-extension)
5. [Per-platform build checks](#per-platform-build-checks)
6. [Testing the extension in a game (composite build)](#testing-the-extension-in-a-game-composite-build)
7. [Packaging the libvlc natives locally](#packaging-the-libvlc-natives-locally)
8. [The Android module](#the-android-module)
9. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Java (JDK 17, Eclipse Temurin).** The build uses the Gradle wrapper (Gradle 9.x) and a Java 17
  toolchain. Install Temurin 17 as described in the framework's
  [COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/develop/COMPILING.md).
- **Git**, to clone this repository and the framework.
- **(Desktop packaging only)** `p7zip` on Linux, needed to extract the macOS VLC DMG when packaging
  natives. See [Packaging the libvlc natives locally](#packaging-the-libvlc-natives-locally).
- **(Android only)** The Android SDK. See [The Android module](#the-android-module).

Verify Java after installing:

```bash
java -version   # should report 17 and mention OpenJDK / Temurin
```

---

## Getting the source

```bash
git clone https://github.com/flixelgdx/flixelgdx-video.git
cd flixelgdx-video
```

If you are contributing, fork the repository first, clone your fork, and add the upstream remote,
exactly as described in the framework's
[CONTRIBUTING.md](https://github.com/flixelgdx/flixelgdx/blob/develop/CONTRIBUTING.md). Use the
`develop` branch for development and pull requests.

---

## How this extension depends on the framework

The video modules declare the framework as ordinary external dependencies, pinned to the
`flixelgdx` version in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) (currently `0.5.1`):

```
flixelgdx-video-core   -> org.flixelgdx:flixelgdx-core
flixelgdx-video-lwjgl3 -> org.flixelgdx:flixelgdx-lwjgl3 (+ flixelgdx-video-core)
flixelgdx-video-teavm  -> org.flixelgdx:flixelgdx-teavm  (+ flixelgdx-video-core)
flixelgdx-video-android-> org.flixelgdx:flixelgdx-android (+ flixelgdx-video-core)
```

For the build to resolve those, the matching framework version must be available from one of the
repositories configured in [`settings.gradle.kts`](settings.gradle.kts):

- **Maven Central** - the normal case once a framework release is published.
- **Your local Maven repository** (`mavenLocal()`) - after you run `publishToMavenLocal` in a
  framework clone. This is the recommended path when you are changing the framework and the
  extension together.
- **JitPack** - a framework build from a GitHub branch or commit.

> [!TIP]
> If you are only changing the video extension (not the framework) and a matching framework release
> is already on Maven Central, you do not have to do anything special. `./gradlew assemble` just
> works.

### Developing against a local framework clone

When your extension change needs a framework change that is not published yet, publish the framework
to your local Maven repository, then build the extension against it:

```bash
# In your framework clone:
./gradlew publishToMavenLocal

# Then, back in flixelgdx-video:
./gradlew assemble
```

Make sure the `flixelgdx` version in `gradle/libs.versions.toml` matches the `projectVersion` of the
framework you published. Re-run `publishToMavenLocal` whenever you change framework code you want the
extension to pick up.

---

## Building the extension

Build every default module (everything except the optional Android module):

```bash
./gradlew assemble
```

Run the same checks CI runs on every push:

```bash
./gradlew spotlessCheck   # formatting
./gradlew javadocAll      # Javadoc with doclint on the published API modules
```

Apply formatting fixes automatically before committing:

```bash
./gradlew spotlessApply
```

---

## Per-platform build checks

CI compiles each platform backend on its own. You can reproduce any of them locally:

| Platform | Command |
|----------|---------|
| **Desktop (LWJGL3)** | `./gradlew :flixelgdx-video-core:assemble :flixelgdx-video-lwjgl3:assemble` |
| **Web (TeaVM)** | `./gradlew :flixelgdx-video-core:assemble :flixelgdx-video-teavm:assemble` |
| **Android** | `./gradlew -PincludeAndroid=true :flixelgdx-video-core:assemble :flixelgdx-video-android:assembleRelease` |

---

## Testing the extension in a game (composite build)

To actually watch a video play, you consume your local extension from a test game. The generated
FlixelGDX starter project (from the [Getting Started](https://flixelgdx.org/getting-started) page) is
the easiest starting point. There are two ways to point it at your local extension.

### Method 1: Composite build (recommended)

A **Gradle composite build** makes the test game compile directly against your extension source, so
every change is picked up on the next build with no republishing.

1. Open your test game project.
2. In the game's **`settings.gradle`** (or `settings.gradle.kts`), include the extension build below
   the `rootProject.name` line:
   ```gradle
   includeBuild '/path/to/flixelgdx-video'
   ```
   - **Windows**: use forward slashes, e.g. `C:/Users/You/flixelgdx-video`.
   - **macOS/Linux**: e.g. `/home/you/projects/flixelgdx-video`.
3. Declare the dependencies normally in the matching modules; the composite build substitutes them
   with your local projects automatically:
   ```gradle
   // core module
   implementation 'org.flixelgdx:flixelgdx-video-core:0.5.1'

   // lwjgl3 (desktop) module
   implementation 'org.flixelgdx:flixelgdx-video-lwjgl3:0.5.1'
   ```
4. Install the backend in your launcher and create a video, exactly as shown in the
   [README](README.md#usage).
5. Refresh Gradle and run the game (for example `./gradlew :lwjgl3:run`).

> [!NOTE]
> The version string in the dependency does not have to match while a composite build is active;
> Gradle substitutes by module coordinates (group and name), so your local extension is used
> regardless of the number you write.

> [!TIP]
> If you are also changing the framework at the same time, publish the framework to your local Maven
> repository (`publishToMavenLocal`) so the composited extension can resolve it. Keeping the framework
> on `mavenLocal()` avoids nested composite build edge cases (both this repo and the framework ship a
> build named `build-logic`).

### Method 2: `mavenLocal()`

If you prefer not to use a composite build:

1. Publish the extension to your local Maven repository:
   ```bash
   ./gradlew publishToMavenLocal
   ```
2. In the test game's root `build.gradle`, add `mavenLocal()` to `repositories` before
   `mavenCentral()`.

Re-run `publishToMavenLocal` each time you change the extension and want the game to pick it up. Use
Method 1 to avoid that.

---

## Packaging the libvlc natives locally

By default the desktop backend JAR is built **without** bundled natives, so a normal
`./gradlew assemble` is fast and never touches the network. The natives are only fetched when the
`packageVlcNatives` property is set (this is what the Maven Central publish workflow does):

```bash
# On Linux, install p7zip first so the macOS DMG can be extracted:
sudo apt-get install -y p7zip-full

./gradlew :flixelgdx-video-vlc-natives-linux-amd64:build \
          :flixelgdx-video-vlc-natives-windows-amd64:build \
          :flixelgdx-video-vlc-natives-macos-universal:build \
          -PpackageVlcNatives=true
```

`DownloadVlcNativesTask` (in [`build-logic`](build-logic/src/main/kotlin/DownloadVlcNativesTask.kt))
downloads the official VLC bundles, verifies their checksums, strips the plugin categories a game
does not need, and packages the result under `org/flixelgdx/video/natives`. The download is cached
between builds and the extraction is skipped if the expected files already exist.

You usually do not need bundled natives to test locally: if none are on the classpath,
`FlixelVlcDiscovery` falls back to a game-shipped `vlc/` folder or a system VLC installation, so
installing VLC on your machine is enough to see desktop video play.

---

## The Android module

The Android backend is optional so the extension can be built without an Android SDK. It is excluded
from the build by default. Enable it the same way the framework does:

- **CI or one-off builds**: pass the property on the command line:
  ```bash
  ./gradlew -PincludeAndroid=true :flixelgdx-video-android:assembleRelease
  ```
- **Local development**: add `includeAndroid=true` to `local.properties` (gitignored). See
  [`example.local.properties`](example.local.properties).

Building the Android module requires the Android SDK and an `sdk.dir` entry in `local.properties`.
Full setup instructions are in the framework's
[COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/develop/COMPILING.md#setting-up-the-android-sdk-for-contributing-to-the-android-platform).

> [!NOTE]
> Because `flixelgdx-video-android` depends on `org.flixelgdx:flixelgdx-android`, that framework
> module must be resolvable too (from Maven Central or a local `publishToMavenLocal` build with
> `-PincludeAndroid=true`).

---

## Troubleshooting

### `Could not find org.flixelgdx:flixelgdx-core` (or `-jvm`, `-lwjgl3`, `-teavm`, `-android`)

- **Cause**: the framework version this extension targets is not available in any configured
  repository.
- **Fix**: either publish that framework version to your local Maven repository
  (`./gradlew publishToMavenLocal` in a framework clone whose `projectVersion` matches the
  `flixelgdx` version in `gradle/libs.versions.toml`), or use a composite/JitPack path. Remember that
  transitive framework modules (such as `flixelgdx-jvm`) must be published too, so publishing the
  whole framework is the safest option.

### Desktop video never becomes ready

- **Cause**: no libvlc could be loaded (no bundled natives, no shipped `vlc/` folder, and no system
  VLC).
- **Fix**: install VLC on your machine, ship a `vlc/` folder next to your game, or build the desktop
  natives locally with `-PpackageVlcNatives=true`. The reason is always logged under the
  `FlixelVideo` tag.

### `p7zip` / DMG extraction errors when packaging natives on Linux

- **Fix**: install `p7zip-full` (`sudo apt-get install -y p7zip-full`). It is required to read the
  macOS `.dmg` on non-macOS build machines.

### Spotless failures

- **Fix**: run `./gradlew spotlessApply` and commit the reformatted files.

For anything not covered here, the framework's
[COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/develop/COMPILING.md#troubleshooting)
troubleshooting section applies to this repository as well.
