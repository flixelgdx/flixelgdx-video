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
import org.jetbrains.annotations.NotNull;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGLRenderingContext;
import org.teavm.jso.webgl.WebGLTexture;

/**
 * A frame texture for HTML5 video that uploads pixels directly from a DOM element.
 *
 * <p>The standard {@link FlixelWebGlTexture#update} path copies pixels through Java: it reads
 * bytes into a Java {@code byte[]}, boxes that into a {@code Uint8Array}, then calls
 * {@code texSubImage2D}. For video frames, that round trip allocates on every frame and taxes the
 * garbage collector.
 *
 * <p>This subclass adds {@link #uploadFrom(JSObject)}, which hands a DOM element straight to
 * {@code texSubImage2D}: the {@code <video>} element for full-quality playback, or the offscreen
 * {@code <canvas>} element for quality-scaled playback. The browser handles that without any CPU
 * round-trip or Java-side allocation. It still extends {@link FlixelWebGlTexture} because the
 * framework's WebGL batch only accepts that type as a drawable texture.
 */
public final class FlixelWebGlVideoTexture extends FlixelWebGlTexture {

  private final WebGLRenderingContext gl;

  /**
   * Creates a blank, updateable frame texture.
   *
   * @param gl The WebGL rendering context.
   * @param width Texture width in pixels.
   * @param height Texture height in pixels.
   */
  public FlixelWebGlVideoTexture(@NotNull WebGLRenderingContext gl, int width, int height) {
    super(gl, width, height, false);
    this.gl = gl;
  }

  /**
   * Binds this texture and uploads the given DOM element (video or canvas) as its pixels.
   *
   * <p>The browser reads the element's current displayed frame at its natural dimensions, so the
   * element must match this texture's size. No Java-side copy or typed-array allocation occurs:
   * the pixel data travels from the DOM element straight to the GPU.
   *
   * @param source The {@code <video>} or {@code <canvas>} element to read pixels from.
   */
  public void uploadFrom(@NotNull JSObject source) {
    jsTexSubImage2DFromSource(gl, getGlTexture(), source);
  }

  @JSBody(params = { "gl", "tex", "src" }, script = "gl.bindTexture(gl.TEXTURE_2D, tex);"
      + "gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, src);")
  private static native void jsTexSubImage2DFromSource(WebGLRenderingContext gl, WebGLTexture tex,
      JSObject src);
}
