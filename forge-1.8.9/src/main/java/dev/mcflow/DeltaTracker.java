package dev.mcflow;

/** 1.8.9 compatibility: the modern {@code DeltaTracker} API, i.e. this frame's partial tick. */
final class DeltaTracker {
    private final float partialTick;

    DeltaTracker(float partialTick) {
        this.partialTick = partialTick;
    }

    float getGameTimeDeltaPartialTick(boolean ignored) {
        return partialTick;
    }
}
