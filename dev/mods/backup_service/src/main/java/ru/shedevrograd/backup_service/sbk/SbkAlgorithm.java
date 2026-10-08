// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

/**
 * Compression algorithm used for SBK data frames and the index block.
 *
 * <p>Written as a single {@code u8} at byte offset 10 of the archive header.
 * The same algorithm applies to every frame and to the index block -
 * there is no per-frame algorithm field.
 *
 * <p>On compression, the algorithm is chosen at write time and stored in
 * the header. On decompression, the algorithm is read from the header
 * and used unconditionally - there is no fallback during reading.
 */
public enum SbkAlgorithm {

    /**
     * LZMA2 in XZ container format.
     * Uses {@link org.tukaani.xz.XZOutputStream} / {@link org.tukaani.xz.XZInputStream}.
     * Requires {@code xz-java} to be present in the external-dependencies directory.
     * Loaded via {@link SbkCodecs} at runtime.
     */
    LZMA2(0),

    /**
     * Zstandard.
     * Requires {@code zstd-jni} to be present in the external-dependencies directory.
     * Loaded via {@link SbkCodecs} at runtime.
     */
    ZSTD(1);

    /**
     * On-disk byte value for this algorithm.
     */
    public final int id;

    SbkAlgorithm(int id) {this.id = id;}

    /**
     * Returns the algorithm for the given on-disk byte value.
     *
     * @param id on-disk algorithm identifier
     * @return the matching algorithm
     * @throws SbkException if {@code id} is not a known algorithm value
     */
    public static SbkAlgorithm fromId(int id) {
        for (SbkAlgorithm algorithm : SbkAlgorithm.values()) {
            if (algorithm.id == id) {
                return algorithm;
            }
        }

        throw new SbkException("Unsupported compression algorithm: " + id);
    }
}
