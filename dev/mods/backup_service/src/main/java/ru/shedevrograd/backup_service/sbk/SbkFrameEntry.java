// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * One frame directory entry. 20 bytes on disk (little-endian):
 * frameOffset(u64) + compressedSize(u32) + rawSize(u32) + checksum(u32)
 */
public record SbkFrameEntry(long frameOffset, int compressedSize, int rawSize, int checksum) {

    public static final int DISK_SIZE = 20;

    /**
     * Write this entry in little-endian format.
     */
    public void write(DataOutputStream out) throws IOException {
        out.writeLong(Long.reverseBytes(this.frameOffset));
        out.writeInt(Integer.reverseBytes(this.compressedSize));
        out.writeInt(Integer.reverseBytes(this.rawSize));
        out.writeInt(Integer.reverseBytes(this.checksum));
    }

    /**
     * Read one entry from the stream (little-endian).
     */
    public static SbkFrameEntry read(DataInputStream in) throws IOException {
        long frameOffset = Long.reverseBytes(in.readLong());
        int compressedSize = Integer.reverseBytes(in.readInt());
        int rawSize = Integer.reverseBytes(in.readInt());
        int checksum = Integer.reverseBytes(in.readInt());

        return new SbkFrameEntry(frameOffset, compressedSize, rawSize, checksum);
    }
}
