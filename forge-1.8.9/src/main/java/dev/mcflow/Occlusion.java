package dev.mcflow;

// 1.8.9 port of the shared Occlusion: blocks by material and collisionRayTrace instead of fluid states / voxel shapes

import net.minecraft.block.Block;
import net.minecraft.block.BlockBreakable;
import net.minecraft.block.BlockPane;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * What lies between the camera and a point of text: how much of it still shows through, and what colour it's
 * tinted. Walks the blocks along the line of sight one by one (Amanatides & Woo voxel traversal) and uses each
 * block's real shape, so a slab only hides what's actually behind it.
 */
final class Occlusion {
    /** {@code visibility} 0 (hidden) .. 1 (clear); {@code tint} is mixed into the text colour by {@code tintAmount}. */
    record Result(float visibility, int tint, float tintAmount) {}

    static final Result CLEAR = new Result(1, 0xFFFFFF, 0);
    static final Result HIDDEN = new Result(0, 0xFFFFFF, 0);

    private static final int WATER_TINT = 0x3F76E4;
    /** How much each block of water / glass lets through. */
    private static final float WATER_PASS = 0.8f, GLASS_PASS = 0.85f;

    private Occlusion() {}

    static Result between(World level, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        double length = d.length();
        if (length < 1e-6) return CLEAR;
        int x = floor(from.x), y = floor(from.y), z = floor(from.z);
        int stepX = sign(d.x), stepY = sign(d.y), stepZ = sign(d.z);
        // t (0..1 along the ray) at which it crosses the next block boundary on each axis, and per whole block
        double tMaxX = boundary(from.x, d.x, stepX), tMaxY = boundary(from.y, d.y, stepY), tMaxZ = boundary(from.z, d.z, stepZ);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.x);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.y);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.z);

        float visibility = 1, tintWeight = 0, r = 0, g = 0, b = 0;
        net.minecraft.util.Vec3 start = new net.minecraft.util.Vec3(from.x, from.y, from.z), end = new net.minecraft.util.Vec3(to.x, to.y, to.z);
        for (int steps = 0, max = (int) (length * 3) + 3; steps < max; steps++) {
            BlockPos pos = new BlockPos(x, y, z);
            IBlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            Material material = block.getMaterial();
            if (material == Material.lava) return HIDDEN;
            if (material == Material.water) {
                visibility *= WATER_PASS;
                r += 0.2f * ((WATER_TINT >> 16) & 0xFF);
                g += 0.2f * ((WATER_TINT >> 8) & 0xFF);
                b += 0.2f * (WATER_TINT & 0xFF);
                tintWeight += 0.2f;
            } else if (material != Material.air && block.collisionRayTrace(level, pos, start, end) != null) {
                // (the block's real outline, so a slab only hides what's actually behind it)
                if (block instanceof BlockBreakable || block instanceof BlockPane) {
                    // glass, ice, panes, iron bars: see through; stained glass tints with its colour
                    visibility *= GLASS_PASS;
                    int col = block.getMapColor(state).colorValue;
                    if (col != 0) {
                        r += 0.45f * ((col >> 16) & 0xFF);
                        g += 0.45f * ((col >> 8) & 0xFF);
                        b += 0.45f * (col & 0xFF);
                        tintWeight += 0.45f;
                    }
                } else {
                    return HIDDEN;
                }
            }
            if (visibility < 0.02f) return HIDDEN;

            // step into whichever neighbouring block the ray reaches first; stop once past the text
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > 1) break;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > 1) break;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1) break;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        if (tintWeight == 0) return visibility == 1 ? CLEAR : new Result(visibility, 0xFFFFFF, 0);
        int tint = Math.round(r / tintWeight) << 16 | Math.round(g / tintWeight) << 8 | Math.round(b / tintWeight);
        return new Result(visibility, tint, Math.min(0.75f, tintWeight));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    private static int sign(double v) {
        return v > 0 ? 1 : v < 0 ? -1 : 0;
    }

    /** t at which a ray starting at {@code start} moving {@code delta} per t crosses its first whole-block boundary. */
    private static double boundary(double start, double delta, int step) {
        if (step == 0) return Double.MAX_VALUE;
        double next = step > 0 ? Math.floor(start) + 1 : Math.floor(start);
        return (next - start) / delta;
    }
}
