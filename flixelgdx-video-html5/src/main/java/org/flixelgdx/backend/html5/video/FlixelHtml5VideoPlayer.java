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

import org.flixelgdx.Flixel;
import org.flixelgdx.backend.html5.graphics.FlixelHtml5Graphics;
import org.flixelgdx.graphics.FlixelRenderTarget;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoPlayer;
import org.flixelgdx.video.FlixelVideoQuality;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGLRenderingContext;

/**
 * Web video player built on a hidden HTML video element.
 *
 * <p>The video element is used strictly as a decoding source and is never attached to
 * the DOM, so it cannot float above or below the game canvas. This player owns one
 * {@link FlixelWebGlVideoTexture} and rewrites its pixels every time the browser decodes a new
 * frame, so videos draw through the regular batch and keep state draw order intact (a sprite added
 * after the video renders above it). {@link FlixelVideo} wraps this player; game code does not use
 * it directly.
 *
 * <p>Each ready frame is uploaded to the GPU with no Java-side allocation. The video element is
 * always passed directly to {@code texSubImage2D}, letting the browser copy pixels straight from
 * the decoder to the GPU. At full quality that texture is the frame. At lower
 * {@link FlixelVideoQuality} presets it is only a source: a tiny shader draws it into a smaller
 * render target on the GPU, and that target is the frame. Nothing is ever read back to the CPU, and
 * no {@code <canvas>} sits in between (a 2D canvas forces a slow software path in some browsers).
 *
 * <p>Autoplay policies may block {@link #play()} with sound before the first user
 * gesture; in that case playback resumes automatically on the next pointer or key
 * event (the rejection handler in {@link #jsPlay} registers one-shot listeners).
 */
public final class FlixelHtml5VideoPlayer implements FlixelVideoPlayer {

  /** The hidden video element doing the decoding. */
  private final JSObject element;

  /** Full-screen quad program and vertex array that shrink frames on the GPU, made on first use. */
  @Nullable
  private JSObject blit;

  /**
   * Receives the shrunk frame at lower quality presets; {@code null} at full quality. Its size is
   * the quality-scaled frame size.
   */
  @Nullable
  private FlixelRenderTarget target;

  /**
   * The texture the browser frames are uploaded into, always at the native video size; owned by
   * this player. It is the drawn frame at full quality and the shrink source otherwise.
   */
  @Nullable
  private FlixelWebGlVideoTexture videoTex;

  @NotNull
  private FlixelVideoQuality mediaQuality = FlixelVideoQuality.FULL;

  private float volume = 1f;
  private float rate = 1f;

  /** Seek requested before the element had metadata; applied once seekable. -1 = none. */
  private float pendingSeekMs = -1f;

  private boolean looping;

  /** Set by {@link #setQuality} to force a re-read of the current frame on the next pump. */
  private boolean forceNextFrame;

  /** Set once a frame has been uploaded into the texture. */
  private boolean ready;

  private boolean disposed;

  /**
   * Creates a video element for the given URL path.
   *
   * @param url The video URL, typically an internal asset path relative to the page.
   */
  public FlixelHtml5VideoPlayer(@NotNull String url) {
    element = jsCreateVideo(url);
  }

  @Override
  public void play() {
    if (disposed) {
      return;
    }
    jsPlay(element);
  }

  @Override
  public void pause() {
    if (disposed) {
      return;
    }
    jsPause(element);
  }

  @Override
  public void resume() {
    play();
  }

  @Override
  public void stop() {
    if (disposed) {
      return;
    }
    jsPause(element);
    jsSetTime(element, 0.0);
    pendingSeekMs = -1f;
  }

  @Override
  public boolean isPlaying() {
    return !disposed && jsIsPlaying(element);
  }

  @Override
  public boolean isEnded() {
    return !disposed && jsIsEnded(element);
  }

  @Override
  public float getTime() {
    if (disposed) {
      return 0f;
    }
    if (pendingSeekMs >= 0f) {
      return pendingSeekMs;
    }
    return (float) (jsGetTime(element) * 1000.0);
  }

  @Override
  public void setTime(float timeMs) {
    if (disposed) {
      return;
    }
    float target = Math.max(0f, timeMs);
    if (jsGetReadyState(element) >= 1) {
      // HAVE_METADATA or better: the element accepts seeks immediately.
      jsSetTime(element, target / 1000.0);
      pendingSeekMs = -1f;
    } else {
      pendingSeekMs = target;
    }
  }

  @Override
  public float getLength() {
    if (disposed) {
      return 0f;
    }
    double duration = jsGetDuration(element);
    if (Double.isNaN(duration) || Double.isInfinite(duration)) {
      return 0f;
    }
    return (float) (duration * 1000.0);
  }

  @Override
  public void setRate(float rate) {
    if (disposed || rate <= 0f) {
      return;
    }
    this.rate = rate;
    jsSetRate(element, rate);
  }

  @Override
  public void setLooped(boolean looped) {
    this.looping = looped;
    if (!disposed) {
      jsSetLoop(element, looped);
    }
  }

  @Override
  public void setVolume(float volume) {
    this.volume = Math.max(0f, Math.min(1f, volume));
    if (!disposed) {
      jsSetVolume(element, this.volume);
    }
  }

  @Override
  public void setQuality(@NotNull FlixelVideoQuality quality) {
    if (this.mediaQuality == quality) {
      return;
    }
    this.mediaQuality = quality;
    // Force a re-read on the next pump so the frame is captured at the new scaled dimensions.
    // When dimensions change, the size check already triggers the re-read; forceNextFrame handles
    // the edge case where the new scale produces the same pixel size as the old one (e.g., on a
    // paused video where the decoder will not advance to signal a new frame).
    forceNextFrame = true;
  }

  @Override
  public void update(float elapsed) {
    if (disposed) {
      return;
    }

    if (pendingSeekMs >= 0f && jsGetReadyState(element) >= 1) {
      jsSetTime(element, pendingSeekMs / 1000.0);
      pendingSeekMs = -1f;
    }

    // Re-assert volume every frame; some browsers reset v.volume on autoplay
    // initialization or when the audio context unlocks after a user gesture.
    jsSetVolume(element, volume);

    // HAVE_CURRENT_DATA (2) means a decoded frame is available for the current time.
    if (jsGetReadyState(element) < 2) {
      return;
    }
    int nativeWidth = jsGetVideoWidth(element);
    int nativeHeight = jsGetVideoHeight(element);
    if (nativeWidth <= 0 || nativeHeight <= 0) {
      return;
    }
    int width = scaledDimension(nativeWidth);
    int height = scaledDimension(nativeHeight);
    boolean full = mediaQuality == FlixelVideoQuality.FULL;

    FlixelWebGlVideoTexture tex = videoTex;
    FlixelRenderTarget scaled = target;
    boolean sizeChanged = tex == null || nativeWidth != tex.getWidth() || nativeHeight != tex.getHeight();
    boolean targetStale = full
        ? scaled != null
        : scaled == null || scaled.getWidth() != width || scaled.getHeight() != height;
    boolean newFrame = jsConsumeFrameDirty(element);
    if (!sizeChanged && !targetStale && !newFrame && !forceNextFrame) {
      return;
    }

    WebGLRenderingContext gl = ((FlixelHtml5Graphics) Flixel.graphics).getGl();
    if (gl == null) {
      return;
    }

    if (sizeChanged) {
      if (tex != null) {
        tex.destroy();
      }
      // Linear filtering only matters when this texture is the shrink source.
      tex = new FlixelWebGlVideoTexture(gl, nativeWidth, nativeHeight, !full);
      videoTex = tex;
      ready = false;
    } else if (forceNextFrame) {
      tex.setSmooth(!full);
    }

    if (full) {
      if (scaled != null) {
        scaled.destroy();
        target = null;
      }
    } else if (targetStale) {
      FlixelRenderTarget created = Flixel.graphics.createRenderTarget(width, height);
      if (created.getWidth() <= 0) {
        // Graphics are not ready yet; the stale check keeps this retrying on the next frame.
        return;
      }
      if (scaled != null) {
        scaled.destroy();
      }
      scaled = created;
      target = created;
      ready = false;
    }
    forceNextFrame = false;

    // The browser copies the decoded frame straight to the GPU. At lower quality, a GPU pass then
    // shrinks it into the render target.
    tex.uploadFrom(element);
    if (!full) {
      if (blit == null) {
        blit = jsCreateBlit(gl);
      }
      // Anything the framework queued must reach the previous surface before the target changes.
      Flixel.graphics.getBatch().flush();
      scaled.begin();
      jsBlit(gl, blit, tex.getGlTexture());
      scaled.end();
    }
    ready = true;
  }

  @Override
  public void destroy() {
    if (disposed) {
      return;
    }
    disposed = true;
    jsDispose(element);
    if (target != null) {
      target.destroy();
      target = null;
    }
    if (blit != null) {
      WebGLRenderingContext gl = ((FlixelHtml5Graphics) Flixel.graphics).getGl();
      if (gl != null) {
        jsDeleteBlit(gl, blit);
      }
      blit = null;
    }
    if (videoTex != null) {
      videoTex.destroy();
      videoTex = null;
    }
    ready = false;
  }

  private int scaledDimension(int sourceSize) {
    if (sourceSize <= 0) {
      return 0;
    }
    if (mediaQuality == FlixelVideoQuality.FULL) {
      return sourceSize;
    }
    return Math.max(2, Math.round(sourceSize * mediaQuality.getScale()));
  }

  @Override
  public boolean isReady() {
    return ready;
  }

  @Override
  public float getRate() {
    return rate;
  }

  @Override
  public boolean isLooped() {
    return looping;
  }

  @Override
  public float getVolume() {
    return volume;
  }

  @Nullable
  @Override
  public FlixelTexture getFrame() {
    if (!ready) {
      return null;
    }
    FlixelRenderTarget scaled = target;
    return scaled != null ? scaled.getTexture() : videoTex;
  }

  @Override
  public int getVideoWidth() {
    return disposed ? 0 : jsGetVideoWidth(element);
  }

  @Override
  public int getVideoHeight() {
    return disposed ? 0 : jsGetVideoHeight(element);
  }

  @Override
  public int getFrameWidth() {
    if (disposed) {
      return 0;
    }
    return scaledDimension(jsGetVideoWidth(element));
  }

  @Override
  public int getFrameHeight() {
    if (disposed) {
      return 0;
    }
    return scaledDimension(jsGetVideoHeight(element));
  }

  @JSBody(params = { "url" }, script = """
      const v = document.createElement('video');
      v.src = url;
      v.crossOrigin = 'anonymous';
      v.preload = 'auto';
      v.playsInline = true;
      v.flxLastTime = -1.0;
      v.flxFrameDirty = false;
      v.flxFrameCb = 0;
      if (v.requestVideoFrameCallback) {
        var onFrame = function() {
          v.flxFrameDirty = true;
          v.flxFrameCb = v.requestVideoFrameCallback(onFrame);
        };
        v.flxFrameCb = v.requestVideoFrameCallback(onFrame);
        v.addEventListener('seeked', function() { v.flxFrameDirty = true; });
      }
      v.load();
      return v;""")
  private static native JSObject jsCreateVideo(String url);

  /**
   * Starts playback. If the browser's autoplay policy rejects the call (no user
   * gesture yet), one-shot listeners retry on the next pointer or key event.
   */
  @JSBody(params = { "v" }, script = """
      var p = v.play();
      if (p && p.catch) {
        p.catch(function() {
          if (v.flixelResumeArmed) return;
          v.flixelResumeArmed = true;
          var resume = function() {
            v.flixelResumeArmed = false;
            document.removeEventListener('pointerdown', resume);
            document.removeEventListener('keydown', resume);
            v.play();
          };
          document.addEventListener('pointerdown', resume);
          document.addEventListener('keydown', resume);
        });
      }""")
  private static native void jsPlay(JSObject v);

  @JSBody(params = { "v" }, script = "v.pause();")
  private static native void jsPause(JSObject v);

  @JSBody(params = { "v" }, script = "return !v.paused && !v.ended;")
  private static native boolean jsIsPlaying(JSObject v);

  @JSBody(params = { "v" }, script = "return v.ended;")
  private static native boolean jsIsEnded(JSObject v);

  @JSBody(params = { "v" }, script = "return v.currentTime;")
  private static native double jsGetTime(JSObject v);

  @JSBody(params = { "v", "seconds" }, script = "v.currentTime = seconds;")
  private static native void jsSetTime(JSObject v, double seconds);

  @JSBody(params = { "v" }, script = "return v.duration;")
  private static native double jsGetDuration(JSObject v);

  @JSBody(params = { "v", "rate" }, script = "v.playbackRate = rate;")
  private static native void jsSetRate(JSObject v, float rate);

  @JSBody(params = { "v", "loop" }, script = "v.loop = loop;")
  private static native void jsSetLoop(JSObject v, boolean loop);

  @JSBody(params = { "v", "volume" }, script = "v.volume = volume;")
  private static native void jsSetVolume(JSObject v, float volume);

  @JSBody(params = { "v" }, script = "return v.readyState;")
  private static native int jsGetReadyState(JSObject v);

  /**
   * Returns whether the browser has presented a new video frame since the last call.
   *
   * <p>Browsers that support {@code requestVideoFrameCallback} fire it once per presented frame,
   * so a 30 fps video reports 30 new frames per second no matter how fast the game renders. That
   * keeps the costly texture upload from running on game frames that would only repeat the same
   * picture. A {@code seeked} listener also marks the frame dirty so a seek while paused still
   * refreshes the texture.
   *
   * <p>Browsers without that API fall back to comparing {@code currentTime}. That value is
   * interpolated by the media clock and usually changes on every game frame, so the fallback is
   * correct but uploads more often than needed.
   */
  @JSBody(params = { "v" }, script = "if (v.flxFrameCb) {"
      + "var d = v.flxFrameDirty; v.flxFrameDirty = false; return d; }"
      + "var t = v.currentTime;"
      + "if (t !== v.flxLastTime) { v.flxLastTime = t; return true; }"
      + "return false;")
  private static native boolean jsConsumeFrameDirty(JSObject v);

  @JSBody(params = { "v" }, script = "return v.videoWidth;")
  private static native int jsGetVideoWidth(JSObject v);

  @JSBody(params = { "v" }, script = "return v.videoHeight;")
  private static native int jsGetVideoHeight(JSObject v);

  @JSBody(params = { "v" }, script = "if (v.flxFrameCb && v.cancelVideoFrameCallback) {"
      + "v.cancelVideoFrameCallback(v.flxFrameCb); }"
      + "v.flxFrameCb = 0;"
      + "v.pause();"
      + "v.removeAttribute('src');"
      + "v.load();")
  private static native void jsDispose(JSObject v);

  /**
   * Builds the program and vertex array that draw one texture across the whole framebuffer.
   *
   * <p>The quad has its own vertex array, so the batch's vertex state is left alone. Texture
   * coordinate {@code y = 0} lands on the first row of memory, which is what a render target
   * expects (top-down rows), so no flip is needed.
   */
  @JSBody(params = { "gl" }, script = """
      var compile = function(type, source) {
        var s = gl.createShader(type);
        gl.shaderSource(s, source);
        gl.compileShader(s);
        return s;
      };
      var prog = gl.createProgram();
      gl.attachShader(prog, compile(gl.VERTEX_SHADER, '#version 300 es\\n'
          + 'layout(location=0) in vec2 a; out vec2 u;'
          + 'void main() { u = a * 0.5 + 0.5; gl_Position = vec4(a, 0.0, 1.0); }'));
      gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, '#version 300 es\\n'
          + 'precision mediump float; in vec2 u; uniform sampler2D t; out vec4 c;'
          + 'void main() { c = texture(t, u); }'));
      gl.linkProgram(prog);
      var vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      var buf = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.bindVertexArray(null);
      gl.bindBuffer(gl.ARRAY_BUFFER, null);
      return { prog: prog, vao: vao, buf: buf };""")
  private static native JSObject jsCreateBlit(WebGLRenderingContext gl);

  /**
   * Draws the source texture across the bound framebuffer, then puts back the GL state it touched.
   *
   * <p>Blending and the scissor test are switched off for the draw and restored afterward. The
   * batch re-applies its own program, texture, buffers, and blend mode on every flush, but it does
   * not manage the scissor test, so that one has to be put back here.
   */
  @JSBody(params = { "gl", "b", "src" }, script = """
      var scissor = gl.isEnabled(gl.SCISSOR_TEST);
      var blend = gl.isEnabled(gl.BLEND);
      var prev = gl.getParameter(gl.CURRENT_PROGRAM);
      if (scissor) gl.disable(gl.SCISSOR_TEST);
      if (blend) gl.disable(gl.BLEND);
      gl.useProgram(b.prog);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, src);
      gl.bindVertexArray(b.vao);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.bindVertexArray(null);
      gl.useProgram(prev);
      if (blend) gl.enable(gl.BLEND);
      if (scissor) gl.enable(gl.SCISSOR_TEST);""")
  private static native void jsBlit(WebGLRenderingContext gl, JSObject b, JSObject src);

  @JSBody(params = { "gl", "b" }, script = "gl.deleteProgram(b.prog);"
      + "gl.deleteVertexArray(b.vao);"
      + "gl.deleteBuffer(b.buf);")
  private static native void jsDeleteBlit(WebGLRenderingContext gl, JSObject b);
}
