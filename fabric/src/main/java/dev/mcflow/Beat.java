package dev.mcflow;

/**
 * Beat detection from the bass (kick drum range) of whatever this PC is playing, captured by audiolevel.ps1.
 * A beat is a sudden rise in bass energy: bigger than the typical recent rise, and not right after the last beat.
 * ponytail: device-wide loopback, so loud bassy game sounds can trigger it too; per-app capture (Win10 2004+
 * process loopback) would fix that if it turns out to matter.
 */
public final class Beat {
    /** Shortest gap between beats (~214 BPM), so one hit isn't counted twice. */
    private static final long MIN_GAP_MS = 280;
    /** How much bigger than the typical rise a rise has to be. Tuned on real loopback recordings. */
    private static final double JUMP = 1.2;
    /** Bass energy below this is silence; don't pulse on noise. */
    private static final double FLOOR = 1e-4;
    private static final int WARMUP_SAMPLES = 25;
    /** How long a pulse takes to die down. */
    private static final double DECAY_MS = 120;

    private static double previous, typicalRise = 1e-4, peak = 1e-3;
    /** Bass energy relative to the recent loudest, 0..1, for the equalizer. */
    private static volatile float energy;
    private static volatile long lastSample;
    private static int samples;
    private static volatile long lastBeat = -1_000_000;

    private Beat() {}

    static void start() {
        Helper.start("audiolevel.ps1", line -> {
            try {
                onLevel(Double.parseDouble(line.trim()), System.currentTimeMillis());
            } catch (NumberFormatException ignored) {
            }
        });
    }

    /** Feeds one bass energy sample taken at {@code now}. */
    static synchronized void onLevel(double level, long now) {
        double rise = Math.max(0, level - previous);
        previous = level;
        boolean warmedUp = ++samples > WARMUP_SAMPLES; // the typical rise needs a moment to settle
        if (warmedUp && level > FLOOR && rise > typicalRise * JUMP && now - lastBeat >= MIN_GAP_MS) lastBeat = now;
        if (rise > 0) typicalRise += (rise - typicalRise) * 0.1;
        peak = Math.max(level, peak * 0.995); // loudest recently, fading over a few seconds
        energy = (float) Math.clamp(level / Math.max(peak, FLOOR * 4), 0, 1);
        lastSample = now;
    }

    /** How loud the bass is right now compared to the recent loudest (0..1); 0 if the audio helper isn't running. */
    public static float energy() {
        return System.currentTimeMillis() - lastSample > 500 ? 0 : energy;
    }

    /** 1 right on a beat, easing back to 0 over a few hundred ms. */
    public static float pulse() {
        long dt = System.currentTimeMillis() - lastBeat;
        return dt > 6 * DECAY_MS ? 0 : (float) Math.exp(-dt / DECAY_MS);
    }

    public static void main(String[] args) {
        // kicks every 500 ms that ring out over ~150 ms, on top of quiet wobbling bass, sampled every 30 ms for 10 s
        int beats = 0;
        long last = lastBeat;
        for (long t = 0; t < 10_000; t += 30) {
            long sinceKick = t % 500;
            double level = 0.002 + 0.0005 * Math.sin(t / 70.0) + (sinceKick < 150 ? 0.01 * (1 - sinceKick / 150.0) : 0);
            onLevel(level, t);
            if (lastBeat != last) {
                beats++;
                last = lastBeat;
                assert sinceKick < 60 : "beat off the kick at " + t;
            }
        }
        assert beats >= 18 : "found " + beats + " of 20 kicks";
        // silence never pulses
        lastBeat = -1_000_000;
        for (long t = 20_000; t < 25_000; t += 30) onLevel(Math.random() * 1e-6, t);
        assert lastBeat == -1_000_000;
        System.out.println("ok");
    }
}
