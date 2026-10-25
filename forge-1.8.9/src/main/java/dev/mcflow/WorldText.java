package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Pinned lyrics drawn as real quads in the world ("Render in the world" setting): blocks, mobs and players in front
 * hide them through the depth buffer. {@link LyricsHud} works out where each letter's corners are and queues them
 * here; {@link #render} draws them at the end of the world render. 1.8.9 port: plain OpenGL instead of render types.
 */
final class WorldText {
    /** One letter (or its glow/outline layer) as a textured quad: corners clockwise from top left, in world space. */
    private record Quad(int texture, Vec3 topLeft, Vec3 bottomLeft, Vec3 bottomRight, Vec3 topRight, int color) {}

    /** Quads older than this aren't drawn (the HUD stopped queueing, e.g. hidden with F1). */
    private static final long STALE_MS = 150;

    static volatile boolean supported = true;

    private static final List<Quad> QUADS = new ArrayList<>();
    private static long queuedAt;

    private WorldText() {}

    /** Render thread, once per HUD frame before queueing: drops last frame's quads. */
    static void begin() {
        QUADS.clear();
        queuedAt = System.currentTimeMillis();
    }

    static void add(int texture, Vec3 topLeft, Vec3 bottomLeft, Vec3 bottomRight, Vec3 topRight, int argb) {
        QUADS.add(new Quad(texture, topLeft, bottomLeft, bottomRight, topRight, argb));
    }

    /**
     * End of the world render (RenderWorldLastEvent): draws last HUD frame's quads, relative to where the world is drawn
     * from. Unlit (full bright, like a glowing sign) and translucent, with depth testing so things in front hide them.
     */
    static void render() {
        if (QUADS.isEmpty() || System.currentTimeMillis() - queuedAt > STALE_MS) return;
        Vec3 o = Camera.get().origin();
        Minecraft.getMinecraft().entityRenderer.disableLightmap();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.enableTexture2D();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        for (Quad q : QUADS) {
            GlStateManager.bindTexture(q.texture());
            int c = q.color();
            GlStateManager.color(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, (c >>> 24) / 255f);
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            vertex(wr, q.topLeft(), o, 0, 0);
            vertex(wr, q.bottomLeft(), o, 0, 1);
            vertex(wr, q.bottomRight(), o, 1, 1);
            vertex(wr, q.topRight(), o, 1, 0);
            tess.draw();
        }
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        Minecraft.getMinecraft().entityRenderer.enableLightmap();
    }

    private static void vertex(WorldRenderer wr, Vec3 p, Vec3 origin, double u, double v) {
        wr.pos(p.x - origin.x, p.y - origin.y, p.z - origin.z).tex(u, v).endVertex();
    }
}
