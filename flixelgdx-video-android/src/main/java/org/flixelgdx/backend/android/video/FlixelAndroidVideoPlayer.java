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
package org.flixelgdx.backend.android.video;

import org.flixelgdx.Flixel;
import org.flixelgdx.backend.gpu.FlixelGpuGraphics;
import org.flixelgdx.backend.gpu.FlixelGpuTexture;
import org.flixelgdx.graphics.FlixelRenderTarget;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.logging.FlixelLogger;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoPlayer;
import org.flixelgdx.video.FlixelVideoQuality;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.stringdotjar.jwebgpu.GpuDevice;
import io.github.stringdotjar.jwebgpu.GpuQueue;

import java.io.IOException;

import android.content.res.AssetFileDescriptor;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/**
 * Android video player that decodes with {@code MediaPlayer} and converts frames to RGBA on the GPU.
 *
 * <p>The decoder renders into an {@link ImageReader} as YUV 4:2:0 frames. Each new frame's three planes
 * are uploaded with WebGPU's {@code writeTexture} straight from the planes' direct buffers (no Java-side
 * copy), and a small WGSL pass ({@link FlixelAndroidYuvPass}) converts them to RGBA inside a
 * {@link FlixelRenderTarget}, already scaled to the {@link FlixelVideoQuality} preset. The render target's
 * texture is what {@link #getFrame()} returns, and the batch draws it like any other image.
 *
 * <p>Allocation: this player and the pass allocate nothing per frame. {@code ImageReader} itself creates a
 * small wrapper object for every frame it hands out; that is inside the Android platform and cannot be
 * avoided through its public API.
 *
 * <p>Rotation: frames reach the reader exactly as the decoder stores them. A video tagged with a rotation
 * (common for phone recordings) is therefore drawn unrotated; re-encode it upright, or rotate the sprite.
 *
 * <p>Game code does not use this class directly; {@link FlixelVideo} wraps it.
 *
 * <p>Threading contract: every public method is called on the game thread. The {@code MediaPlayer} and
 * {@code ImageReader} listeners run on the main looper, so they only write {@code volatile} fields that
 * {@link #update(float)} reads. Loading starts inside the first {@link #update(float)} call, and every GPU
 * object is created lazily there too, because the framework graphics are guaranteed to exist then.
 *
 * <p>Calls that need a prepared {@code MediaPlayer} (play, seek, rate) are queued and applied as
 * soon as preparation finishes, so game code can call them right after creating the video.
 *
 * <p>Device loss: if the framework replaces its GPU device, the next decoded frame rebuilds every GPU
 * object on the new device. A paused video stays blank until it gets a new frame.
 */
public final class FlixelAndroidVideoPlayer implements FlixelVideoPlayer {

  private static final FlixelLogger LOG = Flixel.log.tagged("FlixelVideo");

  /** How many frames the reader may hold at once: one being read, one decoded, one in flight. */
  private static final int MAX_IMAGES = 3;

  private final MediaPlayer.OnPreparedListener onPrepared = player -> prepared = true;
  private final MediaPlayer.OnCompletionListener onCompletion = player -> completed = true;
  private final ImageReader.OnImageAvailableListener onImage = reader -> frameAvailable = true;

  private final MediaPlayer.OnVideoSizeChangedListener onSize = (player, width, height) -> {
    videoWidth = width;
    videoHeight = height;
  };

  private final MediaPlayer.OnErrorListener onError = (player, what, extra) -> {
    errorWhat = what;
    errorExtra = extra;
    failed = true;
    return true;
  };

  /** Source video size reported by the decoder, written by a main-looper listener. */
  private volatile int videoWidth;

  private volatile int videoHeight;
  private volatile int errorWhat;
  private volatile int errorExtra;

  /** Size the current {@link #reader} was made for. */
  private int readerWidth;

  private int readerHeight;

  private float volume = 1f;
  private float rate = 1f;

  /** Seek queued until the player is prepared. -1 = none. */
  private float pendingSeekMs = -1f;

  @NotNull
  private FlixelVideoQuality quality = FlixelVideoQuality.FULL;

  @NotNull
  private final MediaPlayer mediaPlayer = new MediaPlayer();

  /** Uploads the planes and converts them to RGBA on the GPU. */
  @NotNull
  private final FlixelAndroidYuvPass yuv = new FlixelAndroidYuvPass();

  /** Delivers the reader's frame callbacks on the main looper, like the {@code MediaPlayer} ones. */
  @NotNull
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Nullable
  private final String path;

  @Nullable
  private AssetFileDescriptor descriptor;

  /** Receives decoded frames once the video size is known. */
  @Nullable
  private ImageReader reader;

  @Nullable
  private FlixelRenderTarget renderTarget;

  /** Set by the prepared listener. */
  private volatile boolean prepared;

  /** Set by the reader listener when the decoder has a new frame for the reader. */
  private volatile boolean frameAvailable;

  /** Set by the completion listener when a non-looping stream reaches its end. */
  private volatile boolean completed;

  /** Set by the error listener; the player is unusable afterwards. */
  private volatile boolean failed;

  /** Whether the prepared state has been processed on the game thread. */
  private boolean ready;

  /** Whether the game wants the video playing (as opposed to paused or stopped). */
  private boolean wantPlaying;

  /** Whether the rate must be pushed to the MediaPlayer the next time it is playing. */
  private boolean rateDirty;

  private boolean looping;
  private boolean ended;

  /** Whether the plane textures hold at least one decoded frame. */
  private boolean hasUpload;

  /** Whether the render target must be redrawn (new frame, or a quality change). */
  private boolean redraw;

  /** Whether at least one frame has been drawn into the render target. */
  private boolean hasFrame;

  /** Whether loading has started. */
  private boolean started;

  private boolean failureLogged;
  private boolean disposed;

  /**
   * Creates a player for a video stored uncompressed in the APK assets.
   *
   * @param source The opened asset; this player closes it.
   */
  public FlixelAndroidVideoPlayer(@NotNull AssetFileDescriptor source) {
    this(source, null);
  }

  /**
   * Creates a player for a video file on disk.
   *
   * @param path Absolute path of the video file.
   */
  public FlixelAndroidVideoPlayer(@NotNull String path) {
    this(null, path);
  }

  private FlixelAndroidVideoPlayer(@Nullable AssetFileDescriptor descriptor, @Nullable String path) {
    this.descriptor = descriptor;
    this.path = path;
    mediaPlayer.setOnPreparedListener(onPrepared);
    mediaPlayer.setOnCompletionListener(onCompletion);
    mediaPlayer.setOnVideoSizeChangedListener(onSize);
    mediaPlayer.setOnErrorListener(onError);
  }

  @Override
  public void play() {
    if (disposed) {
      return;
    }
    wantPlaying = true;
    ended = false;
    completed = false;
    applyPlayState();
  }

  @Override
  public void pause() {
    if (disposed) {
      return;
    }
    wantPlaying = false;
    applyPlayState();
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
    wantPlaying = false;
    ended = false;
    completed = false;
    applyPlayState();
    setTime(0f);
  }

  @Override
  public float getTime() {
    if (disposed) {
      return 0f;
    }
    if (pendingSeekMs >= 0f) {
      return pendingSeekMs;
    }
    if (!ready || failed) {
      return 0f;
    }
    return Math.max(0, mediaPlayer.getCurrentPosition());
  }

  @Override
  public void setTime(float timeMs) {
    if (disposed) {
      return;
    }
    pendingSeekMs = Math.max(0f, timeMs);
    ended = false;
    completed = false;
    applySeek();
  }

  @Override
  public float getLength() {
    if (disposed || !ready || failed) {
      return 0f;
    }
    return Math.max(0, mediaPlayer.getDuration());
  }

  @Override
  public void setRate(float rate) {
    if (disposed || rate <= 0f) {
      return;
    }
    this.rate = rate;
    rateDirty = true;
    applyPlayState();
  }

  @Override
  public void setQuality(@NotNull FlixelVideoQuality quality) {
    if (this.quality == quality) {
      return;
    }
    this.quality = quality;
    // The last decoded frame is still in the plane textures, so a paused video can be redrawn at
    // the new size right away.
    redraw = true;
  }

  @Override
  public void update(float elapsed) {
    if (disposed) {
      return;
    }
    if (failed) {
      logFailure();
      return;
    }
    if (!started) {
      start();
      if (!started) {
        return;
      }
    }

    if (prepared && !ready) {
      ready = true;
      onFirstPrepared();
    }

    if (completed) {
      completed = false;
      if (looping) {
        // Looping was switched on after the stream already finished, so restart it by hand.
        pendingSeekMs = 0f;
        wantPlaying = true;
        applySeek();
        applyPlayState();
      } else {
        wantPlaying = false;
        ended = true;
      }
    }

    if (ready) {
      ensureReader();
    }

    if (!(Flixel.graphics instanceof FlixelGpuGraphics graphics)) {
      return;
    }
    GpuDevice device = graphics.getDevice();
    GpuQueue queue = graphics.getQueue();
    if (device == null || queue == null) {
      return;
    }

    if (frameAvailable) {
      // Clear the flag first, so a frame that arrives during the update is not lost.
      frameAvailable = false;
      ImageReader source = reader;
      Image image = source != null ? source.acquireLatestImage() : null;
      if (image != null) {
        try {
          if (upload(device, queue, image)) {
            hasUpload = true;
            redraw = true;
          }
        } finally {
          image.close();
        }
      }
    }

    if (redraw && hasUpload) {
      convert(device, queue);
    }
  }

  @Override
  public void destroy() {
    if (disposed) {
      return;
    }
    disposed = true;
    wantPlaying = false;
    mediaPlayer.setOnPreparedListener(null);
    mediaPlayer.setOnCompletionListener(null);
    mediaPlayer.setOnVideoSizeChangedListener(null);
    mediaPlayer.setOnErrorListener(null);
    mediaPlayer.release();
    closeDescriptor();
    closeReader();
    yuv.destroy();
    if (renderTarget != null) {
      renderTarget.destroy();
      renderTarget = null;
    }
    hasUpload = false;
    hasFrame = false;
  }

  /**
   * Uploads one decoded frame's planes to the GPU.
   *
   * @return {@code true} if the frame was a usable YUV 4:2:0 image and was uploaded.
   */
  private boolean upload(@NotNull GpuDevice device, @NotNull GpuQueue queue, @NotNull Image image) {
    if (image.getFormat() != ImageFormat.YUV_420_888) {
      return false;
    }
    Image.Plane[] planes = image.getPlanes();
    if (planes.length < 3 || planes[0].getPixelStride() != 1) {
      return false;
    }
    int width = image.getWidth();
    int height = image.getHeight();
    int shownWidth = videoWidth > 0 ? Math.min(videoWidth, width) : width;
    int shownHeight = videoHeight > 0 ? Math.min(videoHeight, height) : height;
    Image.Plane u = planes[1];
    return yuv.upload(device, queue, width, height, shownWidth, shownHeight, planes[0].getBuffer(),
        planes[0].getRowStride(), u.getBuffer(), planes[2].getBuffer(), u.getRowStride(), u.getPixelStride());
  }

  /** Converts the uploaded frame into the render target, recreating the target if the size changed. */
  private void convert(@NotNull GpuDevice device, @NotNull GpuQueue queue) {
    int sourceWidth = videoWidth;
    int sourceHeight = videoHeight;
    if (sourceWidth <= 0 || sourceHeight <= 0) {
      return;
    }
    float scale = quality.getScale();
    int width = Math.max(1, Math.round(sourceWidth * scale));
    int height = Math.max(1, Math.round(sourceHeight * scale));

    FlixelRenderTarget target = renderTarget;
    if (target == null || target.getWidth() != width || target.getHeight() != height) {
      FlixelRenderTarget created = Flixel.graphics.createRenderTarget(width, height);
      if (!(created.getTexture() instanceof FlixelGpuTexture)) {
        // Graphics are not ready yet (an unsupported stub); try again next frame.
        created.destroy();
        return;
      }
      if (target != null) {
        target.destroy();
      }
      target = created;
      renderTarget = created;
    }

    if (yuv.convert(device, queue, (FlixelGpuTexture) target.getTexture())) {
      redraw = false;
      hasFrame = true;
    } else {
      // The device changed since the last upload; wait for the next decoded frame.
      hasUpload = false;
    }
  }

  /** Starts loading the video; runs once on the game thread. */
  private void start() {
    try {
      started = true;
      mediaPlayer.setVolume(volume, volume);
      mediaPlayer.setLooping(looping);
      openSource();
      mediaPlayer.prepareAsync();
    } catch (IOException | RuntimeException error) {
      fail("Could not start the video: " + error.getMessage(), error);
    }
  }

  /**
   * Makes sure an {@link ImageReader} of the current video size is the decoder's output.
   *
   * <p>The reader is made only once the decoder reports the size, so its buffers match the frames. A video
   * with no picture never gets one.
   */
  private void ensureReader() {
    int width = videoWidth;
    int height = videoHeight;
    if (width <= 0 || height <= 0 || (reader != null && width == readerWidth && height == readerHeight)) {
      return;
    }
    try {
      ImageReader created = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES);
      created.setOnImageAvailableListener(onImage, handler);
      mediaPlayer.setSurface(created.getSurface());
      closeReader();
      reader = created;
      readerWidth = width;
      readerHeight = height;
    } catch (IllegalStateException | IllegalArgumentException error) {
      fail("Could not create the video frame reader: " + error.getMessage(), error);
    }
  }

  private void closeReader() {
    ImageReader old = reader;
    reader = null;
    if (old != null) {
      old.setOnImageAvailableListener(null, null);
      old.close();
    }
  }

  private void openSource() throws IOException {
    AssetFileDescriptor asset = descriptor;
    if (asset != null) {
      mediaPlayer.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
      closeDescriptor();
    } else if (path != null) {
      mediaPlayer.setDataSource(path);
    } else {
      throw new IOException("No video source.");
    }
  }

  /** Applies everything that was requested before the MediaPlayer was prepared. */
  private void onFirstPrepared() {
    if (pendingSeekMs < 0f && !wantPlaying) {
      // Seeking to the start makes the decoder show the first frame, even while paused.
      pendingSeekMs = 0f;
    }
    applySeek();
    applyPlayState();
  }

  /**
   * Makes the MediaPlayer match {@link #wantPlaying}, and applies a pending rate change.
   *
   * <p>The rate is only pushed while the player is playing, because setting a non-zero speed on a
   * paused MediaPlayer starts playback by itself.
   */
  private void applyPlayState() {
    if (!ready || failed || disposed) {
      return;
    }
    try {
      boolean playing = mediaPlayer.isPlaying();
      if (wantPlaying && !playing) {
        mediaPlayer.start();
        playing = true;
      } else if (!wantPlaying && playing) {
        mediaPlayer.pause();
        playing = false;
      }
      if (rateDirty && playing) {
        mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(rate));
        rateDirty = false;
      }
    } catch (IllegalStateException | IllegalArgumentException error) {
      fail("Playback state change failed: " + error.getMessage(), error);
    }
  }

  /** Sends the queued seek to the MediaPlayer once it is prepared. */
  private void applySeek() {
    if (!ready || failed || disposed || pendingSeekMs < 0f) {
      return;
    }
    try {
      mediaPlayer.seekTo((long) pendingSeekMs, MediaPlayer.SEEK_CLOSEST);
      pendingSeekMs = -1f;
    } catch (IllegalStateException error) {
      fail("Seek failed: " + error.getMessage(), error);
    }
  }

  private void logFailure() {
    if (failureLogged) {
      return;
    }
    failureLogged = true;
    wantPlaying = false;
    LOG.error("MediaPlayer reported an error (what={}, extra={}); the video was stopped.", errorWhat, errorExtra);
  }

  private void fail(@NotNull String message, @NotNull Throwable error) {
    failed = true;
    failureLogged = true;
    wantPlaying = false;
    LOG.error("{}", message, error);
  }

  private void closeDescriptor() {
    AssetFileDescriptor asset = descriptor;
    descriptor = null;
    if (asset == null) {
      return;
    }
    try {
      asset.close();
    } catch (IOException error) {
      LOG.warn("Could not close the video asset: {}", error.getMessage());
    }
  }

  @Override
  public boolean isPlaying() {
    return !disposed && !failed && wantPlaying;
  }

  @Override
  public boolean isReady() {
    return !disposed && hasFrame;
  }

  @Override
  public boolean isEnded() {
    return ended && !looping;
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
  public void setLooped(boolean looped) {
    this.looping = looped;
    if (started && !failed && !disposed) {
      mediaPlayer.setLooping(looped);
    }
  }

  @Override
  public float getVolume() {
    return volume;
  }

  @Override
  public void setVolume(float volume) {
    this.volume = Math.max(0f, Math.min(1f, volume));
    if (started && !failed && !disposed) {
      mediaPlayer.setVolume(this.volume, this.volume);
    }
  }

  @Nullable
  @Override
  public FlixelTexture getFrame() {
    FlixelRenderTarget target = renderTarget;
    return !disposed && hasFrame && target != null ? target.getTexture() : null;
  }

  @Override
  public int getVideoWidth() {
    return videoWidth;
  }

  @Override
  public int getVideoHeight() {
    return videoHeight;
  }

  @Override
  public int getFrameWidth() {
    FlixelRenderTarget target = renderTarget;
    return target != null ? target.getWidth() : 0;
  }

  @Override
  public int getFrameHeight() {
    FlixelRenderTarget target = renderTarget;
    return target != null ? target.getHeight() : 0;
  }
}
