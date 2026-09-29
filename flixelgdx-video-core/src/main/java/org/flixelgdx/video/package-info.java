/**
 * Platform-neutral video playback API for FlixelGDX.
 *
 * <p>This package is the only one a game's shared code needs to depend on. It defines
 * the public contracts, the shared frame-upload path, and the lifecycle rules. The actual
 * decoder (i.e. libvlc on desktop) lives in a sibling module and is invisible to game code.
 *
 * <h2>Types in this package</h2>
 *
 * <ul>
 *   <li>{@link org.flixelgdx.video.FlixelVideo FlixelVideo}: the game-facing video object.
 *       Extends {@link org.flixelgdx.FlixelBasic FlixelBasic}, so it carries the normal
 *       lifecycle flags, participates in state draw order, and can be pooled like any other game
 *       object. It holds a {@code FlixelVideoPlayer} and adds signals, drawing, centering, and
 *       auto-pause on top. It is final; platforms customize the player, not this class.</li>
 *   <li>{@link org.flixelgdx.video.FlixelVideoPlayer FlixelVideoPlayer}: the platform backend
 *       contract. It decodes the file and owns the texture that holds the latest frame.</li>
 *   <li>{@link org.flixelgdx.video.FlixelVideos FlixelVideos}: static helper that creates
 *       {@code FlixelVideo} instances through the backend registered by the launcher.</li>
 *   <li>{@link org.flixelgdx.video.FlixelVideoFactory FlixelVideoFactory}: the service
 *       contract each platform satisfies by creating players. One implementation is installed
 *       once in the launcher; everything else in the game never touches it again.</li>
 *   <li>{@link org.flixelgdx.video.FlixelVideoCpuFrame FlixelVideoCpuFrame}: a helper for
 *       players that decode to CPU RGBA pixels. It keeps one reusable image and texture and
 *       rewrites the pixels in place.</li>
 *   <li>{@link org.flixelgdx.video.FlixelVideoQuality FlixelVideoQuality}: a decode quality
 *       preset. Lower presets reduce CPU cost and upload bandwidth by decoding into a smaller
 *       pixel buffer; the drawn size on screen is unaffected.</li>
 *   <li>{@link org.flixelgdx.video.FlixelUnavailableVideoPlayer FlixelUnavailableVideoPlayer}:
 *       a do-nothing player returned when the native decoder cannot be set up (for example, no
 *       VLC on a desktop machine). Every control call is a safe no-op so the game keeps
 *       running; {@link org.flixelgdx.video.FlixelVideo#isReady() FlixelVideo.isReady()} stays
 *       {@code false}.</li>
 * </ul>
 *
 * <h2>Getting started</h2>
 *
 * <p>Step one: register the platform backend in the launcher, before the game starts.
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   FlixelDesktopVideoHandler.install();  // Desktop example.
 *   FlixelDesktopLauncher.launch(new MyGame());
 * }
 * }</pre>
 *
 * <p>Step two: create a video anywhere in game code and add it to a state, exactly like a
 * sprite. The file path is resolved through {@link org.flixelgdx.Flixel#files Flixel.files},
 * the same seam all other assets use.
 *
 * <pre>{@code
 * FlixelVideo cutscene = FlixelVideos.create(Flixel.files.internal("videos/intro.mp4"));
 * cutscene.setSize(Flixel.getDesignWidth(), Flixel.getDesignHeight());
 * cutscene.setLooped(false);
 * cutscene.onComplete.add(data -> Flixel.switchState(() -> new MenuState()));
 * add(cutscene);
 * cutscene.play();
 * }</pre>
 *
 * <p>All time values are in milliseconds, matching
 * {@link org.flixelgdx.audio.FlixelSound FlixelSound}. Call
 * {@link org.flixelgdx.video.FlixelVideo#destroy() FlixelVideo.destroy()} when the
 * video leaves the game for good to release the decoder and the frame texture.
 *
 * <h2>Frame texture architecture</h2>
 *
 * <p>Each player owns one {@link org.flixelgdx.graphics.FlixelTexture FlixelTexture} that holds
 * the latest frame, and exposes it through
 * {@link org.flixelgdx.video.FlixelVideoPlayer#getFrame() FlixelVideoPlayer.getFrame()}. The
 * framework batch draws it like any normal texture. How the texture is filled is up to the
 * backend: players that decode to CPU pixels use {@code FlixelVideoCpuFrame} to reuse one image
 * and one texture and rewrite the pixels in place, while GPU-based players (such as Android) render
 * straight into their texture.
 *
 * <p>Decoders often produce a texture slightly larger than the visible frame because of codec
 * row-alignment requirements. The player reports the visible size through
 * {@code getFrameWidth()} and {@code getFrameHeight()}, and the draw call crops the sampled region
 * to it rather than stretching the padding across the quad, so the edges are always sharp.
 *
 * <h2>Auto-pause</h2>
 *
 * <p>When {@link org.flixelgdx.Flixel#autoPause Flixel.autoPause} is {@code true}, every
 * {@code FlixelVideo} listens to
 * {@link org.flixelgdx.Flixel.Signals#windowUnfocused Flixel.Signals.windowUnfocused} and
 * {@link org.flixelgdx.Flixel.Signals#windowFocused Flixel.Signals.windowFocused}. The video
 * pauses automatically when the window loses focus or the app is backgrounded, and resumes
 * when focus returns.
 *
 * <h2>Writing a backend</h2>
 *
 * <p>A backend implements {@code FlixelVideoPlayer} and a {@code FlixelVideoFactory} that
 * creates it. The player handles the decoder lifecycle and the frame texture; {@code FlixelVideo}
 * handles display, lifecycle flags, auto-pause, and the {@code onComplete} signal. Install the
 * factory once in the launcher via
 * {@link org.flixelgdx.video.FlixelVideos#setBackendFactory(FlixelVideoFactory)
 * FlixelVideos.setBackendFactory}. See the {@code flixelgdx-video-desktop} and
 * {@code flixelgdx-video-html5} modules for reference implementations.
 */
package org.flixelgdx.video;
