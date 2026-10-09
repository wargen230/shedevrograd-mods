package ru.shedevrograd.backup_service;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import ru.shedevrograd.backup_service.s3.S3Client;
import ru.shedevrograd.backup_service.s3.S3Sync;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs {@link S3Sync} in the background, one sync at a time.
 *
 * <p>Requests that arrive while a sync is queued are merged into it: a sync always looks at the
 * whole backup directory, so one run covers them all.
 */
public final class S3Uploader {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, BackupService.MOD_ID + "-s3");
        // An upload must not keep the JVM alive after the server stopped; the next start resumes it
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean QUEUED = new AtomicBoolean(false);

    private S3Uploader() {}

    public static boolean isEnabled() {
        return BackupConfig.S3_ENABLED.get();
    }

    /** Human-readable target for status output, e.g. "http://host:7070/bucket/prefix/". */
    public static String target() {
        return BackupConfig.S3_ENDPOINT.get().replaceAll("/+$", "") + "/" + BackupConfig.S3_BUCKET.get() + "/"
                + S3Sync.normalizePrefix(BackupConfig.S3_PREFIX.get());
    }

    /**
     * Queues a sync. Must be called on the server thread (reads config).
     *
     * @param onFinish called on the server thread with a summary or an error text; null to only log
     * @return false if S3 is disabled or misconfigured (the reason is reported to {@code onFinish})
     */
    public static boolean requestSync(MinecraftServer server, @Nullable Consumer<String> onFinish) {
        if (!S3Uploader.isEnabled()) {
            return false;
        }

        String problem = S3Uploader.configProblem();
        if (problem != null) {
            BackupService.LOGGER.error("S3 upload is enabled but misconfigured: {}", problem);
            if (onFinish != null) {
                onFinish.accept("S3 настроено неверно: " + problem);
            }
            return false;
        }

        S3Client client = new S3Client(new S3Client.Settings(
                URI.create(BackupConfig.S3_ENDPOINT.get().replaceAll("/+$", "")),
                BackupConfig.S3_REGION.get(),
                BackupConfig.S3_BUCKET.get(),
                BackupConfig.S3_ACCESS_KEY.get(),
                BackupConfig.S3_SECRET_KEY.get(),
                BackupConfig.S3_PATH_STYLE.get()));
        String prefix = S3Sync.normalizePrefix(BackupConfig.S3_PREFIX.get());
        Path backupDir = BackupConfig.directory();
        int dailyDays = BackupConfig.DAILY_DAYS.get();
        int weeklyCount = BackupConfig.WEEKLY_COUNT.get();

        if (!QUEUED.compareAndSet(false, true)) {
            if (onFinish != null) {
                onFinish.accept("Отправка в S3 уже запланирована, новые бэкапы попадут в неё");
            }
            return true;
        }

        EXECUTOR.execute(() -> {
            QUEUED.set(false);
            String summary;
            try {
                S3Sync.Result result = S3Sync.sync(client, prefix, backupDir, LocalDate.now(), dailyDays, weeklyCount);
                summary = result.describe();
                if (result.isSuccess()) {
                    BackupService.LOGGER.info(summary);
                } else {
                    BackupService.LOGGER.error(summary);
                }
            } catch (Exception e) {
                BackupService.LOGGER.error("S3 sync failed, backups stay on disk and will be uploaded next time", e);
                summary = "S3 недоступно: " + e.getMessage() + ". Бэкапы остались на диске и будут дозагружены в следующий раз";
            }

            if (onFinish != null) {
                String text = summary;
                server.execute(() -> onFinish.accept(text));
            }
        });
        return true;
    }

    private static String configProblem() {
        String endpoint = BackupConfig.S3_ENDPOINT.get();
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return "endpoint должен начинаться с http:// или https://";
        }
        try {
            if (URI.create(endpoint).getHost() == null) {
                return "в endpoint нет адреса хоста";
            }
        } catch (IllegalArgumentException e) {
            return "endpoint не похож на адрес: " + e.getMessage();
        }
        if (BackupConfig.S3_BUCKET.get().isBlank()) {
            return "не указан bucket";
        }
        if (BackupConfig.S3_ACCESS_KEY.get().isBlank() || BackupConfig.S3_SECRET_KEY.get().isBlank()) {
            return "не указаны accessKey и secretKey";
        }
        return null;
    }
}
