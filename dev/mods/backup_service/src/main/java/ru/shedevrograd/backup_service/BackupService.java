package ru.shedevrograd.backup_service;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(BackupService.MOD_ID)
public class BackupService {
    public static final String MOD_ID = "backup_service";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BackupService(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, BackupConfig.SPEC);
    }
}
