package dev.mcflow;

// Generated from mod/ by sync-forge-1.8.9.py: edit the shared PlayerGlow, not this copy.

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players glowing to the beat: a soft halo of colour over and around the body that flares on every beat and settles
 * to a faint glow in between. Yours shows in third person; with sharing on, nearby players with Mc'Flow see it too
 * (each of your beats goes to them through the server, see {@link SingAlong#BEAT}).
 */
final class PlayerGlow {
    /** Glow between beats, and how fast a beat's flare fades. */
    private static final float BASE = 0.18f;
    private static final long FLARE_MS = 300;
    /** Someone else's glow goes away this long after their last beat (they stopped the music or turned it off). */
    private static final long FORGET_MS = 4000;
    /** The halo's height, as a multiple of the player's. */
    private static final float SIZE = 1.6f;

    /** A nearby player's last beat: their colour and how strong it was. */
    private record Pulse(int color, float strength, long at) {}

    private static final Map<UUID, Pulse> OTHERS = new ConcurrentHashMap<>();
    private static int lastBeats;
    private static GpuImage halo;

    private PlayerGlow() {}

    /** Any thread: a nearby player's beat, as "rrggbb strength". */
    static void receive(UUID player, String text) {
        try {
            String[] parts = text.trim().split(" ");
            OTHERS.put(player, new Pulse(Integer.parseInt(parts[0], 16), Math.clamp(Float.parseFloat(parts[1]), 0f, 1f), System.currentTimeMillis()));
        } catch (Exception ignored) {
            // not ours to worry about: a malformed beat just doesn't glow
        }
    }

    /** Every client tick: shares each of your beats while your glow is on and shared. */
    static void tick() {
        int beats = Beat.beats();
        if (beats == lastBeats) return;
        lastBeats = beats;
        Settings s = Settings.get();
        if (s.playerGlow && s.glowShared) {
            float strength = Math.clamp(0.5f + Beat.energy(), 0f, 1f);
            // Locale.ROOT: "0.85", never "0,85" (the receiver parses a dot)
            PeerLyrics.send(SingAlong.BEAT, String.format(Locale.ROOT, "%06x %.2f", s.playerGlowColor & 0xFFFFFF, strength));
        }
    }

    /** Render thread, in physical screen pixels (see {@link LyricsHud}): every glowing player in view. */
    static void draw(GuiGraphicsExtractor g, float partialTick) {
        Minecraft mc = Minecraft.getMinecraft();
        Settings s = Settings.get();
        if (mc.theWorld == null || (!s.playerGlow && OTHERS.isEmpty())) return;
        Window window = Window.get();
        int w = window.getWidth(), h = window.getHeight();
        Camera camera = Camera.get();
        long now = System.currentTimeMillis();
        OTHERS.values().removeIf(p -> now - p.at() > FORGET_MS);
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            float flare;
            int color;
            if (p == mc.thePlayer) {
                if (!s.playerGlow) continue;
                flare = Beat.pulse();
                color = s.playerGlowColor;
            } else {
                Pulse pulse = s.showOthers ? OTHERS.get(p.getUniqueID()) : null;
                if (pulse == null) continue;
                flare = pulse.strength() * (float) Math.exp(-(now - pulse.at()) / (double) FLARE_MS);
                color = pulse.color();
            }
            Vec3 middle = Pvp.feet(p, partialTick).add(0, p.height / 2, 0);
            if (middle.subtract(camera.position()).lengthSqr() < 1) continue; // that's you in first person
            float[] at = LyricsHud.project(camera, middle, w, h);
            if (at == null) continue;
            float visible = s.blockDetection ? Occlusion.between(mc.theWorld, camera.position(), middle).visibility() : 1;
            float alpha = Math.min(1, BASE + 0.7f * flare) * visible;
            if (alpha < 0.02f) continue;
            GpuImage image = halo();
            float scale = at[2] * p.height * SIZE / image.height * (1 + 0.15f * flare); // swells a little on the beat
            g.pose().pushMatrix();
            g.pose().translate(at[0], at[1]);
            g.pose().scale(scale, scale);
            image.draw(g, -image.width / 2, -image.height / 2, Math.round(alpha * 255) << 24 | (color & 0xFFFFFF));
            g.pose().popMatrix();
        }
    }

    /** A soft white oval, bright in the middle and gone at the edge; tinted with each player's colour when drawn. */
    private static GpuImage halo() {
        if (halo == null) {
            int w = 96, h = 192;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = TextArt.smooth(img);
            g.scale(1, 2); // an oval: a circle stretched to the body's shape
            g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, w / 2f), w / 2f, new float[]{0, 0.45f, 1},
                    new Color[]{new Color(255, 255, 255, 200), new Color(255, 255, 255, 90), new Color(255, 255, 255, 0)}));
            g.fillRect(0, 0, w, w);
            g.dispose();
            halo = new GpuImage(img);
        }
        return halo;
    }
}
