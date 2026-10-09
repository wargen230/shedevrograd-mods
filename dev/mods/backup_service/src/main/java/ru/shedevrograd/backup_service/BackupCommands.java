package ru.shedevrograd.backup_service;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ru.shedevrograd.backup_service.archive.BackupFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@EventBusSubscriber(modid = BackupService.MOD_ID)
public final class BackupCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("backup")
                // Уровень 4 — консоль сервера и операторы с максимальными правами
                .requires(source -> source.hasPermission(4))
                .executes(context -> BackupCommands.status(context.getSource()))
                .then(Commands.literal("create")
                        .executes(context -> BackupCommands.create(context.getSource(), BackupManager.Request.FULL))
                        .then(Commands.literal("full")
                                .executes(context -> BackupCommands.create(context.getSource(), BackupManager.Request.FULL)))
                        .then(Commands.literal("diff")
                                .executes(context -> BackupCommands.create(context.getSource(), BackupManager.Request.DIFF)))
                        .then(Commands.literal("restore")))
                .then(Commands.literal("list")
                        .executes(context -> BackupCommands.list(context.getSource())))
                .then(Commands.literal("upload")
                        .executes(context -> BackupCommands.upload(context.getSource()))));
    }

    private static int upload(CommandSourceStack source) {
        if (!S3Uploader.isEnabled()) {
            source.sendFailure(Component.literal("Отправка в S3 выключена: s3.enabled = false в config/backup_service-common.toml"));
            return 0;
        }

        boolean queued = S3Uploader.requestSync(source.getServer(), summary -> source.sendSuccess(() -> Component.literal(summary), true));
        if (queued) {
            source.sendSuccess(() -> Component.literal("Отправка в " + S3Uploader.target() + " запущена"), true);
        }
        return queued ? 1 : 0;
    }

    private static int create(CommandSourceStack source, BackupManager.Request request) {
        boolean started = BackupManager.start(source.getServer(), request, result -> {
            if (result.isSuccess()) {
                // broadcastToAdmins = true: сообщение само попадает и в консоль, и операторам
                source.sendSuccess(() -> Component.literal(result.describe()), true);
            } else {
                BackupService.LOGGER.error("Backup failed", result.error());
                source.sendFailure(Component.literal(result.describe()));
            }
        });

        if (!started) {
            source.sendFailure(Component.literal("Бэкап уже выполняется"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Бэкап запущен, сохранение мира приостановлено"), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        String schedule = BackupConfig.SCHEDULE_ENABLED.get()
                ? "следующий по расписанию " + BackupScheduler.nextRun(LocalDateTime.now()).toString().replace('T', ' ')
                + ", полный по дням " + BackupConfig.FULL_BACKUP_DAY.get()
                : "расписание выключено в конфиге";
        String running = BackupManager.isRunning() ? "Бэкап выполняется сейчас. " : "";
        String s3 = S3Uploader.isEnabled() ? "копии в " + S3Uploader.target() : "отправка в S3 выключена";
        source.sendSuccess(() -> Component.literal(running + "Бэкапы: " + schedule + "; " + s3
                + "\nКоманды: backup create [full|diff], backup list, backup upload"), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        Path directory = BackupConfig.directory();
        List<BackupFile> backups;
        try {
            backups = BackupFile.listIn(directory);
        } catch (IOException e) {
            source.sendFailure(Component.literal("Не удалось прочитать " + directory + ": " + e.getMessage()));
            return 0;
        }

        if (backups.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Бэкапов пока нет (" + directory + ")"), false);
            return 0;
        }

        StringBuilder text = new StringBuilder("Бэкапы в " + directory + ":");
        long total = 0;
        for (BackupFile backup : backups) {
            long size = BackupCommands.sizeOf(directory.resolve(backup.fileName()));
            total += size;
            text.append("\n  ").append(backup.fileName()).append("  ").append(BackupResult.formatSize(size));
        }
        text.append("\nВсего: ").append(backups.size()).append(", ").append(BackupResult.formatSize(total));
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return backups.size();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }
}
