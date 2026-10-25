package dev.mcflow;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Mc'Flow for Minecraft 1.8.9 (Forge). The common part: the sing-along channel, which a server with Mc'Flow uses to
 * relay lines between players. Everything else is client-only, in {@link McFlowClient}. Optional on both sides.
 */
@Mod(modid = McFlow.MODID, useMetadata = true, acceptableRemoteVersions = "*", acceptedMinecraftVersions = "[1.8.9]",
        guiFactory = "dev.mcflow.GuiFactory")
public class McFlow {
    public static final String MODID = "mcflow";
    public static final Logger LOGGER = LogManager.getLogger(MODID);
    private static Path configDir;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        configDir = event.getModConfigurationDirectory().toPath();
        SingAlong.register();
        FMLCommonHandler.instance().bus().register(new SingAlong()); // player join: the server's hello
        if (event.getSide().isClient()) McFlowClient.init();
    }

    /** Minecraft's config folder; Mc'Flow keeps its files in config/mcflow (and its settings in config/mcflow.json). */
    static Path configDir() {
        return configDir;
    }
}
