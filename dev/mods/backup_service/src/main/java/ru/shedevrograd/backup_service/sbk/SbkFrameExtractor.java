// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for determining which frames are needed to reconstruct an index entry
 * and for slicing the entry's bytes out of the decompressed frame cache.
 */
public final class SbkFrameExtractor {

    private SbkFrameExtractor() {}

    /**
     * Returns the set of frame keys required to reconstruct the given entry.
     * Frame key encoding: {@code (groupId << 32) | frameIndex}.
     * The set is ordered by ascending frame index.
     */
    public static Set<Long> requiredFrames(SbkIndexEntry entry, long frameSize) {
        if (entry.streamRawSize() == 0) {
            return new LinkedHashSet<>();
        }

        long startFrame = entry.streamOffset() / frameSize;
        long endFrame = (entry.streamOffset() + entry.streamRawSize() - 1) / frameSize;

        Set<Long> keys = new LinkedHashSet<>();
        for (long f = startFrame; f <= endFrame; f++) {
            keys.add(((long) entry.group().id << 32) | f);
        }

        return keys;
    }

    /**
     * Slice the raw preprocessed bytes for an entry from the decompressed frame cache.
     *
     * @param frames    map from frame key (as returned by requiredFrames) to decompressed frame bytes
     * @param entry     the index entry to extract
     * @param frameSize the frame size used when writing (same as SbkHeader.frameSizeBytes)
     * @return the raw preprocessed bytes for this entry
     */
    public static byte[] slice(Map<Long, byte[]> frames, SbkIndexEntry entry, long frameSize) {
        int rawSize = (int) entry.streamRawSize();
        if (rawSize == 0) {
            return new byte[0];
        }

        byte[] result = new byte[rawSize];
        int resultOffset = 0;

        long startFrame = entry.streamOffset() / frameSize;
        long endFrame = (entry.streamOffset() + entry.streamRawSize() - 1) / frameSize;

        for (long f = startFrame; f <= endFrame; f++) {
            long frameKey = ((long) entry.group().id << 32) | f;
            byte[] frameData = frames.get(frameKey);

            if (frameData == null) {
                throw new SbkException("Missing frame " + f + " for group " + entry.group());
            }

            long frameStart = f * frameSize;

            long entryStart = entry.streamOffset();
            long entryEnd = entry.streamOffset() + entry.streamRawSize();

            long readStart = Math.max(entryStart, frameStart);
            long readEnd = Math.min(entryEnd, frameStart + frameData.length);

            if (readStart >= readEnd) {
                continue;
            }

            int srcOffset = (int) (readStart - frameStart);
            int copyLen = (int) (readEnd - readStart);

            System.arraycopy(frameData, srcOffset, result, resultOffset, copyLen);
            resultOffset += copyLen;
        }

        if (resultOffset != rawSize) {
            throw new SbkException("Failed to slice entry " + entry.path() + ": expected "
                    + rawSize + " bytes but got " + resultOffset);
        }

        return result;
    }
}
