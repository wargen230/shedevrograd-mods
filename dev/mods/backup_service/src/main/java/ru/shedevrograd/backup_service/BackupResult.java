package ru.shedevrograd.backup_service;

import org.jetbrains.annotations.Nullable;
import ru.shedevrograd.backup_service.archive.BackupFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record BackupResult(@Nullable Path archive, @Nullable BackupFile backup, int archivedFiles, int worldFiles,
                           List<BackupFile> deleted, long sizeBytes, long seconds, @Nullable Throwable error) {

    public static BackupResult success(Path archive, BackupFile backup, int archivedFiles, int worldFiles, List<BackupFile> deleted, long seconds) {
        long size;
        try {
            size = Files.size(archive);
        } catch (IOException e) {
            size = -1;
        }
        return new BackupResult(archive, backup, archivedFiles, worldFiles, deleted, size, seconds, null);
    }

    public static BackupResult failure(Throwable error) {
        return new BackupResult(null, null, 0, 0, List.of(), -1, 0, error);
    }

    public boolean isSuccess() {
        return this.error == null;
    }

    public String describe() {
        if (!this.isSuccess()) {
            Throwable root = this.error;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return "Бэкап не удался: " + (root.getMessage() != null ? root.getMessage() : root.toString());
        }

        String files = this.backup.kind() == BackupFile.Kind.DIFF
                ? this.archivedFiles + " изменённых из " + this.worldFiles + " файлов"
                : this.worldFiles + " файлов";
        String text = String.format("Бэкап готов: %s (%s, %s, %d с)",
                this.archive.getFileName(), files, BackupResult.formatSize(this.sizeBytes), this.seconds);
        if (!this.deleted.isEmpty()) {
            text += ". Удалено старых: " + this.deleted.size();
        }
        return text;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format("%.1f КБ", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f МБ", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2f ГБ", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
