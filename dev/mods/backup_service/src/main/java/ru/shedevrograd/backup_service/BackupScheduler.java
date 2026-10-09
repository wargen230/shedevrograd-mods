package ru.shedevrograd.backup_service;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import ru.shedevrograd.backup_service.archive.BackupPolicy;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Starts the daily backup at the configured time.
 *
 * <p>Fires when the clock crosses the backup time between two checks. A backup missed while the
 * server was down is not caught up on start: the next one runs at the next backup time.
 */
@EventBusSubscriber(modid = BackupService.MOD_ID)
public final class BackupScheduler {
    private static final int CHECK_INTERVAL_TICKS = 20 * 10;

    private static LocalDateTime lastCheck;
    private static int ticksSinceCheck;

    private BackupScheduler() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        lastCheck = LocalDateTime.now();
        ticksSinceCheck = 0;
        if (BackupConfig.SCHEDULE_ENABLED.get()) {
            BackupService.LOGGER.info("Next scheduled backup: {}", BackupScheduler.nextRun(lastCheck));
        } else {
            BackupService.LOGGER.info("Scheduled backups are disabled in config");
        }
        // Upload whatever did not make it to S3 before, e.g. because the storage was unreachable
        S3Uploader.requestSync(event.getServer(), null);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticksSinceCheck < CHECK_INTERVAL_TICKS || lastCheck == null) {
            return;
        }
        ticksSinceCheck = 0;

        LocalDateTime previous = lastCheck;
        LocalDateTime now = LocalDateTime.now();
        lastCheck = now;

        if (!BackupConfig.SCHEDULE_ENABLED.get() || !BackupPolicy.timeReached(previous, now, BackupConfig.time())) {
            return;
        }

        MinecraftServer server = event.getServer();
        boolean started = BackupManager.start(server, BackupManager.Request.SCHEDULED, result -> {
            if (result.isSuccess()) {
                BackupService.LOGGER.info(result.describe());
            } else {
                BackupService.LOGGER.error("Scheduled backup failed", result.error());
            }
            BackupService.LOGGER.info("Next scheduled backup: {}", BackupScheduler.nextRun(LocalDateTime.now()));
        });
        if (!started) {
            BackupService.LOGGER.warn("Scheduled backup skipped: another backup is still running");
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Fires before the final world save; let a running backup finish reading the files first
        BackupManager.awaitCompletion(10, TimeUnit.MINUTES);
        lastCheck = null;
    }

    /** Next moment the scheduled backup will run, for status output. */
    public static LocalDateTime nextRun(LocalDateTime now) {
        LocalDateTime today = now.toLocalDate().atTime(BackupConfig.time());
        return today.isAfter(now) ? today : today.plusDays(1);
    }
}
