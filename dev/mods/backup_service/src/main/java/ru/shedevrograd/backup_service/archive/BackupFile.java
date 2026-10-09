package ru.shedevrograd.backup_service.archive;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A backup archive identified by its file name.
 *
 * <p>The name carries everything needed for retention and restore, so a human picking files
 * from a directory listing can see which full backup a diff needs:
 * <pre>
 *   full-2026-10-04_03-00-00.sbk
 *   diff-2026-10-09_03-00-00.base-2026-10-04_03-00-00.sbk
 * </pre>
 */
public record BackupFile(Kind kind, LocalDateTime createdAt, @Nullable LocalDateTime base) {

    public enum Kind { FULL, DIFF }

    public static final String EXTENSION = ".sbk";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String TIME = "(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})";
    private static final Pattern FULL = Pattern.compile("full-" + TIME + "\\.sbk");
    private static final Pattern DIFF = Pattern.compile("diff-" + TIME + "\\.base-" + TIME + "\\.sbk");

    public static BackupFile full(LocalDateTime createdAt) {
        return new BackupFile(Kind.FULL, createdAt.withNano(0), null);
    }

    public static BackupFile diff(LocalDateTime createdAt, BackupFile base) {
        if (base.kind != Kind.FULL) {
            throw new IllegalArgumentException("A diff must be based on a full backup, got " + base.fileName());
        }
        return new BackupFile(Kind.DIFF, createdAt.withNano(0), base.createdAt);
    }

    /** Parses a backup file name; empty for anything that is not a backup archive. */
    public static Optional<BackupFile> parse(Path file) {
        String name = file.getFileName().toString();
        try {
            Matcher full = FULL.matcher(name);
            if (full.matches()) {
                return Optional.of(new BackupFile(Kind.FULL, LocalDateTime.parse(full.group(1), TIME_FORMAT), null));
            }
            Matcher diff = DIFF.matcher(name);
            if (diff.matches()) {
                return Optional.of(new BackupFile(Kind.DIFF,
                        LocalDateTime.parse(diff.group(1), TIME_FORMAT),
                        LocalDateTime.parse(diff.group(2), TIME_FORMAT)));
            }
        } catch (DateTimeParseException ignored) {
            // matches the pattern but is not a real date, e.g. month 13
        }
        return Optional.empty();
    }

    /** Backup archives in a directory, oldest first. Other files are ignored. */
    public static List<BackupFile> listIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .map(BackupFile::parse)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(BackupFile::createdAt))
                    .toList();
        }
    }

    public String fileName() {
        String created = this.createdAt.format(TIME_FORMAT);
        return this.kind == Kind.FULL
                ? "full-" + created + EXTENSION
                : "diff-" + created + ".base-" + this.base.format(TIME_FORMAT) + EXTENSION;
    }

    /** File name of the full backup a diff is based on. */
    public String baseFileName() {
        if (this.kind != Kind.DIFF) {
            throw new IllegalStateException(this.fileName() + " is not a diff");
        }
        return BackupFile.full(this.base).fileName();
    }

    public boolean isBasedOn(BackupFile full) {
        return this.kind == Kind.DIFF && full.kind == Kind.FULL && this.base.equals(full.createdAt);
    }
}
