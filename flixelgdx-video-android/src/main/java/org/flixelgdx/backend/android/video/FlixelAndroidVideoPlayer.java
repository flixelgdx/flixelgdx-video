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
import org.flixelgdx.graphics.FlixelRenderTarget;
import org.flixelgdx.graphics.FlixelTexture;
import org.flixelgdx.logging.FlixelLogger;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoPlayer;
import org.flixelgdx.video.FlixelVideoQuality;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Build;
import android.view.Surface;

/**
 * Android video player that decodes with {@code MediaPlayer} and never copies pixels through the CPU.
 *
 * <p>The decoder renders straight into a {@link SurfaceTexture}, which exposes each frame as an
 * external OpenGL texture (an "OES" texture). The framework batch can only draw normal 2D textures,
 * so on the render thread this player draws that external texture once per new frame into a
 * {@link FlixelRenderTarget}. The render target's texture is what {@link #getFrame()} returns, and
 * the batch draws it like any other image. No pixel data ever reaches Java memory, and the
 * per-frame path allocates nothing.
 *
 * <p>Game code does not use this class directly; {@link FlixelVideo} wraps it.
 *
 * <p>Threading contract: every public method is called on the render (GL) thread. The
 * {@code MediaPlayer} and {@code SurfaceTexture} listeners run on the main looper, so they only
 * write {@code volatile} fields that {@link #update(float)} reads. Everything that touches OpenGL
 * is created lazily inside the first {@link #update(float)} call, because the GL context and the
 * framework graphics are guaranteed to exist there.
 *
 * <p>Calls that need a prepared {@code MediaPlayer} (play, seek, rate) are queued and applied as
 * soon as preparation finishes, so game code can call them right after creating the video.
 *
 * <p>Context loss: the framework keeps the EGL context alive when the app is paused and expects a
 * full restart otherwise, so this player does not try to recover GL resources after a context loss.
 * If the context is ever destroyed, create a new video.
 */
public final class FlixelAndroidVideoPlayer implements FlixelVideoPlayer {

  private static final FlixelLogger LOG = Flixel.log.tagged("FlixelVideo");

  /**
   * Full-screen quad as a triangle strip, four vertices of (x, y, s, t).
   *
   * <p>Orientation: a render target's rows are stored top-down, which means the first row in memory
   * (the bottom of clip space, y = -1) must hold the top of the picture, so the batch can sample it
   * with v = 0 at the top. {@code SurfaceTexture} coordinates follow the usual GL convention, where
   * t = 0 is the bottom of the picture (its transform matrix already accounts for the decoder's
   * own flip and rotation). So the vertices at clip y = -1 sample t = 1 (the top of the picture),
   * and the vertices at clip y = +1 sample t = 0 (the bottom of the picture).
   */
  private static final float[] QUAD = {
      -1f, -1f, 0f, 1f,
      1f, -1f, 1f, 1f,
      -1f, 1f, 0f, 0f,
      1f, 1f, 1f, 0f,
  };

  private static final String VERTEX_SOURCE =
      """
          attribute vec2 a_pos;
          attribute vec2 a_uv;
          uniform mat4 u_texMatrix;
          varying vec2 v_uv;
          void main() {
            gl_Position = vec4(a_pos, 0.0, 1.0);
            v_uv = (u_texMatrix * vec4(a_uv, 0.0, 1.0)).xy;
          }
          """;

  private static final String FRAGMENT_SOURCE =
      """
          #extension GL_OES_EGL_image_external : require
          precision mediump float;
          varying vec2 v_uv;
          uniform samplerExternalOES u_texture;
          void main() {
            gl_FragColor = vec4(texture2D(u_texture, v_uv).rgb, 1.0);
          }
          """;

  private static final int ATTR_POS = 0;
  private static final int ATTR_UV = 1;

  /** Reused for the SurfaceTexture transform matrix. */
  private final float[] texMatrix = new float[16];

  /** Reused out-parameter for GL object creation. */
  private final int[] ids = new int[1];

  /** Reused out-parameter for GL state queries. */
  private final int[] query = new int[1];

  private final MediaPlayer.OnPreparedListener onPrepared = player -> prepared = true;
  private final MediaPlayer.OnCompletionListener onCompletion = player -> completed = true;
  private final SurfaceTexture.OnFrameAvailableListener onFrame = texture -> frameAvailable = true;
  private final Runnable glCleanup = this::releaseGl;

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

  private int texture;
  private int program;
  private int vbo;
  private int vao;
  private int texMatrixLocation;

  private float volume = 1f;
  private float rate = 1f;

  /** Seek queued until the player is prepared. -1 = none. */
  private float pendingSeekMs = -1f;

  @NotNull
  private FlixelVideoQuality quality = FlixelVideoQuality.FULL;

  @NotNull
  private final MediaPlayer mediaPlayer = new MediaPlayer();

  @Nullable
  private final String path;

  @Nullable
  private AssetFileDescriptor descriptor;

  @Nullable
  private SurfaceTexture surfaceTexture;

  @Nullable
  private Surface surface;

  @Nullable
  private FlixelRenderTarget renderTarget;

  /** Set by the prepared listener. */
  private volatile boolean prepared;

  /** Set by the frame listener when the decoder has a new frame for the surface texture. */
  private volatile boolean frameAvailable;

  /** Set by the completion listener when a non-looping stream reaches its end. */
  private volatile boolean completed;

  /** Set by the error listener; the player is unusable afterwards. */
  private volatile boolean failed;

  /** Whether the prepared state has been processed on the render thread. */
  private boolean ready;

  /** Whether the game wants the video playing (as opposed to paused or stopped). */
  private boolean wantPlaying;

  /** Whether the rate must be pushed to the MediaPlayer the next time it is playing. */
  private boolean rateDirty;

  private boolean looping;
  private boolean ended;

  /** Whether the surface texture has received at least one decoded frame. */
  private boolean hasSurfaceFrame;

  /** Whether the render target must be redrawn (new frame, or a quality change). */
  private boolean redraw;

  /** Whether at least one frame has been drawn into the render target. */
  private boolean hasFrame;

  private boolean glReady;
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
    // The last decoded frame is still in the surface texture, so a paused video can be redrawn at
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
    if (!glReady) {
      initGl();
      if (!glReady) {
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

    if (frameAvailable) {
      // Clear the flag first, so a frame that arrives during the update is not lost.
      frameAvailable = false;
      SurfaceTexture source = surfaceTexture;
      if (source != null) {
        source.updateTexImage();
        source.getTransformMatrix(texMatrix);
        hasSurfaceFrame = true;
        redraw = true;
      }
    }

    if (redraw && hasSurfaceFrame) {
      blit();
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
    if (!glReady) {
      return;
    }
    if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
      releaseGl();
    } else {
      // Not on the GL thread, so hand the GL deletes over to it.
      Flixel.graphics.queueMainThread(glCleanup);
    }
  }

  /**
   * Draws the current external texture into the render target, recreating the target if the size
   * changed.
   *
   * <p>Only the GL state this method touches is saved and restored: the bound program, the active
   * texture unit, blending, and the scissor test. The framework batch rebinds its own program,
   * textures, vertex array, and buffers on every flush, and this method uses its own vertex array,
   * so nothing else leaks. The render target's begin and end calls handle the framebuffer and the
   * viewport.
   */
  private void blit() {
    int sourceWidth = videoWidth;
    int sourceHeight = videoHeight;
    if (sourceWidth <= 0 || sourceHeight <= 0) {
      return;
    }
    float scale = quality.getScale();
    int width = Math.max(1, Math.round(sourceWidth * scale));
    int height = Math.max(1, Math.round(sourceHeight * scale));

    // Anything the framework queued must reach the previous surface before we change targets.
    Flixel.graphics.getBatch().flush();

    FlixelRenderTarget target = renderTarget;
    if (target == null || target.getWidth() != width || target.getHeight() != height) {
      FlixelRenderTarget created = Flixel.graphics.createRenderTarget(width, height);
      if (created.getWidth() <= 0) {
        // Graphics are not ready yet (an unsupported stub); try again next frame.
        return;
      }
      if (target != null) {
        target.destroy();
      }
      target = created;
      renderTarget = created;
    }

    GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, query, 0);
    int previousProgram = query[0];
    GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, query, 0);
    int previousUnit = query[0];
    boolean blend = GLES30.glIsEnabled(GLES30.GL_BLEND);
    boolean scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST);

    target.begin();

    GLES30.glDisable(GLES30.GL_BLEND);
    GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
    GLES30.glUseProgram(program);
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES30.glUniformMatrix4fv(texMatrixLocation, 1, false, texMatrix, 0);
    GLES30.glBindVertexArray(vao);
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    GLES30.glBindVertexArray(0);
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

    target.end();

    GLES30.glUseProgram(previousProgram);
    GLES30.glActiveTexture(previousUnit);
    if (blend) {
      GLES30.glEnable(GLES30.GL_BLEND);
    }
    if (scissor) {
      GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
    }

    redraw = false;
    hasFrame = true;
  }

  /** Creates every GL object and starts loading the video; runs once on the render thread. */
  private void initGl() {
    try {
      GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, query, 0);
      int previousUnit = query[0];

      GLES30.glGenTextures(1, ids, 0);
      texture = ids[0];
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
      GLES30.glActiveTexture(previousUnit);

      program = buildProgram();
      texMatrixLocation = GLES30.glGetUniformLocation(program, "u_texMatrix");
      buildQuad();
      glReady = true;

      surfaceTexture = new SurfaceTexture(texture);
      surfaceTexture.setOnFrameAvailableListener(onFrame);
      surface = new Surface(surfaceTexture);
      mediaPlayer.setSurface(surface);
      mediaPlayer.setVolume(volume, volume);
      mediaPlayer.setLooping(looping);
      openSource();
      mediaPlayer.prepareAsync();
    } catch (IOException | RuntimeException error) {
      fail("Could not start the video: " + error.getMessage(), error);
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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        mediaPlayer.seekTo((long) pendingSeekMs, MediaPlayer.SEEK_CLOSEST);
      } else {
        mediaPlayer.seekTo((int) pendingSeekMs);
      }
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

  /** Creates the vertex array and buffer that hold the full-screen quad. */
  private void buildQuad() {
    FloatBuffer data = ByteBuffer.allocateDirect(QUAD.length * Float.BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer();
    data.put(QUAD).position(0);

    GLES30.glGenBuffers(1, ids, 0);
    vbo = ids[0];
    GLES30.glGenVertexArrays(1, ids, 0);
    vao = ids[0];

    GLES30.glBindVertexArray(vao);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD.length * Float.BYTES, data, GLES30.GL_STATIC_DRAW);
    int stride = 4 * Float.BYTES;
    GLES30.glEnableVertexAttribArray(ATTR_POS);
    GLES30.glVertexAttribPointer(ATTR_POS, 2, GLES30.GL_FLOAT, false, stride, 0);
    GLES30.glEnableVertexAttribArray(ATTR_UV);
    GLES30.glVertexAttribPointer(ATTR_UV, 2, GLES30.GL_FLOAT, false, stride, 2 * Float.BYTES);
    GLES30.glBindVertexArray(0);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
  }

  /** Deletes every GL object and releases the surface; must run on the GL thread. */
  private void releaseGl() {
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (surfaceTexture != null) {
      surfaceTexture.setOnFrameAvailableListener(null);
      surfaceTexture.release();
      surfaceTexture = null;
    }
    ids[0] = texture;
    GLES30.glDeleteTextures(1, ids, 0);
    ids[0] = vbo;
    GLES30.glDeleteBuffers(1, ids, 0);
    ids[0] = vao;
    GLES30.glDeleteVertexArrays(1, ids, 0);
    GLES30.glDeleteProgram(program);
    if (renderTarget != null) {
      renderTarget.destroy();
      renderTarget = null;
    }
    glReady = false;
    hasFrame = false;
  }

  private static int buildProgram() {
    int vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SOURCE);
    int fragment = compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SOURCE);
    int linked = GLES30.glCreateProgram();
    GLES30.glAttachShader(linked, vertex);
    GLES30.glAttachShader(linked, fragment);
    GLES30.glBindAttribLocation(linked, ATTR_POS, "a_pos");
    GLES30.glBindAttribLocation(linked, ATTR_UV, "a_uv");
    GLES30.glLinkProgram(linked);
    GLES30.glDeleteShader(vertex);
    GLES30.glDeleteShader(fragment);
    int[] status = new int[1];
    GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0);
    if (status[0] == 0) {
      String log = GLES30.glGetProgramInfoLog(linked);
      GLES30.glDeleteProgram(linked);
      throw new IllegalStateException("Video shader link failed: " + log);
    }
    return linked;
  }

  private static int compile(int type, @NotNull String source) {
    int shader = GLES30.glCreateShader(type);
    GLES30.glShaderSource(shader, source);
    GLES30.glCompileShader(shader);
    int[] status = new int[1];
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
    if (status[0] == 0) {
      String log = GLES30.glGetShaderInfoLog(shader);
      GLES30.glDeleteShader(shader);
      throw new IllegalStateException("Video shader compile failed: " + log);
    }
    return shader;
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
    if (glReady && !failed && !disposed) {
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
    if (glReady && !failed && !disposed) {
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
