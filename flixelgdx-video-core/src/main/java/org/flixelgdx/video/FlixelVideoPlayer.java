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

import org.flixelgdx.graphics.FlixelTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The platform backend contract that decodes and plays one video.
 *
 * <p>Game code never talks to a player directly. It uses {@link FlixelVideo}, which owns one
 * player and adds the game-facing behavior (signals, drawing, auto-pause, and so on). Each platform
 * module ships its own implementation (libvlc on desktop, a browser video element on the web, and
 * the Android media stack on Android) and returns it from a {@link FlixelVideoFactory}.
 *
 * <p>Every method is called on the render thread. A player that decodes on another thread must
 * hand its results over safely inside {@link #update(float)}.
 *
 * <p>The player owns the texture that holds the latest frame and returns it from
 * {@link #getFrame()}. The framework batch draws it like any normal texture, so a backend is free to
 * fill it from CPU pixels (see {@link FlixelVideoCpuFrame}) or entirely on the GPU.
 */
public interface FlixelVideoPlayer {

  /** Starts (or restarts) the underlying media player. */
  void play();

  /** Pauses the underlying media player at the current position. */
  void pause();

  /** Resumes the underlying media player after a pause. */
  void resume();

  /** Stops the underlying media player and resets its position. */
  void stop();

  /**
   * Returns whether the underlying player is actively playing.
   *
   * @return {@code true} while playing.
   */
  boolean isPlaying();

  /**
   * Returns whether the underlying player has decoded its first frame.
   *
   * @return {@code true} once the stream is ready.
   */
  boolean isReady();

  /**
   * Returns whether a non-looping stream has reached its end.
   *
   * @return {@code true} once the stream finished playing.
   */
  boolean isEnded();

  /**
   * Returns the current playback position in milliseconds.
   *
   * @return Playback position in milliseconds.
   */
  float getTime();

  /**
   * Seeks to the given playback position.
   *
   * @param timeMs Target position in milliseconds.
   */
  void setTime(float timeMs);

  /**
   * Returns the total duration of the media.
   *
   * @return Duration in milliseconds, or {@code 0} if not yet known.
   */
  float getLength();

  /**
   * Returns the current playback speed multiplier.
   *
   * @return Speed multiplier; {@code 1} is normal.
   */
  float getRate();

  /**
   * Sets the playback speed multiplier.
   *
   * @param rate Speed multiplier; must be greater than {@code 0}.
   */
  void setRate(float rate);

  /**
   * Returns whether the media is set to loop automatically.
   *
   * @return {@code true} if looping is enabled.
   */
  boolean isLooped();

  /**
   * Enables or disables automatic looping.
   *
   * @param looped {@code true} to loop, {@code false} to play once.
   */
  void setLooped(boolean looped);

  /**
   * Returns the current audio volume.
   *
   * @return Volume in {@code [0, 1]}.
   */
  float getVolume();

  /**
   * Sets the audio volume.
   *
   * @param volume Volume in {@code [0, 1]}; implementations must clamp out-of-range values.
   */
  void setVolume(float volume);

  /**
   * Applies a decode quality preset to the underlying player.
   *
   * @param quality The preset to apply.
   */
  void setQuality(@NotNull FlixelVideoQuality quality);

  /**
   * Per-frame pump; advances player state and refreshes the frame texture.
   *
   * <p>{@link FlixelVideo#update(float)} calls this once per frame after its lifecycle checks.
   * Implementations must not allocate here.
   *
   * @param elapsed Seconds since the last frame.
   */
  void update(float elapsed);

  /**
   * Returns the texture that holds the latest decoded frame.
   *
   * <p>The player owns this texture and destroys it in {@link #destroy()}. The instance may be
   * replaced (for example when the frame size changes), so callers should fetch it again each
   * time they draw instead of caching it.
   *
   * @return The frame texture, or {@code null} until the first frame is available.
   */
  @Nullable
  FlixelTexture getFrame();

  /**
   * Returns the visible picture width in pixels.
   *
   * <p>This can be smaller than the frame texture width (for example, codec padding). The caller
   * crops the sampled region to {@code frameWidth / texture.getWidth()}.
   *
   * @return Visible frame width, or {@code 0} until the player is ready.
   */
  int getFrameWidth();

  /**
   * Returns the visible picture height in pixels.
   *
   * <p>This can be smaller than the frame texture height (for example, codec padding). The caller
   * crops the sampled region to {@code frameHeight / texture.getHeight()}.
   *
   * @return Visible frame height, or {@code 0} until the player is ready.
   */
  int getFrameHeight();

  /** Releases every native resource and the frame texture held by this player. */
  void destroy();
}
