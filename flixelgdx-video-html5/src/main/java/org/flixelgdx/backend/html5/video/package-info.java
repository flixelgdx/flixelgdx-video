/**
 * Web backend for the FlixelGDX video extension.
 *
 * <p>A hidden HTML video element decodes the stream; each new frame is copied straight from the
 * element to a WebGPU texture with {@code copyExternalImageToTexture}, with no CPU readback. Lower
 * quality presets shrink that texture into a smaller render target with a small WGSL pass. Videos then draw through the normal batch and layer with other
 * state members. Install with
 * {@link org.flixelgdx.backend.html5.video.FlixelHtml5VideoHandler#install()} in your web launcher.
 */
package org.flixelgdx.backend.html5.video;
