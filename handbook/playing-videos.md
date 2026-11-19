# Playing Videos: Creating, Showing, and Releasing a Video

This guide explains what a `FlixelVideo` is, how it fits into a state, what "ready" means, and how a video is cleaned
up when you are done with it.

## Table of contents

1. [The mental model](#the-mental-model)
2. [Creating a video](#creating-a-video)
3. [Adding it to a state](#adding-it-to-a-state)
4. [Waiting for the first frame](#waiting-for-the-first-frame)
5. [Sizing and centering](#sizing-and-centering)
6. [Cleaning up](#cleaning-up)
7. [When a video cannot play](#when-a-video-cannot-play)
8. [Common mistakes](#common-mistakes)
9. [Check your understanding](#check-your-understanding)

## The mental model

Picture a television on a stage. The television (`FlixelVideo`) is a prop: you can place it, size it, turn it, and put
other props in front of it. Inside the television sits a video player (a `FlixelVideoPlayer`) that actually decodes the
file. You only ever handle the television; the player inside is chosen for you by the platform.

| Piece                | Role                                                                                         |
|----------------------|----------------------------------------------------------------------------------------------|
| `FlixelVideos`       | The entry point. `FlixelVideos.create(file)` builds a video with the installed platform player. |
| `FlixelVideo`        | The game object you add to a state. It plays, pauses, seeks, draws, and fires signals.        |
| `FlixelVideoPlayer`  | The platform decoder inside a video. Game code does not need to touch it.                     |
| `FlixelVideoQuality` | Presets that trade sharpness for speed and memory (see [Drawing and Quality](drawing-and-quality.md)). |

`FlixelVideo` extends the framework's `FlixelBasic`, so it has the usual `exists`, `active`, `visible`, and `cameras`
fields, and it can live in any state or group.

## Creating a video

```java
FlixelVideo video = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
```

`create(...)` takes a `FlixelFile`, the same file handle the rest of the framework loads assets with. Most videos are
bundled assets (`Flixel.files.internal(...)`), but a file elsewhere on disk works too on desktop and Android
(`Flixel.files.local(...)`, `Flixel.files.external(...)`, or `Flixel.files.absolute(...)`).

Creating a video does not start it. Call `play()` when you want it to begin.

## Adding it to a state

```java
add(video);
video.play();
```

Adding the video to a state matters for two reasons:

- **It is updated every frame.** Each update copies the newest decoded frame into the video's texture and checks
  whether the video has ended. On Android, loading only begins on the first update, so a video that is never updated
  never starts.
- **It is drawn in member order.** Anything added after the video is drawn on top of it, exactly like two sprites. To
  put subtitles or a "Press Enter to skip" label over a video, add them after the video.

## Waiting for the first frame

A video takes a moment to open. Until the decoder has read the file's details and produced a first frame, the video is
not **ready**:

```java
if (video.isReady()) {
  // Width, height, and length are known now.
}
```

Before it is ready, `getVideoWidth()`, `getVideoHeight()`, and `getLength()` return `0`, and nothing is drawn. This
usually lasts only a few frames after `play()`, so most games never notice it. It matters when you size or center the
video by its own dimensions, which the next section covers.

## Sizing and centering

By default, a video is drawn at its native size, the resolution of the file itself. You can draw it at any size you
like instead:

```java
video.setSize(Flixel.getDesignWidth(), Flixel.getDesignHeight()); // Fill the design area.
video.setPosition(40f, 20f);                                      // Top-left corner.
```

A width or height of `0` means "use the native size." `getDrawWidth()` and `getDrawHeight()` always return the size
the video is actually drawn at.

`screenCenter()` centers the video using its drawn size. If you gave it an explicit size, call it once:

```java
video.setSize(960, 540);
video.screenCenter();
```

If you kept the native size, the size is `0` until the video is ready, so center it again once it is:

```java
@Override
public void update(float elapsed) {
  super.update(elapsed);
  video.screenCenter(); // Cheap, and correct as soon as the first frame arrives.
}
```

`screenCenter(FlixelAxes.X)` and `screenCenter(FlixelAxes.Y)` center along a single axis.

## Cleaning up

A video holds a decoder and a texture, which are worth releasing as soon as you no longer need them. How depends on
where the video lives:

- **In a state.** When you switch states, the old state is destroyed, and so is every member, including the video. You
  do not have to do anything.
- **Removed from a state, or never added.** Call `destroy()` yourself when you are done with it.
- **A one-shot video.** Call `setAutoDestroy(true)`, and the video destroys itself as soon as it finishes playing.

```java
FlixelVideo flash = FlixelVideos.create(Flixel.files.internal("videos/flash.mp4"));
flash.setAutoDestroy(true);
add(flash);
flash.play();
```

`destroy()` also clears every signal listener on the video. Calling it more than once is harmless.

## When a video cannot play

A missing file or a missing decoder never crashes your game. Instead, the platform hands back a stand-in player
(`FlixelUnavailableVideoPlayer`) and logs the reason. The video is still a valid object: every method works, but it never
becomes ready, never draws, and never fires `onComplete`.

If your game must not get stuck (for example, waiting on a cutscene's `onComplete` to continue), give it a way out.
The [Cookbook](cookbook.md#skip-a-video-that-cannot-play) shows a timeout for exactly this.

## Common mistakes

- **Centering too early.** A native-size video centered right after `create(...)` ends up off-center, because its size
  is still `0`. Give it an explicit size or center it again once it is ready.
- **Hiding instead of pausing.** Setting `visible = false`, `active = false`, or calling `kill()` stops drawing or
  updating the video, but the decoder keeps playing, and you keep hearing its sound. Call `pause()` or `stop()` too.
- **Keeping a removed video forever.** `remove(video)` takes it out of the state, but does not release it. Destroy it
  when you are finished with it.

## Check your understanding

1. You add a caption sprite and then a video to a state. Which one is drawn on top?
2. A video at its native size appears in the bottom-right quarter of the screen. What happened?
3. Do you need to destroy a video in your state's `destroy()` method?

(Answers: the video, because it was added last; it was centered while its size was still `0`, so center it again once
it is ready; no, a video that is a member of the state is destroyed along with the state.)

**Next:** [Controlling Playback](controlling-playback.md)
