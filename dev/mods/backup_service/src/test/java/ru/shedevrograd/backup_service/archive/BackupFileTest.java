package ru.shedevrograd.backup_service.archive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupFileTest {
    private static final LocalDateTime SUNDAY = LocalDateTime.of(2026, 10, 4, 3, 0, 0);

    @Test
    void namesRoundTrip() {
        BackupFile full = BackupFile.full(SUNDAY);
        BackupFile diff = BackupFile.diff(SUNDAY.plusDays(5).plusSeconds(12), full);

        assertEquals("full-2026-10-04_03-00-00.sbk", full.fileName());
        assertEquals("diff-2026-10-09_03-00-12.base-2026-10-04_03-00-00.sbk", diff.fileName());
        assertEquals("full-2026-10-04_03-00-00.sbk", diff.baseFileName());
        assertEquals(Optional.of(full), BackupFile.parse(Path.of("backups", full.fileName())));
        assertEquals(Optional.of(diff), BackupFile.parse(Path.of(diff.fileName())));
        assertTrue(diff.isBasedOn(full));
    }

    @Test
    void ignoresOtherFiles() {
        for (String name : List.of("full-2026-10-04_03-00-00.sbk.tmp", "notes.txt", "full-2026-13-04_03-00-00.sbk",
                "diff-2026-10-09_03-00-00.sbk", "full-2026-10-04.sbk")) {
            assertEquals(Optional.empty(), BackupFile.parse(Path.of(name)), name);
        }
    }

    @Test
    void manifestRoundTrip() throws IOException {
        DiffManifest manifest = new DiffManifest("full-2026-10-04_03-00-00.sbk",
                List.of("world/level.dat", "world/region/r.0.0.mca", "world/DIM-1/region/r.-1.0.mca"));
        assertEquals(manifest, DiffManifest.parse(manifest.toBytes()));
    }

    @Test
    void manifestRejectsUnknownContent() {
        assertThrows(IOException.class, () -> DiffManifest.parse("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
