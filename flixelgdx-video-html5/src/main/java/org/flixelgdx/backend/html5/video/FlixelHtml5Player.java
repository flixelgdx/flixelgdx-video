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
 * {@link FlixelVideoWebGlTexture} and rewrites its pixels every time the browser decodes a new
 * frame, so videos draw through the regular batch and keep state draw order intact (a sprite added
 * after the video renders above it). {@link FlixelVideo} wraps this player; game code does not use
 * it directly.
 *
 * <p>Each ready frame is uploaded to the GPU with no Java-side allocation. At full quality the
 * video element is passed directly to {@code texSubImage2D}, letting the browser copy pixels
 * straight from the decoder to the GPU. At lower {@link FlixelVideoQuality} presets the frame is
 * first drawn onto a reused offscreen canvas at the target size, and that canvas is passed to
 * {@code texSubImage2D} instead. Both paths avoid any {@code getImageData} or {@code byte[]} copy.
 *
 * <p>Autoplay policies may block {@link #play()} with sound before the first user
 * gesture; in that case playback resumes automatically on the next pointer or key
 * event (the rejection handler in {@link #jsPlay} registers one-shot listeners).
 */
public final class FlixelHtml5Player implements FlixelVideoPlayer {

  /** The hidden video element doing the decoding. */
  private final JSObject element;

  /** Offscreen canvas used to draw each frame at quality-scaled dimensions before upload. */
  @Nullable
  private JSObject scaleCanvas;

  /** The frame texture the browser frames are uploaded into; owned by this player. */
  @Nullable
  private FlixelVideoWebGlTexture videoTex;

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
  public FlixelHtml5Player(@NotNull String url) {
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
    int width = getFrameWidth();
    int height = getFrameHeight();
    if (width <= 0 || height <= 0) {
      return;
    }

    FlixelVideoWebGlTexture tex = videoTex;
    boolean sizeChanged = tex == null || width != tex.getWidth() || height != tex.getHeight();
    boolean newFrame = jsConsumeFrameDirty(element);
    if (!sizeChanged && !newFrame && !forceNextFrame) {
      return;
    }

    if (sizeChanged) {
      WebGLRenderingContext gl = ((FlixelHtml5Graphics) Flixel.graphics).getGl();
      if (gl == null) {
        return;
      }
      if (tex != null) {
        tex.destroy();
      }
      tex = new FlixelVideoWebGlTexture(gl, width, height);
      videoTex = tex;
      ready = false;
    }
    forceNextFrame = false;

    // For full quality, pass the video element directly to texSubImage2D, with no canvas readback
    // and no Java copy. For scaled quality, draw to the offscreen canvas at the target size first,
    // then pass that canvas.
    if (mediaQuality == FlixelVideoQuality.FULL) {
      tex.uploadFrom(element);
    } else {
      if (scaleCanvas == null) {
        scaleCanvas = jsCreateCanvas();
      }
      jsDrawScaled(scaleCanvas, element, width, height);
      tex.uploadFrom(scaleCanvas);
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
    scaleCanvas = null;
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
    return ready ? videoTex : null;
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

  @JSBody(script = "return document.createElement('canvas');")
  private static native JSObject jsCreateCanvas();

  @JSBody(params = { "canvas", "v", "w", "h" }, script = "if (canvas.width !== w) canvas.width = w;"
      + "if (canvas.height !== h) canvas.height = h;"
      + "canvas.getContext('2d').drawImage(v, 0, 0, w, h);")
  private static native void jsDrawScaled(JSObject canvas, JSObject v, int w, int h);
}
