package ru.shedevrograd.backup_service.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Stored inside every diff archive as {@code <world>/backup_service-diff.txt}.
 *
 * <p>A diff only contains files changed since its full backup, so on its own it cannot tell a
 * restore which files were deleted meanwhile. The manifest lists every file the world had at the
 * time of the diff; a restore removes whatever the full backup brought in that is not listed.
 *
 * <p>Plain text on purpose: the standalone sbk-tool has no JSON library.
 * <pre>
 *   # backup_service diff manifest v1
 *   base full-2026-10-04_03-00-00.sbk
 *   world/level.dat
 *   world/region/r.0.0.mca
 * </pre>
 */
public record DiffManifest(String baseFileName, List<String> paths) {

    public static final String FILE_NAME = "backup_service-diff.txt";
    private static final String HEADER = "# backup_service diff manifest v1";
    private static final String BASE_PREFIX = "base ";

    public byte[] toBytes() {
        StringBuilder text = new StringBuilder(HEADER).append('\n')
                .append(BASE_PREFIX).append(this.baseFileName).append('\n');
        for (String path : this.paths) {
            text.append(path).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static DiffManifest parse(byte[] bytes) throws IOException {
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.size() < 2 || !lines.get(0).equals(HEADER) || !lines.get(1).startsWith(BASE_PREFIX)) {
            throw new IOException("Unrecognized diff manifest");
        }

        String base = lines.get(1).substring(BASE_PREFIX.length());
        List<String> paths = new ArrayList<>(lines.subList(2, lines.size()));
        paths.removeIf(String::isEmpty);
        return new DiffManifest(base, paths);
    }
}
