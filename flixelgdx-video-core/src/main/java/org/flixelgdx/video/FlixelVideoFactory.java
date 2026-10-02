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

import org.flixelgdx.Flixel;
import org.flixelgdx.file.FlixelFile;
import org.jetbrains.annotations.NotNull;

/**
 * Platform-specific factory for creating video players.
 *
 * <p>One instance is registered through
 * {@link FlixelVideos#setBackendFactory(FlixelVideoFactory)} by the platform launcher (for example
 * {@code FlixelDesktopVideoHandler.install()} on desktop) before any {@link FlixelVideo} is created.
 *
 * <p>The factory is handed a {@link FlixelFile} rather than a path string, so the same seam the
 * rest of the framework loads assets through decides where the video lives. Each backend reads
 * whatever it needs from the handle: the desktop backend takes its filesystem location, and the web
 * backend turns it into a URL.
 */
public interface FlixelVideoFactory {

  /**
   * Creates a new platform player for the given file.
   *
   * <p>{@link FlixelVideos#create(FlixelFile)} wraps the returned player in a {@link FlixelVideo}.
   * If the native decoder cannot be set up, return a {@link FlixelUnavailableVideoPlayer} instead
   * of throwing so the game keeps running.
   *
   * @param file The video file to open, obtained from {@link Flixel#files}.
   * @return A new player instance; never {@code null}.
   */
  @NotNull
  FlixelVideoPlayer createPlayer(@NotNull FlixelFile file);
}
