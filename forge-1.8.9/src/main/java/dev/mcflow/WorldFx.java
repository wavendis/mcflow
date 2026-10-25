package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumParticleTypes;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The world reacting to the music: a burst of particles around the player on every beat, a big one on drops.
 * 1.8.9 port: 1.8.9 has no end rod or glow particles, so "Sparkles" are firework sparks and "Glow" is witch magic.
 */
final class WorldFx {
    private static int lastBeats;

    private WorldFx() {}

    /** Call once per client tick. */
    static void tick(Minecraft mc) {
        int beats = Beat.beats();
        if (beats == lastBeats) return;
        lastBeats = beats;
        Settings s = Settings.get();
        if (s.beatParticles == Settings.Particles.OFF || mc.theWorld == null || mc.thePlayer == null || mc.isGamePaused()) return;

        EnumParticleTypes type = switch (s.beatParticles) {
            case NOTES -> EnumParticleTypes.NOTE;
            case GLOW -> EnumParticleTypes.SPELL_WITCH;
            default -> EnumParticleTypes.FIREWORKS_SPARK;
        };
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean drop = Beat.drop() > 0.9f;
        int count = 4 + Math.round(8 * Beat.energy()) + (drop ? 40 : 0);
        double eyeX = mc.thePlayer.posX, eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), eyeZ = mc.thePlayer.posZ;
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(Math.PI * 2), radius = random.nextDouble(1.2, drop ? 5 : 3.5);
            double x = eyeX + Math.cos(angle) * radius, y = eyeY + random.nextDouble(-1.2, 1.6), z = eyeZ + Math.sin(angle) * radius;
            if (type == EnumParticleTypes.NOTE) {
                mc.theWorld.spawnParticle(type, x, y, z, random.nextDouble(), 0, 0); // a note's x speed picks its colour
            } else {
                double speed = drop ? 0.12 : 0.04;
                mc.theWorld.spawnParticle(type, x, y, z, Math.cos(angle) * speed, random.nextDouble(0.01, 0.06), Math.sin(angle) * speed);
            }
        }
    }
}
