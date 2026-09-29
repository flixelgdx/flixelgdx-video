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
import org.flixelgdx.graphics.FlixelImage;
import org.flixelgdx.graphics.FlixelTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A helper for backends that decode video into CPU RGBA pixels.
 *
 * <p>It owns one reusable {@link FlixelImage} and one {@link FlixelTexture}. A backend asks for the
 * image with {@link #prepare(int, int)}, writes the decoded pixels into it, and then calls
 * {@link #upload()} on the render thread to copy them into the texture. This is the "reuse one
 * texture, rewrite its pixels" path, so nothing is allocated while the frame size stays the same.
 *
 * <p>Backends that render on the GPU (for example, Android) do not need this class.
 *
 * <p>All methods must be called on the render thread.
 */
public final class FlixelVideoCpuFrame {

  @Nullable
  private FlixelImage image;

  @Nullable
  private FlixelTexture texture;

  private boolean ready;

  /**
   * Returns the reusable image sized to the given dimensions.
   *
   * <p>The image and texture are recreated only when the size differs from the current one, so
   * calling this every frame with a steady size does not allocate. Recreating drops the ready flag
   * until the next {@link #upload()}.
   *
   * @param width Frame width in pixels; must be greater than {@code 0}.
   * @param height Frame height in pixels; must be greater than {@code 0}.
   * @return The image to write RGBA pixels into; owned by this helper.
   * @throws IllegalArgumentException If {@code width} or {@code height} is not positive.
   */
  @NotNull
  public FlixelImage prepare(int width, int height) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Frame size must be positive.");
    }
    FlixelImage current = image;
    if (current == null || current.getWidth() != width || current.getHeight() != height) {
      if (texture != null) {
        texture.destroy();
        texture = null;
      }
      ready = false;
      current = new FlixelImage(width, height);
      image = current;
      FlixelTexture tex = Flixel.graphics.createTexture(width, height);
      tex.setSmooth(true);
      texture = tex;
    }
    return current;
  }

  /**
   * Uploads the prepared image into the texture and marks this frame as ready.
   *
   * <p>Does nothing if {@link #prepare(int, int)} has not been called yet.
   */
  public void upload() {
    FlixelImage img = image;
    FlixelTexture tex = texture;
    if (img == null || tex == null) {
      return;
    }
    tex.update(0, 0, img);
    ready = true;
  }

  /** Destroys the texture and drops the image, returning this helper to its initial state. */
  public void destroy() {
    if (texture != null) {
      texture.destroy();
      texture = null;
    }
    image = null;
    ready = false;
  }

  /**
   * Returns the texture holding the latest uploaded frame.
   *
   * @return The texture, or {@code null} before the first {@link #upload()}.
   */
  @Nullable
  public FlixelTexture getTexture() {
    return ready ? texture : null;
  }

  /**
   * Returns whether at least one frame has been uploaded.
   *
   * @return {@code true} once a frame is available.
   */
  public boolean isReady() {
    return ready;
  }
}
