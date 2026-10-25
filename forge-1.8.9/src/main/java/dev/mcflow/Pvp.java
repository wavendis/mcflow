package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/** PvP mode: the last player you hit, until {@link Settings#pvpSeconds} pass without hitting them (or anyone) again. */
final class Pvp {
    private static EntityPlayer target;
    private static long hitAt;

    private Pvp() {}

    /** Called by the client-side attack event. */
    static void onAttack(Entity hit) {
        HitFx.onHit(hit);
        if (Settings.get().pvpMode && hit instanceof EntityPlayer p && p != Minecraft.getMinecraft().thePlayer) {
            target = p;
            hitAt = System.currentTimeMillis();
        }
    }

    /** Who the lyrics follow right now, or null for the normal placement. */
    static EntityPlayer target() {
        Settings s = Settings.get();
        if (target != null && (!s.pvpMode || System.currentTimeMillis() - hitAt > s.pvpSeconds * 1000
                || target.isDead || !target.isEntityAlive() || target.worldObj != Minecraft.getMinecraft().theWorld)) {
            target = null;
        }
        return target;
    }

    /** An entity's feet, interpolated for this frame. */
    static Vec3 feet(Entity e, float partialTick) {
        return new Vec3(e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTick,
                e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTick,
                e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTick);
    }
}
