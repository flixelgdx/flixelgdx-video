/**
 * Android backend for the FlixelGDX video extension.
 *
 * <p>Frames are decoded by the platform {@code MediaPlayer} into an {@code ImageReader} as YUV 4:2:0
 * planes. The planes are uploaded to WebGPU textures straight from their direct buffers, and a small
 * WGSL pass converts them to RGBA inside a render target that the batch draws like any other image.
 */
package org.flixelgdx.backend.android.video;
