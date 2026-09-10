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
package org.flixelgdx.backend.html5.video;

import org.flixelgdx.backend.html5.graphics.FlixelWebGlTexture;
import org.flixelgdx.graphics.FlixelImage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGLRenderingContext;
import org.teavm.jso.webgl.WebGLTexture;

/**
 * A frame texture for HTML5 video that uploads pixels directly from a DOM element.
 *
 * <p>The standard {@link FlixelWebGlTexture#update} path copies pixels through Java: it reads
 * {@code getImageData} into a Java {@code byte[]}, boxes that into a {@code Uint8Array}, then
 * calls {@code texSubImage2D}. For video frames, that round trip allocates on every frame and
 * taxes the garbage collector.
 *
 * <p>This subclass short-circuits it. Before {@link FlixelHtml5Video} calls
 * {@link #update}, it calls {@link #setDirectSource} with the raw DOM element to use as the pixel
 * source: the {@code <video>} element for full-quality playback, or the offscreen {@code <canvas>}
 * element for quality-scaled playback. When a source is pending, {@link #update} calls
 * {@code texSubImage2D} with that element directly, which the browser handles without any CPU
 * round-trip or Java-side allocation.
 */
public final class FlixelVideoWebGlTexture extends FlixelWebGlTexture {

  private final WebGLRenderingContext gl;

  @Nullable
  private JSObject pendingSource;

  /**
   * Creates a blank, updateable frame texture.
   *
   * @param gl The WebGL rendering context.
   * @param width Texture width in pixels.
   * @param height Texture height in pixels.
   */
  public FlixelVideoWebGlTexture(@NotNull WebGLRenderingContext gl, int width, int height) {
    super(gl, width, height, false);
    this.gl = gl;
  }

  /**
   * Sets the DOM element whose pixels should be uploaded on the next {@link #update} call.
   *
   * <p>Pass the {@code <video>} element for full-quality frames, or the offscreen
   * {@code <canvas>} element for quality-scaled frames. The reference is consumed and cleared
   * after the first {@link #update} call that follows.
   *
   * @param source The DOM element to use as the pixel source; {@code null} clears any pending
   *     source and falls back to the normal Java pixel-copy path.
   */
  public void setDirectSource(@Nullable JSObject source) {
    pendingSource = source;
  }

  /**
   * Uploads pixels either from a pending DOM source or through the standard Java pixel-copy path.
   *
   * <p>If a source was registered with {@link #setDirectSource}, it is consumed here and
   * passed directly to {@code texSubImage2D}; the {@code image} parameter is only used for
   * its dimensions in that case. If no source is pending, the call delegates to the parent
   * implementation, which reads RGBA bytes out of {@code image}.
   */
  @Override
  public void update(int x, int y, @NotNull FlixelImage image) {
    JSObject source = pendingSource;
    if (source != null) {
      pendingSource = null;
      jsTexSubImage2DFromSource(gl, getGlTexture(), source);
    } else {
      super.update(x, y, image);
    }
  }

  /**
   * Binds the texture and uploads the given DOM element (video or canvas) as its pixels.
   *
   * <p>The browser reads the element's current displayed frame at its natural dimensions.
   * No Java-side copy or typed-array allocation occurs: the pixel data travels from the DOM
   * element straight to the GPU.
   */
  @JSBody(params = { "gl", "tex", "src" }, script = "gl.bindTexture(gl.TEXTURE_2D, tex);"
      + "gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, src);")
  private static native void jsTexSubImage2DFromSource(WebGLRenderingContext gl, WebGLTexture tex,
      JSObject src);
}
