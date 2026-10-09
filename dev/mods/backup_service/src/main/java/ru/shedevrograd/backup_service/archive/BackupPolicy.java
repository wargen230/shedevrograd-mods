package ru.shedevrograd.backup_service.archive;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which kind of backup to make and which archives to delete. Pure logic, no IO.
 *
 * <p>Scheme: a full backup once a week, a diff against the latest full on the other days.
 * Retention keeps every backup from the last {@code dailyDays} days, the newest
 * {@code weeklyCount} full backups, and any full backup a kept diff still depends on.
 */
public final class BackupPolicy {

    private BackupPolicy() {}

    /**
     * Base for today's scheduled diff, or empty when today's backup must be full: on the full
     * backup day, when there is no full backup yet, or when the latest one is a week old or more
     * (the full backup day was missed, e.g. the server was down).
     */
    public static Optional<BackupFile> scheduledDiffBase(Collection<BackupFile> backups, LocalDate today, DayOfWeek fullBackupDay) {
        if (today.getDayOfWeek() == fullBackupDay) {
            return Optional.empty();
        }
        return BackupPolicy.latestFull(backups)
                .filter(full -> full.createdAt().toLocalDate().isAfter(today.minusDays(7)));
    }

    /**
     * True if the daily backup time lies in {@code (previous, now]}, i.e. the clock passed it since
     * the last check. Both dates are checked in case the interval spans midnight.
     */
    public static boolean timeReached(LocalDateTime previous, LocalDateTime now, LocalTime time) {
        for (LocalDate date : List.of(previous.toLocalDate(), now.toLocalDate())) {
            LocalDateTime candidate = date.atTime(time);
            if (candidate.isAfter(previous) && !candidate.isAfter(now)) {
                return true;
            }
        }
        return false;
    }

    public static Optional<BackupFile> latestFull(Collection<BackupFile> backups) {
        return backups.stream()
                .filter(backup -> backup.kind() == BackupFile.Kind.FULL)
                .max(Comparator.comparing(BackupFile::createdAt));
    }

    public static Set<BackupFile> toDelete(Collection<BackupFile> backups, LocalDate today, int dailyDays, int weeklyCount) {
        Set<BackupFile> keep = new HashSet<>();

        LocalDate oldestDailyDate = today.minusDays(dailyDays - 1L);
        for (BackupFile backup : backups) {
            if (!backup.createdAt().toLocalDate().isBefore(oldestDailyDate)) {
                keep.add(backup);
            }
        }

        List<BackupFile> fullsNewestFirst = backups.stream()
                .filter(backup -> backup.kind() == BackupFile.Kind.FULL)
                .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                .toList();
        keep.addAll(fullsNewestFirst.subList(0, Math.min(weeklyCount, fullsNewestFirst.size())));

        // A diff is useless without its full backup
        for (BackupFile backup : Set.copyOf(keep)) {
            if (backup.kind() == BackupFile.Kind.DIFF) {
                fullsNewestFirst.stream().filter(backup::isBasedOn).findFirst().ifPresent(keep::add);
            }
        }

        Set<BackupFile> delete = new HashSet<>(backups);
        delete.removeAll(keep);
        return delete;
    }
}
