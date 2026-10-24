package dev.mcflow;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;

/** A Java2D image uploaded as a smoothly-filtered GUI texture. Call {@link #close()} when done with it. */
final class GpuImage implements AutoCloseable {
    private static int nextId;

    final int width, height;
    private final Identifier id;

    GpuImage(BufferedImage img) {
        width = img.getWidth();
        height = img.getHeight();
        NativeImage pixels = new NativeImage(width, height, false);
        int[] argb = img.getRGB(0, 0, width, height, null, 0, width); // one bulk read instead of one call per pixel
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++, i++) pixels.setPixel(x, y, argb[i]);
        }
        DynamicTexture texture = new DynamicTexture(() -> "mcflow hud", pixels) {
            {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR); // vanilla default is NEAREST
            }
        };
        id = Identifier.fromNamespaceAndPath(McFlow.MODID, "hud/" + nextId++);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    /** The texture's name, for drawing it outside the GUI (pinned lyrics in the world). */
    Identifier id() {
        return id;
    }

    /** Draws at (x, y) in the current pose's units, tinted by ARGB {@code color} (use alpha to fade). */
    void draw(GuiGraphics g, int x, int y, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, width, height, width, height, width, height, color);
    }

    /**
     * Draws a soft-focus version: the image at reduced strength plus eight faint copies in a ring {@code radius}
     * units around it. {@code amount} 0 = sharp, 1 = fully softened.
     */
    void drawSoft(GuiGraphics g, int x, int y, int color, float radius, float amount) {
        if (amount < 0.01f || radius < 0.5f) {
            draw(g, x, y, color);
            return;
        }
        int alpha = color >>> 24, rgb = color & 0xFFFFFF;
        draw(g, x, y, Math.round(alpha * (1 - 0.7f * amount)) << 24 | rgb);
        int ring = Math.round(alpha * 0.22f * amount);
        if (ring < 2) return;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            draw(g, x + (int) Math.round(Math.cos(angle) * radius), y + (int) Math.round(Math.sin(angle) * radius), ring << 24 | rgb);
        }
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(id);
    }
}
