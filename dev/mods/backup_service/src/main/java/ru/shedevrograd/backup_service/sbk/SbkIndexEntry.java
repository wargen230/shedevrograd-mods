// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Variable-length index entry for one file in the SBK archive.
 * On disk (all LE):
 * path_len(u16) + path(UTF-8) + mtime_ms(i64) + group_id(u8) +
 * stream_offset(u64) + stream_raw_size(u64) + original_size(u64) + file_checksum(u32)
 */
public record SbkIndexEntry(String path, long mtimeMs, SbkGroup group, long streamOffset, long streamRawSize, long originalSize, int fileChecksum) {

    public void write(DataOutputStream out) throws IOException {
        byte[] pathBytes = this.path.getBytes(StandardCharsets.UTF_8);

        // path_len u16 LE
        out.writeByte(pathBytes.length & 0xFF);
        out.writeByte((pathBytes.length >>> 8) & 0xFF);

        // path bytes
        out.write(pathBytes);

        // mtime_ms i64 LE
        out.writeLong(Long.reverseBytes(this.mtimeMs));

        // group_id u8
        out.writeByte(this.group.id);

        // stream_offset u64 LE
        out.writeLong(Long.reverseBytes(this.streamOffset));

        // stream_raw_size u64 LE
        out.writeLong(Long.reverseBytes(this.streamRawSize));

        // original_size u64 LE
        out.writeLong(Long.reverseBytes(this.originalSize));

        // file_checksum u32 LE
        out.writeInt(Integer.reverseBytes(this.fileChecksum));
    }

    public static SbkIndexEntry read(DataInputStream in) throws IOException {
        // path_len u16 LE
        int lo = in.readUnsignedByte();
        int hi = in.readUnsignedByte();
        int pathLen = lo | (hi << 8);

        // path bytes
        byte[] pathBytes = new byte[pathLen];
        in.readFully(pathBytes);
        String path = new String(pathBytes, StandardCharsets.UTF_8);

        // mtime_ms i64 LE
        long mtimeMs = Long.reverseBytes(in.readLong());

        // group_id u8
        int groupId = in.readUnsignedByte();
        SbkGroup group = SbkGroup.fromId(groupId);
        if (group == null) {
            throw new SbkException("Unknown group id: " + groupId);
        }

        // stream_offset u64 LE
        long streamOffset = Long.reverseBytes(in.readLong());

        // stream_raw_size u64 LE
        long streamRawSize = Long.reverseBytes(in.readLong());

        // original_size u64 LE
        long originalSize = Long.reverseBytes(in.readLong());

        // file_checksum u32 LE
        int fileChecksum = Integer.reverseBytes(in.readInt());

        return new SbkIndexEntry(path, mtimeMs, group, streamOffset, streamRawSize, originalSize, fileChecksum);
    }
}
