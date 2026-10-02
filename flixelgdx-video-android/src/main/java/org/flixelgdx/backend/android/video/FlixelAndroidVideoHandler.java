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

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import org.flixelgdx.Flixel;
import org.flixelgdx.file.FlixelFile;
import org.flixelgdx.logging.FlixelLogger;
import org.flixelgdx.video.FlixelUnavailableVideoPlayer;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideoFactory;
import org.flixelgdx.video.FlixelVideoPlayer;
import org.flixelgdx.video.FlixelVideos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Android video backend factory powered by the platform {@code MediaPlayer}.
 *
 * <p>It creates one {@link FlixelAndroidVideoPlayer} per video; {@link FlixelVideos#create(FlixelFile)}
 * wraps the player in a {@link FlixelVideo}. Nothing here needs a native library, because the
 * decoder ships with Android itself.
 *
 * <p>Install it once from your {@code Activity}, before the framework launches:
 *
 * <pre>{@code
 * public class MyActivity extends Activity {
 *   protected void onCreate(Bundle savedInstanceState) {
 *     super.onCreate(savedInstanceState);
 *     FlixelAndroidVideoHandler.install(this);
 *     FlixelAndroidLauncher.launch(this, new MyGame());
 *   }
 * }
 * }</pre>
 *
 * <p>Which files can be played:
 *
 * <ul>
 *   <li>Files in the APK {@code assets} folder ({@code Flixel.files.internal(...)}) are opened
 *       through {@code AssetManager.openFd}, which only works for assets stored
 *       <b>uncompressed</b> in the APK.</li>
 *   <li>Files on disk ({@code local}, {@code external}, and {@code absolute}) are opened by path,
 *       and there is no compression concern.</li>
 * </ul>
 *
 * <p>The Android build tools (aapt2) already store these video extensions uncompressed by default,
 * so they work with no extra setup: {@code .mp4}, {@code .m4v}, {@code .3gp}, {@code .3gpp},
 * {@code .3g2}, {@code .3gpp2}, {@code .mkv}, and {@code .webm}. Any other extension (for example
 * {@code .mov}) is compressed by default and cannot be opened as an asset. Add it to your game
 * module's {@code build.gradle.kts}:
 *
 * <pre>{@code
 * android {
 *   androidResources {
 *     noCompress += "mov"
 *   }
 * }
 * }</pre>
 *
 * <p>When a video cannot be opened, the reason is logged and the game receives a
 * {@link FlixelUnavailableVideoPlayer}, so a missing or unplayable video never crashes the game.
 *
 * <p>Automatic pause and resume when the app loses focus is handled by {@link FlixelVideo} itself
 * through the framework's window focus signals, so this factory only has to register itself.
 */
public final class FlixelAndroidVideoHandler implements FlixelVideoFactory {

  private static final FlixelLogger LOG = Flixel.log.tagged("FlixelVideo");

  /** Application context used to reach the APK assets; set by {@link #install(Context)}. */
  @Nullable
  private static Context appContext;

  /**
   * Registers this handler as the video backend factory for {@link FlixelVideos}.
   *
   * <p>Only the application context is kept, so passing an {@code Activity} does not leak it. Safe
   * to call multiple times.
   *
   * @param context Any context; its application context is stored for asset access.
   */
  public static void install(@NotNull Context context) {
    appContext = context.getApplicationContext();
    FlixelVideos.setBackendFactory(new FlixelAndroidVideoHandler());
  }

  @NotNull
  @Override
  public FlixelVideoPlayer createPlayer(@NotNull FlixelFile file) {
    Context context = appContext;
    if (context == null) {
      LOG.error("FlixelAndroidVideoHandler.install(context) has not been called.");
      return new FlixelUnavailableVideoPlayer();
    }

    Object handle = file.getNativeHandle();
    if (handle instanceof File onDisk) {
      if (!onDisk.isFile()) {
        LOG.error("Video file could not be found: {}", file.getPath());
        return new FlixelUnavailableVideoPlayer();
      }
      return new FlixelAndroidVideoPlayer(onDisk.getAbsolutePath());
    }

    String assetPath = file.getPath();
    if (assetPath.startsWith("/")) {
      assetPath = assetPath.substring(1);
    }
    try {
      AssetFileDescriptor descriptor = context.getAssets().openFd(assetPath);
      return new FlixelAndroidVideoPlayer(descriptor);
    } catch (IOException error) {
      if (!file.exists()) {
        LOG.error("Video file could not be found: {}", file.getPath());
      } else {
        LOG.error("Video '{}' cannot be opened as an uncompressed APK asset. If the file is compressed in "
            + "the APK, store it uncompressed with androidResources { noCompress += \"{}\" } in your "
            + "game module's build.gradle.kts.", file.getPath(), extensionOf(file.getName()), error);
      }
      return new FlixelUnavailableVideoPlayer();
    }
  }

  @NotNull
  private static String extensionOf(@NotNull String name) {
    int dot = name.lastIndexOf('.');
    return dot >= 0 ? name.substring(dot + 1) : name;
  }
}
