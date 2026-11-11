/*
 * MIT License
 *
 * Copyright (c) 2026 stringdotjar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.flixelgdx.backend.android.video;

import org.flixelgdx.backend.gpu.FlixelGpuTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.stringdotjar.jwebgpu.GpuAddressMode;
import io.github.stringdotjar.jwebgpu.GpuBindGroup;
import io.github.stringdotjar.jwebgpu.GpuBindGroupDescriptor;
import io.github.stringdotjar.jwebgpu.GpuBindGroupLayout;
import io.github.stringdotjar.jwebgpu.GpuBindGroupLayoutDescriptor;
import io.github.stringdotjar.jwebgpu.GpuBindGroupLayoutEntry;
import io.github.stringdotjar.jwebgpu.GpuBuffer;
import io.github.stringdotjar.jwebgpu.GpuBufferBindingType;
import io.github.stringdotjar.jwebgpu.GpuBufferDescriptor;
import io.github.stringdotjar.jwebgpu.GpuBufferUsage;
import io.github.stringdotjar.jwebgpu.GpuColorWriteMask;
import io.github.stringdotjar.jwebgpu.GpuCommandBuffer;
import io.github.stringdotjar.jwebgpu.GpuCommandEncoder;
import io.github.stringdotjar.jwebgpu.GpuCullMode;
import io.github.stringdotjar.jwebgpu.GpuDevice;
import io.github.stringdotjar.jwebgpu.GpuExtent3D;
import io.github.stringdotjar.jwebgpu.GpuFilterMode;
import io.github.stringdotjar.jwebgpu.GpuFragmentState;
import io.github.stringdotjar.jwebgpu.GpuFrontFace;
import io.github.stringdotjar.jwebgpu.GpuLoadOp;
import io.github.stringdotjar.jwebgpu.GpuMipmapFilterMode;
import io.github.stringdotjar.jwebgpu.GpuPipelineLayout;
import io.github.stringdotjar.jwebgpu.GpuPipelineLayoutDescriptor;
import io.github.stringdotjar.jwebgpu.GpuPrimitiveTopology;
import io.github.stringdotjar.jwebgpu.GpuQueue;
import io.github.stringdotjar.jwebgpu.GpuRenderPassColorAttachment;
import io.github.stringdotjar.jwebgpu.GpuRenderPassDescriptor;
import io.github.stringdotjar.jwebgpu.GpuRenderPassEncoder;
import io.github.stringdotjar.jwebgpu.GpuRenderPipeline;
import io.github.stringdotjar.jwebgpu.GpuRenderPipelineDescriptor;
import io.github.stringdotjar.jwebgpu.GpuSampler;
import io.github.stringdotjar.jwebgpu.GpuSamplerBindingType;
import io.github.stringdotjar.jwebgpu.GpuSamplerDescriptor;
import io.github.stringdotjar.jwebgpu.GpuShaderModule;
import io.github.stringdotjar.jwebgpu.GpuShaderModuleDescriptor;
import io.github.stringdotjar.jwebgpu.GpuShaderSourceWGSL;
import io.github.stringdotjar.jwebgpu.GpuShaderStage;
import io.github.stringdotjar.jwebgpu.GpuStoreOp;
import io.github.stringdotjar.jwebgpu.GpuTexelCopyBufferLayout;
import io.github.stringdotjar.jwebgpu.GpuTexelCopyTextureInfo;
import io.github.stringdotjar.jwebgpu.GpuTexture;
import io.github.stringdotjar.jwebgpu.GpuTextureAspect;
import io.github.stringdotjar.jwebgpu.GpuTextureDescriptor;
import io.github.stringdotjar.jwebgpu.GpuTextureDimension;
import io.github.stringdotjar.jwebgpu.GpuTextureFormat;
import io.github.stringdotjar.jwebgpu.GpuTextureSampleType;
import io.github.stringdotjar.jwebgpu.GpuTextureUsage;
import io.github.stringdotjar.jwebgpu.GpuTextureView;
import io.github.stringdotjar.jwebgpu.GpuTextureViewDimension;

import java.nio.ByteBuffer;

/**
 * Turns one decoded YUV 4:2:0 video frame into RGBA pixels inside a render target, on the GPU.
 *
 * <p>Android decoders hand out frames as three planes: full-size brightness (Y) and two half-size color
 * planes (U and V). This pass uploads each plane into its own one-channel {@code R8} texture with
 * {@code writeTexture}, straight from the plane's direct buffer, then draws one full-screen triangle that
 * converts YUV to RGB into the target. The target can be smaller than the video (lower quality presets);
 * brightness is then sampled with linear filtering.
 *
 * <p>The color planes come in two layouts. "Planar" (I420) keeps U and V apart with one byte per sample.
 * "Semi-planar" (NV12 and NV21) interleaves them, so each plane's buffer has a pixel stride of 2 and the
 * next sample is two bytes further on. A {@code writeTexture} copy cannot skip bytes, so each color plane is
 * uploaded as it sits in memory, interleaved bytes included, and the shader reads every
 * {@code pixelStride}-th texel. That works for both layouts with the same code.
 *
 * <p>Conversion uses limited-range BT.709 for videos at least 720 pixels tall and BT.601 below that, which
 * is what most files use.
 *
 * <p>Every GPU object is built once and reused; the plane textures and the bind group are only rebuilt when
 * the frame size or layout changes. A frame allocates nothing. If the framework replaces its device (after a
 * device loss), everything is rebuilt on the new one.
 */
final class FlixelAndroidYuvPass {

  /** Size of the uniform block in bytes: visible size, chroma pixel stride, and the BT.709 switch. */
  private static final int PARAMS_SIZE = 16;

  private static final String SHADER = """
      struct Params {
        size: vec2f,
        step: f32,
        bt709: f32,
      };

      @group(0) @binding(0) var lumaTex: texture_2d<f32>;
      @group(0) @binding(1) var uTex: texture_2d<f32>;
      @group(0) @binding(2) var vTex: texture_2d<f32>;
      @group(0) @binding(3) var smp: sampler;
      @group(0) @binding(4) var<uniform> p: Params;

      struct Out {
        @builtin(position) pos: vec4f,
        @location(0) uv: vec2f,
      };

      @vertex
      fn vs(@builtin(vertex_index) i: u32) -> Out {
        let q = vec2f(f32((i << 1u) & 2u), f32(i & 2u));
        var o: Out;
        o.pos = vec4f(q * 2.0 - 1.0, 0.0, 1.0);
        o.uv = vec2f(q.x, 1.0 - q.y);
        return o;
      }

      @fragment
      fn fs(in: Out) -> @location(0) vec4f {
        let px = in.uv * p.size;
        let y = textureSampleLevel(lumaTex, smp, px / vec2f(textureDimensions(lumaTex)), 0.0).r;
        let step = i32(p.step);
        let dims = vec2i(textureDimensions(uTex));
        let last = vec2i((dims.x - 1) / step, dims.y - 1);
        let c = clamp(vec2i(px * 0.5), vec2i(0), last);
        let at = vec2i(c.x * step, c.y);
        let u = textureLoad(uTex, at, 0).r - 0.5;
        let v = textureLoad(vTex, at, 0).r - 0.5;
        let l = (y - 0.0627451) * 1.164384;
        var rgb: vec3f;
        if (p.bt709 > 0.5) {
          rgb = vec3f(l + 1.792741 * v, l - 0.213249 * u - 0.532909 * v, l + 2.112402 * u);
        } else {
          rgb = vec3f(l + 1.596027 * v, l - 0.391762 * u - 0.812968 * v, l + 2.017232 * u);
        }
        return vec4f(clamp(rgb, vec3f(0.0), vec3f(1.0)), 1.0);
      }
      """;

  private final float[] params = new float[4];
  private final GpuCommandBuffer[] submit = new GpuCommandBuffer[1];
  private final GpuTexture[] planes = new GpuTexture[3];
  private final GpuTextureView[] views = new GpuTextureView[3];
  private final GpuBindGroupDescriptor groupDescriptor = new GpuBindGroupDescriptor(5);
  private final GpuRenderPassDescriptor passDescriptor = new GpuRenderPassDescriptor(1);
  private final GpuTextureDescriptor textureDescriptor = new GpuTextureDescriptor();
  private final GpuTexelCopyTextureInfo copyDestination = new GpuTexelCopyTextureInfo();
  private final GpuTexelCopyBufferLayout copyLayout = new GpuTexelCopyBufferLayout();
  private final GpuExtent3D copyExtent = new GpuExtent3D();

  /** Width of the luma texture, which is the decoder's buffer width (it can be wider than the video). */
  private int lumaWidth;

  private int lumaHeight;

  /** Width of each chroma texture, counting the interleaved bytes of the other plane. */
  private int chromaWidth;

  private int chromaHeight;

  @Nullable
  private GpuDevice device;

  @Nullable
  private GpuBindGroupLayout layout;

  @Nullable
  private GpuPipelineLayout pipelineLayout;

  @Nullable
  private GpuShaderModule module;

  @Nullable
  private GpuRenderPipeline pipeline;

  @Nullable
  private GpuSampler sampler;

  @Nullable
  private GpuBuffer uniforms;

  @Nullable
  private GpuBindGroup group;

  @Nullable
  private GpuCommandEncoder encoder;

  @Nullable
  private GpuRenderPassEncoder pass;

  @Nullable
  private GpuCommandBuffer commands;

  /**
   * Uploads one frame's planes into the pass's plane textures.
   *
   * <p>The plane buffers are read during this call only, so the frame can be closed as soon as it returns.
   * A frame whose buffers are smaller than their strides promise is skipped and {@code false} is returned.
   * Call {@link #convert} afterward to turn the planes into RGBA pixels.
   *
   * @param device The framework's device.
   * @param queue The framework's queue.
   * @param frameWidth The decoder's buffer width in pixels.
   * @param frameHeight The decoder's buffer height in pixels.
   * @param videoWidth The visible video width in pixels, at most {@code frameWidth}.
   * @param videoHeight The visible video height in pixels, at most {@code frameHeight}.
   * @param y The luma plane; its pixel stride must be 1.
   * @param yRowStride Bytes between the starts of two luma rows.
   * @param u The U plane.
   * @param v The V plane.
   * @param uvRowStride Bytes between the starts of two rows of the U and V planes.
   * @param uvPixelStride Bytes between two samples of the U and V planes, 1 or 2.
   * @return {@code true} if the planes were uploaded.
   */
  boolean upload(@NotNull GpuDevice device, @NotNull GpuQueue queue, int frameWidth, int frameHeight,
      int videoWidth, int videoHeight, @NotNull ByteBuffer y, int yRowStride, @NotNull ByteBuffer u,
      @NotNull ByteBuffer v, int uvRowStride, int uvPixelStride) {
    int halfWidth = (frameWidth + 1) / 2;
    int halfHeight = (frameHeight + 1) / 2;
    int uvWidth = (halfWidth - 1) * uvPixelStride + 1;
    if (y.remaining() < (long) yRowStride * (frameHeight - 1) + frameWidth
        || u.remaining() < (long) uvRowStride * (halfHeight - 1) + uvWidth
        || v.remaining() < (long) uvRowStride * (halfHeight - 1) + uvWidth) {
      return false;
    }

    if (device != this.device) {
      build(device);
    }
    if (frameWidth != lumaWidth || frameHeight != lumaHeight || uvWidth != chromaWidth
        || halfHeight != chromaHeight || group == null) {
      resize(device, frameWidth, frameHeight, uvWidth, halfHeight);
    }

    write(queue, planes[0], y, yRowStride, frameWidth, frameHeight);
    write(queue, planes[1], u, uvRowStride, uvWidth, halfHeight);
    write(queue, planes[2], v, uvRowStride, uvWidth, halfHeight);
    params[0] = videoWidth;
    params[1] = videoHeight;
    params[2] = uvPixelStride;
    params[3] = videoHeight >= 720 ? 1f : 0f;
    queue.writeBuffer(uniforms, 0L, params, 0, params.length);
    return true;
  }

  /**
   * Converts the last uploaded frame into {@code target}, replacing everything the target held.
   *
   * <p>This can run again without a new upload, for example to redraw a paused video at another size.
   *
   * @param device The framework's device.
   * @param queue The framework's queue.
   * @param target The render target texture to fill. It must be {@code RGBA8} and allow rendering.
   * @return {@code false} if there is no frame on {@code device} to convert yet.
   */
  boolean convert(@NotNull GpuDevice device, @NotNull GpuQueue queue, @NotNull FlixelGpuTexture target) {
    if (device != this.device || group == null) {
      return false;
    }
    GpuRenderPassColorAttachment color = passDescriptor.colorAttachments[0];
    color.view = target.getView();
    encoder = encoder == null ? device.createCommandEncoder(null) : device.createCommandEncoder(null, encoder);
    pass = pass == null ? encoder.beginRenderPass(passDescriptor) : encoder.beginRenderPass(passDescriptor, pass);
    pass.setPipeline(pipeline);
    pass.setBindGroup(0, group);
    pass.draw(3, 1, 0, 0);
    pass.end();
    commands = commands == null ? encoder.finish(null) : encoder.finish(null, commands);
    submit[0] = commands;
    queue.submit(submit, 1);
    color.view = null;
    return true;
  }

  /** Releases every GPU object this pass holds. Safe to call more than once. */
  void destroy() {
    release();
    device = null;
  }

  private void write(@NotNull GpuQueue queue, @NotNull GpuTexture texture, @NotNull ByteBuffer data, int rowStride,
      int width, int height) {
    copyDestination.texture = texture;
    copyLayout.bytesPerRow = rowStride;
    copyLayout.rowsPerImage = height;
    copyExtent.width = width;
    copyExtent.height = height;
    queue.writeTexture(copyDestination, data, copyLayout, copyExtent);
  }

  /** Recreates the plane textures and the bind group for a new frame size or layout. */
  private void resize(@NotNull GpuDevice device, int width, int height, int uvWidth, int uvHeight) {
    releasePlanes();
    planes[0] = createPlane(device, width, height);
    planes[1] = createPlane(device, uvWidth, uvHeight);
    planes[2] = createPlane(device, uvWidth, uvHeight);
    for (int i = 0; i < planes.length; i++) {
      views[i] = planes[i].createView(null);
    }
    lumaWidth = width;
    lumaHeight = height;
    chromaWidth = uvWidth;
    chromaHeight = uvHeight;

    groupDescriptor.reset();
    groupDescriptor.label = "flixel video yuv";
    groupDescriptor.layout = layout;
    groupDescriptor.entryCount = 5;
    for (int i = 0; i < 3; i++) {
      groupDescriptor.entries[i].binding = i;
      groupDescriptor.entries[i].textureView = views[i];
    }
    groupDescriptor.entries[3].binding = 3;
    groupDescriptor.entries[3].sampler = sampler;
    groupDescriptor.entries[4].binding = 4;
    groupDescriptor.entries[4].buffer = uniforms;
    groupDescriptor.entries[4].offset = 0L;
    groupDescriptor.entries[4].size = PARAMS_SIZE;
    group = group == null ? device.createBindGroup(groupDescriptor) : device.createBindGroup(groupDescriptor, group);
  }

  @NotNull
  private GpuTexture createPlane(@NotNull GpuDevice device, int width, int height) {
    textureDescriptor.reset();
    textureDescriptor.label = "flixel video plane";
    textureDescriptor.usage = GpuTextureUsage.TEXTURE_BINDING | GpuTextureUsage.COPY_DST;
    textureDescriptor.dimension = GpuTextureDimension.D2;
    textureDescriptor.size.width = width;
    textureDescriptor.size.height = height;
    textureDescriptor.size.depthOrArrayLayers = 1;
    textureDescriptor.format = GpuTextureFormat.R8_UNORM;
    return device.createTexture(textureDescriptor);
  }

  /** Builds the pipeline, layouts, sampler, and uniform buffer on {@code device}, dropping older objects. */
  private void build(@NotNull GpuDevice device) {
    release();
    this.device = device;

    GpuBindGroupLayoutDescriptor layoutDescriptor = new GpuBindGroupLayoutDescriptor(5);
    layoutDescriptor.label = "flixel video yuv";
    layoutDescriptor.entryCount = 5;
    for (int i = 0; i < 3; i++) {
      GpuBindGroupLayoutEntry entry = layoutDescriptor.entries[i];
      entry.binding = i;
      entry.visibility = GpuShaderStage.FRAGMENT;
      entry.texture.sampleType = GpuTextureSampleType.FLOAT;
      entry.texture.viewDimension = GpuTextureViewDimension.D2;
    }
    layoutDescriptor.entries[3].binding = 3;
    layoutDescriptor.entries[3].visibility = GpuShaderStage.FRAGMENT;
    layoutDescriptor.entries[3].sampler.type = GpuSamplerBindingType.FILTERING;
    layoutDescriptor.entries[4].binding = 4;
    layoutDescriptor.entries[4].visibility = GpuShaderStage.FRAGMENT;
    layoutDescriptor.entries[4].buffer.type = GpuBufferBindingType.UNIFORM;
    layoutDescriptor.entries[4].buffer.minBindingSize = PARAMS_SIZE;
    layout = device.createBindGroupLayout(layoutDescriptor);

    GpuPipelineLayoutDescriptor pipelineLayoutDescriptor = new GpuPipelineLayoutDescriptor(1);
    pipelineLayoutDescriptor.label = "flixel video yuv";
    pipelineLayoutDescriptor.bindGroupLayoutCount = 1;
    pipelineLayoutDescriptor.bindGroupLayouts[0] = layout;
    pipelineLayout = device.createPipelineLayout(pipelineLayoutDescriptor);

    GpuShaderSourceWGSL wgsl = new GpuShaderSourceWGSL();
    wgsl.code = SHADER;
    GpuShaderModuleDescriptor moduleDescriptor = new GpuShaderModuleDescriptor();
    moduleDescriptor.label = "flixel video yuv";
    moduleDescriptor.nextInChain = wgsl;
    module = device.createShaderModule(moduleDescriptor);

    GpuRenderPipelineDescriptor pipelineDescriptor = new GpuRenderPipelineDescriptor();
    pipelineDescriptor.label = "flixel video yuv";
    pipelineDescriptor.layout = pipelineLayout;
    pipelineDescriptor.vertex.module = module;
    pipelineDescriptor.vertex.entryPoint = "vs";
    pipelineDescriptor.primitive.topology = GpuPrimitiveTopology.TRIANGLE_LIST;
    pipelineDescriptor.primitive.frontFace = GpuFrontFace.CCW;
    pipelineDescriptor.primitive.cullMode = GpuCullMode.NONE;
    GpuFragmentState fragment = new GpuFragmentState();
    fragment.module = module;
    fragment.entryPoint = "fs";
    fragment.targetCount = 1;
    fragment.targets[0].format = GpuTextureFormat.RGBA8_UNORM;
    fragment.targets[0].writeMask = GpuColorWriteMask.ALL;
    pipelineDescriptor.fragment = fragment;
    pipeline = device.createRenderPipeline(pipelineDescriptor);

    GpuSamplerDescriptor samplerDescriptor = new GpuSamplerDescriptor();
    samplerDescriptor.label = "flixel video yuv";
    samplerDescriptor.addressModeU = GpuAddressMode.CLAMP_TO_EDGE;
    samplerDescriptor.addressModeV = GpuAddressMode.CLAMP_TO_EDGE;
    samplerDescriptor.addressModeW = GpuAddressMode.CLAMP_TO_EDGE;
    samplerDescriptor.magFilter = GpuFilterMode.LINEAR;
    samplerDescriptor.minFilter = GpuFilterMode.LINEAR;
    samplerDescriptor.mipmapFilter = GpuMipmapFilterMode.NEAREST;
    sampler = device.createSampler(samplerDescriptor);

    GpuBufferDescriptor bufferDescriptor = new GpuBufferDescriptor();
    bufferDescriptor.label = "flixel video yuv";
    bufferDescriptor.usage = GpuBufferUsage.UNIFORM | GpuBufferUsage.COPY_DST;
    bufferDescriptor.size = PARAMS_SIZE;
    uniforms = device.createBuffer(bufferDescriptor);

    copyDestination.reset();
    copyDestination.aspect = GpuTextureAspect.ALL;
    copyLayout.reset();
    copyExtent.reset();
    copyExtent.depthOrArrayLayers = 1;

    GpuRenderPassColorAttachment color = passDescriptor.colorAttachments[0];
    color.loadOp = GpuLoadOp.CLEAR;
    color.storeOp = GpuStoreOp.STORE;
    color.clearValue.a = 1.0;
    passDescriptor.colorAttachmentCount = 1;
  }

  private void releasePlanes() {
    for (int i = 0; i < planes.length; i++) {
      if (views[i] != null) {
        views[i].release();
        views[i] = null;
      }
      if (planes[i] != null) {
        planes[i].destroy();
        planes[i].release();
        planes[i] = null;
      }
    }
    lumaWidth = 0;
    lumaHeight = 0;
    chromaWidth = 0;
    chromaHeight = 0;
  }

  private void release() {
    releasePlanes();
    if (group != null) {
      group.release();
      group = null;
    }
    if (uniforms != null) {
      uniforms.destroy();
      uniforms.release();
      uniforms = null;
    }
    if (sampler != null) {
      sampler.release();
      sampler = null;
    }
    if (pipeline != null) {
      pipeline.release();
      pipeline = null;
    }
    if (module != null) {
      module.release();
      module = null;
    }
    if (pipelineLayout != null) {
      pipelineLayout.release();
      pipelineLayout = null;
    }
    if (layout != null) {
      layout.release();
      layout = null;
    }
  }
}
