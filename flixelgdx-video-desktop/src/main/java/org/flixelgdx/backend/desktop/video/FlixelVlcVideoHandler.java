/*
 * MIT License
 *
 * Copyright (c) 2026 stringdotjar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.flixelgdx.backend.desktop.video;

import com.sun.jna.Pointer;
import com.sun.jna.StringArray;

import org.flixelgdx.Flixel;
import org.flixelgdx.file.FlixelFile;
import org.flixelgdx.video.FlixelUnavailableVideo;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoFactory;
import org.flixelgdx.video.FlixelVideos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Desktop video backend factory powered by libvlc.
 *
 * <p>Install it once in your desktop launcher, before the game starts:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   FlixelVlcVideoHandler.install();
 *   FlixelDesktopLauncher.launch(new MyGame());
 * }
 * }</pre>
 *
 * <p>Installation is cheap: no native library is touched until the first
 * {@link FlixelVideo} is created, at which point {@link FlixelVlcDiscovery} locates
 * libvlc (bundled natives, a game-shipped {@code vlc/} folder, or a system installation)
 * and a single shared libvlc instance is created for the whole game.
 *
 * <p>When no working VLC can be found at all, videos are still created; they just
 * stay in a never-ready state (see {@link FlixelUnavailableVideo}) and the
 * reason is logged, so a missing decoder degrades the game instead of crashing it.
 *
 * <p>Automatic pause and resume on focus changes is handled by {@link FlixelVideo} itself through
 * the framework's window focus signals, so this factory only has to register itself.
 */
public final class FlixelVlcVideoHandler implements FlixelVideoFactory {

  private static Pointer instance;

  /** Set after discovery fails once, so every later video degrades without re-probing. */
  private static boolean unavailable;

  /**
   * Registers this handler as the video backend factory for {@link FlixelVideos}. Safe to call
   * multiple times.
   */
  public static void install() {
    FlixelVideos.setBackendFactory(new FlixelVlcVideoHandler());
  }

  /**
   * Returns the libvlc runtime version string, loading libvlc if needed.
   *
   * <p>Useful for diagnostics screens; most games never need this.
   *
   * @return The libvlc version, e.g. {@code "3.0.23 Vetinari"}.
   */
  @NotNull
  public static String getLibVlcVersion() {
    ensureInstance();
    return LibVlc.libvlc_get_version();
  }

  @NotNull
  @Override
  public FlixelVideo createVideo(@NotNull FlixelFile file) {
    // A broken or missing VLC installation must not crash the game: the video
    // degrades to a backend that is never ready, and the reason is logged loudly so
    // the problem is diagnosable.
    if (unavailable) {
      return new FlixelUnavailableVideo();
    }
    String path = resolvePath(file);
    if (path == null) {
      Flixel.error("FlixelVideo", "Video file could not be found: " + file.getPath());
      return new FlixelUnavailableVideo();
    }
    try {
      ensureInstance();
    } catch (IllegalStateException | LinkageError error) {
      unavailable = true;
      Flixel.error("FlixelVideo", "Video playback is disabled for this session: " + error.getMessage());
      return new FlixelUnavailableVideo();
    }
    try {
      return new FlixelVlcVideo(instance, path);
    } catch (IllegalStateException error) {
      Flixel.error("FlixelVideo", error.getMessage());
      return new FlixelUnavailableVideo();
    }
  }

  /**
   * Turns a file handle into an absolute filesystem path libvlc can open.
   *
   * <p>Most handles (internal assets during development, external, and absolute files) are already
   * backed by a real file on disk, so their path is used directly. A handle that exists only inside
   * the classpath (for example an asset packed into a released JAR) has no filesystem path, so its
   * bytes are extracted once to a temporary file that libvlc can read.
   *
   * @param file The video file handle.
   * @return An absolute filesystem path, or {@code null} when the file cannot be found.
   */
  @Nullable
  private static String resolvePath(@NotNull FlixelFile file) {
    Object handle = file.getNativeHandle();
    if (handle instanceof File onDisk && onDisk.isFile()) {
      return onDisk.getAbsolutePath();
    }
    if (file.exists()) {
      return extractToTemp(file);
    }
    return null;
  }

  /**
   * Extracts a classpath-only video to a temporary file so libvlc has a path to open.
   *
   * @param file The video file handle to read.
   * @return The temporary file's absolute path, or {@code null} when the bytes could not be read.
   */
  @Nullable
  private static String extractToTemp(@NotNull FlixelFile file) {
    try {
      byte[] bytes = file.readBytes();
      if (bytes.length == 0) {
        return null;
      }
      String name = file.getName();
      int dot = name.lastIndexOf('.');
      String suffix = dot >= 0 ? name.substring(dot) : ".video";
      File temp = File.createTempFile("flixel-video-", suffix);
      temp.deleteOnExit();
      Files.write(temp.toPath(), bytes);
      return temp.getAbsolutePath();
    } catch (IOException error) {
      Flixel.error("FlixelVideo", "Could not extract video '" + file.getPath() + "': " + error.getMessage());
      return null;
    }
  }

  private static synchronized void ensureInstance() {
    if (instance != null) {
      return;
    }
    LibVlc.register(FlixelVlcDiscovery.load());
    // Video output is negotiated per player through the vmem callbacks, so libvlc never opens a window.
    String[] args = { "--intf=dummy", "--quiet", "--no-xlib" };
    Pointer created = LibVlc.libvlc_new(args.length, new StringArray(args));
    if (created == null) {
      throw new IllegalStateException(
          "libvlc_new failed. The located VLC installation may be incomplete (missing plugins).");
    }
    instance = created;
    // A one-time confirmation of which libvlc actually satisfied the game, so a
    // "plugins cannot be found" report can be traced to the exact install that loaded.
    Flixel.info("FlixelVideo", "libvlc " + LibVlc.libvlc_get_version()
        + " initialized from " + FlixelVlcDiscovery.getLoadedFrom() + ".");
  }
}
