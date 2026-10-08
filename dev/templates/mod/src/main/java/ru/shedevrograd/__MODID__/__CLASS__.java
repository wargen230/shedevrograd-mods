package ru.shedevrograd.__MODID__;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(__CLASS__.MOD_ID)
public class __CLASS__ {
    public static final String MOD_ID = "__MODID__";
    public static final Logger LOGGER = LogUtils.getLogger();

    public __CLASS__(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("{} loaded", MOD_ID);
    }
}
