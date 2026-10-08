// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes the SBK index block.
 *
 * <p>The index is compressed using the archive's declared algorithm and protected
 * by an xxHash32 checksum of the compressed bytes.
 */
public final class SbkIndex {

    /**
     * Sanity cap on the compressed index size accepted during reading.
     */
    private static final long MAX_INDEX_COMPRESSED_SIZE = 256L * 1024 * 1024; // 256 MiB

    /**
     * Decompression-bomb guard for the index block.
     */
    private static final long MAX_INDEX_RAW_SIZE = 1024L * 1024 * 1024; // 1 GiB

    private SbkIndex() {}

    /**
     * Serializes {@code entries}, compresses with {@code algorithm}, writes to {@code out},
     * and returns the compressed size, raw size, and checksum.
     *
     * @param entries   index entries, sorted by path
     * @param algorithm compression algorithm to use
     * @param level     compression level (0–9)
     * @param out       output stream positioned at the index block start
     * @return compressed size, raw size, and xxHash32 of the compressed bytes
     */
    public static WriteResult write(List<SbkIndexEntry> entries, SbkAlgorithm algorithm,
            int level, OutputStream out) throws IOException {
        // Serialize entries to raw bytes
        ByteArrayOutputStream rawOutputStream = new ByteArrayOutputStream();
        DataOutputStream rawDos = new DataOutputStream(rawOutputStream);

        long count = entries.size();
        rawDos.writeByte((int) (count & 0xFF));
        rawDos.writeByte((int) ((count >>> 8) & 0xFF));
        rawDos.writeByte((int) ((count >>> 16) & 0xFF));
        rawDos.writeByte((int) ((count >>> 24) & 0xFF));
        rawDos.writeByte((int) ((count >>> 32) & 0xFF));
        rawDos.writeByte((int) ((count >>> 40) & 0xFF));
        rawDos.writeByte((int) ((count >>> 48) & 0xFF));
        rawDos.writeByte((int) ((count >>> 56) & 0xFF));

        for (SbkIndexEntry entry : entries) {
            entry.write(rawDos);
        }
        rawDos.flush();
        byte[] raw = rawOutputStream.toByteArray();

        // Compress
        byte[] compressed = SbkFrameCompressor.compress(raw, algorithm, level);
        int checksum = SbkChecksum.xxHash32(compressed);
        out.write(compressed);

        return new WriteResult(compressed.length, raw.length, checksum);
    }

    /**
     * Reads the compressed index block, verifies its checksum, decompresses, and
     * deserializes the entries.
     *
     * @param in               stream positioned at the index block start
     * @param algorithm        algorithm declared in the archive header
     * @param compressedSize   bytes to read (from {@link SbkHeader#indexCompressedSize})
     * @param expectedChecksum stored checksum (from {@link SbkHeader#indexChecksum})
     * @throws SbkException if the checksum mismatches or the compressed/decompressed
     *                      size exceeds the sanity limits
     */
    public static List<SbkIndexEntry> read(InputStream in, SbkAlgorithm algorithm, long compressedSize, int expectedChecksum) throws IOException {
        if (compressedSize > MAX_INDEX_COMPRESSED_SIZE) {
            throw new SbkException("Index compressed size " + compressedSize
                    + " exceeds sanity limit " + MAX_INDEX_COMPRESSED_SIZE);
        }

        byte[] compressed = new byte[(int) compressedSize];
        SbkIndex.readFully(in, compressed);

        int checksum = SbkChecksum.xxHash32(compressed);
        if (checksum != expectedChecksum) {
            throw new SbkException("Index checksum mismatch: expected=0x"
                    + Integer.toHexString(expectedChecksum)
                    + " actual=0x" + Integer.toHexString(checksum));
        }

        byte[] raw = SbkFrameCompressor.decompress(compressed, algorithm, MAX_INDEX_RAW_SIZE);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(raw));
        // Read entry count (u64 LE) - must consume what write() emitted
        long entryCount = 0;
        for (int shift = 0; shift < 64; shift += 8) {
            entryCount |= ((long) dis.readUnsignedByte()) << shift;
        }

        List<SbkIndexEntry> entries = new ArrayList<>((int) Math.min(entryCount, 65536));
        for (long i = 0; i < entryCount; i++) {
            if (dis.available() == 0) {
                break;
            }

            entries.add(SbkIndexEntry.read(dis));
        }

        return entries;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int read = in.read(buf, offset, buf.length - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of stream reading SBK index");
            }

            offset += read;
        }
    }

    public record WriteResult(long compressedSize, long rawSize, int checksum) {}
}
