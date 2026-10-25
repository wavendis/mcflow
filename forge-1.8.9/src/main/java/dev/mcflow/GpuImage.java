package dev.mcflow;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;

/** A Java2D image uploaded as a smoothly-filtered texture (1.8.9 version). Call {@link #close()} when done with it. */
final class GpuImage implements AutoCloseable {
    final int width, height;
    private final DynamicTexture texture;

    GpuImage(BufferedImage img) {
        width = img.getWidth();
        height = img.getHeight();
        texture = new DynamicTexture(img);
        // vanilla uploads it NEAREST; smooth filtering keeps scaled text and images soft instead of pixelated
        GlStateManager.bindTexture(texture.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** The OpenGL texture, for drawing it in the world (pinned lyrics in 3D). */
    int id() {
        return texture.getGlTextureId();
    }

    /** Draws at (x, y) in the current matrix's units, tinted by ARGB {@code color} (use alpha to fade). */
    void draw(GuiGraphicsExtractor g, int x, int y, int color) {
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture.getGlTextureId());
        GlStateManager.color(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f, (color >>> 24) / 255f);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        wr.pos(x, y + height, 0).tex(0, 1).endVertex();
        wr.pos(x + width, y + height, 0).tex(1, 1).endVertex();
        wr.pos(x + width, y, 0).tex(1, 0).endVertex();
        wr.pos(x, y, 0).tex(0, 0).endVertex();
        Tessellator.getInstance().draw();
        GlStateManager.color(1, 1, 1, 1);
    }

    /**
     * Draws a soft-focus version: the image at reduced strength plus eight faint copies in a ring {@code radius}
     * units around it. {@code amount} 0 = sharp, 1 = fully softened.
     */
    void drawSoft(GuiGraphicsExtractor g, int x, int y, int color, float radius, float amount) {
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
        texture.deleteGlTexture();
    }
}
