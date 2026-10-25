package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/** The world reacting to the music: a burst of particles around the player on every beat, a big one on drops. */
final class WorldFx {
    private static int lastBeats;

    private WorldFx() {}

    /** Call once per client tick. */
    static void tick(Minecraft mc) {
        int beats = Beat.beats();
        if (beats == lastBeats) return;
        lastBeats = beats;
        Settings s = Settings.get();
        if (s.beatParticles == Settings.Particles.OFF || mc.level == null || mc.player == null || mc.isPaused()) return;

        ParticleOptions type = switch (s.beatParticles) {
            case NOTES -> ParticleTypes.NOTE;
            case GLOW -> ParticleTypes.GLOW;
            default -> ParticleTypes.END_ROD;
        };
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean drop = Beat.drop() > 0.9f;
        int count = 4 + Math.round(8 * Beat.energy()) + (drop ? 40 : 0);
        Vec3 eye = mc.player.getEyePosition();
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(Math.PI * 2), radius = random.nextDouble(1.2, drop ? 5 : 3.5);
            double x = eye.x + Math.cos(angle) * radius, y = eye.y + random.nextDouble(-1.2, 1.6), z = eye.z + Math.sin(angle) * radius;
            if (type == ParticleTypes.NOTE) {
                mc.level.addParticle(type, x, y, z, random.nextDouble(), 0, 0); // a note's x speed picks its colour
            } else {
                double speed = drop ? 0.12 : 0.04;
                mc.level.addParticle(type, x, y, z, Math.cos(angle) * speed, random.nextDouble(0.01, 0.06), Math.sin(angle) * speed);
            }
        }
    }
}
