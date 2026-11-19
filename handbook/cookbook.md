# Cookbook: Recipes and Quick Answers

Complete recipes for the most common video tasks, followed by one-line answers to quick "How do I...?" questions. Each
links to the guide that explains it fully.

## Table of contents

1. [A skippable cutscene](#a-skippable-cutscene)
2. [Skip a video that cannot play](#skip-a-video-that-cannot-play)
3. [A looping menu background](#a-looping-menu-background)
4. [Pausing a video with your pause menu](#pausing-a-video-with-your-pause-menu)
5. [Quick answers](#quick-answers)

## A skippable cutscene

A cutscene that fills the screen, moves on when it ends, and can be skipped with Enter, Escape, or a mouse click. The
`finished` flag makes sure the state only switches once, even if the player presses a key on the same frame the video
ends.

```java
import org.flixelgdx.Flixel;
import org.flixelgdx.FlixelState;
import org.flixelgdx.input.keyboard.FlixelKey;
import org.flixelgdx.input.mouse.FlixelMouseButton;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideos;

public class CutsceneState extends FlixelState {

  private FlixelVideo cutscene;
  private boolean finished;

  @Override
  public void create() {
    cutscene = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
    cutscene.setSize(Flixel.getDesignWidth(), Flixel.getDesignHeight());
    cutscene.setScrollFactor(0f, 0f);
    cutscene.onComplete.add(data -> finish());
    add(cutscene);
    cutscene.play();
  }

  @Override
  public void update(float elapsed) {
    super.update(elapsed);
    if (Flixel.keys.anyJustPressed(FlixelKey.ENTER, FlixelKey.ESCAPE)
        || Flixel.mouse.justPressed(FlixelMouseButton.LEFT)) {
      finish();
    }
  }

  private void finish() {
    if (finished) {
      return;
    }
    finished = true;
    Flixel.switchState(MenuState::new);
  }
}
```

There is no need to stop or destroy the video: switching states destroys this state and the video with it. To show a
"Press Enter to skip" hint, add a text object after the video so it is drawn on top.

## Skip a video that cannot play

If the video file is missing or the platform has no working decoder, the video never becomes ready and never fires
`onComplete` (see [When a video cannot play](playing-videos.md#when-a-video-cannot-play)). A cutscene that waits for
`onComplete` would then wait forever. Add a timeout to the cutscene above:

```java
private float waited;

@Override
public void update(float elapsed) {
  super.update(elapsed);
  if (!cutscene.isReady()) {
    waited += elapsed; // Seconds.
    if (waited > 5f) {
      finish(); // Nothing to show; move on.
      return;
    }
  }
  if (Flixel.keys.anyJustPressed(FlixelKey.ENTER, FlixelKey.ESCAPE)
      || Flixel.mouse.justPressed(FlixelMouseButton.LEFT)) {
    finish();
  }
}
```

A video that is ready even once never trips the timeout, so a long cutscene plays in full.

## A looping menu background

A silent video that loops behind a menu. It is added first so every menu item is drawn over it, pinned to the screen,
and set to half quality, since a background behind menu text rarely needs full sharpness.

```java
@Override
public void create() {
  FlixelVideo background = FlixelVideos.create(Flixel.files.internal("videos/menu-loop.mp4"));
  background.setSize(Flixel.getDesignWidth(), Flixel.getDesignHeight());
  background.setScrollFactor(0f, 0f);
  background.setLooped(true);
  background.setVolume(0f);
  background.setQuality(FlixelVideoQuality.HALF);
  add(background);
  background.play();

  // Add the title, buttons, and other menu items after this.
}
```

The video is a member of the state, so it is destroyed when the player leaves the menu. A looping video never fires
`onComplete`. On the web, the browser may still wait for the player's first click or key press before it starts the
video (see [Autoplay rules](platforms.md#autoplay-rules)).

## Pausing a video with your pause menu

A substate stops the state behind it from updating, but the video's decoder keeps running, so its sound keeps
playing. Pause it when the menu opens and resume it when the menu closes:

```java
private void openPauseMenu() {
  cutscene.pause();
  PauseSubState menu = new PauseSubState();
  menu.closeCallback = cutscene::resume;
  openSubState(menu);
}
```

See [Pause menus and substates](controlling-playback.md#pause-menus-and-substates).

## Quick answers

**...play a video once and have it clean up after itself?**
`video.setAutoDestroy(true);` before `play()`. ([Playing Videos](playing-videos.md#cleaning-up))

**...continue a paused video instead of restarting it?**
`video.resume();` (`play()` always restarts). ([Controlling Playback](controlling-playback.md#play-pause-resume-and-stop))

**...start a video partway through?**
`video.play(true, 12000f);` starts 12 seconds in. ([Controlling Playback](controlling-playback.md#play-pause-resume-and-stop))

**...show how far along a video is?**
`video.getTime() / video.getLength()`, once `getLength()` is greater than `0`.
([Controlling Playback](controlling-playback.md#seeking-and-reading-the-position))

**...apply the player's volume setting to videos?**
Call `video.setVolume(...)` with the setting when you create each video. Video sound does not follow
`Flixel.sound`'s master volume. ([Controlling Playback](controlling-playback.md#speed-and-volume))

**...draw a video on the HUD camera only?**
`video.cameras = new FlixelCamera[] {hudCamera};` ([Drawing and Quality](drawing-and-quality.md#scroll-factors-and-cameras))

**...keep a video still while the camera moves?**
`video.setScrollFactor(0f, 0f);` ([Drawing and Quality](drawing-and-quality.md#scroll-factors-and-cameras))

**...put subtitles over a video?**
Add the text after the video. Members are drawn in the order they were added. ([Drawing and Quality](drawing-and-quality.md#draw-order))

**...make a video cheaper to play?**
`video.setQuality(FlixelVideoQuality.HALF);` ([Drawing and Quality](drawing-and-quality.md#quality-presets))

**...keep videos playing when the window loses focus?**
`Flixel.autoPause = false;` ([Controlling Playback](controlling-playback.md#automatic-pause-when-the-window-loses-focus))

**...find out why a desktop video never shows up?**
Read the log: the reason is logged when the video is created. ([Platform Notes](platforms.md#when-no-decoder-is-found))

**...play a `.mov` file on Android?**
Add `noCompress += "mov"` under `androidResources` in the Android module, or convert it to MP4.
([Platform Notes](platforms.md#uncompressed-assets))
