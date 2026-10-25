package dev.mcflow;

/**
 * 1.8.9 compatibility: a 3D vector with the modern (Mojang-mapped) {@code Vec3} API the shared HUD code is written
 * against. 1.8.9's own {@code net.minecraft.util.Vec3} has different method names, so it isn't used here.
 */
final class Vec3 {
    final double x, y, z;

    Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    Vec3 scale(double f) {
        return new Vec3(x * f, y * f, z * f);
    }

    double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    double lengthSqr() {
        return x * x + y * y + z * z;
    }

    double length() {
        return Math.sqrt(lengthSqr());
    }

    Vec3 normalize() {
        double l = length();
        return l < 1e-4 ? new Vec3(0, 0, 0) : new Vec3(x / l, y / l, z / l);
    }

    /** Looking direction for a pitch ({@code xRot}) and yaw ({@code yRot}) in degrees, as Minecraft defines them. */
    static Vec3 directionFromRotation(float xRot, float yRot) {
        double yaw = Math.toRadians(-yRot) - Math.PI, pitch = Math.toRadians(-xRot);
        double horizontal = -Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * horizontal, Math.sin(pitch), Math.cos(yaw) * horizontal);
    }
}
