package ru.shedevrograd.backup_service.archive;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupPolicyTest {
    private static final LocalTime BACKUP_TIME = LocalTime.of(3, 0);
    // 2026-10-04 is a Sunday
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 4);

    @Test
    void fullOnFullBackupDay() {
        List<BackupFile> backups = List.of(BackupFile.full(SUNDAY.minusDays(7).atTime(BACKUP_TIME)));
        assertEquals(Optional.empty(), BackupPolicy.scheduledDiffBase(backups, SUNDAY, DayOfWeek.SUNDAY));
    }

    @Test
    void diffAgainstLatestFullOnOtherDays() {
        BackupFile full = BackupFile.full(SUNDAY.atTime(BACKUP_TIME));
        List<BackupFile> backups = List.of(BackupFile.full(SUNDAY.minusDays(7).atTime(BACKUP_TIME)), full);
        assertEquals(Optional.of(full), BackupPolicy.scheduledDiffBase(backups, SUNDAY.plusDays(3), DayOfWeek.SUNDAY));
    }

    @Test
    void fullWhenThereIsNoFullYet() {
        assertEquals(Optional.empty(), BackupPolicy.scheduledDiffBase(List.of(), SUNDAY.plusDays(2), DayOfWeek.SUNDAY));
    }

    @Test
    void fullWhenLatestFullIsAWeekOld() {
        // The server was down on Sunday: Monday must not build on a full from 8 days ago
        List<BackupFile> backups = List.of(BackupFile.full(SUNDAY.minusDays(7).atTime(BACKUP_TIME)));
        assertEquals(Optional.empty(), BackupPolicy.scheduledDiffBase(backups, SUNDAY.plusDays(1), DayOfWeek.SUNDAY));
    }

    @Test
    void timeReachedOnlyWhenClockPassesBackupTime() {
        LocalDateTime before = SUNDAY.atTime(2, 59, 55);
        assertTrue(BackupPolicy.timeReached(before, SUNDAY.atTime(3, 0, 5), BACKUP_TIME));
        assertTrue(BackupPolicy.timeReached(before, SUNDAY.atTime(3, 0, 0), BACKUP_TIME));
        assertFalse(BackupPolicy.timeReached(before, SUNDAY.atTime(2, 59, 59), BACKUP_TIME));
        // already ran at exactly 03:00:00 in the previous check
        assertFalse(BackupPolicy.timeReached(SUNDAY.atTime(3, 0, 0), SUNDAY.atTime(3, 0, 10), BACKUP_TIME));
    }

    @Test
    void timeReachedAcrossMidnight() {
        LocalTime lateTime = LocalTime.of(23, 59, 59);
        assertTrue(BackupPolicy.timeReached(SUNDAY.atTime(23, 59, 55), SUNDAY.plusDays(1).atTime(0, 0, 5), lateTime));
        LocalTime midnight = LocalTime.MIDNIGHT;
        assertTrue(BackupPolicy.timeReached(SUNDAY.atTime(23, 59, 55), SUNDAY.plusDays(1).atTime(0, 0, 5), midnight));
    }

    @Test
    void serverStartedAfterBackupTimeDoesNotCatchUp() {
        // started at 10:00, first check 10 seconds later
        assertFalse(BackupPolicy.timeReached(SUNDAY.atTime(10, 0), SUNDAY.atTime(10, 0, 10), BACKUP_TIME));
    }

    @Test
    void retentionOverTwoMonthsOfSchedule() {
        List<BackupFile> backups = new ArrayList<>();
        for (int day = 0; day < 60; day++) {
            LocalDate date = SUNDAY.plusDays(day);
            LocalDateTime now = date.atTime(BACKUP_TIME);
            BackupFile backup = BackupPolicy.scheduledDiffBase(backups, date, DayOfWeek.SUNDAY)
                    .map(base -> BackupFile.diff(now, base))
                    .orElseGet(() -> BackupFile.full(now));
            assertEquals(date.getDayOfWeek() == DayOfWeek.SUNDAY ? BackupFile.Kind.FULL : BackupFile.Kind.DIFF, backup.kind(), date.toString());
            backups.add(backup);

            Set<BackupFile> delete = BackupPolicy.toDelete(backups, date, 7, 4);
            backups.removeAll(delete);

            // every diff can still be restored
            for (BackupFile kept : backups) {
                if (kept.kind() == BackupFile.Kind.DIFF) {
                    assertTrue(backups.stream().anyMatch(kept::isBasedOn), date + ": " + kept.fileName() + " lost its full backup");
                }
            }
            // the last 7 days are always restorable day by day
            for (int back = 0; back < Math.min(7, day + 1); back++) {
                LocalDate expected = date.minusDays(back);
                assertTrue(backups.stream().anyMatch(b -> b.createdAt().toLocalDate().equals(expected)), date + ": no backup for " + expected);
            }
        }

        // Day 59 is Wednesday 2026-12-02. Kept: 7 daily (Thu..Sat diffs on the 11-22 full, the 11-29 full,
        // Mon..Wed diffs) plus the 11-22, 11-15 and 11-08 fulls to reach 4 fulls
        assertEquals(10, backups.size());
        assertEquals(4, backups.stream().filter(b -> b.kind() == BackupFile.Kind.FULL).count());
        assertEquals(LocalDate.of(2026, 11, 8), backups.getFirst().createdAt().toLocalDate());
    }

    @Test
    void retentionKeepsBaseOfOldDiffEvenBeyondWeeklyCount() {
        BackupFile oldFull = BackupFile.full(SUNDAY.atTime(BACKUP_TIME));
        BackupFile diff = BackupFile.diff(SUNDAY.plusDays(6).atTime(BACKUP_TIME), oldFull);
        List<BackupFile> backups = List.of(oldFull, diff, BackupFile.full(SUNDAY.plusDays(7).atTime(BACKUP_TIME)));

        // weeklyCount = 1 would drop oldFull, but the diff from yesterday still needs it
        Set<BackupFile> delete = BackupPolicy.toDelete(backups, SUNDAY.plusDays(7), 7, 1);
        assertTrue(delete.isEmpty(), "deleted " + delete);
    }
}
