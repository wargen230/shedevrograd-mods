package ru.shedevrograd.backup_service.s3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.shedevrograd.backup_service.archive.BackupFile;
import ru.shedevrograd.backup_service.archive.BackupPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors local backups to S3: uploads every local archive the bucket does not have yet, then
 * applies the same retention to the bucket.
 *
 * <p>Retention is computed from the bucket's own listing, not by mirroring local deletions, so a
 * file removed from the server disk by hand stays in S3. Running sync again is always safe: a
 * failed or interrupted upload is simply retried next time.
 */
public final class S3Sync {
    private static final Logger LOGGER = LoggerFactory.getLogger(S3Sync.class);

    public record Result(List<String> uploaded, List<String> deleted, List<String> failed) {
        public boolean isSuccess() {
            return this.failed.isEmpty();
        }

        public String describe() {
            String text = "S3: загружено " + this.uploaded.size() + ", удалено старых " + this.deleted.size();
            return this.failed.isEmpty() ? text : text + ", ошибок " + this.failed.size() + ": " + String.join("; ", this.failed);
        }
    }

    private S3Sync() {}

    /**
     * @param prefix key prefix inside the bucket, "" or ending with '/'
     */
    public static Result sync(S3Client client, String prefix, Path localDir, LocalDate today, int dailyDays, int weeklyCount) throws IOException {
        Map<BackupFile, Long> remote = S3Sync.listRemote(client, prefix);

        List<String> uploaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        // Newest first: if the storage goes away midway, the most recent backups are the ones that made it
        List<BackupFile> local = new ArrayList<>(BackupFile.listIn(localDir));
        local.sort(Comparator.comparing(BackupFile::createdAt).reversed());
        for (BackupFile backup : local) {
            Path file = localDir.resolve(backup.fileName());
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                continue; // deleted by local retention meanwhile
            }
            if (Long.valueOf(size).equals(remote.get(backup))) {
                continue;
            }
            // Only upload what local retention would keep anyway: no point sending a backup
            // that the bucket's retention deletes right after
            if (BackupPolicy.toDelete(S3Sync.with(remote.keySet(), backup), today, dailyDays, weeklyCount).contains(backup)) {
                continue;
            }

            try {
                LOGGER.info("Uploading {} ({} bytes) to S3", backup.fileName(), size);
                client.upload(prefix + backup.fileName(), file);
                remote.put(backup, size);
                uploaded.add(backup.fileName());
            } catch (IOException e) {
                LOGGER.error("Failed to upload {} to S3", backup.fileName(), e);
                failed.add(backup.fileName() + ": " + e.getMessage());
            }
        }

        List<String> deleted = new ArrayList<>();
        for (BackupFile old : BackupPolicy.toDelete(remote.keySet(), today, dailyDays, weeklyCount)) {
            try {
                client.delete(prefix + old.fileName());
                LOGGER.info("Deleted old backup {} from S3", old.fileName());
                deleted.add(old.fileName());
            } catch (IOException e) {
                LOGGER.error("Failed to delete {} from S3", old.fileName(), e);
                failed.add("удаление " + old.fileName() + ": " + e.getMessage());
            }
        }

        return new Result(uploaded, deleted, failed);
    }

    /** Backup archives directly under {@code prefix}; anything else in the bucket is left alone. */
    private static Map<BackupFile, Long> listRemote(S3Client client, String prefix) throws IOException {
        Map<BackupFile, Long> remote = new HashMap<>();
        for (S3Client.ObjectInfo object : client.list(prefix)) {
            String name = object.key().substring(prefix.length());
            if (name.contains("/")) {
                continue;
            }
            Optional<BackupFile> backup = BackupFile.parse(Path.of(name));
            backup.ifPresent(b -> remote.put(b, object.size()));
        }
        return remote;
    }

    private static List<BackupFile> with(java.util.Collection<BackupFile> backups, BackupFile extra) {
        List<BackupFile> all = new ArrayList<>(backups);
        if (!all.contains(extra)) {
            all.add(extra);
        }
        return all;
    }

    /** "" stays "", anything else gets exactly one trailing '/' and no leading one. */
    public static String normalizePrefix(String prefix) {
        String trimmed = prefix.strip().replaceAll("^/+", "").replaceAll("/+$", "");
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }
}
