# Drawing and Quality: Placing Videos, Cameras, and Quality Presets

A video is drawn like a sprite, so everything you know about placing sprites carries over. This guide covers position,
size, rotation, scroll factors, cameras, quality presets, drawing the video's texture yourself, and inspecting videos in
the debugger.

## Table of contents

1. [Position, size, and rotation](#position-size-and-rotation)
2. [Scroll factors and cameras](#scroll-factors-and-cameras)
3. [Draw order](#draw-order)
4. [Quality presets](#quality-presets)
5. [Drawing the frame texture yourself](#drawing-the-frame-texture-yourself)
6. [Videos in the debugger](#videos-in-the-debugger)
7. [Check your understanding](#check-your-understanding)

## Position, size, and rotation

```java
video.setPosition(100f, 50f);   // Top-left corner, in world coordinates.
video.x += 2f;                  // x and y are public fields, like a sprite's.
video.setSize(640, 360);        // Drawn size in pixels. 0 means the native size.
video.angle = 15f;              // Degrees, clockwise, around the center of the video.
video.screenCenter();           // Center on screen using the drawn size.

float centerX = video.getMidpointX();
float centerY = video.getMidpointY();
```

`x`, `y`, `width`, `height`, `angle`, `scrollX`, and `scrollY` are public fields, and each also has a getter and setter.
Use whichever reads better in your code.

The drawn size is independent of the file's resolution. A 1280 by 720 video drawn at 640 by 360 is scaled down, and
the same video stretched to a 1920 by 1080 screen is scaled up. `getVideoWidth()` and `getVideoHeight()` always report
the file's own resolution, which is handy for keeping the aspect ratio:

```java
if (video.isReady()) {
  float scale = Flixel.getDesignHeight() / (float) video.getVideoHeight();
  video.setSize(video.getVideoWidth() * scale, Flixel.getDesignHeight());
  video.screenCenter();
}
```

## Scroll factors and cameras

The scroll factor works exactly like a sprite's. With the default of `1`, the video moves with the camera like part of
the world. A cutscene or a full-screen background should stay put no matter where the camera is, so pin it to the
screen:

```java
video.setScrollFactor(0f, 0f);
```

To show a video only on one camera (a HUD camera, or one half of a split screen), set its `cameras` field, the same
field every `FlixelBasic` has:

```java
video.cameras = new FlixelCamera[] {hudCamera};
```

Do this once when you set the video up, not every frame. See the framework's
[Cameras](https://github.com/flixelgdx/flixelgdx/blob/master/handbook/cameras.md) guide for HUD cameras and viewports.

## Draw order

A video is drawn into the game's normal batch, in the order it appears in its state or group. It is not a separate
window floating over the game, so you can layer anything above or below it:

```java
add(background);    // Drawn first, behind the video.
add(video);
add(subtitles);     // Drawn over the video.
add(skipHint);      // Drawn over everything.
```

## Quality presets

Decoding and copying video frames is some of the heaviest work a game can do every frame, especially on phones and in
browsers. A quality preset lowers the resolution of the frame texture, which saves memory and drawing cost. The video
stays the same size on screen; a lower preset just looks softer, like a photo enlarged from a smaller print.

| Preset    | Frame resolution                         |
|-----------|------------------------------------------|
| `FULL`    | The file's own resolution (the default). |
| `HALF`    | Half the width and height.               |
| `QUARTER` | A quarter of the width and height.       |

```java
video.setQuality(FlixelVideoQuality.HALF);
FlixelVideoQuality current = video.getQuality();
```

What a lower preset saves depends on the platform:

| Platform | What gets cheaper                                                                                    |
|----------|------------------------------------------------------------------------------------------------------|
| Desktop  | Everything after decoding: the copy of each frame into the texture, the upload, and texture memory. Changing the preset while a video plays briefly restarts it and seeks back to where it was. |
| Web      | Texture memory and drawing cost. The browser still decodes at full size. Applies immediately.          |
| Android  | Texture memory and drawing cost. The phone still decodes and uploads at full size.                     |

A good rule: use `FULL` for cutscenes that fill the screen, and try `HALF` for videos drawn small (a monitor inside the
game world) or blurred and darkened behind a menu, where the softness is invisible.

## Drawing the frame texture yourself

Most games never need this, but if you want to feed the video into your own drawing code, `getTexture()` returns the
texture that holds the current frame:

```java
FlixelTexture frame = video.getTexture(); // null until the video is ready.
```

Keep these rules in mind:

- **Fetch it every time you draw.** The video may replace its texture (for example after a quality change), so do not
  keep it in a field.
- **Do not destroy it.** The video owns the texture and releases it in `destroy()`.
- **Crop to the picture.** Decoders often make the texture a little larger than the picture. The picture's size inside
  the texture is `video.getPlayer().getFrameWidth()` by `getFrameHeight()`, starting at the top-left corner.

```java
@Override
public void draw(FlixelBatch batch) {
  super.draw(batch);
  FlixelTexture frame = video.getTexture();
  if (frame != null) {
    float u2 = Math.min(1f, video.getPlayer().getFrameWidth() / (float) frame.getWidth());
    float v2 = Math.min(1f, video.getPlayer().getFrameHeight() / (float) frame.getHeight());
    batch.draw(frame, 0f, 0f, 320f, 180f, 0f, 0f, u2, v2);
  }
}
```

`getPlayer()` is meant for advanced uses like this one. Never call `destroy()` on the player yourself; destroy the
video instead.

## Videos in the debugger

Videos work with the FlixelGDX debugger app the same way sprites do. In debug mode, a video shows up in the scene tree,
draws its box in the viewport, and can be clicked, dragged, and focused with "Go to." Selecting one opens a **Video**
section in the inspector with its position, size, angle, scroll factors, and playback state.

Most of those rows can be edited live: you can seek by typing a time, toggle "Playing," change the rate, volume, or
looping, and switch the quality by typing a preset name such as `half` (any letter case works). Edits go through the
video's normal methods, so toggling "Playing" fires `onPause` and `onResume` just like your own code would.

Nothing has to be set up for this, and release builds are not affected. See the framework's
[Debugging](https://github.com/flixelgdx/flixelgdx/blob/master/handbook/debugging.md) guide for turning on debug mode.

## Check your understanding

1. A cutscene drifts off screen when the camera follows the player. What one line fixes it?
2. You switch a full-screen cutscene to `QUARTER` quality. Does it get smaller on screen?
3. You stored `video.getTexture()` in a field in `create()`. Why is that a problem?

(Answers: `video.setScrollFactor(0f, 0f)`; no, it is stretched to the same size and only looks softer; it is `null`
until the video is ready, and the video may replace the texture later.)

**Next:** [Platform Notes](platforms.md)
