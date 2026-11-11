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
import org.flixelgdx.debug.feeds.FlixelDebugInspector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Teaches the debugger's inspector how to show a {@link FlixelVideo}.
 *
 * <p>Think of the inspector as a doctor reading a chart: without a chart for a type, it can only say
 * that the object exists. This class writes the chart for videos, so selecting one shows where it
 * is, how big it is drawn, and how playback is going. The framework's own "Basic" section (id,
 * exists, active, visible) still shows above it, because sections stack from the base class up.
 *
 * <p>{@link FlixelVideo} calls {@link #register()} every time one is created, and it only does
 * anything while the game runs in debug mode, so nothing has to be wired by hand and a release build
 * pays nothing. It is safe to call again; the section is only added once per inspector.
 *
 * <p>Editable rows go through the video's normal methods, so a value changed from the debugger
 * behaves like one changed in game code. Turning "Playing" on or off calls
 * {@link FlixelVideo#resume()} or {@link FlixelVideo#pause()}, which fire {@link FlixelVideo#onResume}
 * and {@link FlixelVideo#onPause}, and editing "Time" seeks. "Quality" takes a preset name such as
 * {@code HALF}, in any letter case.
 */
public final class FlixelVideoDebugSections {

  private FlixelVideoDebugSections() {}

  /**
   * Adds the video section to the running game's inspector when debug mode is on.
   *
   * <p>Does nothing when the debugger does not exist, when the game is not in debug mode, or when
   * the section is already there.
   */
  public static void register() {
    if (Flixel.isDebugMode()) {
      register(Flixel.debug.inspector);
    }
  }

  /**
   * Adds the video section to an inspector, unless it is already there.
   *
   * @param inspector The inspector to teach.
   */
  public static void register(@NotNull FlixelDebugInspector inspector) {
    if (inspector.isRegistered(FlixelVideo.class)) {
      return;
    }
    inspector.register(FlixelVideo.class, "Video", b -> b
        .floatProp("X", FlixelVideo::getX, FlixelVideo::setX)
        .floatProp("Y", FlixelVideo::getY, FlixelVideo::setY)
        .floatProp("Width", FlixelVideo::getWidth, FlixelVideo::setWidth)
        .floatProp("Height", FlixelVideo::getHeight, FlixelVideo::setHeight)
        .floatProp("Drawn width", FlixelVideo::getDrawWidth)
        .floatProp("Drawn height", FlixelVideo::getDrawHeight)
        .floatProp("Angle", FlixelVideo::getAngle, FlixelVideo::setAngle)
        .floatProp("Scroll factor X", FlixelVideo::getScrollX, (v, s) -> v.setScrollFactor(s, v.getScrollY()))
        .floatProp("Scroll factor Y", FlixelVideo::getScrollY, (v, s) -> v.setScrollFactor(v.getScrollX(), s))
        .boolProp("Playing", FlixelVideo::isPlaying, (v, on) -> {
          if (on) {
            v.resume();
          } else {
            v.pause();
          }
        })
        .boolProp("Ready", FlixelVideo::isReady)
        .floatProp("Time (ms)", FlixelVideo::getTime, FlixelVideo::setTime)
        .floatProp("Length (ms)", FlixelVideo::getLength)
        .floatProp("Rate", FlixelVideo::getRate, FlixelVideo::setRate)
        .floatProp("Volume", FlixelVideo::getVolume, FlixelVideo::setVolume)
        .boolProp("Looped", FlixelVideo::isLooped, FlixelVideo::setLooped)
        .boolProp("Auto destroy", FlixelVideo::isAutoDestroy, FlixelVideo::setAutoDestroy)
        .stringProp("Quality", (v, out) -> out.concat(v.getQuality().name()), (v, text) -> {
          FlixelVideoQuality quality = qualityNamed(text);
          if (quality != null) {
            v.setQuality(quality);
          }
        })
        .intProp("Video width", FlixelVideo::getVideoWidth)
        .intProp("Video height", FlixelVideo::getVideoHeight)
        .intProp("Frame width", v -> v.getPlayer().getFrameWidth())
        .intProp("Frame height", v -> v.getPlayer().getFrameHeight()));
  }

  /** Returns the preset whose name matches {@code text} ignoring case and surrounding spaces, or {@code null}. */
  @Nullable
  private static FlixelVideoQuality qualityNamed(@NotNull CharSequence text) {
    int start = 0;
    int end = text.length();
    while (start < end && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    FlixelVideoQuality[] presets = FlixelVideoQuality.values();
    for (int i = 0; i < presets.length; i++) {
      String name = presets[i].name();
      if (name.length() != end - start) {
        continue;
      }
      boolean match = true;
      for (int c = 0; c < name.length() && match; c++) {
        match = Character.toUpperCase(text.charAt(start + c)) == name.charAt(c);
      }
      if (match) {
        return presets[i];
      }
    }
    return null;
  }
}
