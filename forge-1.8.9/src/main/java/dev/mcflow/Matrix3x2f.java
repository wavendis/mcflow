package dev.mcflow;

/**
 * 1.8.9 compatibility: JOML's 2D affine matrix as the shared HUD code builds it:
 * x' = m00 * x + m10 * y + m20, y' = m01 * x + m11 * y + m21.
 */
final class Matrix3x2f {
    final float m00, m01, m10, m11, m20, m21;

    Matrix3x2f(float m00, float m01, float m10, float m11, float m20, float m21) {
        this.m00 = m00;
        this.m01 = m01;
        this.m10 = m10;
        this.m11 = m11;
        this.m20 = m20;
        this.m21 = m21;
    }
}
