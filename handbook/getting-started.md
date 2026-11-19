# Getting Started: Installing the Extension and Playing Your First Video

This guide adds the video extension to a FlixelGDX game, explains why it is split into several modules, and plays a
first video on screen.

## Table of contents

1. [Why the extension is split into modules](#why-the-extension-is-split-into-modules)
2. [Adding the dependencies](#adding-the-dependencies)
3. [Installing the video handler](#installing-the-video-handler)
4. [Where to put video files](#where-to-put-video-files)
5. [Your first video](#your-first-video)
6. [Common mistakes](#common-mistakes)
7. [Check your understanding](#check-your-understanding)

## Why the extension is split into modules

Decoding video is very different on each platform. A desktop game uses a bundled video library, a web game asks the
browser to decode, and an Android game uses the decoder built into the phone. If all of that lived in one module, every
game would ship every decoder, even the ones it can never use.

Think of a travel adapter kit: you carry one phone (your game code) and only plug in the adapter for the country you
are in. The extension works the same way:

| Module                     | Where it goes                | What it does                                                                 |
|----------------------------|------------------------------|------------------------------------------------------------------------------|
| `flixelgdx-video-core`     | Your shared `core` module    | The API your game code uses: `FlixelVideo`, `FlixelVideos`, `FlixelVideoQuality`. |
| `flixelgdx-video-desktop`  | Your desktop launcher module | Decodes with libvlc. The libvlc natives for each desktop platform come with it. |
| `flixelgdx-video-html5`    | Your web launcher module     | Decodes with the browser's own video support.                                |
| `flixelgdx-video-android`  | Your Android launcher module | Decodes with the Android `MediaPlayer`. Needs Android 8.0 (API 26) or newer.  |

Your game code only ever talks to the core API. The platform module is plugged in by one line in each launcher.

## Adding the dependencies

The video artifacts are published to Maven Central under the group `org.flixelgdx` and are versioned in lockstep with
the framework, so use the same version as your `flixelgdx-core` dependency.

```kotlin
// core/build.gradle.kts
dependencies {
  api("org.flixelgdx:flixelgdx-video-core:$flixelVersion")
}

// desktop/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-video-desktop:$flixelVersion")
}

// html5/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-video-html5:$flixelVersion")
}

// android/build.gradle.kts
dependencies {
  implementation("org.flixelgdx:flixelgdx-video-android:$flixelVersion")
}
```

Each platform module depends on `flixelgdx-video-core`, so the API comes along with it. Only add the platform modules
for the platforms you actually ship.

## Installing the video handler

Before any video can be created, the platform has to register its decoder. Each platform module has a handler with an
`install(...)` method. Call it in your launcher, before the game starts:

```java
// desktop/src/main/java/com/example/mygame/DesktopLauncher.java
public final class DesktopLauncher {
  public static void main(String[] args) {
    FlixelDesktopVideoHandler.install();
    FlixelDesktopLauncher.launch(new MyGame());
  }

  private DesktopLauncher() {}
}
```

```java
// html5/src/main/java/com/example/mygame/WebLauncher.java
public final class WebLauncher {
  public static void main(String[] args) {
    FlixelHtml5VideoHandler.install();
    FlixelHtml5Launcher.launch(new MyGame());
  }

  private WebLauncher() {}
}
```

```java
// android/src/main/java/com/example/mygame/GameActivity.java
public final class GameActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    FlixelAndroidVideoHandler.install(this);
    FlixelAndroidLauncher.launch(this, new MyGame());
  }
}
```

The Android handler takes a `Context` because it needs to reach the APK's assets. It only keeps the application
context, so passing your activity does not leak it.

Installing is cheap. On desktop, libvlc is not even loaded until the first video is created. Calling `install(...)`
more than once is safe.

| Platform | Handler                     | Package                             |
|----------|-----------------------------|-------------------------------------|
| Desktop  | `FlixelDesktopVideoHandler` | `org.flixelgdx.backend.desktop.video` |
| Web      | `FlixelHtml5VideoHandler`   | `org.flixelgdx.backend.html5.video`   |
| Android  | `FlixelAndroidVideoHandler` | `org.flixelgdx.backend.android.video` |

## Where to put video files

Videos are loaded through `Flixel.files`, exactly like images and sounds. Put them in your assets folder (for example
`assets/videos/intro.mp4`) and refer to them with `Flixel.files.internal(...)`:

```java
FlixelFile file = Flixel.files.internal("videos/intro.mp4");
```

Keep file names lowercase and use forward slashes, because the web and Android are case-sensitive. For the safest
format choice and per-platform details, see [Platform Notes](platforms.md).

## Your first video

A `FlixelVideo` is a game object, so you add it to a state like a sprite. Here is a state that plays an intro video
and moves on to the menu when it ends:

```java
import org.flixelgdx.Flixel;
import org.flixelgdx.FlixelState;
import org.flixelgdx.video.FlixelVideo;
import org.flixelgdx.video.FlixelVideos;

public class IntroState extends FlixelState {

  private FlixelVideo intro;

  @Override
  public void create() {
    intro = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
    intro.setSize(Flixel.getDesignWidth(), Flixel.getDesignHeight());
    intro.onComplete.add(data -> Flixel.switchState(MenuState::new));
    add(intro);
    intro.play();
  }
}
```

That is the whole setup. Three things happened:

1. `FlixelVideos.create(...)` asked the installed handler for a decoder and wrapped it in a `FlixelVideo`.
2. `add(intro)` put the video in the state, so it is updated and drawn every frame, in member order.
3. `play()` started playback. When the video finished, `onComplete` fired and switched states. Switching states
   destroys the old state and its members, so the decoder and its texture were released too.

The next guide, [Playing Videos](playing-videos.md), explains each of these steps in detail.

## Common mistakes

- **Forgetting the handler.** `FlixelVideos.create(...)` throws an `IllegalStateException` if no handler was installed.
  The message names the `install(...)` method for each platform.
- **Adding the core module only.** The game compiles, but the launcher has no handler to install. Each launcher module
  needs its own platform module.
- **Installing after launching.** Call `install(...)` before the launcher's `launch(...)`, so the handler is ready
  before your first state is created.

## Check your understanding

1. Your shared `core` module needs one video dependency. Which one?
2. A web build throws "No video backend factory registered" when a state is created. What is missing?
3. Why does the Android handler need a `Context`, while the desktop and web handlers do not?

(Answers: `flixelgdx-video-core`; the web launcher never calls `FlixelHtml5VideoHandler.install()`; it opens videos
packed in the APK's assets, which are reached through a `Context`.)

**Next:** [Playing Videos](playing-videos.md)
