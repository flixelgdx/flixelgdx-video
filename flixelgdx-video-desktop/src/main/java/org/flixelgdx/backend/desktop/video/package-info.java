/**
 * Desktop backend for the FlixelGDX video extension.
 *
 * <p>Frames are decoded by libvlc directly into memory through its video callbacks, copied into a
 * reusable {@link org.flixelgdx.graphics.FlixelImage FlixelImage}, and handed to core, which keeps a single
 * texture alive and scrubs its pixels. libvlc never owns a window, so videos layer like ordinary
 * state members. Install with
 * {@link org.flixelgdx.backend.desktop.video.FlixelDesktopVideoHandler#install()} in your desktop
 * launcher.
 */
package org.flixelgdx.backend.desktop.video;
