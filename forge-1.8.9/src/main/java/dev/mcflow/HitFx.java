package dev.mcflow;

// Generated from mod/ by sync-forge-1.8.9.py: edit the shared HitFx, not this copy.

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Comic hit effects (BOOM!, POW!...): each hit on a player pops one, picked at random, somewhere around them, at a
 * random size and tilt. They stick to the player while they last.
 */
final class HitFx {
    private static final String[] NAMES = {"boom", "pow", "bam", "whack", "crash", "smack", "splat", "zap", "kapow", "thud"};
    /** How long one stays fully shown, between popping in and fading out (both set in {@link Settings}). */
    private static final long HOLD_MS = 580;

    /** One effect: which image, where relative to the player's feet (blocks, fixed when hit), how wide (blocks), tilt (radians). */
    private record Burst(int image, Entity target, Vec3 offset, float width, float tilt, long born) {}

    private static final List<Burst> BURSTS = new ArrayList<>();
    private static final GpuImage[] IMAGES = new GpuImage[NAMES.length];
    private static int last = -1;

    private HitFx() {}

    /** Called for every attack (see {@link Pvp#onAttack}). */
    static void onHit(Entity hit) {
        if (!Settings.get().hitEffects || !(hit instanceof EntityPlayer) || hit == Minecraft.getMinecraft().thePlayer) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int image = r.nextInt(NAMES.length - 1);
        if (image >= last) image++; // never the same one twice in a row
        last = image;
        // anywhere around them as you see them: a random point on a ring around the middle of their body, facing you
        // (left, right, above, below), so it never hides behind them or bunches up over their head
        Camera camera = Camera.get();
        Vec3 forward = Vec3.directionFromRotation(0, camera.yRot());
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        double angle = r.nextDouble(Math.PI * 2);
        double across = Math.cos(angle) * r.nextDouble(0.8, 1.4), up = Math.sin(angle) * r.nextDouble(0.7, 1.2);
        Vec3 offset = right.scale(across).add(0, hit.height / 2 + up, 0).subtract(forward.scale(0.3)); // a touch towards you
        // at most hitMax on screen (attack spam on 1.8.9 would pile them up): a new hit replaces the oldest
        while (BURSTS.size() >= Math.max(1, Settings.get().hitMax)) BURSTS.removeFirst();
        BURSTS.add(new Burst(image, hit, offset, (float) r.nextDouble(0.7, 1.5), (float) r.nextDouble(-0.35, 0.35), System.currentTimeMillis()));
    }

    /** Render thread, in physical screen pixels (see {@link LyricsHud}). */
    static void draw(GuiGraphicsExtractor g, float partialTick) {
        if (BURSTS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Window window = Window.get();
        int w = window.getWidth(), h = window.getHeight();
        Camera camera = Camera.get();
        Settings s = Settings.get();
        long now = System.currentTimeMillis(), in = Math.max(1, s.hitFadeInMs), out = Math.max(1, s.hitFadeOutMs), life = in + HOLD_MS + out;
        BURSTS.removeIf(b -> now - b.born() > life || b.target().worldObj != mc.theWorld);
        while (BURSTS.size() > Math.max(1, s.hitMax)) BURSTS.removeFirst(); // the limit was just lowered
        for (Burst b : BURSTS) {
            float age = now - b.born();
            float arrive = Math.min(1, age / in);
            float pop = easeOutBack(arrive) * s.hitSize;
            float fade = Math.min(arrive, Math.clamp((life - age) / out, 0f, 1f));
            double rise = age / (double) life * 0.25; // drifts up a little
            float[] p = LyricsHud.project(camera, Pvp.feet(b.target(), partialTick).add(b.offset()).add(0, rise, 0), w, h);
            GpuImage image = image(b.image());
            if (p == null || image == null) continue;
            float scale = p[2] * b.width() / image.width * pop; // p[2] = screen px per block there
            g.pose().pushMatrix();
            g.pose().translate(p[0], p[1]);
            g.pose().rotate(b.tilt());
            g.pose().scale(scale, scale);
            image.draw(g, -image.width / 2, -image.height / 2, Math.round(fade * 255) << 24 | 0xFFFFFF);
            g.pose().popMatrix();
        }
    }

    /** Loads an effect's picture the first time it's needed (on the render thread, where textures can be made). */
    private static GpuImage image(int i) {
        if (IMAGES[i] == null) {
            try (InputStream in = HitFx.class.getResourceAsStream("/assets/mcflow/hits/" + NAMES[i] + ".png")) {
                if (in != null) IMAGES[i] = new GpuImage(ImageIO.read(in));
            } catch (Exception e) {
                McFlow.LOGGER.warn("Couldn't load hit effect {}", NAMES[i], e);
            }
        }
        return IMAGES[i];
    }

    /** Overshoots past 1 and settles back: a comic "pop". */
    private static float easeOutBack(float t) {
        float c = 2.2f, u = t - 1;
        return 1 + (c + 1) * u * u * u + c * u * u;
    }
}
