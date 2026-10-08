// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * The SBK fixed header. Written at byte offset 0 of every archive.
 *
 * <h2>On-disk layout (79 bytes, all integers little-endian)</h2>
 * <pre>
 *  Byte   Size  Field
 *  0–7      8   magic = "SBK!V1\r\n"
 *  8        1   format_version
 *  9        1   flags
 *  10       1   compression_algorithm  (0 = lzma2, 1 = zstd)
 *  11–14    4   reserved               must be 0x00000000
 *  15–22    8   file_count
 *  23–30    8   frame_size_bytes
 *  31–38    8   frame_dir_offset
 *  39–46    8   frame_dir_size
 *  47–54    8   index_offset
 *  55–62    8   index_compressed_size
 *  63–70    8   index_raw_size
 *  71–74    4   index_checksum         xxHash32 of compressed index bytes
 *  75–78    4   header_checksum        xxHash32 of bytes 0–74 (this field = 0)
 * </pre>
 */
public final class SbkHeader {

    public static final byte[] MAGIC = {0x53, 0x42, 0x4B, 0x21, 0x56, 0x31, 0x0D, 0x0A};
    public static final int FORMAT_VERSION = 1;
    public static final int DISK_SIZE = 79;
    public static final long DEFAULT_FRAME_SIZE = 16L * 1024 * 1024;

    public final int formatVersion;
    public final int flags;
    public final SbkAlgorithm algorithm;
    public final long fileCount;
    public final long frameSizeBytes;
    public final long frameDirOffset;
    public final long frameDirSize;
    public final long indexOffset;
    public final long indexCompressedSize;
    public final long indexRawSize;
    public final int indexChecksum;

    public SbkHeader(int formatVersion, int flags, SbkAlgorithm algorithm,
            long fileCount, long frameSizeBytes,
            long frameDirOffset, long frameDirSize,
            long indexOffset, long indexCompressedSize,
            long indexRawSize, int indexChecksum) {
        this.formatVersion = formatVersion;
        this.flags = flags;
        this.algorithm = algorithm;
        this.fileCount = fileCount;
        this.frameSizeBytes = frameSizeBytes;
        this.frameDirOffset = frameDirOffset;
        this.frameDirSize = frameDirSize;
        this.indexOffset = indexOffset;
        this.indexCompressedSize = indexCompressedSize;
        this.indexRawSize = indexRawSize;
        this.indexChecksum = indexChecksum;
    }

    /**
     * Writes 79 zero-bytes as a placeholder at the current stream position.
     * Seek back and call {@link #write} once all offsets are known.
     */
    public static void writePlaceholder(OutputStream out) throws IOException {
        out.write(new byte[DISK_SIZE]);
    }

    /**
     * Serializes this header and writes it to {@code out}.
     * Computes {@code header_checksum} as xxHash32 of bytes 0–74
     * (with bytes 75–78 zeroed during computation).
     */
    public void write(OutputStream out) throws IOException {
        byte[] buf = new byte[DISK_SIZE];

        System.arraycopy(MAGIC, 0, buf, 0, 8); // 0–7
        buf[8] = (byte) this.formatVersion; // 8
        buf[9] = (byte) this.flags; // 9
        buf[10] = (byte) this.algorithm.id; // 10
        // 11–14: reserved = 0 (already zero)
        SbkHeader.putLongLE(buf, 15, this.fileCount); // 15–22
        SbkHeader.putLongLE(buf, 23, this.frameSizeBytes); // 23–30
        SbkHeader.putLongLE(buf, 31, this.frameDirOffset); // 31–38
        SbkHeader.putLongLE(buf, 39, this.frameDirSize); // 39–46
        SbkHeader.putLongLE(buf, 47, this.indexOffset); // 47–54
        SbkHeader.putLongLE(buf, 55, this.indexCompressedSize); // 55–62
        SbkHeader.putLongLE(buf, 63, this.indexRawSize); // 63–70
        SbkHeader.putIntLE(buf, 71, this.indexChecksum); // 71–74
        // 75–78: leave as 0 for checksum computation

        int checksum = SbkChecksum.xxHash32(buf, 0, 75);
        SbkHeader.putIntLE(buf, 75, checksum);

        out.write(buf);
    }

    /**
     * Reads and validates the 79-byte header from the beginning of {@code raf}.
     *
     * @throws SbkException if magic is wrong, the version unsupported, checksum mismatches,
     *                      algorithm is unknown, or reserved bytes are non-zero
     */
    public static SbkHeader read(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        byte[] buf = new byte[DISK_SIZE];
        raf.readFully(buf);

        // 1. Magic
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf[i] != MAGIC[i]) {
                throw new SbkException("Not an SBK archive: invalid magic bytes");
            }
        }

        // 2. Version
        int version = buf[8] & 0xFF;
        if (version != FORMAT_VERSION) {
            throw new SbkException("Unsupported SBK format version: " + version);
        }

        // 3. Header checksum - covers bytes 0–74, checksum field (75–78) zeroed
        int storedChecksum = SbkHeader.getIntLE(buf, 75);
        SbkHeader.putIntLE(buf, 75, 0);
        int computedChecksum = SbkChecksum.xxHash32(buf, 0, 75);
        if (storedChecksum != computedChecksum) {
            throw new SbkException("SBK header checksum mismatch: stored=0x"
                    + Integer.toHexString(storedChecksum)
                    + " computed=0x" + Integer.toHexString(computedChecksum));
        }

        // 4. Algorithm
        SbkAlgorithm algorithm = SbkAlgorithm.fromId(buf[10] & 0xFF);

        // 5. Reserved bytes must be zero
        if (buf[11] != 0 || buf[12] != 0 || buf[13] != 0 || buf[14] != 0) {
            throw new SbkException("Non-zero reserved bytes in SBK header (bytes 11–14)");
        }

        return new SbkHeader(
                version,
                buf[9] & 0xFF,
                algorithm,
                SbkHeader.getLongLE(buf, 15), // file_count
                SbkHeader.getLongLE(buf, 23), // frame_size_bytes
                SbkHeader.getLongLE(buf, 31), // frame_dir_offset
                SbkHeader.getLongLE(buf, 39), // frame_dir_size
                SbkHeader.getLongLE(buf, 47), // index_offset
                SbkHeader.getLongLE(buf, 55), // index_compressed_size
                SbkHeader.getLongLE(buf, 63), // index_raw_size
                SbkHeader.getIntLE(buf, 71)   // index_checksum
        );
    }

    // --- little-endian helpers ---

    private static void putLongLE(byte[] buf, int offset, long value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >>> 8);
        buf[offset + 2] = (byte) (value >>> 16);
        buf[offset + 3] = (byte) (value >>> 24);
        buf[offset + 4] = (byte) (value >>> 32);
        buf[offset + 5] = (byte) (value >>> 40);
        buf[offset + 6] = (byte) (value >>> 48);
        buf[offset + 7] = (byte) (value >>> 56);
    }

    private static long getLongLE(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF))
                | ((long) (buf[offset + 1] & 0xFF) << 8)
                | ((long) (buf[offset + 2] & 0xFF) << 16)
                | ((long) (buf[offset + 3] & 0xFF) << 24)
                | ((long) (buf[offset + 4] & 0xFF) << 32)
                | ((long) (buf[offset + 5] & 0xFF) << 40)
                | ((long) (buf[offset + 6] & 0xFF) << 48)
                | ((long) (buf[offset + 7] & 0xFF) << 56);
    }

    private static void putIntLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) value;
        buf[offset + 1] = (byte) (value >>> 8);
        buf[offset + 2] = (byte) (value >>> 16);
        buf[offset + 3] = (byte) (value >>> 24);
    }

    private static int getIntLE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
                | ((buf[offset + 1] & 0xFF) << 8)
                | ((buf[offset + 2] & 0xFF) << 16)
                | ((buf[offset + 3] & 0xFF) << 24);
    }
}
