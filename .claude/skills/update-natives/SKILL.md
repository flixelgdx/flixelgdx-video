---
name: update-natives
description: Update the bundled libvlc natives to a new VLC release across all five native modules (Windows amd64 and aarch64, Linux amd64 and aarch64, macOS universal), including checksums and plugin stripping. Use when asked to update, bump, or rebuild the VLC natives.
argument-hint: "[VLC version] (defaults to the latest stable release)"
---

# Update the libvlc natives

Move the bundled natives to VLC `$ARGUMENTS` (or the latest stable release when empty).

## Background

The desktop backend can bundle libvlc for each platform. Five modules package them:

- `flixelgdx-video-vlc-natives-windows-amd64`
- `flixelgdx-video-vlc-natives-windows-aarch64`
- `flixelgdx-video-vlc-natives-linux-amd64`
- `flixelgdx-video-vlc-natives-linux-aarch64`
- `flixelgdx-video-vlc-natives-macos-universal`

`DownloadVlcNativesTask` in `build-logic/src/main/kotlin/` downloads the official bundles, checks their checksums, strips plugin categories a game does not need, and packages the result under `org/flixelgdx/video/natives/<os>-<arch>`. The full steps live in the "Packaging the libvlc natives locally" section of `COMPILING.md`. Read it first.

## Steps

1. **Find the version and its downloads.** Confirm the release exists on the official VideoLAN download site for every platform above. If one platform has no build for that version, stop and report it instead of mixing versions.
2. **Find where the version and checksums are pinned.** Search `build-logic/`, the five module build files, `gradle.properties`, and `gradle/libs.versions.toml`. Update the version and each checksum. Take every checksum from the official VideoLAN files, never compute one from a download you could not verify.
3. **Check the plugin list.** Compare the new release's plugin folders with the strip list in `DownloadVlcNativesTask`. Report any new category instead of silently keeping or dropping it.
4. **Build the natives.** Run the command from `COMPILING.md` with `-PpackageVlcNatives=true`. On Linux the macOS DMG needs `p7zip`. This downloads several large files, so run it once and keep the output.
5. **Check the result.** Confirm each module's JAR contains `org/flixelgdx/video/natives/<os>-<arch>` with libvlc, libvlccore, and the expected plugins, and compare JAR sizes with the previous version.
6. **Update docs** that name the bundled VLC version.

## Report

List the old and new versions, the files changed, the size difference per platform, plugin changes, and anything that could not be verified on this machine (for example running the Windows or macOS natives). Do not commit unless asked.
