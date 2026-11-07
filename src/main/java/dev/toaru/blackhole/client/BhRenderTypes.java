package dev.toaru.blackhole.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom render types for black hole visual effects.
 * DARK: Flat black for event horizon (no glow, so it can be truly black)
 * DISTORTION: For gravitational lensing/Einstein ring effects
 * FIELD: For magnetic field lines (translucent additive)
 */
public final class BhRenderTypes extends RenderType {
    private BhRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort,
                          Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
        throw new IllegalStateException("not meant to be constructed");
    }

    /** Flat black geometry - event horizon, shadow */
    public static final RenderType DARK = create("blackhole_dark", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 8192, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    /** Distortion/lensing - uses texture for chromatic aberration effect */
    public static final RenderType DISTORTION = create("blackhole_distortion", DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS, 8192, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_TEX_COLOR_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(
                            ResourceLocation.fromNamespaceAndPath("blackhole", "textures/misc/distortion.png"), false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** Magnetic field lines - translucent additive */
    public static final RenderType FIELD = create("blackhole_field", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 8192, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    private static RenderType create(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, RenderType.CompositeState state) {
        return new RenderType(name, format, mode, size, crumbling, sort, () -> {}, () -> {}) {
            @Override
            public RenderType.CompositeState state() { return state; }
        };
    }
}