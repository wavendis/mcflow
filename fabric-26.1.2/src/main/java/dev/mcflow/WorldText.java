package dev.mcflow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Pinned lyrics drawn as real quads in the world ("Render in the world" setting): blocks, mobs and players in front
 * hide them through the depth buffer, and shaders treat them like sign text. {@link LyricsHud} works out where each
 * letter's corners are and queues them here; the loader's world-render hook draws them.
 */
public final class WorldText {
    /** One letter (or its glow/outline layer) as a textured quad: corners clockwise from top left, in world space. */
    private record Quad(Identifier texture, Vec3 topLeft, Vec3 bottomLeft, Vec3 bottomRight, Vec3 topRight, int color) {}

    /** Light level packed the way vertices take it: full block and sky light, so the text glows like a lit sign. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Quads older than this aren't drawn (the HUD stopped queueing, e.g. hidden with F1). */
    private static final long STALE_MS = 150;

    /** Set by loaders that call {@link #render}; the setting only shows (and only applies) where it works. */
    static volatile boolean supported;

    private static final List<Quad> QUADS = new ArrayList<>();
    private static long queuedAt;

    private WorldText() {}

    /** Render thread, once per HUD frame before queueing: drops last frame's quads. */
    static void begin() {
        QUADS.clear();
        queuedAt = System.currentTimeMillis();
    }

    static void add(Identifier texture, Vec3 topLeft, Vec3 bottomLeft, Vec3 bottomRight, Vec3 topRight, int argb) {
        QUADS.add(new Quad(texture, topLeft, bottomLeft, bottomRight, topRight, argb));
    }

    /**
     * World render hook: draws the queued quads relative to the camera. {@code pose} is the level's pose stack and
     * {@code buffers} hands out the vertex buffer for a render type (the loader's buffer source).
     */
    public static void render(PoseStack pose, Function<RenderType, VertexConsumer> buffers, Vec3 camera) {
        if (QUADS.isEmpty() || System.currentTimeMillis() - queuedAt > STALE_MS) return;
        PoseStack.Pose last = pose.last();
        for (Quad q : QUADS) {
            VertexConsumer vc = buffers.apply(RenderTypes.text(q.texture()));
            vertex(vc, last, q.topLeft(), camera, 0, 0, q.color());
            vertex(vc, last, q.bottomLeft(), camera, 0, 1, q.color());
            vertex(vc, last, q.bottomRight(), camera, 1, 1, q.color());
            vertex(vc, last, q.topRight(), camera, 1, 0, q.color());
        }
    }

    /**
     * The same quads for renderers that collect geometry and draw it later (Fabric 26.x's submit system):
     * {@code submit} gets each quad's render type and a writer that adds its four vertices to the pose and buffer it's given.
     */
    public static void submit(BiConsumer<RenderType, BiConsumer<PoseStack.Pose, VertexConsumer>> submit, Vec3 camera) {
        if (QUADS.isEmpty() || System.currentTimeMillis() - queuedAt > STALE_MS) return;
        for (Quad q : List.copyOf(QUADS)) {
            submit.accept(RenderTypes.text(q.texture()), (pose, vc) -> {
                vertex(vc, pose, q.topLeft(), camera, 0, 0, q.color());
                vertex(vc, pose, q.bottomLeft(), camera, 0, 1, q.color());
                vertex(vc, pose, q.bottomRight(), camera, 1, 1, q.color());
                vertex(vc, pose, q.topRight(), camera, 1, 0, q.color());
            });
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, Vec3 p, Vec3 camera, float u, float v, int color) {
        vc.addVertex(pose, (float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z))
                .setColor(color).setUv(u, v).setLight(FULL_BRIGHT);
    }
}
