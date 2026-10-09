package ru.shedevrograd.backup_service;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import ru.shedevrograd.backup_service.archive.BackupFile;
import ru.shedevrograd.backup_service.archive.BackupPolicy;
import ru.shedevrograd.backup_service.archive.DiffManifest;
import ru.shedevrograd.backup_service.sbk.SbkAlgorithm;
import ru.shedevrograd.backup_service.sbk.SbkIndexEntry;
import ru.shedevrograd.backup_service.sbk.SbkProgress;
import ru.shedevrograd.backup_service.sbk.SbkReader;
import ru.shedevrograd.backup_service.sbk.SbkWriteOptions;
import ru.shedevrograd.backup_service.sbk.SbkWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creates SBK backups of the server world.
 *
 * <p>The world is flushed and saving is paused on the server thread, the archive is written on a
 * background thread, and saving is resumed on the server thread once the archive is done.
 */
public final class BackupManager {

    public enum Request {
        /** Full backup of the whole world. */
        FULL,
        /** Only files changed since the latest full backup. */
        DIFF,
        /** Full or diff, as the schedule says for today. */
        SCHEDULED
    }

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile CompletableFuture<?> currentArchive;

    private BackupManager() {}

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Starts a backup. Must be called on the server thread.
     *
     * @param onFinish receives the result, called on the server thread
     * @return false if another backup is already running
     */
    public static boolean start(MinecraftServer server, Request request, Consumer<BackupResult> onFinish) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }

        // Config values are read here, on the server thread, and handed to the worker as a snapshot
        Job job = new Job(
                request,
                server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),
                BackupConfig.directory(),
                BackupConfig.FULL_BACKUP_DAY.get(),
                BackupConfig.DAILY_DAYS.get(),
                BackupConfig.WEEKLY_COUNT.get(),
                BackupConfig.COMPRESSION_LEVEL.get());

        List<ServerLevel> pausedLevels = new ArrayList<>();
        try {
            // Same as /save-all flush: write every dirty chunk and wait for region IO to finish
            server.saveEverything(true, true, true);

            // Same as /save-off: keep the files stable while they are being read
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.noSave) {
                    level.noSave = true;
                    pausedLevels.add(level);
                }
            }
        } catch (RuntimeException e) {
            BackupManager.resumeSaving(pausedLevels);
            RUNNING.set(false);
            throw e;
        }

        long startedAt = System.nanoTime();
        CompletableFuture<BackupResult> archive = CompletableFuture.supplyAsync(() -> job.run(startedAt), BackupManager::runOnWorkerThread);
        currentArchive = archive;
        archive.whenComplete((result, error) -> server.execute(() -> {
            BackupManager.resumeSaving(pausedLevels);
            currentArchive = null;
            RUNNING.set(false);
            onFinish.accept(error == null ? result : BackupResult.failure(error.getCause() != null ? error.getCause() : error));
            if (error == null) {
                // Saving is already resumed: the upload never holds the world
                S3Uploader.requestSync(server, null);
            }
        }));

        return true;
    }

    /**
     * Blocks until a running backup has written its archive. Called while the server stops:
     * otherwise the final world save would race with the archive being read.
     */
    public static void awaitCompletion(long timeout, TimeUnit unit) {
        CompletableFuture<?> archive = currentArchive;
        if (archive == null) {
            return;
        }
        BackupService.LOGGER.info("Waiting for the running backup to finish before shutdown...");
        try {
            archive.get(timeout, unit);
        } catch (TimeoutException e) {
            BackupService.LOGGER.error("Backup did not finish in {} {}, shutting down anyway", timeout, unit);
        } catch (Exception e) {
            // the failure itself is reported by the regular completion callback
        }
    }

    private static void resumeSaving(List<ServerLevel> pausedLevels) {
        for (ServerLevel level : pausedLevels) {
            level.noSave = false;
        }
    }

    private static void runOnWorkerThread(Runnable task) {
        Thread thread = new Thread(task, BackupService.MOD_ID + "-worker");
        thread.setDaemon(true);
        thread.start();
    }

    private record Job(Request request, Path worldDir, Path backupDir, DayOfWeek fullBackupDay,
                       int dailyDays, int weeklyCount, int compressionLevel) {

        BackupResult run(long startedAt) {
            Path temp = null;
            try {
                Files.createDirectories(this.backupDir);
                List<BackupFile> existing = BackupFile.listIn(this.backupDir);
                LocalDateTime now = LocalDateTime.now();

                Optional<BackupFile> base = switch (this.request) {
                    case FULL -> Optional.empty();
                    case DIFF -> Optional.of(BackupPolicy.latestFull(existing)
                            .orElseThrow(() -> new IOException("Нет полного бэкапа, относительно которого делать diff. Сначала: backup create full")));
                    case SCHEDULED -> BackupPolicy.scheduledDiffBase(existing, now.toLocalDate(), this.fullBackupDay);
                };
                BackupFile target = base.map(full -> BackupFile.diff(now, full)).orElseGet(() -> BackupFile.full(now));

                Path archive = this.backupDir.resolve(target.fileName());
                if (Files.exists(archive)) {
                    throw new IOException("Бэкап с таким именем уже есть: " + archive.getFileName());
                }
                temp = archive.resolveSibling(archive.getFileName() + ".tmp");

                List<Path> worldFiles = this.listWorldFiles();
                List<Path> toArchive = worldFiles;
                Map<String, byte[]> extraEntries = Map.of();
                if (base.isPresent()) {
                    toArchive = this.changedSince(base.get(), worldFiles);
                    DiffManifest manifest = new DiffManifest(base.get().fileName(), worldFiles.stream().map(this::archivePath).sorted().toList());
                    extraEntries = Map.of(DiffManifest.FILE_NAME, manifest.toBytes());
                }

                BackupService.LOGGER.info("Creating {}: {} of {} files from {}", archive.getFileName(), toArchive.size(), worldFiles.size(), this.worldDir);
                SbkWriteOptions options = SbkWriteOptions.builder()
                        .algorithm(SbkAlgorithm.LZMA2)
                        .lzmaPreset(this.compressionLevel)
                        .build();
                SbkWriter.compress(this.worldDir, toArchive, this.worldDir.getFileName(), temp, options, new ProgressLogger(), extraEntries);

                // Only a fully written archive gets the .sbk name
                Files.move(temp, archive, StandardCopyOption.ATOMIC_MOVE);
                temp = null;

                List<BackupFile> deleted = this.applyRetention(now);
                long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
                return BackupResult.success(archive, target, toArchive.size(), worldFiles.size(), deleted, seconds);
            } catch (IOException | RuntimeException e) {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
                throw new BackupException("Backup failed", e);
            }
        }

        private List<Path> listWorldFiles() throws IOException {
            try (Stream<Path> stream = Files.walk(this.worldDir)) {
                return stream
                        .filter(Files::isRegularFile)
                        // Held open by the running server; never needed for a restore
                        .filter(path -> !path.getFileName().toString().equals("session.lock"))
                        .toList();
            }
        }

        /** Files that are new or whose size or modification time differ from the full backup. */
        private List<Path> changedSince(BackupFile full, List<Path> worldFiles) throws IOException {
            Map<String, SbkIndexEntry> baseIndex = new HashMap<>();
            for (SbkIndexEntry entry : SbkReader.info(this.backupDir.resolve(full.fileName())).entries()) {
                baseIndex.put(entry.path(), entry);
            }

            List<Path> changed = new ArrayList<>();
            for (Path file : worldFiles) {
                SbkIndexEntry before = baseIndex.get(this.archivePath(file));
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                if (before == null
                        || before.originalSize() != attrs.size()
                        || before.mtimeMs() != attrs.lastModifiedTime().toMillis()) {
                    changed.add(file);
                }
            }
            return changed;
        }

        /** Same path format SbkWriter stores in the index: "world/region/r.0.0.mca". */
        private String archivePath(Path file) {
            return this.worldDir.getFileName().resolve(this.worldDir.relativize(file)).toString().replace('\\', '/');
        }

        private List<BackupFile> applyRetention(LocalDateTime now) throws IOException {
            List<BackupFile> deleted = new ArrayList<>();
            for (BackupFile old : BackupPolicy.toDelete(BackupFile.listIn(this.backupDir), now.toLocalDate(), this.dailyDays, this.weeklyCount)) {
                Files.deleteIfExists(this.backupDir.resolve(old.fileName()));
                BackupService.LOGGER.info("Deleted old backup {}", old.fileName());
                deleted.add(old);
            }
            return deleted;
        }
    }

    /** Logs every 10% so a long backup is visible in the console. */
    private static final class ProgressLogger implements SbkProgress {
        private int lastDecile = 0;

        @Override
        public void onFile(int completed, int total, String path) {
            int decile = total == 0 ? 10 : completed * 10 / total;
            if (decile > this.lastDecile) {
                this.lastDecile = decile;
                BackupService.LOGGER.info("Backup progress: {}% ({}/{} files)", decile * 10, completed, total);
            }
        }
    }

    public static final class BackupException extends RuntimeException {
        public BackupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
