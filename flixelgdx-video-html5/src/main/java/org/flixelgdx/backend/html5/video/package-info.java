/**
 * Web backend for the FlixelGDX video extension.
 *
 * <p>A hidden HTML video element decodes the stream; each new frame is uploaded straight from the
 * element to a WebGL texture, with no CPU readback. Lower quality presets shrink that texture into a
 * smaller render target on the GPU. Videos then draw through the normal batch and layer with other
 * state members. Install with
 * {@link org.flixelgdx.backend.html5.video.FlixelHtml5VideoHandler#install()} in your web launcher.
 */
package org.flixelgdx.backend.html5.video;
