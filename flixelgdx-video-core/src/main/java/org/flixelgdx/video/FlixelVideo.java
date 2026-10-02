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
import org.flixelgdx.FlixelState;
import org.flixelgdx.audio.FlixelSound;
import org.flixelgdx.file.FlixelFile;
import org.flixelgdx.graphics.FlixelBatch;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.signal.FlixelSignal;
import org.flixelgdx.signal.FlixelSignal.SignalHandler;
import org.flixelgdx.util.FlixelAxes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A video that plays inside your game, drawn like any other object in a state.
 *
 * <p>{@code FlixelVideo} extends {@link FlixelBasic}, so it carries the normal lifecycle
 * flags, can be pooled, and can be added straight to a
 * {@link FlixelState}. Draw order follows state member order:
 * a sprite added after the video renders on top of it, exactly as with two sprites.
 *
 * <p>This class is the game-facing half of a composition design. It does not decode anything
 * itself. Instead it holds a {@link FlixelVideoPlayer}, the platform backend that decodes the
 * file and owns the texture with the latest frame, and adds everything games care about on top:
 * signals, positioning, drawing, auto-pause, and the completion event. Because the player only
 * has to expose a texture, a backend may fill it from CPU pixels or entirely on the GPU.
 *
 * <p>Create instances through {@link FlixelVideos#create(FlixelFile)}, which picks the
 * platform player registered by your launcher:
 *
 * <pre>{@code
 * FlixelVideo cutscene = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
 * cutscene.setSize(Flixel.getDesignWidth(), Flixel.getDesignHeight());
 * cutscene.setLooped(false);
 * cutscene.onComplete.add(data -> Flixel.switchState(() -> new MenuState()));
 * add(cutscene);
 * cutscene.play();
 * }</pre>
 *
 * <p>All time values are in milliseconds, matching
 * {@link FlixelSound}. Call {@link #destroy()} when the video leaves the game for good to
 * release the decoder and the frame texture.
 *
 * <p>The video pauses automatically when the window loses focus or the application is sent to the
 * background (the same {@link Flixel.Signals#windowUnfocused} and {@link Flixel.Signals#windowFocused}
 * events the framework pauses audio on) while {@link Flixel#autoPause} is {@code true}, and resumes
 * when focus returns. Auto-pause talks to the player directly, so it does not fire
 * {@link #onPause} or {@link #onResume}.
 *
 * <p>Advanced code that needs a backend-specific feature can reach the player through
 * {@link #getPlayer()}.
 *
 * @see FlixelVideos
 * @see FlixelVideoPlayer
 */
public final class FlixelVideo extends FlixelBasic {

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

  @NotNull
  private final FlixelVideoPlayer player;

  @NotNull
  private FlixelVideoQuality quality = FlixelVideoQuality.FULL;

  /**
   * Pauses the video when the window loses focus (or the app is backgrounded), so it lines up with
   * the framework pausing audio. Kept as a field so it can be unregistered in {@link #destroy()}.
   */
  private final SignalHandler<Void> onWindowUnfocused;

  /** Resumes the video when focus returns, undoing {@link #onWindowUnfocused}. */
  private final SignalHandler<Void> onWindowFocused;

  /** When {@code true}, {@link #destroy()} is called automatically when playback completes. */
  private boolean autoDestroy;

  /** Guards {@link #onComplete} so the end of a video is only announced once. */
  private boolean completed;

  /**
   * Set when this video was paused automatically by the focus hook so it can be correctly resumed
   * when the application returns to the foreground.
   */
  private boolean autoPaused;

  /**
   * Creates a video that is driven by the given platform player.
   *
   * <p>Most games should call {@link FlixelVideos#create(FlixelFile)} instead, which builds the
   * right player for the current platform.
   *
   * @param player The platform player this video controls and draws; the video destroys it in
   *     {@link #destroy()}.
   * @throws IllegalArgumentException If {@code player} is {@code null}.
   */
  public FlixelVideo(@NotNull FlixelVideoPlayer player) {
    super();
    if (player == null) {
      throw new IllegalArgumentException("Video player cannot be null.");
    }
    this.player = player;
    onWindowUnfocused = data -> {
      if (Flixel.autoPause && !autoPaused && player.isPlaying()) {
        player.pause();
        autoPaused = true;
      }
    };
    onWindowFocused = data -> {
      if (autoPaused) {
        autoPaused = false;
        player.resume();
      }
    };
    Flixel.Signals.windowUnfocused.add(onWindowUnfocused);
    Flixel.Signals.windowFocused.add(onWindowFocused);
  }

  /**
   * Plays the video from the beginning.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo play() {
    return play(true, 0f);
  }

  /**
   * Plays the video.
   *
   * @param forceRestart Should the video restart from the beginning if it is already playing?
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo play(boolean forceRestart) {
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
  public FlixelVideo play(boolean forceRestart, float startTimeMs) {
    completed = false;
    player.play();
    if (forceRestart) {
      player.setTime(startTimeMs);
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
  public FlixelVideo pause() {
    player.pause();
    onPause.dispatch();
    return this;
  }

  /**
   * Resumes from the current position after a pause.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo resume() {
    player.resume();
    onResume.dispatch();
    return this;
  }

  /**
   * Stops the video and resets the position to the beginning.
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo stop() {
    completed = false;
    player.stop();
    return this;
  }

  /**
   * Returns whether the video is currently playing.
   *
   * @return {@code true} if the video is actively playing.
   */
  public boolean isPlaying() {
    return player.isPlaying();
  }

  /**
   * Returns whether the video has finished decoding its metadata and produced a frame.
   *
   * <p>Width, height, and length are {@code 0} until this returns {@code true}, which
   * happens shortly after the first {@link #play()}.
   *
   * @return {@code true} once the video is ready to display.
   */
  public boolean isReady() {
    return player.isReady();
  }

  /**
   * Centers this video on the screen on both axes.
   *
   * <p>The centering uses the drawn size (see {@link #getDrawWidth()}). Before the first frame
   * arrives the video size is {@code 0}, so a video drawn at its native size should be centered
   * again once {@link #isReady()} is {@code true} (or simply every frame).
   *
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo screenCenter() {
    return screenCenter(FlixelAxes.XY);
  }

  /**
   * Centers this video on the screen along the given axes.
   *
   * <p>The centering uses the drawn size (see {@link #getDrawWidth()}). Before the first frame
   * arrives the video size is {@code 0}, so a video drawn at its native size should be centered
   * again once {@link #isReady()} is {@code true} (or simply every frame).
   *
   * @param axes The axes to center on.
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo screenCenter(@NotNull FlixelAxes axes) {
    float halfWidth = getDrawWidth() / 2f;
    float halfHeight = getDrawHeight() / 2f;
    float halfViewWidth = Flixel.getVisibleWidth() / 2f;
    float halfViewHeight = Flixel.getVisibleHeight() / 2f;
    switch (axes) {
      case X -> x = halfViewWidth - halfWidth;
      case Y -> y = halfViewHeight - halfHeight;
      case XY -> {
        x = halfViewWidth - halfWidth;
        y = halfViewHeight - halfHeight;
      }
    }
    return this;
  }

  @Override
  public void update(float elapsed) {
    if (!active || !exists) {
      return;
    }

    player.update(elapsed);

    if (!completed && player.isEnded() && !player.isLooped()) {
      completed = true;
      onComplete.dispatch();
      if (autoDestroy) {
        destroy();
      }
    }
  }

  @Override
  public void draw(@NotNull FlixelBatch batch) {
    if (!visible || !exists || !player.isReady()) {
      return;
    }
    FlixelTexture tex = player.getFrame();
    if (tex == null || !isOnDrawCamera()) {
      return;
    }

    int videoW = player.getFrameWidth();
    int videoH = player.getFrameHeight();
    float drawW = getDrawWidth();
    float drawH = getDrawHeight();
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
    player.destroy();
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
   * Returns the platform player that backs this video.
   *
   * <p>Meant for advanced use, such as reaching a backend-specific feature. Prefer the methods on
   * this class for everything else; in particular, do not call {@link FlixelVideoPlayer#destroy()}
   * yourself, use {@link #destroy()} instead.
   *
   * @return The player; never {@code null}.
   */
  @NotNull
  public FlixelVideoPlayer getPlayer() {
    return player;
  }

  /**
   * Returns the texture that holds the current video frame.
   *
   * <p>Useful when you want to feed the video into your own drawing code instead of
   * relying on the default draw. The player owns this texture; do not destroy it
   * yourself.
   *
   * @return The frame texture, or {@code null} before the first frame arrives.
   */
  @Nullable
  public FlixelTexture getTexture() {
    return player.isReady() ? player.getFrame() : null;
  }

  /**
   * Returns the current playback position in milliseconds.
   *
   * @return Playback position in milliseconds.
   */
  public float getTime() {
    return player.getTime();
  }

  /**
   * Sets the playback position in milliseconds.
   *
   * @param timeMs The time to seek to, in milliseconds.
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo setTime(float timeMs) {
    completed = false;
    player.setTime(timeMs);
    return this;
  }

  /**
   * Returns the total length of the video in milliseconds.
   *
   * @return Duration in milliseconds, or {@code 0} if not yet known.
   */
  public float getLength() {
    return player.getLength();
  }

  /**
   * Returns the playback speed multiplier.
   *
   * @return Speed multiplier; {@code 1} is normal speed.
   */
  public float getRate() {
    return player.getRate();
  }

  /**
   * Sets the playback speed multiplier.
   *
   * @param rate Speed multiplier; must be greater than {@code 0}. {@code 1} is normal,
   *     {@code 2} is double speed, {@code 0.5} is half speed.
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo setRate(float rate) {
    player.setRate(rate);
    return this;
  }

  /**
   * Returns whether this video is set to loop.
   *
   * @return {@code true} if looping is enabled.
   */
  public boolean isLooped() {
    return player.isLooped();
  }

  /**
   * Enables or disables looping.
   *
   * @param looped {@code true} to loop, {@code false} to play once.
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo setLooped(boolean looped) {
    player.setLooped(looped);
    return this;
  }

  /**
   * Returns the audio volume of this video.
   *
   * @return Volume in {@code [0, 1]}.
   */
  public float getVolume() {
    return player.getVolume();
  }

  /**
   * Sets the audio volume of this video.
   *
   * @param volume Volume in {@code [0, 1]}; values outside the range are clamped.
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo setVolume(float volume) {
    player.setVolume(volume);
    return this;
  }

  /**
   * Returns the current decode quality preset.
   *
   * @return The active quality preset.
   */
  @NotNull
  public FlixelVideoQuality getQuality() {
    return quality;
  }

  /**
   * Sets the decode quality preset.
   *
   * <p>How quickly the change applies depends on the platform backend. On the web it applies
   * immediately. On desktop the decoder pipeline is rebuilt, so the change takes effect when
   * playback starts or restarts; the desktop backend restarts a playing video automatically and
   * seeks back to where it was.
   *
   * @param quality The preset to apply (must not be {@code null}).
   * @return {@code this} for chaining.
   * @throws IllegalArgumentException If {@code quality} is {@code null}.
   */
  @NotNull
  public FlixelVideo setQuality(@NotNull FlixelVideoQuality quality) {
    if (quality == null) {
      throw new IllegalArgumentException("Video quality cannot be null.");
    }
    this.quality = quality;
    player.setQuality(quality);
    return this;
  }

  /**
   * Returns the native width of the video in pixels.
   *
   * <p>This is the resolution of the source file and does not change with the
   * {@link #setQuality(FlixelVideoQuality) quality}.
   *
   * @return Native video width, or {@code 0} while the video is not yet ready.
   */
  public int getVideoWidth() {
    return player.getVideoWidth();
  }

  /**
   * Returns the native height of the video in pixels.
   *
   * <p>This is the resolution of the source file and does not change with the
   * {@link #setQuality(FlixelVideoQuality) quality}.
   *
   * @return Native video height, or {@code 0} while the video is not yet ready.
   */
  public int getVideoHeight() {
    return player.getVideoHeight();
  }

  /**
   * Returns whether this video auto-destroys when playback completes.
   *
   * @return {@code true} if auto-destroy is enabled.
   */
  public boolean isAutoDestroy() {
    return autoDestroy;
  }

  /**
   * Sets whether this video auto-destroys when playback completes.
   *
   * @param autoDestroy {@code true} to destroy this video when it finishes.
   * @return {@code this} for chaining.
   */
  @NotNull
  public FlixelVideo setAutoDestroy(boolean autoDestroy) {
    this.autoDestroy = autoDestroy;
    return this;
  }

  /**
   * Returns the world X position of the video's top-left corner.
   *
   * @return World X position.
   */
  public float getX() {
    return x;
  }

  /**
   * Returns the world Y position of the video.
   *
   * @return World Y position.
   */
  public float getY() {
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
  public FlixelVideo setPosition(float x, float y) {
    this.x = x;
    this.y = y;
    return this;
  }

  /**
   * Returns the drawn width in pixels ({@code 0} means the decoded frame width is used).
   *
   * @return Drawn width in pixels.
   */
  public float getWidth() {
    return width;
  }

  /**
   * Returns the drawn height in pixels ({@code 0} means the decoded frame height is used).
   *
   * @return Drawn height in pixels.
   */
  public float getHeight() {
    return height;
  }

  /**
   * Returns the width the video is actually drawn at.
   *
   * @return {@link #width} if it is greater than {@code 0}, otherwise the native video width
   *     (which is {@code 0} until the video is ready).
   */
  public float getDrawWidth() {
    return width > 0f ? width : player.getVideoWidth();
  }

  /**
   * Returns the height the video is actually drawn at.
   *
   * @return {@link #height} if it is greater than {@code 0}, otherwise the native video height
   *     (which is {@code 0} until the video is ready).
   */
  public float getDrawHeight() {
    return height > 0f ? height : player.getVideoHeight();
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
  public FlixelVideo setSize(float width, float height) {
    this.width = width;
    this.height = height;
    return this;
  }

  /**
   * Returns the horizontal parallax factor ({@code 1} = follows the camera fully).
   *
   * @return Horizontal scroll factor.
   */
  public float getScrollX() {
    return scrollX;
  }

  /**
   * Returns the vertical parallax factor ({@code 1} = follows the camera fully).
   *
   * @return Vertical scroll factor.
   */
  public float getScrollY() {
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
  public FlixelVideo setScrollFactor(float scrollX, float scrollY) {
    this.scrollX = scrollX;
    this.scrollY = scrollY;
    return this;
  }
}
