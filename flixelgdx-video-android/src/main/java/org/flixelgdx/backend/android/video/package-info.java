/**
 * Android backend for the FlixelGDX video extension.
 *
 * <p>Frames are decoded by the platform {@code MediaPlayer}, read back into a reusable
 * {@link org.flixelgdx.graphics.FlixelImage FlixelImage}, and handed to core, which keeps a single
 * texture alive and rewrites its pixels each frame.
 */
package org.flixelgdx.backend.android.video;
