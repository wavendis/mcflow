package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * 1.8.9 compatibility: the modern {@code Camera} API the shared HUD code uses (position, pitch, yaw), plus world-to-
 * screen projection, which 1.8.9 has no helper for. The view and projection matrices are captured from OpenGL at the
 * end of each world render ({@link #capture}) and the HUD, drawn right after, projects through them.
 */
final class Camera {
    private static final Camera CURRENT = new Camera();
    private static final FloatBuffer MODELVIEW = BufferUtils.createFloatBuffer(16), PROJECTION = BufferUtils.createFloatBuffer(16);
    private static final float[] mv = new float[16], pr = new float[16];

    /** Where the world was drawn from (the render entity's interpolated feet; world geometry is offset by this). */
    private double originX, originY, originZ;
    private float eyeHeight, xRot, yRot;

    private Camera() {}

    static Camera get() {
        return CURRENT;
    }

    /** World render, after everything is drawn (RenderWorldLastEvent): remembers this frame's view. */
    static void capture(float partialTicks) {
        Entity e = Minecraft.getMinecraft().getRenderViewEntity();
        if (e == null) return;
        Camera c = CURRENT;
        c.originX = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTicks;
        c.originY = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTicks;
        c.originZ = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTicks;
        c.eyeHeight = e.getEyeHeight();
        c.xRot = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * partialTicks;
        c.yRot = e.prevRotationYaw + (e.rotationYaw - e.prevRotationYaw) * partialTicks;
        MODELVIEW.clear();
        PROJECTION.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION);
        MODELVIEW.get(mv);
        PROJECTION.get(pr);
    }

    /** The eye, in world coordinates. */
    Vec3 position() {
        return new Vec3(originX, originY + eyeHeight, originZ);
    }

    /** World-render origin: OpenGL world geometry is drawn relative to this point. */
    Vec3 origin() {
        return new Vec3(originX, originY, originZ);
    }

    float xRot() {
        return xRot;
    }

    float yRot() {
        return yRot;
    }

    /**
     * World point to screen pixels (w x h): {x, y, pixels per block at that depth, depth in blocks}, or null when it's
     * behind the camera. Same contract as the modern versions' {@code LyricsHud.project}.
     */
    static float[] project(Camera camera, Vec3 point, int w, int h) {
        float px = (float) (point.x - camera.originX), py = (float) (point.y - camera.originY), pz = (float) (point.z - camera.originZ);
        // eye space (column-major matrices, as OpenGL stores them)
        float ex = mv[0] * px + mv[4] * py + mv[8] * pz + mv[12];
        float ey = mv[1] * px + mv[5] * py + mv[9] * pz + mv[13];
        float ez = mv[2] * px + mv[6] * py + mv[10] * pz + mv[14];
        float depth = -ez; // the camera looks down -z
        if (depth < 0.5f) return null;
        float cx = pr[0] * ex + pr[4] * ey + pr[8] * ez + pr[12];
        float cy = pr[1] * ex + pr[5] * ey + pr[9] * ez + pr[13];
        float cw = pr[3] * ex + pr[7] * ey + pr[11] * ez + pr[15];
        if (cw <= 1e-6f) return null;
        float x = (cx / cw + 1) / 2 * w, y = (1 - cy / cw) / 2 * h;
        float ppb = pr[5] * h / 2 / depth; // projection[1][1] = 1 / tan(fov / 2)
        return new float[]{x, y, ppb, depth};
    }
}
