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
package org.flixelgdx.backend.desktop.video;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import org.flixelgdx.Flixel;
import org.flixelgdx.graphics.FlixelImage;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.logging.FlixelLogger;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoCpuFrame;
import org.flixelgdx.video.FlixelVideoPlayer;
import org.flixelgdx.video.FlixelVideoQuality;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Desktop video player that decodes through libvlc's in-memory video callbacks.
 *
 * <p>libvlc never owns a window here. The format callback negotiates an RGBA buffer
 * (optionally downscaled for {@link FlixelVideoQuality}), the lock callback hands
 * libvlc one of two pre-allocated native pixel buffers to decode into, and the display
 * callback flips a dirty flag. Audio decoding and output stay entirely inside libvlc.
 *
 * <p>On the render thread, {@link #update(float)} copies the latest completed frame from the
 * native decode buffer into the reusable image of a {@link FlixelVideoCpuFrame} and uploads it to
 * the frame texture. This player never touches the graphics API directly; the same code path
 * uploads frames on every renderer. The two native buffers, the pixel views over them, and the
 * frame image are all allocated once per format and reused, so the per-frame path allocates
 * nothing.
 *
 * <p>Game code does not use this class directly; {@link FlixelVideo} wraps it.
 *
 * <p>Threading contract: every public method here is called on the render thread. The inner
 * callbacks run on libvlc decoder threads and only touch the shared frame buffers under
 * {@code bufferLock} plus a handful of volatile flags.
 */
public final class FlixelVlcPlayer implements FlixelVideoPlayer {

  private static final FlixelLogger LOG = Flixel.log.tagged("FlixelVideo");

  /** Shared initializer that keeps libvlc's native callback threads attached to the JVM as daemons. */
  private static final CallbackThreadInitializer THREAD_INIT = new CallbackThreadInitializer(true, false, "flixel-vlc");

  /** Protects the frame buffer swap between the libvlc thread and the render thread. */
  private final Object bufferLock = new Object();

  /** Native pixel buffers libvlc decodes into; index flipped on every displayed frame. */
  private final Memory[] frameBuffers = new Memory[2];

  /** Direct views over {@link #frameBuffers}, reused for the per-frame copy. */
  private final ByteBuffer[] frameViews = new ByteBuffer[2];

  /** Reused out-parameters for libvlc_video_get_size (avoids per-frame allocation). */
  private final IntByReference sizeWidthRef = new IntByReference();

  private final IntByReference sizeHeightRef = new IntByReference();

  /** Owns the reusable CPU image and the GPU frame texture. */
  private final FlixelVideoCpuFrame cpuFrame = new FlixelVideoCpuFrame();

  // Strong references keep the JNA callback trampolines alive while libvlc holds them.
  private final LibVlc.LockCallback lockCallback;
  private final LibVlc.DisplayCallback displayCallback;
  private final LibVlc.FormatCallback formatCallback;
  private final LibVlc.EventCallback eventCallback;

  private Pointer mediaPlayer;
  private Pointer eventManager;

  @NotNull
  private volatile FlixelVideoQuality mediaQuality = FlixelVideoQuality.FULL;

  /** Decoded frame width in pixels, written by the format callback. */
  private volatile int frameWidth;

  /** Decoded frame height in pixels, written by the format callback. */
  private volatile int frameHeight;

  /** Buffer dimensions libvlc originally proposed, before quality scaling. */
  private volatile int setupSourceWidth;

  private volatile int setupSourceHeight;

  /**
   * Codec buffers carry alignment padding rows below the visible picture (a 1080p
   * H.264 stream decodes into a 1088 or 1090 row buffer). These are the dimensions of
   * the real picture inside the frame, derived from libvlc_video_get_size(...).
   * Render thread only.
   */
  private int visibleWidth;

  private int visibleHeight;

  /**
   * Native (unscaled) display size reported by libvlc_video_get_size(...), cached so the getters
   * never call into libvlc. Render thread only.
   */
  private int nativeWidth;

  private int nativeHeight;

  /** Frame dimensions the visible size was computed for, to detect format changes. */
  private int visibleBasisWidth;

  private int visibleBasisHeight;

  /** Which frame buffer libvlc writes into next. Guarded by {@link #bufferLock}. */
  private int writeIndex;

  /** Which frame buffer holds the latest displayed frame. Guarded by {@link #bufferLock}. */
  private int readyIndex = -1;

  /** Size in bytes of the current frame buffers. */
  private int frameBytes;

  private float desiredVolume = 1f;
  private float desiredRate = 1f;

  /** Seek queued until the player actually reaches a seekable state. -1 = none. */
  private float pendingSeekMs = -1f;

  /** Set by the display callback when a new frame is ready to copy. */
  private volatile boolean frameDirty;

  /** Set by the end-reached event; consumed on the render thread. */
  private volatile boolean endReached;

  /** Set by the error event. */
  private volatile boolean playbackError;

  /** Sticky end state reported by {@link #isEnded()} for non-looping playback. */
  private boolean ended;

  private boolean looping;

  /** The playback rate is re-pushed once per (re)start when the player is live. */
  private boolean settingsApplied;

  private boolean disposed;

  /**
   * Creates a media player for the given file and wires all libvlc callbacks.
   *
   * @param instance The shared libvlc instance.
   * @param path Absolute path of the video file to open.
   * @throws IllegalStateException If libvlc cannot open the media.
   */
  public FlixelVlcPlayer(@NotNull Pointer instance, @NotNull String path) {
    Pointer media = LibVlc.libvlc_media_new_path(instance, path);
    if (media == null) {
      throw new IllegalStateException("libvlc could not open media: " + path);
    }
    mediaPlayer = LibVlc.libvlc_media_player_new_from_media(media);
    LibVlc.libvlc_media_release(media);
    if (mediaPlayer == null) {
      throw new IllegalStateException("libvlc could not create a media player for: " + path);
    }

    lockCallback = this::onLock;
    displayCallback = this::onDisplay;
    formatCallback = this::onFormat;
    eventCallback = this::onEvent;

    // Without an initializer JNA attaches and detaches the native thread on every callback, which
    // allocates a new Thread, name, and TLAB each time. Keeping the thread attached (detach = false)
    // reuses one Java Thread per libvlc thread. The threads are daemons owned by libvlc, so nothing
    // needs to be released on destroy. Unlock and cleanup are optional in libvlc 3.x, so pass null.
    Native.setCallbackThreadInitializer(lockCallback, THREAD_INIT);
    Native.setCallbackThreadInitializer(displayCallback, THREAD_INIT);
    Native.setCallbackThreadInitializer(formatCallback, THREAD_INIT);
    Native.setCallbackThreadInitializer(eventCallback, THREAD_INIT);

    LibVlc.libvlc_video_set_format_callbacks(mediaPlayer, formatCallback, null);
    LibVlc.libvlc_video_set_callbacks(mediaPlayer, lockCallback, null, displayCallback, null);

    eventManager = LibVlc.libvlc_media_player_event_manager(mediaPlayer);
    LibVlc.libvlc_event_attach(eventManager, LibVlc.EVENT_END_REACHED, eventCallback, null);
    LibVlc.libvlc_event_attach(eventManager, LibVlc.EVENT_ENCOUNTERED_ERROR, eventCallback, null);
  }

  @Override
  public void play() {
    if (disposed) {
      return;
    }
    if (LibVlc.libvlc_media_player_get_state(mediaPlayer) == LibVlc.STATE_ENDED) {
      // libvlc 3 refuses to replay from the Ended state until the player is stopped.
      LibVlc.libvlc_media_player_stop(mediaPlayer);
    }
    ended = false;
    endReached = false;
    settingsApplied = false;
    LibVlc.libvlc_media_player_play(mediaPlayer);
  }

  @Override
  public void pause() {
    if (disposed) {
      return;
    }
    LibVlc.libvlc_media_player_set_pause(mediaPlayer, 1);
  }

  @Override
  public void resume() {
    if (disposed) {
      return;
    }
    LibVlc.libvlc_media_player_set_pause(mediaPlayer, 0);
  }

  @Override
  public void stop() {
    if (disposed) {
      return;
    }
    ended = false;
    endReached = false;
    pendingSeekMs = -1f;
    LibVlc.libvlc_media_player_stop(mediaPlayer);
  }

  @Override
  public boolean isPlaying() {
    return !disposed && LibVlc.libvlc_media_player_get_state(mediaPlayer) == LibVlc.STATE_PLAYING;
  }

  @Override
  public boolean isEnded() {
    if (disposed) {
      return false;
    }
    return ended || LibVlc.libvlc_media_player_get_state(mediaPlayer) == LibVlc.STATE_ENDED;
  }

  @Override
  public float getTime() {
    if (disposed) {
      return 0f;
    }
    if (pendingSeekMs >= 0f) {
      return pendingSeekMs;
    }
    long time = LibVlc.libvlc_media_player_get_time(mediaPlayer);
    return time < 0 ? 0f : time;
  }

  @Override
  public void setTime(float timeMs) {
    if (disposed) {
      return;
    }
    float target = Math.max(0f, timeMs);
    int state = LibVlc.libvlc_media_player_get_state(mediaPlayer);
    if (state == LibVlc.STATE_PLAYING || state == LibVlc.STATE_PAUSED) {
      LibVlc.libvlc_media_player_set_time(mediaPlayer, (long) target);
      pendingSeekMs = -1f;
    } else {
      // The player is still opening (or stopped); apply once it is actually running.
      pendingSeekMs = target;
    }
    ended = false;
    endReached = false;
  }

  @Override
  public float getLength() {
    if (disposed) {
      return 0f;
    }
    long length = LibVlc.libvlc_media_player_get_length(mediaPlayer);
    return length < 0 ? 0f : length;
  }

  @Override
  public void setRate(float rate) {
    if (disposed || rate <= 0f) {
      return;
    }
    desiredRate = rate;
    LibVlc.libvlc_media_player_set_rate(mediaPlayer, rate);
  }

  @Override
  public void setVolume(float volume) {
    desiredVolume = Math.max(0f, Math.min(1f, volume));
    if (!disposed) {
      LibVlc.libvlc_audio_set_volume(mediaPlayer, (int) (desiredVolume * 100f));
    }
  }

  @Override
  public void setQuality(@NotNull FlixelVideoQuality quality) {
    if (disposed || this.mediaQuality == quality) {
      return;
    }
    this.mediaQuality = quality;
    int state = LibVlc.libvlc_media_player_get_state(mediaPlayer);
    if (state == LibVlc.STATE_PLAYING || state == LibVlc.STATE_PAUSED) {
      // The vmem format is negotiated at playback start, so rebuild the pipeline in
      // place: remember where we were, restart, and seek back.
      float resumeAt = getTime();
      boolean wasPaused = state == LibVlc.STATE_PAUSED;
      LibVlc.libvlc_media_player_stop(mediaPlayer);
      play();
      pendingSeekMs = resumeAt;
      if (wasPaused) {
        // Let the pipeline restart and produce the frame, then re-pause on the next pump.
        LibVlc.libvlc_media_player_set_pause(mediaPlayer, 1);
      }
    }
  }

  @Override
  public void update(float elapsed) {
    if (disposed) {
      return;
    }

    if (playbackError) {
      playbackError = false;
      LOG.error("libvlc reported a playback error; the video was stopped.");
    }

    if (endReached) {
      endReached = false;
      if (looping) {
        // libvlc 3 parks the player in the Ended state; restart from the render thread
        // (never from the event thread, which libvlc forbids re-entering).
        LibVlc.libvlc_media_player_stop(mediaPlayer);
        play();
      } else {
        ended = true;
      }
    }

    int state = LibVlc.libvlc_media_player_get_state(mediaPlayer);
    if (state == LibVlc.STATE_PLAYING) {
      if (pendingSeekMs >= 0f) {
        LibVlc.libvlc_media_player_set_time(mediaPlayer, (long) pendingSeekMs);
        pendingSeekMs = -1f;
      }
      // Each player re-asserts its own volume every frame while it is live. Guarding
      // this with libvlc_audio_get_volume(...) is not enough: that call returns the
      // value libvlc last stored, not the real output gain, so when an audio server
      // restores a different stream's volume onto this one (PulseAudio restores the
      // application's most recent stream) the guard sees no change and never corrects
      // it, and two videos end up sharing a volume. Pushing the value unconditionally
      // is a cheap idempotent native call that keeps every player pinned to its own
      // volume, which is what stops simultaneous videos from mixing their levels up.
      LibVlc.libvlc_audio_set_volume(mediaPlayer, (int) (desiredVolume * 100f));
      if (!settingsApplied) {
        settingsApplied = true;
        if (desiredRate != 1f) {
          LibVlc.libvlc_media_player_set_rate(mediaPlayer, desiredRate);
        }
      }
    }

    if (frameDirty) {
      uploadLatestFrame();
    }
  }

  @Override
  public void destroy() {
    if (disposed) {
      return;
    }
    disposed = true;
    LibVlc.libvlc_event_detach(eventManager, LibVlc.EVENT_END_REACHED, eventCallback, null);
    LibVlc.libvlc_event_detach(eventManager, LibVlc.EVENT_ENCOUNTERED_ERROR, eventCallback, null);
    LibVlc.libvlc_media_player_stop(mediaPlayer);
    LibVlc.libvlc_media_player_release(mediaPlayer);
    mediaPlayer = null;
    eventManager = null;
    synchronized (bufferLock) {
      frameBuffers[0] = null;
      frameBuffers[1] = null;
      frameViews[0] = null;
      frameViews[1] = null;
      readyIndex = -1;
    }
    cpuFrame.destroy();
  }

  /**
   * Copies the most recent completed frame into the reusable image and uploads it.
   *
   * <p>The native-to-image copy runs under {@code bufferLock} so the dimensions, byte count, and
   * frame contents stay consistent even if libvlc renegotiates the format mid-play; if libvlc wants
   * to swap buffers meanwhile it briefly waits, which beats copying a torn frame. The texture
   * upload itself happens outside the lock.
   */
  private void uploadLatestFrame() {
    int width = frameWidth;
    int height = frameHeight;
    if (width <= 0 || height <= 0) {
      return;
    }
    // The image and texture may be (re)created here, so keep the GPU work outside the lock.
    FlixelImage image = cpuFrame.prepare(width, height);
    synchronized (bufferLock) {
      if (width != frameWidth || height != frameHeight || readyIndex < 0) {
        // libvlc renegotiated the format meanwhile; the frame stays dirty for the next pump.
        return;
      }
      ByteBuffer source = frameViews[readyIndex];
      source.clear();
      ByteBuffer dest = image.getPixels();
      dest.clear();
      dest.put(source);
      frameDirty = false;
    }
    cpuFrame.upload();
    updateVisibleSize();
  }

  /**
   * Derives the visible picture size inside the (padding-aligned) frame buffer.
   *
   * <p>libvlc reports the true display resolution through libvlc_video_get_size(...);
   * scaling it by the ratio between our decode buffer and the source buffer maps it
   * into frame pixels, so draw code can crop away the codec padding rows.
   */
  private void updateVisibleSize() {
    int width = frameWidth;
    int height = frameHeight;
    if (width <= 0 || height <= 0) {
      return;
    }
    if (visibleWidth > 0 && width == visibleBasisWidth && height == visibleBasisHeight) {
      return;
    }
    if (LibVlc.libvlc_video_get_size(mediaPlayer, 0, sizeWidthRef, sizeHeightRef) != 0) {
      return;
    }
    int displayWidth = sizeWidthRef.getValue();
    int displayHeight = sizeHeightRef.getValue();
    int sourceWidth = setupSourceWidth;
    int sourceHeight = setupSourceHeight;
    if (displayWidth <= 0 || displayHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
      return;
    }
    nativeWidth = displayWidth;
    nativeHeight = displayHeight;
    visibleWidth = Math.min(width, Math.round(width * (displayWidth / (float) sourceWidth)));
    visibleHeight = Math.min(height, Math.round(height * (displayHeight / (float) sourceHeight)));
    visibleBasisWidth = width;
    visibleBasisHeight = height;
  }

  /** libvlc format negotiation; runs on a libvlc thread. */
  private int onFormat(PointerByReference opaque, Pointer chroma, IntByReference width,
      IntByReference height, Pointer pitches, Pointer lines) {
    int sourceWidth = width.getValue();
    int sourceHeight = height.getValue();
    setupSourceWidth = sourceWidth;
    setupSourceHeight = sourceHeight;
    float scale = mediaQuality.getScale();
    // Even dimensions keep chroma subsampled sources (which is nearly all of them) happy.
    int decodeWidth = Math.max(2, ((int) (sourceWidth * scale)) & ~1);
    int decodeHeight = Math.max(2, ((int) (sourceHeight * scale)) & ~1);

    chroma.setByte(0, (byte) 'R');
    chroma.setByte(1, (byte) 'G');
    chroma.setByte(2, (byte) 'B');
    chroma.setByte(3, (byte) 'A');
    width.setValue(decodeWidth);
    height.setValue(decodeHeight);
    pitches.setInt(0, decodeWidth * 4);
    lines.setInt(0, decodeHeight);

    int bytes = decodeWidth * 4 * decodeHeight;
    synchronized (bufferLock) {
      if (frameBuffers[0] == null || frameBytes != bytes) {
        frameBuffers[0] = new Memory(bytes);
        frameBuffers[1] = new Memory(bytes);
        frameViews[0] = frameBuffers[0].getByteBuffer(0, bytes);
        frameViews[1] = frameBuffers[1].getByteBuffer(0, bytes);
        frameBytes = bytes;
        writeIndex = 0;
        readyIndex = -1;
      }
      frameWidth = decodeWidth;
      frameHeight = decodeHeight;
    }
    return 1;
  }

  /** libvlc asks for the buffer to decode the next frame into; runs on a libvlc thread. */
  private Pointer onLock(Pointer opaque, Pointer planes) {
    synchronized (bufferLock) {
      Memory buffer = frameBuffers[writeIndex];
      planes.setPointer(0, buffer);
    }
    return null;
  }

  /** A decoded frame is ready to be shown; runs on a libvlc thread. */
  private void onDisplay(Pointer opaque, Pointer picture) {
    synchronized (bufferLock) {
      readyIndex = writeIndex;
      writeIndex ^= 1;
      frameDirty = true;
    }
  }

  /** Player events; runs on the libvlc event thread, so it only flips flags. */
  private void onEvent(Pointer event, Pointer userData) {
    int type = event.getInt(0);
    if (type == LibVlc.EVENT_END_REACHED) {
      endReached = true;
    } else if (type == LibVlc.EVENT_ENCOUNTERED_ERROR) {
      playbackError = true;
      endReached = true;
    }
  }

  @Override
  public boolean isReady() {
    return cpuFrame.isReady();
  }

  @Override
  public float getRate() {
    return desiredRate;
  }

  @Override
  public boolean isLooped() {
    return looping;
  }

  @Override
  public void setLooped(boolean looped) {
    this.looping = looped;
  }

  @Override
  public float getVolume() {
    return desiredVolume;
  }

  @Nullable
  @Override
  public FlixelTexture getFrame() {
    return cpuFrame.getTexture();
  }

  @Override
  public int getVideoWidth() {
    return nativeWidth > 0 ? nativeWidth : setupSourceWidth;
  }

  @Override
  public int getVideoHeight() {
    return nativeHeight > 0 ? nativeHeight : setupSourceHeight;
  }

  @Override
  public int getFrameWidth() {
    return visibleWidth > 0 ? visibleWidth : frameWidth;
  }

  @Override
  public int getFrameHeight() {
    return visibleHeight > 0 ? visibleHeight : frameHeight;
  }
}
