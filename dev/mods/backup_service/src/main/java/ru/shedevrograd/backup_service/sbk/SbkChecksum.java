// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

/**
 * Pure-Java xxHash32 implementation used for SBK frame and header checksums.
 * Implements the xxHash32 algorithm (seed always 0).
 */
public final class SbkChecksum {

    private static final int PRIME1 = 0x9E3779B1;
    private static final int PRIME2 = 0x85EBCA77;
    private static final int PRIME3 = 0xC2B2AE3D;
    private static final int PRIME4 = 0x27D4EB2F;
    private static final int PRIME5 = 0x165667B1;

    private SbkChecksum() {}

    public static int xxHash32(byte[] data) {
        return SbkChecksum.xxHash32(data, 0, data.length);
    }

    public static int xxHash32(byte[] data, int offset, int length) {
        int h32;
        int i = offset;
        int end = offset + length;

        if (length >= 16) {
            int v1 = PRIME1 + PRIME2;
            int v2 = PRIME2;
            int v3 = 0;
            int v4 = -PRIME1;

            int limit = end - 16;
            while (i <= limit) {
                v1 = Integer.rotateLeft(v1 + (SbkChecksum.getIntLE(data, i) * PRIME2), 13) * PRIME1;
                i += 4;
                v2 = Integer.rotateLeft(v2 + (SbkChecksum.getIntLE(data, i) * PRIME2), 13) * PRIME1;
                i += 4;
                v3 = Integer.rotateLeft(v3 + (SbkChecksum.getIntLE(data, i) * PRIME2), 13) * PRIME1;
                i += 4;
                v4 = Integer.rotateLeft(v4 + (SbkChecksum.getIntLE(data, i) * PRIME2), 13) * PRIME1;
                i += 4;
            }

            h32 = Integer.rotateLeft(v1, 1)
                    + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12)
                    + Integer.rotateLeft(v4, 18);
        } else {
            h32 = PRIME5;
        }

        h32 += length;

        while (i + 4 <= end) {
            h32 = Integer.rotateLeft(h32 + (SbkChecksum.getIntLE(data, i) * PRIME3), 17) * PRIME4;
            i += 4;
        }

        while (i < end) {
            h32 = Integer.rotateLeft(h32 + ((data[i] & 0xFF) * PRIME5), 11) * PRIME1;
            i++;
        }

        h32 ^= h32 >>> 15;
        h32 *= PRIME2;
        h32 ^= h32 >>> 13;
        h32 *= PRIME3;
        h32 ^= h32 >>> 16;

        return h32;
    }

    private static int getIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
