# Controlling Playback: Pausing, Seeking, Looping, and Signals

This guide covers everything you can do to a video while it plays: starting and stopping, pausing and resuming,
seeking, speed, volume, looping, reacting to events with signals, and what happens when the window loses focus.

## Table of contents

1. [Play, pause, resume, and stop](#play-pause-resume-and-stop)
2. [Seeking and reading the position](#seeking-and-reading-the-position)
3. [Speed and volume](#speed-and-volume)
4. [Looping](#looping)
5. [Signals: reacting to playback events](#signals-reacting-to-playback-events)
6. [Automatic pause when the window loses focus](#automatic-pause-when-the-window-loses-focus)
7. [Pause menus and substates](#pause-menus-and-substates)
8. [Common mistakes](#common-mistakes)
9. [Check your understanding](#check-your-understanding)

## Play, pause, resume, and stop

The controls work like the buttons on a remote. Every one returns the video, so they chain:

```java
video.play();               // Start from the beginning.
video.pause();              // Freeze on the current frame.
video.resume();             // Continue from where it paused.
video.stop();               // Stop and rewind to the beginning.

video.play(false);          // Start without forcing a restart from the beginning.
video.play(true, 5000f);    // Restart from 5 seconds in.

boolean playing = video.isPlaying();
```

The difference between `play()` and `resume()` is the one people trip over: `play()` always jumps back to the start
(or to the time you pass), while `resume()` continues from the current position. After a `pause()`, use `resume()`.

## Seeking and reading the position

All times are in **milliseconds**, the same unit the framework's sounds use.

```java
float position = video.getTime();   // Where playback is now.
float length = video.getLength();   // Total length, or 0 until the video is ready.
video.setTime(30000f);              // Jump to 30 seconds.
```

Seeking before the video is ready is fine: the position is remembered and applied once the video has opened.

A progress value from `0` to `1`, for a progress bar or a "skip" ring, is one division away:

```java
float length = video.getLength();
float progress = length > 0f ? video.getTime() / length : 0f;
```

## Speed and volume

```java
video.setRate(2f);       // Double speed. 1 is normal, 0.5 is half speed.
video.setVolume(0.5f);   // Half volume, from 0 to 1.

float rate = video.getRate();
float volume = video.getVolume();
```

The rate must be greater than `0`; a rate of `0` or less is ignored, so use `pause()` to stop the picture. Volumes
outside `0` to `1` are clamped.

A video's sound plays through the platform's video decoder, not through `Flixel.sound`. That means the framework's
master volume and sound groups do not affect it. If your options menu has a volume slider, apply it to your videos
yourself with `setVolume(...)`.

## Looping

```java
video.setLooped(true);   // Jump back to the start every time it ends.
boolean looped = video.isLooped();
```

Looping suits menu backgrounds and ambient screens. A looping video never "completes," so `onComplete` never fires for
it, and `setAutoDestroy(true)` has no effect on it.

## Signals: reacting to playback events

Instead of checking the video every frame, you can let it tell you when something happens. Each signal is a
`FlixelSignal<Void>`, so its listener receives `null`:

| Signal       | Fires when                                                              |
|--------------|-------------------------------------------------------------------------|
| `onPlay`     | `play(...)` is called.                                                  |
| `onPause`    | `pause()` is called.                                                    |
| `onResume`   | `resume()` is called.                                                   |
| `onComplete` | A non-looping video reaches its end. It fires once per playthrough.     |

```java
video.onComplete.add(data -> Flixel.switchState(MenuState::new));
video.onPause.add(data -> pauseLabel.visible = true);
video.onResume.add(data -> pauseLabel.visible = false);
```

`onComplete` is checked during the video's update, so it fires on the game thread, where it is safe to switch states
or add objects. Calling `play(...)`, `stop()`, or `setTime(...)` arms it again, so a replayed video completes again.

`addOnce(...)` registers a listener that removes itself after its first call, and `remove(...)` takes one away. When a
video is destroyed, all of its listeners are cleared for you. See the framework's
[Signals](https://github.com/flixelgdx/flixelgdx/blob/master/handbook/signals.md) guide for more.

## Automatic pause when the window loses focus

When the player switches to another window, or sends the app to the background on a phone, a playing video pauses by
itself and resumes when focus returns. This follows the same `Flixel.autoPause` setting the framework uses for audio, so
setting `Flixel.autoPause = false` keeps videos playing in the background too.

Automatic pausing remembers which videos it paused, so a video you paused yourself stays paused when focus returns.
It also does not fire `onPause` or `onResume`; those signals only fire for your own calls.

## Pause menus and substates

Opening a substate over a state usually stops the state from updating. A video in that state then stops refreshing
its picture, but its decoder keeps running, so the sound keeps going and the position keeps moving. Pause the video
yourself when you open a pause menu, and resume it when the menu closes:

```java
private void openPauseMenu() {
  video.pause();
  PauseSubState menu = new PauseSubState();
  menu.closeCallback = video::resume;
  openSubState(menu);
}
```

## Common mistakes

- **Using `play()` to continue after a pause.** It restarts from the beginning. Use `resume()`.
- **Waiting for `onComplete` on a looping video.** It never fires. Turn looping off, or watch `getTime()` instead.
- **Expecting the master volume to affect videos.** Video sound is separate from `Flixel.sound`. Set each video's
  volume yourself.
- **Assuming seconds.** Every time value is in milliseconds. `setTime(5f)` jumps to 5 milliseconds, not 5 seconds.

## Check your understanding

1. The player pauses a cutscene, then presses "Continue." Which method should "Continue" call?
2. A menu background video loops forever. You added an `onComplete` listener to it to count loops, but it never runs.
   Why?
3. The player alt-tabs during a cutscene you had already paused. Does the cutscene start playing when they come back?

(Answers: `resume()`; `onComplete` only fires for non-looping videos; no, automatic pausing only resumes videos it
paused itself.)

**Next:** [Drawing and Quality](drawing-and-quality.md)
