package ru.shedevrograd.backup_service;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record BackupResult(@Nullable Path archive, long sizeBytes, long seconds, @Nullable Throwable error) {

    public static BackupResult success(Path archive, long seconds) {
        long size;
        try {
            size = Files.size(archive);
        } catch (IOException e) {
            size = -1;
        }
        return new BackupResult(archive, size, seconds, null);
    }

    public static BackupResult failure(Throwable error) {
        return new BackupResult(null, -1, 0, error);
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
            return "Бэкап не удался: " + root;
        }
        return String.format("Бэкап готов: %s (%.1f МБ, %d с)",
                this.archive.getFileName(), this.sizeBytes / 1024.0 / 1024.0, this.seconds);
    }
}
