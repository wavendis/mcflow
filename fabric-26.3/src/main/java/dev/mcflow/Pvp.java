package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** PvP mode: the last player you hit, until {@link Settings#pvpSeconds} pass without hitting them (or anyone) again. */
final class Pvp {
    private static Player target;
    private static long hitAt;

    private Pvp() {}

    /** Called by the loader's client-side attack event. */
    static void onAttack(Entity hit) {
        HitFx.onHit(hit);
        if (Settings.get().pvpMode && hit instanceof Player p && p != Minecraft.getInstance().player) {
            target = p;
            hitAt = System.currentTimeMillis();
        }
    }

    /** Who the lyrics follow right now, or null for the normal placement. */
    static Player target() {
        Settings s = Settings.get();
        if (target != null && (!s.pvpMode || System.currentTimeMillis() - hitAt > s.pvpSeconds * 1000
                || target.isRemoved() || !target.isAlive() || target.level() != Minecraft.getInstance().level)) {
            target = null;
        }
        return target;
    }
}
