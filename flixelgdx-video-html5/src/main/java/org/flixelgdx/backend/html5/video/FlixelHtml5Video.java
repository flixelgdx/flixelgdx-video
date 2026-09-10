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
import org.flixelgdx.graphics.FlixelImage;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoQuality;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.webgl.WebGLRenderingContext;

/**
 * Web video backend built on a hidden HTML video element.
 *
 * <p>The video element is used strictly as a decoding source and is never attached to
 * the DOM, so it cannot float above or below the game canvas; every frame is pulled
 * into a {@link FlixelImage} and handed to core, which keeps a single texture alive and
 * scrubs its pixels. That is the same "reuse one texture, rewrite its pixels" path the desktop
 * backend uses, so videos draw through the regular batch and keep state draw order intact (a
 * sprite added after the video renders above it).
 *
 * <p>Each ready frame is uploaded to the GPU with no Java-side allocation. At full quality the
 * video element is passed directly to {@code texSubImage2D}, letting the browser copy pixels
 * straight from the decoder to the GPU. At lower {@link FlixelVideoQuality} presets the frame is
 * first drawn onto a reused offscreen canvas at the target size, and that canvas is passed to
 * {@code texSubImage2D} instead. Both paths avoid any {@code getImageData} or {@code byte[]} copy.
 *
 * <p>Autoplay policies may block {@link #playMedia()} with sound before the first user
 * gesture; in that case playback resumes automatically on the next pointer or key
 * event (the rejection handler in {@link #jsPlay} registers one-shot listeners).
 */
public class FlixelHtml5Video extends FlixelVideo {

  /** Dimensions the reusable frame image was allocated for. */
  private int imageWidth;
  private int imageHeight;

  private float volume = 1f;
  private float rate = 1f;

  /** Seek requested before the element had metadata; applied once seekable. -1 = none. */
  private float pendingSeekMs = -1f;

  /** The hidden video element doing the decoding. */
  private final JSObject element;

  /** Offscreen canvas used to draw each frame at quality-scaled dimensions before upload. */
  @Nullable
  private JSObject scaleCanvas;

  /**
   * The DOM element (video or canvas) to pass directly to {@code texSubImage2D} on the next
   * frame upload. Set just before {@link #updateFrame(FlixelImage)} is called; consumed by
   * {@link FlixelVideoWebGlTexture#update}.
   */
  @Nullable
  private JSObject pendingJsSource;

  /** Reusable CPU frame passed to {@link #updateFrame(FlixelImage)} for its dimensions only. */
  @Nullable
  private FlixelImage frameImage;

  /** The specialized frame texture that accepts direct DOM element uploads. */
  @Nullable
  private FlixelVideoWebGlTexture videoTex;

  @NotNull
  private FlixelVideoQuality mediaQuality = FlixelVideoQuality.FULL;

  private boolean looping;
  /** Set by {@link #applyMediaQuality} to force a re-read of the current frame on the next pump. */
  private boolean forceNextFrame;
  private boolean ready;
  private boolean disposed;

  /**
   * Creates a video element for the given URL path.
   *
   * @param url The video URL, typically an internal asset path relative to the page.
   */
  public FlixelHtml5Video(@NotNull String url) {
    super();
    element = jsCreateVideo(url);
  }

  @Override
  protected void playMedia() {
    if (disposed) {
      return;
    }
    jsPlay(element);
  }

  @Override
  protected void pauseMedia() {
    if (disposed) {
      return;
    }
    jsPause(element);
  }

  @Override
  protected void resumeMedia() {
    playMedia();
  }

  @Override
  protected void stopMedia() {
    if (disposed) {
      return;
    }
    jsPause(element);
    jsSetTime(element, 0.0);
    pendingSeekMs = -1f;
  }

  @Override
  protected boolean isMediaPlaying() {
    return !disposed && jsIsPlaying(element);
  }

  @Override
  protected boolean isMediaEnded() {
    return !disposed && jsIsEnded(element);
  }

  @Override
  protected boolean isMediaReady() {
    return ready;
  }

  @Override
  protected float getMediaTime() {
    if (disposed) {
      return 0f;
    }
    if (pendingSeekMs >= 0f) {
      return pendingSeekMs;
    }
    return (float) (jsGetTime(element) * 1000.0);
  }

  @Override
  protected void setMediaTime(float timeMs) {
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
  protected float getMediaLength() {
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
  protected float getMediaRate() {
    return rate;
  }

  @Override
  protected void setMediaRate(float rate) {
    if (disposed || rate <= 0f) {
      return;
    }
    this.rate = rate;
    jsSetRate(element, rate);
  }

  @Override
  protected boolean isMediaLooped() {
    return looping;
  }

  @Override
  protected void setMediaLooped(boolean looped) {
    this.looping = looped;
    if (!disposed) {
      jsSetLoop(element, looped);
    }
  }

  @Override
  protected float getMediaVolume() {
    return volume;
  }

  @Override
  protected void setMediaVolume(float volume) {
    this.volume = Math.max(0f, Math.min(1f, volume));
    if (!disposed) {
      jsSetVolume(element, this.volume);
    }
  }

  @Override
  protected void applyMediaQuality(@NotNull FlixelVideoQuality quality) {
    if (this.mediaQuality == quality) {
      return;
    }
    this.mediaQuality = quality;
    // Force a re-read on the next pump so the frame is captured at the new scaled dimensions.
    // When dimensions change, sizeChanged already triggers the re-read; forceNextFrame handles
    // the edge case where the new scale produces the same pixel size as the old one (e.g., on a
    // paused video where timeupdate will not fire to signal a new frame).
    forceNextFrame = true;
  }

  @Override
  protected int getMediaVideoWidth() {
    if (disposed) {
      return 0;
    }
    return scaledDimension(jsGetVideoWidth(element));
  }

  @Override
  protected int getMediaVideoHeight() {
    if (disposed) {
      return 0;
    }
    return scaledDimension(jsGetVideoHeight(element));
  }

  @NotNull
  @Override
  protected FlixelTexture createFrameTexture(int width, int height) {
    WebGLRenderingContext glCtx = ((FlixelHtml5Graphics) Flixel.graphics).getGl();
    if (glCtx == null) {
      return super.createFrameTexture(width, height);
    }
    FlixelVideoWebGlTexture tex = new FlixelVideoWebGlTexture(glCtx, width, height);
    tex.setDirectSource(pendingJsSource);
    videoTex = tex;
    return tex;
  }

  @Override
  protected void updateMedia(float elapsed) {
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
    int width = getMediaVideoWidth();
    int height = getMediaVideoHeight();
    if (width <= 0 || height <= 0) {
      return;
    }

    boolean sizeChanged = frameImage == null || width != imageWidth || height != imageHeight;
    boolean newFrame = jsConsumeFrameDirty(element);
    if (!sizeChanged && !newFrame && !forceNextFrame) {
      return;
    }
    forceNextFrame = false;

    FlixelImage image = ensureFrameImage(width, height);

    // Select the pixel source for this frame. For full quality, pass the video element directly
    // to texSubImage2D -- no canvas readback, no Java copy. For scaled quality, draw to the
    // offscreen canvas at the target size first, then pass that canvas.
    if (mediaQuality == FlixelVideoQuality.FULL) {
      pendingJsSource = element;
    } else {
      if (scaleCanvas == null) {
        scaleCanvas = jsCreateCanvas();
      }
      jsDrawScaled(scaleCanvas, element, width, height);
      pendingJsSource = scaleCanvas;
    }

    // If the texture already exists at the right size, prime its source now so the update() call
    // inside updateFrame() picks it up. If the texture is null or the wrong size, createFrameTexture
    // will set the source on the newly created texture before update() is called.
    FlixelVideoWebGlTexture vTex = videoTex;
    if (vTex != null) {
      vTex.setDirectSource(pendingJsSource);
    }
    updateFrame(image);
    pendingJsSource = null;
    ready = true;
  }

  @Override
  protected void disposeMedia() {
    if (disposed) {
      return;
    }
    disposed = true;
    jsDispose(element);
    scaleCanvas = null;
    pendingJsSource = null;
    frameImage = null;
    videoTex = null;
    ready = false;
  }

  /** Allocates (or reuses) the CPU frame image at the current decode size. */
  @NotNull
  private FlixelImage ensureFrameImage(int width, int height) {
    FlixelImage image = frameImage;
    if (image == null || imageWidth != width || imageHeight != height) {
      image = new FlixelImage(width, height);
      frameImage = image;
      imageWidth = width;
      imageHeight = height;
    }
    return image;
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

  @JSBody(params = { "url" }, script = "var v = document.createElement('video');"
      + "v.src = url;"
      + "v.crossOrigin = 'anonymous';"
      + "v.preload = 'auto';"
      + "v.playsInline = true;"
      + "v.flixelVolume = 1;"
      + "v.flixelLoop = false;"
      + "v.flxLastTime = -1.0;"
      + "v.addEventListener('loadedmetadata', function() {"
      + "  v.volume = v.flixelVolume;"
      + "  v.loop = v.flixelLoop;"
      + "});"
      + "v.load();"
      + "return v;")
  private static native JSObject jsCreateVideo(String url);

  /**
   * Starts playback. If the browser's autoplay policy rejects the call (no user
   * gesture yet), one-shot listeners retry on the next pointer or key event.
   */
  @JSBody(params = { "v" }, script = "v.volume = v.flixelVolume;"
      + "var p = v.play();"
      + "if (p && p.catch) {"
      + "  p.catch(function() {"
      + "    if (v.flixelResumeArmed) return;"
      + "    v.flixelResumeArmed = true;"
      + "    var resume = function() {"
      + "      v.flixelResumeArmed = false;"
      + "      document.removeEventListener('pointerdown', resume);"
      + "      document.removeEventListener('keydown', resume);"
      + "      v.volume = v.flixelVolume;"
      + "      v.play();"
      + "    };"
      + "    document.addEventListener('pointerdown', resume);"
      + "    document.addEventListener('keydown', resume);"
      + "  });"
      + "}")
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

  @JSBody(params = { "v", "loop" }, script = "v.flixelLoop = loop; v.loop = loop;")
  private static native void jsSetLoop(JSObject v, boolean loop);

  @JSBody(params = { "v", "volume" }, script = "v.flixelVolume = volume; v.volume = volume;")
  private static native void jsSetVolume(JSObject v, float volume);

  @JSBody(params = { "v" }, script = "return v.readyState;")
  private static native int jsGetReadyState(JSObject v);

  /**
   * Returns whether the decoder has advanced to a new frame since the last call.
   *
   * <p>{@code currentTime} advances exactly once per decoded frame, giving per-frame
   * resolution without any event listener. The {@code timeupdate} event fires at most
   * four times per second per spec, which is too coarse for smooth video playback.
   */
  @JSBody(params = { "v" }, script = "var t = v.currentTime;"
      + "if (t !== v.flxLastTime) { v.flxLastTime = t; return true; }"
      + "return false;")
  private static native boolean jsConsumeFrameDirty(JSObject v);

  @JSBody(params = { "v" }, script = "return v.videoWidth;")
  private static native int jsGetVideoWidth(JSObject v);

  @JSBody(params = { "v" }, script = "return v.videoHeight;")
  private static native int jsGetVideoHeight(JSObject v);

  @JSBody(params = { "v" }, script = "v.pause();"
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
