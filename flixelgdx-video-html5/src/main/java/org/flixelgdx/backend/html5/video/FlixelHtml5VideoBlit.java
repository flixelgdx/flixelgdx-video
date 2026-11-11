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
package org.flixelgdx.backend.html5.video;

import org.flixelgdx.backend.gpu.FlixelGpuTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.stringdotjar.jwebgpu.GpuAddressMode;
import io.github.stringdotjar.jwebgpu.GpuBindGroup;
import io.github.stringdotjar.jwebgpu.GpuBindGroupDescriptor;
import io.github.stringdotjar.jwebgpu.GpuBindGroupLayout;
import io.github.stringdotjar.jwebgpu.GpuBindGroupLayoutDescriptor;
import io.github.stringdotjar.jwebgpu.GpuColorWriteMask;
import io.github.stringdotjar.jwebgpu.GpuCommandBuffer;
import io.github.stringdotjar.jwebgpu.GpuCommandEncoder;
import io.github.stringdotjar.jwebgpu.GpuCullMode;
import io.github.stringdotjar.jwebgpu.GpuDevice;
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
import io.github.stringdotjar.jwebgpu.GpuTextureFormat;
import io.github.stringdotjar.jwebgpu.GpuTextureSampleType;
import io.github.stringdotjar.jwebgpu.GpuTextureView;
import io.github.stringdotjar.jwebgpu.GpuTextureViewDimension;

/**
 * Shrinks one texture into a smaller one on the GPU, which is how lower video quality presets work.
 *
 * <p>The pass draws a single triangle that covers the whole target and samples the source with linear
 * filtering. It records into its own command encoder and submits right away, so it finishes before the
 * framework's frame commands, which are submitted at the end of the frame.
 *
 * <p>Every GPU object is built once and reused. A draw allocates nothing: the encoder, pass, and command
 * buffer wrappers are refilled in place, and the bind group is only rebuilt when the source texture changes.
 * If the framework replaces its device (after a device loss), everything is rebuilt on the new one.
 */
final class FlixelHtml5VideoBlit {

  /** Full-screen triangle that copies the source with linear filtering. Texture {@code y = 0} is the top. */
  private static final String SHADER = """
      @group(0) @binding(0) var src: texture_2d<f32>;
      @group(0) @binding(1) var smp: sampler;

      struct Out {
        @builtin(position) pos: vec4f,
        @location(0) uv: vec2f,
      };

      @vertex
      fn vs(@builtin(vertex_index) i: u32) -> Out {
        let p = vec2f(f32((i << 1u) & 2u), f32(i & 2u));
        var o: Out;
        o.pos = vec4f(p * 2.0 - 1.0, 0.0, 1.0);
        o.uv = vec2f(p.x, 1.0 - p.y);
        return o;
      }

      @fragment
      fn fs(in: Out) -> @location(0) vec4f {
        return textureSampleLevel(src, smp, in.uv, 0.0);
      }
      """;

  private final GpuCommandBuffer[] submit = new GpuCommandBuffer[1];
  private final GpuBindGroupLayoutDescriptor layoutDescriptor = new GpuBindGroupLayoutDescriptor(2);
  private final GpuBindGroupDescriptor groupDescriptor = new GpuBindGroupDescriptor(2);
  private final GpuRenderPassDescriptor passDescriptor = new GpuRenderPassDescriptor(1);

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
  private GpuBindGroup group;

  /** The source view {@link #group} points at. */
  @Nullable
  private GpuTextureView boundView;

  @Nullable
  private GpuCommandEncoder encoder;

  @Nullable
  private GpuRenderPassEncoder pass;

  @Nullable
  private GpuCommandBuffer commands;

  /**
   * Draws {@code source} over all of {@code target}, replacing everything the target held.
   *
   * @param device The framework's device.
   * @param queue The framework's queue.
   * @param source The texture to read.
   * @param target The render target texture to write. It must be {@code RGBA8} and allow rendering.
   */
  void draw(@NotNull GpuDevice device, @NotNull GpuQueue queue, @NotNull FlixelGpuTexture source,
      @NotNull FlixelGpuTexture target) {
    if (device != this.device) {
      build(device);
    }
    GpuTextureView view = source.getView();
    if (view != boundView || group == null) {
      groupDescriptor.reset();
      groupDescriptor.label = "flixel video blit";
      groupDescriptor.layout = layout;
      groupDescriptor.entryCount = 2;
      groupDescriptor.entries[0].binding = 0;
      groupDescriptor.entries[0].textureView = view;
      groupDescriptor.entries[1].binding = 1;
      groupDescriptor.entries[1].sampler = sampler;
      group = group == null ? device.createBindGroup(groupDescriptor) : device.createBindGroup(groupDescriptor, group);
      boundView = view;
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
  }

  /** Releases every GPU object this blit holds. Safe to call more than once. */
  void destroy() {
    release();
    device = null;
  }

  /** Builds the pipeline, layout, and sampler on {@code device}, dropping anything made on an older device. */
  private void build(@NotNull GpuDevice device) {
    release();
    this.device = device;

    layoutDescriptor.reset();
    layoutDescriptor.label = "flixel video blit";
    layoutDescriptor.entryCount = 2;
    layoutDescriptor.entries[0].binding = 0;
    layoutDescriptor.entries[0].visibility = GpuShaderStage.FRAGMENT;
    layoutDescriptor.entries[0].texture.sampleType = GpuTextureSampleType.FLOAT;
    layoutDescriptor.entries[0].texture.viewDimension = GpuTextureViewDimension.D2;
    layoutDescriptor.entries[1].binding = 1;
    layoutDescriptor.entries[1].visibility = GpuShaderStage.FRAGMENT;
    layoutDescriptor.entries[1].sampler.type = GpuSamplerBindingType.FILTERING;
    layout = device.createBindGroupLayout(layoutDescriptor);

    GpuPipelineLayoutDescriptor pipelineLayoutDescriptor = new GpuPipelineLayoutDescriptor(1);
    pipelineLayoutDescriptor.label = "flixel video blit";
    pipelineLayoutDescriptor.bindGroupLayoutCount = 1;
    pipelineLayoutDescriptor.bindGroupLayouts[0] = layout;
    pipelineLayout = device.createPipelineLayout(pipelineLayoutDescriptor);

    GpuShaderSourceWGSL wgsl = new GpuShaderSourceWGSL();
    wgsl.code = SHADER;
    GpuShaderModuleDescriptor moduleDescriptor = new GpuShaderModuleDescriptor();
    moduleDescriptor.label = "flixel video blit";
    moduleDescriptor.nextInChain = wgsl;
    module = device.createShaderModule(moduleDescriptor);

    GpuRenderPipelineDescriptor pipelineDescriptor = new GpuRenderPipelineDescriptor();
    pipelineDescriptor.label = "flixel video blit";
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
    samplerDescriptor.label = "flixel video blit";
    samplerDescriptor.addressModeU = GpuAddressMode.CLAMP_TO_EDGE;
    samplerDescriptor.addressModeV = GpuAddressMode.CLAMP_TO_EDGE;
    samplerDescriptor.addressModeW = GpuAddressMode.CLAMP_TO_EDGE;
    samplerDescriptor.magFilter = GpuFilterMode.LINEAR;
    samplerDescriptor.minFilter = GpuFilterMode.LINEAR;
    samplerDescriptor.mipmapFilter = GpuMipmapFilterMode.NEAREST;
    sampler = device.createSampler(samplerDescriptor);

    GpuRenderPassColorAttachment color = passDescriptor.colorAttachments[0];
    color.loadOp = GpuLoadOp.CLEAR;
    color.storeOp = GpuStoreOp.STORE;
    color.clearValue.a = 1.0;
    passDescriptor.colorAttachmentCount = 1;
  }

  private void release() {
    if (group != null) {
      group.release();
      group = null;
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
    boundView = null;
  }
}
