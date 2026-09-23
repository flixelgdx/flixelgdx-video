# Compiling & Testing

FlixelGDX Video is a library extension, not a standalone game, so it cannot be run by itself. It
also is not fully self-contained: every video module depends on the FlixelGDX framework
(`org.flixelgdx:flixelgdx-core`, `flixelgdx-desktop`, and so on). Testing your changes therefore has
two parts: getting the extension to build against the framework, and then consuming your local
extension from a separate test game.

This guide focuses on what is specific to this repository. For the full environment setup (installing
JDK 17 with Eclipse Temurin, Git, IDE configuration, and platform troubleshooting), follow the
framework's guide, which applies here unchanged:
**[flixelgdx/flixelgdx -> COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/master/COMPILING.md)**.

---

## Table of contents

1. [Prerequisites](#prerequisites)
2. [Getting the source](#getting-the-source)
3. [How this extension depends on the framework](#how-this-extension-depends-on-the-framework)
4. [Building the extension](#building-the-extension)
5. [Per-platform build checks](#per-platform-build-checks)
6. [Testing the extension in a game (composite build)](#testing-the-extension-in-a-game-composite-build)
7. [Packaging the libvlc natives locally](#packaging-the-libvlc-natives-locally)
8. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Java (JDK 17, Eclipse Temurin).** The build uses the Gradle wrapper and a Java 17 toolchain.
  Install Temurin 17 as described in the framework's
  [COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/master/COMPILING.md).
- **Git**, to clone this repository and the framework.
- **(Desktop packaging only)** `p7zip` on Linux, needed to extract the macOS VLC DMG when packaging
  natives. See [Packaging the libvlc natives locally](#packaging-the-libvlc-natives-locally).

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
[CONTRIBUTING.md](https://github.com/flixelgdx/flixelgdx/blob/master/CONTRIBUTING.md).

---

## How this extension depends on the framework

The video modules declare the framework as ordinary external dependencies, pinned to the
`flixelgdx` version in [`gradle/libs.versions.toml`](gradle/libs.versions.toml):

```
flixelgdx-video-core    -> org.flixelgdx:flixelgdx-core
flixelgdx-video-desktop -> org.flixelgdx:flixelgdx-desktop (+ flixelgdx-video-core)
flixelgdx-video-html5   -> org.flixelgdx:flixelgdx-html5   (+ flixelgdx-video-core)
```

Gradle resolves those coordinates from the repositories declared in
[`settings.gradle.kts`](settings.gradle.kts):

- **Maven Central** - the normal case once a framework release is published.
- **Sonatype OSS** (release and snapshot repositories) - for framework builds published ahead of a
  Maven Central sync.
- **JitPack** - a framework build from a GitHub branch or commit, referenced by its JitPack
  coordinates.

### Building against a local framework checkout

If you are changing the framework and the extension together, you do not need to edit any file to
build against a sibling clone. Gradle supports attaching an
[included build](https://docs.gradle.org/current/userguide/composite_builds.html) from the command
line:

```bash
./gradlew assemble --include-build ../flixelgdx
```

This substitutes the framework artifacts with your local `../flixelgdx` checkout by module
coordinates, so the `flixelgdx` version number in the catalog does not need to match while the flag
is present. Any framework change is picked up on the next build with no republishing. Add the flag to
whichever Gradle command you are running (`build`, `test`, an IDE's Gradle sync arguments, and so on).

---

## Building the extension

Build every module:

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
| **Desktop** | `./gradlew :flixelgdx-video-core:assemble :flixelgdx-video-desktop:assemble` |
| **Web** | `./gradlew :flixelgdx-video-core:assemble :flixelgdx-video-html5:assemble` |

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
   implementation 'org.flixelgdx:flixelgdx-video-core:<flixelgdx-version>'

   // desktop module
   implementation 'org.flixelgdx:flixelgdx-video-desktop:<flixelgdx-version>'
   ```
4. Install the backend in your launcher and create a video, exactly as shown in the
   [README](README.md#usage).
5. Refresh Gradle and run the game (for example `./gradlew :desktop:run`).

> [!NOTE]
> The version string in the dependency does not have to match while a composite build is active;
> Gradle substitutes by module coordinates (group and name), so your local extension is used
> regardless of the number you write.

> [!TIP]
> If you are also changing the framework, pass `--include-build ../flixelgdx` when you run the test
> game (see [above](#building-against-a-local-framework-checkout)) so both the extension and the
> framework build from source. If your Gradle setup trips over the nested `build-logic` builds, publish
> the framework to your local Maven repository instead (`publishToMavenLocal` in the framework clone)
> and drop the `--include-build` flag.

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

./gradlew :flixelgdx-video-vlc-natives-windows-amd64:build \
          :flixelgdx-video-vlc-natives-windows-aarch64:build \
          :flixelgdx-video-vlc-natives-linux-amd64:build \
          :flixelgdx-video-vlc-natives-linux-aarch64:build \
          :flixelgdx-video-vlc-natives-macos-universal:build \
          -PpackageVlcNatives=true
```

`DownloadVlcNativesTask` (in [`build-logic`](build-logic/src/main/kotlin/DownloadVlcNativesTask.kt))
downloads the official VLC bundles, verifies their checksums, strips the plugin categories a game
does not need, and packages the result under `org/flixelgdx/video/natives/<os>-<arch>`. The download
is cached between builds and the extraction is skipped if the expected files already exist.

You usually do not need bundled natives to test locally: if none are on the classpath,
`FlixelVlcDiscovery` falls back to a game-shipped `vlc/` folder or a system VLC installation, so
installing VLC on your machine is enough to see desktop video play.

---

## Troubleshooting

### `Could not find org.flixelgdx:flixelgdx-core` (or `-jvm`, `-desktop`, `-html5`)

- **Cause**: the framework version this extension targets (`flixelgdx` in
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml)) is not available in any of the
  repositories configured in [`settings.gradle.kts`](settings.gradle.kts), and you did not attach a
  local framework checkout.
- **Fix**: clone the framework next to this repository (`../flixelgdx`) and build with
  `--include-build ../flixelgdx` (see
  [above](#building-against-a-local-framework-checkout)), so the framework builds from source instead
  of being resolved from a repository. Remember that transitive framework modules (such as
  `flixelgdx-jvm`) must resolve too, so including the whole framework checkout is the safest option.

### Desktop video never becomes ready

- **Cause**: no libvlc could be loaded (no bundled natives, no shipped `vlc/` folder, and no system
  VLC).
- **Fix**: install VLC on your machine, ship a `vlc/` folder next to your game, or build the desktop
  natives locally with `-PpackageVlcNatives=true`. The reason is always logged under the
  `FlixelVideo` tag.

### `p7zip` / DMG extraction errors when packaging natives on Linux

- **Fix**: install `p7zip-full` (for Ubuntu, `sudo apt-get install -y p7zip-full`). It is required to
  read the macOS `.dmg` on non-macOS build machines.

### Spotless failures

- **Fix**: run `./gradlew spotlessApply` and commit the reformatted files.

For anything not covered here, the framework's
[COMPILING.md](https://github.com/flixelgdx/flixelgdx/blob/master/COMPILING.md#troubleshooting)
troubleshooting section applies to this repository as well.
