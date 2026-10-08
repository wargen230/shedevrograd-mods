// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

/**
 * Order-5 (32x32) Hilbert curve. Maps (x,z) in [0,31]^2 to index in [0,1023].
 */
public final class HilbertCurve {

    private HilbertCurve() {}

    /**
     * Maps (x, z) coordinates in [0,31]^2 to a Hilbert curve index in [0,1023].
     * Uses the standard iterative xy2d algorithm for n=32 (order 5).
     *
     * @param x chunk X in region [0,31]
     * @param z chunk Z in region [0,31]
     * @return Hilbert curve index in [0,1023]
     */
    public static int index(int x, int z) {
        if (x < 0 || x > 31 || z < 0 || z > 31) {
            throw new IllegalArgumentException("x and z must be in [0,31], got x=" + x + ", z=" + z);
        }

        int rx, rz, s, d = 0;

        for (s = 16; s > 0; s >>= 1) {
            rx = (x & s) > 0 ? 1 : 0;
            rz = (z & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ rz);

            // rotate
            if (rz == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    z = s - 1 - z;
                }
                int t = x;
                x = z;
                z = t;
            }
        }

        return d;
    }
}
