// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks per-file stream offsets and index entries for one {@link SbkGroup}.
 *
 * <p>This class does not buffer file bytes. Actual frame building and writing
 * is handled by {@link SbkWriter}, which calls {@link #add} after writing each
 * file's preprocessed bytes into the current frame buffer.
 */
public final class SbkSolidBuilder {

    private final SbkGroup group;
    private long solidStreamOffset = 0;
    private final List<SbkIndexEntry> entries = new ArrayList<>();

    /**
     * @param group the file group this builder handles
     */
    public SbkSolidBuilder(SbkGroup group) {
        this.group = group;
    }

    /**
     * Records a file that has been appended to the solid stream.
     *
     * <p>Must be called in the same order files are written into the stream
     * so that {@code streamOffset} values are assigned correctly.
     *
     * @param relativePath  forward-slash relative archive path
     * @param streamRawSize byte count of the preprocessed data in the solid stream
     * @param originalSize  file size before preprocessing
     * @param mtimeMs       last-modified time in milliseconds since Unix epoch
     * @param checksum      xxHash32 of the original file bytes (before preprocessing)
     */
    public void add(String relativePath, long streamRawSize, long originalSize, long mtimeMs, int checksum) {
        this.entries.add(
                new SbkIndexEntry(
                        relativePath,
                        mtimeMs,
                        this.group,
                        this.solidStreamOffset,
                        streamRawSize,
                        originalSize,
                        checksum
                )
        );
        this.solidStreamOffset += streamRawSize;
    }

    /**
     * Returns the index entries for all files added so far.
     *
     * @return unmodifiable list of index entries in insertion order
     */
    public List<SbkIndexEntry> entries() {
        return Collections.unmodifiableList(this.entries);
    }
}
