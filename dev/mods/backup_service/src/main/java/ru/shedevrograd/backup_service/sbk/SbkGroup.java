// Ported from SimpleBackups (https://github.com/ChaoticTrials/SimpleBackups, commit 45ebc71),
// Apache License 2.0. Modified for Shedevrograd: package renamed, changes marked "Shedevrograd:".
package ru.shedevrograd.backup_service.sbk;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Locale;

public enum SbkGroup {
    MCA(0),
    NBT(1),
    JSON(2),
    RAW(3);

    public final int id;

    SbkGroup(int id) {
        this.id = id;
    }

    @Nullable
    public static SbkGroup fromId(int id) {
        for (SbkGroup g : values()) {
            if (g.id == id) {
                return g;
            }
        }

        return null;
    }

    public static SbkGroup classify(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".mca")) {
            return MCA;
        }

        if (name.endsWith(".dat") || name.endsWith(".dat_old")) {
            return NBT;
        }

        if (name.endsWith(".json")) {
            return JSON;
        }

        return RAW;
    }
}
