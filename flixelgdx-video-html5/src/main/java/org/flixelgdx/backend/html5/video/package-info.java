/**
 * Web backend for the FlixelGDX video extension.
 *
 * <p>A hidden HTML video element decodes the stream; each frame is drawn onto an offscreen canvas,
 * read back into a reusable {@link org.flixelgdx.graphics.FlixelImage}, and handed to core, which
 * keeps a single texture alive and scrubs its pixels. Videos then draw through the normal batch and
 * layer with other state members. Install with
 * {@link org.flixelgdx.backend.html5.video.FlixelHtml5VideoHandler#install()} in your web launcher.
 */
package org.flixelgdx.backend.html5.video;
