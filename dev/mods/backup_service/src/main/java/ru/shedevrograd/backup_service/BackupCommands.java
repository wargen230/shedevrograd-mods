package ru.shedevrograd.backup_service;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = BackupService.MOD_ID)
public final class BackupCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("backup")
                // Уровень 4 — консоль сервера и операторы с максимальными правами
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("create")
                        .executes(context -> BackupCommands.create(context.getSource()))));
    }

    private static int create(CommandSourceStack source) {
        boolean started = BackupManager.startFullBackup(source.getServer(), result -> {
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
}
