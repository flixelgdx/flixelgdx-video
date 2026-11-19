# The FlixelGDX Video Handbook

Welcome! This handbook is the guide to playing video in a FlixelGDX game with the video extension: cutscenes, intro
logos, menu backgrounds, and anything else that moves. It explains every part of the extension in plain language, with
working code examples and short quizzes, ordered from installing it to the details of each platform.

## How to use this handbook

- **Every guide stands on its own.** Each one starts with its own table of contents and links to related guides, so
  you can jump straight to what you need.
- **Code examples use the real API.** Copy them into your project and adapt them. Imports are shown in longer
  examples; shorter snippets assume the usual `org.flixelgdx` and `org.flixelgdx.video` imports.
- **The [Cookbook](cookbook.md)** has complete recipes, such as a skippable cutscene, and one-line answers to quick
  questions.
- **The Javadoc goes deeper.** Every class is documented in detail, and the `org.flixelgdx.video` package overview is a
  short tutorial of its own.

If you are new to FlixelGDX itself, start with the framework's
[handbook](https://github.com/flixelgdx/flixelgdx/blob/master/handbook/README.md). This one assumes you know what a
state, a sprite, and `Flixel.files` are.

## Guides

| # | Guide | What you will learn |
|---|-------|---------------------|
| 1 | [Getting Started](getting-started.md) | The modules, the Gradle dependencies for each platform, installing the handler, and a first video. |
| 2 | [Playing Videos](playing-videos.md) | What a video is, adding it to a state, waiting for the first frame, sizing, centering, and cleanup. |
| 3 | [Controlling Playback](controlling-playback.md) | Pause, resume, stop, seeking, speed, volume, looping, signals, auto-pause, and pause menus. |
| 4 | [Drawing and Quality](drawing-and-quality.md) | Position, rotation, scroll factors, cameras, draw order, quality presets, the frame texture, and the debugger. |
| 5 | [Platform Notes](platforms.md) | File formats, finding libvlc on desktop, web autoplay and URLs, and Android asset rules. |
| 6 | [Cookbook](cookbook.md) | A skippable cutscene, a timeout for missing videos, a looping menu background, and quick answers. |

## Other documentation

- **[`README.md`](../README.md)**: the project overview and a quick start.
- **[`ARCHITECTURE.md`](../ARCHITECTURE.md)**: the module layout and how each platform gets frames to the screen, for
  contributors.
- **[`COMPILING.md`](../COMPILING.md)** and **[`CONTRIBUTING.md`](../CONTRIBUTING.md)**: building the extension and
  contributing to it.
