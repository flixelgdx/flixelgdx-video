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
package org.flixelgdx.video;

import org.flixelgdx.file.FlixelFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Static helper that creates {@link FlixelVideo} instances from the platform backend registered by
 * your launcher.
 *
 * <p>Each platform module ships an installer that wires itself in here once, before the game
 * starts:
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   FlixelVlcVideoHandler.install();
 *   FlixelDesktopLauncher.launch(new MyGame());
 * }
 * }</pre>
 *
 * <p>After that, creating a video anywhere in the game needs no further setup. Videos are located
 * through {@link org.flixelgdx.Flixel#files}, the same file seam the rest of the framework loads
 * assets through:
 *
 * <pre>{@code
 * FlixelVideo cutscene = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
 * add(cutscene);
 * cutscene.play();
 * }</pre>
 *
 * @see FlixelVideo
 * @see FlixelVideoFactory
 */
public final class FlixelVideos {

  @Nullable
  private static FlixelVideoFactory backendFactory;

  private FlixelVideos() {}

  /**
   * Creates a new video for the given file using the current platform backend.
   *
   * <p>Obtain the {@link FlixelFile} from {@link org.flixelgdx.Flixel#files}: for example
   * {@code Flixel.files.internal("videos/intro.mp4")} for a bundled asset, or
   * {@code Flixel.files.absolute(path)} for a file elsewhere on disk.
   *
   * @param file The video file to play (must not be {@code null}).
   * @return A new video instance.
   * @throws IllegalStateException If no platform backend factory has been registered.
   * @throws IllegalArgumentException If {@code file} is {@code null}.
   */
  @NotNull
  public static FlixelVideo create(@NotNull FlixelFile file) {
    if (file == null) {
      throw new IllegalArgumentException("Video file cannot be null.");
    }
    FlixelVideoFactory factory = backendFactory;
    if (factory == null) {
      throw new IllegalStateException(
          "No video backend factory registered. Call the platform installer first, e.g. "
              + "FlixelVlcVideoHandler.install() in your desktop launcher or "
              + "FlixelHtml5VideoHandler.install() in your web launcher.");
    }
    return factory.createVideo(file);
  }

  /**
   * Registers the platform video backend factory.
   *
   * <p>Called once by the platform installer (for example {@code FlixelVlcVideoHandler.install()}
   * on desktop or {@code FlixelHtml5VideoHandler.install()} on the web) before any video is created.
   *
   * @param factory The backend factory to use (must not be {@code null}).
   * @throws IllegalArgumentException If {@code factory} is {@code null}.
   */
  public static void setBackendFactory(@NotNull FlixelVideoFactory factory) {
    if (factory == null) {
      throw new IllegalArgumentException("Video backend factory cannot be null.");
    }
    backendFactory = factory;
  }

  /**
   * Returns the registered platform video backend factory.
   *
   * @return The factory, or {@code null} if no platform installer has run yet.
   */
  @Nullable
  public static FlixelVideoFactory getBackendFactory() {
    return backendFactory;
  }
}
