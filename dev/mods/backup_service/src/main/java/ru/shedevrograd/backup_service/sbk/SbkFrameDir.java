// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Directory of frame entries, one list per group.
 * On disk: for each group (IDs 0–3 in order): u32 LE frame count, then N SbkFrameEntry records.
 */
public final class SbkFrameDir {

    private final Map<SbkGroup, List<SbkFrameEntry>> frames = new EnumMap<>(SbkGroup.class);

    public SbkFrameDir() {
        for (SbkGroup g : SbkGroup.values()) {
            this.frames.put(g, new ArrayList<>());
        }
    }

    public List<SbkFrameEntry> framesFor(SbkGroup group) {
        return this.frames.get(group);
    }

    public void add(SbkGroup group, SbkFrameEntry entry) {
        this.frames.get(group).add(entry);
    }

    /**
     * Total disk size: 4 bytes per group count (4 groups) + 20 bytes per entry.
     */
    public long diskSize() {
        long size = 4L * SbkGroup.values().length; // one u32 count per group
        for (SbkGroup g : SbkGroup.values()) {
            size += (long) this.frames.get(g).size() * SbkFrameEntry.DISK_SIZE;
        }

        return size;
    }

    /**
     * Write frame dir (groups in id order 0,1,2,3).
     */
    public void write(DataOutputStream out) throws IOException {
        for (SbkGroup g : SbkGroup.values()) {
            List<SbkFrameEntry> list = this.frames.get(g);
            out.writeInt(Integer.reverseBytes(list.size()));

            for (SbkFrameEntry entry : list) {
                entry.write(out);
            }
        }
    }

    /**
     * Read frame dir from stream (groups in id order 0,1,2,3).
     */
    public static SbkFrameDir read(DataInputStream in) throws IOException {
        SbkFrameDir dir = new SbkFrameDir();

        for (SbkGroup g : SbkGroup.values()) {
            int count = Integer.reverseBytes(in.readInt());
            for (int i = 0; i < count; i++) {
                dir.add(g, SbkFrameEntry.read(in));
            }
        }

        return dir;
    }
}
