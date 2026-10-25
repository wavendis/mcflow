package dev.mcflow;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * 1.8.9 compatibility: the bit of the modern GUI drawing API the shared HUD code uses ({@code pose()} transforms and
 * {@code fill}), on 1.8.9's fixed-function OpenGL. Everything draws into the current GUI matrix.
 */
final class GuiGraphicsExtractor {
    private final Pose pose = new Pose();

    Pose pose() {
        return pose;
    }

    /** A solid rectangle from (x0, y0) to (x1, y1), ARGB colour. */
    void fill(int x0, int y0, int x1, int y1, int argb) {
        GlStateManager.disableTexture2D();
        GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        wr.pos(x0, y1, 0).endVertex();
        wr.pos(x1, y1, 0).endVertex();
        wr.pos(x1, y0, 0).endVertex();
        wr.pos(x0, y0, 0).endVertex();
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** The modern 2D pose stack's calls, on the OpenGL matrix stack (rotations in radians, like JOML). */
    static final class Pose {
        private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

        void pushMatrix() {
            GlStateManager.pushMatrix();
        }

        void popMatrix() {
            GlStateManager.popMatrix();
        }

        void translate(float x, float y) {
            GlStateManager.translate(x, y, 0);
        }

        void scale(float x, float y) {
            GlStateManager.scale(x, y, 1);
        }

        void rotate(float radians) {
            GlStateManager.rotate((float) Math.toDegrees(radians), 0, 0, 1);
        }

        void mul(Matrix3x2f m) {
            matrix.clear();
            matrix.put(new float[]{m.m00, m.m01, 0, 0, m.m10, m.m11, 0, 0, 0, 0, 1, 0, m.m20, m.m21, 0, 1}).flip();
            GL11.glMultMatrix(matrix);
        }
    }
}
