package ru.shedevrograd.backup_service;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import ru.shedevrograd.backup_service.sbk.SbkAlgorithm;
import ru.shedevrograd.backup_service.sbk.SbkWriteOptions;
import ru.shedevrograd.backup_service.sbk.SbkWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creates full SBK backups of the server world.
 *
 * <p>The world is flushed and saving is paused on the server thread, the archive is written on a
 * background thread, and saving is resumed on the server thread once the archive is done.
 */
public final class BackupManager {
    private static final Path BACKUP_DIR = Path.of("backups");
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BackupManager() {}

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Starts a full backup. Must be called on the server thread.
     *
     * @param onFinish receives a human-readable result, called on the server thread
     * @return false if another backup is already running
     */
    public static boolean startFullBackup(MinecraftServer server, Consumer<BackupResult> onFinish) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }

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

        Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String name = "full-" + LocalDateTime.now().format(NAME_FORMAT) + ".sbk";
        long startedAt = System.nanoTime();

        CompletableFuture
                .supplyAsync(() -> BackupManager.writeArchive(worldDir, name), BackupManager::runOnWorkerThread)
                .whenComplete((archive, error) -> server.execute(() -> {
                    BackupManager.resumeSaving(pausedLevels);
                    RUNNING.set(false);

                    long seconds = (System.nanoTime() - startedAt) / 1_000_000_000L;
                    BackupResult result = error == null
                            ? BackupResult.success(archive, seconds)
                            : BackupResult.failure(error.getCause() != null ? error.getCause() : error);
                    onFinish.accept(result);
                }));

        return true;
    }

    private static Path writeArchive(Path worldDir, String name) {
        Path target = BACKUP_DIR.resolve(name).toAbsolutePath();
        Path temp = target.resolveSibling(name + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            List<Path> files = BackupManager.listWorldFiles(worldDir);
            BackupService.LOGGER.info("Backing up {} files from {} to {}", files.size(), worldDir, target);

            SbkWriteOptions options = SbkWriteOptions.builder()
                    .algorithm(SbkAlgorithm.LZMA2)
                    .lzmaPreset(SbkWriteOptions.DEFAULT_PRESET)
                    .build();
            SbkWriter.compress(worldDir, files, worldDir.getFileName(), temp, options, new ProgressLogger());

            // Only a fully written archive gets the .sbk name
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new BackupException("Backup " + name + " failed", e);
        }
    }

    private static List<Path> listWorldFiles(Path worldDir) throws IOException {
        try (Stream<Path> stream = Files.walk(worldDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    // Held open by the running server; never needed for a restore
                    .filter(path -> !path.getFileName().toString().equals("session.lock"))
                    .toList();
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

    /** Logs every 10% so a long backup is visible in the console. */
    private static final class ProgressLogger implements ru.shedevrograd.backup_service.sbk.SbkProgress {
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
