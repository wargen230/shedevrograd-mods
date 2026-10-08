// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import javax.annotation.Nonnull;
import java.util.List;

public record SbkInfo(int formatVersion, long fileCount, long frameSizeBytes,
                      int[] groupFrames, long[] groupSizes, List<SbkIndexEntry> entries) {

    @Nonnull
    @Override
    public String toString() {
        return "Format: " + this.formatVersion
                + ", Files: " + this.fileCount
                + ", FrameSize: " + this.frameSizeBytes
                + ", Groups: " + this.groupFrames.length
                + "," + this.groupSizes.length;
    }
}
