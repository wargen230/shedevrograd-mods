// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;


import java.io.*;

/**
 * Compresses and decompresses SBK frames and the index block.
 *
 * <h2>Algorithm selection</h2>
 * <ul>
 *   <li>{@link SbkAlgorithm#LZMA2}: uses {@code XZOutputStream}/{@code XZInputStream}
 *       loaded via {@link SbkCodecs} at runtime.
 *       If xz-java is not present, throws {@link SbkException}.</li>
 *   <li>{@link SbkAlgorithm#ZSTD}: uses {@code ZstdOutputStream}
 *       and {@code ZstdInputStream} loaded via {@link SbkCodecs} at runtime.
 *       If zstd-jni is not present, throws {@link SbkException}.</li>
 * </ul>
 *
 * <h2>OOM prevention</h2>
 * All decompression calls cap output at {@code expectedRawSize + 1} bytes via
 * a streaming read loop. If more bytes are produced, an exception is thrown
 * before they are loaded into memory.
 */
public final class SbkFrameCompressor {

    private SbkFrameCompressor() {}

    /**
     * Compresses {@code data} using the given algorithm and level.
     *
     * @param data      bytes to compress
     * @param algorithm compression algorithm
     * @param level     compression level: 0–9 for lzma2; mapped to 1–19 for zstd
     * @return compressed bytes
     * @throws SbkException if zstd is requested but zstd-jni is not available
     */
    public static byte[] compress(byte[] data, SbkAlgorithm algorithm, int level) throws IOException {
        return switch(algorithm) {
            case LZMA2 -> SbkFrameCompressor.compressLzma2(data, level);
            case ZSTD -> SbkFrameCompressor.compressZstd(data, level);
        };
    }

    /**
     * Decompresses a single frame or the index block.
     *
     * <p>Output is capped at {@code expectedRawSize + 1} bytes. If the decompressed
     * stream exceeds this, an exception is thrown - the excess is never
     * loaded into memory.
     *
     * @param compressed      compressed bytes
     * @param algorithm       algorithm declared in the archive header
     * @param expectedRawSize declared raw size; used as a decompression-bomb guard
     * @return decompressed bytes
     * @throws SbkException if decompressed size exceeds {@code expectedRawSize},
     *                      or if zstd is required but zstd-jni is not available
     */
    public static byte[] decompress(byte[] compressed, SbkAlgorithm algorithm, long expectedRawSize) throws IOException {
        return switch(algorithm) {
            case LZMA2 -> SbkFrameCompressor.decompressLzma2(compressed, expectedRawSize);
            case ZSTD -> SbkFrameCompressor.decompressZstd(compressed, expectedRawSize);
        };
    }

    // ── lzma2 ────────────────────────────────────────────────────────────────

    private static byte[] compressLzma2(byte[] data, int level) throws IOException {
        if (!SbkCodecs.isLzmaAvailable()) {
            throw new SbkException(
                    "xz-java is not available in external-dependencies. " +
                            "Download it and place it in the external-dependencies directory.");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (OutputStream xz = SbkCodecs.wrapWithXz(outputStream, level)) {
            xz.write(data);
        }

        return outputStream.toByteArray();
    }

    private static byte[] decompressLzma2(byte[] compressed, long expectedRawSize) throws IOException {
        if (!SbkCodecs.isLzmaAvailable()) {
            throw new SbkException(
                    "xz-java is not available in external-dependencies. " +
                            "Download it and place it in the external-dependencies directory.");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        try (InputStream xz = SbkCodecs.wrapXzInput(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = xz.read(buf)) >= 0) {
                total += read;

                if (total > expectedRawSize) {
                    throw new SbkException("lzma2 frame decompressed beyond declared size " + expectedRawSize);
                }

                outputStream.write(buf, 0, read);
            }
        }

        return outputStream.toByteArray();
    }

    // ── zstd ─────────────────────────────────────────────────────────────────

    private static byte[] compressZstd(byte[] data, int level) throws IOException {
        if (!SbkCodecs.isZstdAvailable()) {
            throw new SbkException(
                    "zstd-jni is not available in external-dependencies. " +
                            "Download it and place it in the external-dependencies directory.");
        }

        // Map config level 0–9 → zstd level 1–19
        int zstdLevel = Math.max(1, level * 2 + 1);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (OutputStream outputStream = SbkCodecs.wrapWithZstd(byteArrayOutputStream, zstdLevel)) {
            outputStream.write(data);
        }

        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] decompressZstd(byte[] compressed, long expectedRawSize) throws IOException {
        if (!SbkCodecs.isZstdAvailable()) {
            throw new SbkException(
                    "zstd-jni is not available in external-dependencies. " +
                            "Download it and place it in the external-dependencies directory.");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        try (InputStream inputStream = SbkCodecs.wrapZstdInput(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = inputStream.read(buf)) >= 0) {
                total += read;

                if (total > expectedRawSize) {
                    throw new SbkException("zstd frame decompressed beyond declared size " + expectedRawSize);
                }

                outputStream.write(buf, 0, read);
            }
        }

        return outputStream.toByteArray();
    }
}
