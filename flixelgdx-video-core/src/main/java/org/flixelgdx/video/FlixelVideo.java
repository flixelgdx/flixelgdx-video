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
import org.flixelgdx.FlixelBasic;
import org.flixelgdx.FlixelCamera;
import org.flixelgdx.graphics.FlixelBatch;
import org.flixelgdx.graphics.FlixelImage;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.util.signal.FlixelSignal;
import org.flixelgdx.util.signal.FlixelSignal.SignalHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A video that plays inside your game, drawn like any other object in a state.
 *
 * <p>{@code FlixelVideo} extends {@link FlixelBasic}, so it carries the normal lifecycle
 * flags, can be pooled, and can be added straight to a
 * {@link org.flixelgdx.FlixelState FlixelState}. Draw order follows state member order:
 * a sprite added after the video renders on top of it, exactly as with two sprites.
 *
 * <p>Create instances through {@link FlixelVideos#create(org.flixelgdx.file.FlixelFile)}, which
 * picks the platform backend registered by your launcher:
 *
 * <pre>{@code
 * FlixelVideo cutscene = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
 * cutscene.setSize(Flixel.game.getWidth(), Flixel.game.getHeight());
 * cutscene.setLooped(false);
 * cutscene.onComplete.add(data -> Flixel.switchState(MenuState::new));
 * add(cutscene);
 * cutscene.play();
 * }</pre>
 *
 * <p>All time values are in milliseconds, matching
 * {@link org.flixelgdx.audio.FlixelSound FlixelSound}. Call {@link #destroy()} when the
 * video leaves the game for good to release the decoder and the frame texture.
 *
 * <p>The frame image is the same on every platform: each backend decodes into a reusable
 * {@link FlixelImage} of RGBA pixels and hands it to {@link #updateFrame(FlixelImage)}, which keeps
 * a single {@link FlixelTexture} alive and scrubs its pixels in place. Because that "reuse one
 * texture, rewrite its pixels" step lives here in core and rides on the portable graphics
 * interface, every backend gets it without repeating the GPU plumbing.
 *
 * <p>The video pauses automatically when the window loses focus or the application is sent to the
 * background (the same {@link Flixel.Signals#windowUnfocused} and {@link Flixel.Signals#windowFocused}
 * events the framework pauses audio on) while {@link Flixel#autoPause} is {@code true}, and resumes
 * when focus returns.
 *
 * <p>Subclassing this type is only needed for a custom media pipeline. Platform backends
 * implement the {@code protected abstract} template methods ({@link #playMedia()},
 * {@link #updateMedia(float)}, and so on) and inherit the display, lifecycle, and
 * auto-pause behavior automatically.
 *
 * @see FlixelVideos
 */
public abstract class FlixelVideo extends FlixelBasic {

  /** Signal dispatched when this video starts playing. **/
  @NotNull
  public final FlixelSignal<Void> onPlay = new FlixelSignal<>();

  /** Signal dispatched when this video is paused. **/
  @NotNull
  public final FlixelSignal<Void> onPause = new FlixelSignal<>();

  /** Signal dispatched when this video resumes playing. **/
  @NotNull
  public final FlixelSignal<Void> onResume = new FlixelSignal<>();

  /** Signal dispatched once when this non-looping video reaches its end. */
  @NotNull
  public final FlixelSignal<Void> onComplete = new FlixelSignal<>();

  @NotNull
  private FlixelVideoQuality quality = FlixelVideoQuality.FULL;

  /** The reusable GPU texture the latest frame is scrubbed into; {@code null} before the first frame. */
  @Nullable
  private FlixelTexture texture;

  /**
   * Pauses the video when the window loses focus (or the app is backgrounded), so it lines up with
   * the framework pausing audio. Kept as a field so it can be unregistered in {@link #destroy()}.
   */
  private final SignalHandler<Void> onWindowUnfocused = data -> {
    // These fields (autoPaused) are declared lower in the class, so qualify with this to satisfy
    // the forward-reference rule in a field initializer.
    if (Flixel.autoPause && !this.autoPaused && isMediaPlaying()) {
      pauseMedia();
      this.autoPaused = true;
    }
  };

  /** Resumes the video when focus returns, undoing {@link #onWindowUnfocused}. */
  private final SignalHandler<Void> onWindowFocused = data -> {
    if (this.autoPaused) {
      this.autoPaused = false;
      resumeMedia();
    }
  };

  /** World X position of the top-left corner in view coordinates. */
  public float x;

  /** World Y position of the video in view coordinates. */
  public float y;

  /** Drawn width in pixels. {@code 0} (the default) draws at the decoded frame width. */
  public float width;

  /** Drawn height in pixels. {@code 0} (the default) draws at the decoded frame height. */
  public float height;

  /** Horizontal parallax factor, same contract as sprites ({@code 1} = follows the camera). */
  public float scrollX = 1f;

  /** Vertical parallax factor, same contract as sprites ({@code 1} = follows the camera). */
  public float scrollY = 1f;

  /**
   * Set when this video was paused automatically (by the focus hook or a platform-specific
   * focus event) so it can be correctly resumed when the application returns to the foreground.
   */
  protected boolean autoPaused;

  /** When {@code true}, {@link #destroy()} is called automatically when playback completes. */
  private boolean autoDestroy;

  /** Guards {@link #onComplete} so the end of a video is only announced once. */
  private boolean completed;

  /** Set once the first frame has been uploaded, so drawing waits for real pixels. */
  private boolean frameReady;

  /** Starts the underlying media player. */
  protected abstract void playMedia();

  /** Pauses the underlying media player at the current position. */
  protected abstract void pauseMedia();

  /** Resumes the underlying media player after a pause. */
  protected abstract void resumeMedia();

  /** Stops the underlying media player and resets its position. */
  protected abstract void stopMedia();

  /**
   * Returns whether the underlying player is actively playing.
   *
   * @return {@code true} while playing.
   */
  protected abstract boolean isMediaPlaying();

  /**
   * Returns whether the underlying player has decoded its first frame.
   *
   * @return {@code true} once the stream is ready.
   */
  protected abstract boolean isMediaReady();

  /**
   * Returns whether a non-looping stream has reached its end.
   *
   * @return {@code true} once the stream finished playing.
   */
  protected abstract boolean isMediaEnded();

  /**
   * Returns the current playback position in milliseconds.
   *
   * @return Playback position in milliseconds.
   */
  protected abstract float getMediaTime();

  /**
   * Seeks to the given playback position.
   *
   * @param timeMs Target position in milliseconds.
   */
  protected abstract void setMediaTime(float timeMs);

  /**
   * Returns the total duration of the media.
   *
   * @return Duration in milliseconds, or {@code 0} if not yet known.
   */
  protected abstract float getMediaLength();

  /**
   * Returns the current playback speed multiplier.
   *
   * @return Speed multiplier; {@code 1} is normal.
   */
  protected abstract float getMediaRate();

  /**
   * Sets the playback speed multiplier.
   *
   * @param rate Speed multiplier; must be greater than {@code 0}.
   */
  protected abstract void setMediaRate(float rate);

  /**
   * Returns whether the media is set to loop automatically.
   *
   * @return {@code true} if looping is enabled.
   */
  protected abstract boolean isMediaLooped();

  /**
   * Enables or disables automatic looping.
   *
   * @param looped {@code true} to loop, {@code false} to play once.
   */
  protected abstract void setMediaLooped(boolean looped);

  /**
   * Returns the current audio volume.
   *
   * @return Volume in {@code [0, 1]}.
   */
  protected abstract float getMediaVolume();

  /**
   * Sets the audio volume.
   *
   * @param volume Volume in {@code [0, 1]}; implementations should clamp out-of-range values.
   */
  protected abstract void setMediaVolume(float volume);

  /**
   * Applies a decode quality preset to the underlying player.
   *
   * @param quality The preset to apply.
   */
  protected abstract void applyMediaQuality(@NotNull FlixelVideoQuality quality);

  /**
   * Returns the decoded frame width in pixels.
   *
   * @return Frame width, or {@code 0} while not yet ready.
   */
  protected abstract int getMediaVideoWidth();

  /**
   * Returns the decoded frame height in pixels.
   *
   * @return Frame height, or {@code 0} while not yet ready.
   */
  protected abstract int getMediaVideoHeight();

  /**
   * Per-frame pump; advances player state and pushes the newest decoded frame.
   *
   * <p>Must be called on the render thread. {@link #update(float)} calls this automatically after
   * the standard lifecycle checks. When a new frame has been decoded, the backend copies its RGBA
   * pixels into a reusable {@link FlixelImage} and hands it to {@link #updateFrame(FlixelImage)}.
   *
   * @param elapsed Seconds since the last frame.
   */
  protected abstract void updateMedia(float elapsed);

  /** Releases all native resources held by this backend. */
  protected abstract void disposeMedia();

  protected FlixelVideo() {
    super();
    Flixel.Signals.windowUnfocused.add(onWindowUnfocused);
    Flixel.Signals.windowFocused.add(onWindowFocused);
  }

  /**
   * Plays the video from the beginning.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo play() {
    return play(true, 0f);
  }

  /**
   * Plays the video.
   *
   * @param forceRestart Should the video restart from the beginning if it is already playing?
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo play(boolean forceRestart) {
    return play(forceRestart, 0f);
  }

  /**
   * Plays the video.
   *
   * @param forceRestart Whether to restart if the video is already playing.
   * @param startTimeMs The time to start playback at, in milliseconds.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo play(boolean forceRestart, float startTimeMs) {
    completed = false;
    playMedia();
    if (forceRestart) {
      setMediaTime(startTimeMs);
    }
    onPlay.dispatch();
    return this;
  }

  /**
   * Pauses the video at its current position.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo pause() {
    pauseMedia();
    onPause.dispatch();
    return this;
  }

  /**
   * Resumes from the current position after a pause.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo resume() {
    resumeMedia();
    onResume.dispatch();
    return this;
  }

  /**
   * Stops the video and resets the position to the beginning.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo stop() {
    completed = false;
    stopMedia();
    return this;
  }

  /**
   * Returns whether the video is currently playing.
   *
   * @return {@code true} if the video is actively playing.
   */
  public final boolean isPlaying() {
    return isMediaPlaying();
  }

  /**
   * Returns whether the video has finished decoding its metadata and produced a frame.
   *
   * <p>Width, height, and length are {@code 0} until this returns {@code true}, which
   * happens shortly after the first {@link #play()}.
   *
   * @return {@code true} once the video is ready to display.
   */
  public final boolean isReady() {
    return isMediaReady();
  }

  @Override
  public void update(float elapsed) {
    if (!active || !exists) {
      return;
    }

    updateMedia(elapsed);

    if (!completed && isMediaEnded() && !isLooped()) {
      completed = true;
      onComplete.dispatch();
      if (autoDestroy) {
        destroy();
      }
    }
  }

  @Override
  public void draw(@NotNull FlixelBatch batch) {
    if (!visible || !exists || !frameReady) {
      return;
    }
    FlixelTexture tex = texture;
    if (tex == null || !isOnDrawCamera()) {
      return;
    }

    int videoW = getMediaVideoWidth();
    int videoH = getMediaVideoHeight();
    float drawW = width > 0f ? width : videoW;
    float drawH = height > 0f ? height : videoH;
    if (drawW <= 0f || drawH <= 0f || videoW <= 0 || videoH <= 0) {
      return;
    }

    FlixelCamera cam = Flixel.getDrawCamera() != null ? Flixel.getDrawCamera() : Flixel.cameras.first();
    float wx = cam.worldToViewX(x, scrollX);
    float wy = cam.worldToViewY(y, scrollY);
    if (!cam.isInView(wx, wy, drawW, drawH)) {
      return;
    }

    // The picture sits in the top-left of the texture; decoders often make the texture a little
    // larger than the visible frame (codec row alignment), so crop the sampled region to the real
    // picture size instead of stretching the padding across the quad.
    int texW = tex.getWidth();
    int texH = tex.getHeight();
    float u2 = texW > 0 ? Math.min(1f, videoW / (float) texW) : 1f;
    float v2 = texH > 0 ? Math.min(1f, videoH / (float) texH) : 1f;
    batch.draw(tex, wx, wy, drawW, drawH, 0f, 0f, u2, v2);
  }

  @Override
  public void destroy() {
    super.destroy();
    Flixel.Signals.windowUnfocused.remove(onWindowUnfocused);
    Flixel.Signals.windowFocused.remove(onWindowFocused);
    onPlay.clear();
    onPause.clear();
    onResume.clear();
    onComplete.clear();
    disposeMedia();
    if (texture != null) {
      texture.destroy();
      texture = null;
    }
    frameReady = false;
    quality = FlixelVideoQuality.FULL;
    x = 0f;
    y = 0f;
    width = 0f;
    height = 0f;
    scrollX = 1f;
    scrollY = 1f;
    autoPaused = false;
    autoDestroy = false;
    completed = false;
  }

  /**
   * Uploads the newest decoded frame into the reusable frame texture.
   *
   * <p>Backends call this from {@link #updateMedia(float)} on the render thread whenever a fresh
   * frame is ready. The first call, and any call whose pixel size differs from the current texture,
   * creates the texture; every other call scrubs the existing texture's pixels in place. This is the
   * "reuse one texture, rewrite its pixels" path, kept here so every backend shares it: a backend
   * only has to decode RGBA into a {@link FlixelImage}, never manage a GPU texture itself.
   *
   * @param image The freshly decoded RGBA pixels; owned by the backend and reused between frames.
   */
  protected final void updateFrame(@NotNull FlixelImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    if (w <= 0 || h <= 0) {
      return;
    }
    FlixelTexture current = texture;
    if (current == null || current.getWidth() != w || current.getHeight() != h) {
      if (current != null) {
        current.destroy();
      }
      current = Flixel.graphics.createTexture(image);
      current.setSmooth(true);
      texture = current;
    } else {
      current.update(0, 0, image);
    }
    frameReady = true;
  }

  /**
   * Returns the texture that holds the current video frame.
   *
   * <p>Useful when you want to feed the video into your own drawing code instead of
   * relying on the default draw. The video owns this texture; do not destroy it
   * yourself.
   *
   * @return The frame texture, or {@code null} before the first frame arrives.
   */
  @Nullable
  public final FlixelTexture getTexture() {
    return frameReady ? texture : null;
  }

  /**
   * Returns the current playback position in milliseconds.
   *
   * @return Playback position in milliseconds.
   */
  public final float getTime() {
    return getMediaTime();
  }

  /**
   * Sets the playback position in milliseconds.
   *
   * @param timeMs The time to seek to, in milliseconds.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setTime(float timeMs) {
    completed = false;
    setMediaTime(timeMs);
    return this;
  }

  /**
   * Returns the total length of the video in milliseconds.
   *
   * @return Duration in milliseconds, or {@code 0} if not yet known.
   */
  public final float getLength() {
    return getMediaLength();
  }

  /**
   * Returns the playback speed multiplier.
   *
   * @return Speed multiplier; {@code 1} is normal speed.
   */
  public final float getRate() {
    return getMediaRate();
  }

  /**
   * Sets the playback speed multiplier.
   *
   * @param rate Speed multiplier; must be greater than {@code 0}. {@code 1} is normal,
   *     {@code 2} is double speed, {@code 0.5} is half speed.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setRate(float rate) {
    setMediaRate(rate);
    return this;
  }

  /**
   * Returns whether this video is set to loop.
   *
   * @return {@code true} if looping is enabled.
   */
  public final boolean isLooped() {
    return isMediaLooped();
  }

  /**
   * Enables or disables looping.
   *
   * @param looped {@code true} to loop, {@code false} to play once.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setLooped(boolean looped) {
    setMediaLooped(looped);
    return this;
  }

  /**
   * Returns the audio volume of this video.
   *
   * @return Volume in {@code [0, 1]}.
   */
  public final float getVolume() {
    return getMediaVolume();
  }

  /**
   * Sets the audio volume of this video.
   *
   * @param volume Volume in {@code [0, 1]}; values outside the range are clamped.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setVolume(float volume) {
    setMediaVolume(volume);
    return this;
  }

  /**
   * Returns the current decode quality preset.
   *
   * @return The active quality preset.
   */
  @NotNull
  public final FlixelVideoQuality getQuality() {
    return quality;
  }

  /**
   * Sets the decode quality preset.
   *
   * <p>On the web the change applies immediately. On desktop the decoder pipeline is
   * rebuilt, so the change takes effect when playback starts or restarts; the desktop
   * backend restarts a playing video automatically and seeks back to where it was.
   *
   * @param quality The preset to apply (must not be {@code null}).
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setQuality(@NotNull FlixelVideoQuality quality) {
    if (quality == null) {
      throw new IllegalArgumentException("Video quality cannot be null.");
    }
    this.quality = quality;
    applyMediaQuality(quality);
    return this;
  }

  /**
   * Returns the width of the decoded video frame in pixels.
   *
   * @return Decoded frame width, or {@code 0} while the video is not yet ready.
   */
  public final int getVideoWidth() {
    return getMediaVideoWidth();
  }

  /**
   * Returns the height of the decoded video frame in pixels.
   *
   * @return Decoded frame height, or {@code 0} while the video is not yet ready.
   */
  public final int getVideoHeight() {
    return getMediaVideoHeight();
  }

  /**
   * Returns whether this video auto-destroys when playback completes.
   *
   * @return {@code true} if auto-destroy is enabled.
   */
  public final boolean isAutoDestroy() {
    return autoDestroy;
  }

  /**
   * Sets whether this video auto-destroys when playback completes.
   *
   * @param autoDestroy {@code true} to destroy this video when it finishes.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setAutoDestroy(boolean autoDestroy) {
    this.autoDestroy = autoDestroy;
    return this;
  }

  /**
   * Returns the world X position of the video's top-left corner.
   *
   * @return World X position.
   */
  public final float getX() {
    return x;
  }

  /**
   * Returns the world Y position of the video.
   *
   * @return World Y position.
   */
  public final float getY() {
    return y;
  }

  /**
   * Positions the video in world coordinates.
   *
   * @param x World X of the top-left corner.
   * @param y World Y of the video.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setPosition(float x, float y) {
    this.x = x;
    this.y = y;
    return this;
  }

  /**
   * Returns the drawn width in pixels ({@code 0} means the decoded frame width is used).
   *
   * @return Drawn width in pixels.
   */
  public final float getWidth() {
    return width;
  }

  /**
   * Returns the drawn height in pixels ({@code 0} means the decoded frame height is used).
   *
   * @return Drawn height in pixels.
   */
  public final float getHeight() {
    return height;
  }

  /**
   * Sets how large the video is drawn on screen, in pixels.
   *
   * <p>This is independent of the decode {@link #setQuality(FlixelVideoQuality) quality}:
   * a HALF-quality video stretched to full screen simply looks softer.
   *
   * @param width Drawn width in pixels ({@code 0} = decoded frame width).
   * @param height Drawn height in pixels ({@code 0} = decoded frame height).
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setSize(float width, float height) {
    this.width = width;
    this.height = height;
    return this;
  }

  /**
   * Returns the horizontal parallax factor ({@code 1} = follows the camera fully).
   *
   * @return Horizontal scroll factor.
   */
  public final float getScrollX() {
    return scrollX;
  }

  /**
   * Returns the vertical parallax factor ({@code 1} = follows the camera fully).
   *
   * @return Vertical scroll factor.
   */
  public final float getScrollY() {
    return scrollY;
  }

  /**
   * Sets the parallax factors, same contract as sprites. Use {@code 0, 0} to pin the
   * video to the screen regardless of camera scroll (typical for cutscenes).
   *
   * @param scrollX Horizontal scroll factor.
   * @param scrollY Vertical scroll factor.
   * @return {@code this} for chaining.
   */
  @NotNull
  public final FlixelVideo setScrollFactor(float scrollX, float scrollY) {
    this.scrollX = scrollX;
    this.scrollY = scrollY;
    return this;
  }
}
