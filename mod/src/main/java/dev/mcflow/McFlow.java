package dev.mcflow;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Path;

@Mod(value = McFlow.MODID, dist = Dist.CLIENT)
public class McFlow {
    public static final String MODID = "mcflow";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final int NUDGE_MS = 100;
    private static final KeyMapping.Category KEYS = new KeyMapping.Category(Identifier.fromNamespaceAndPath(MODID, "lyrics"));
    private static final KeyMapping SETTINGS = new KeyMapping("key.mcflow.settings", InputConstants.KEY_K, KEYS);
    private static final KeyMapping LATER = new KeyMapping("key.mcflow.later", InputConstants.KEY_LBRACKET, KEYS);
    private static final KeyMapping EARLIER = new KeyMapping("key.mcflow.earlier", InputConstants.KEY_RBRACKET, KEYS);

    public McFlow(IEventBus modBus, ModContainer container) {
        Settings.load();
        // "Config" button in the Mods list
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));
        modBus.addListener(FMLClientSetupEvent.class, e -> {
            NowPlaying.start();
            Beat.start();
        });
        modBus.addListener(RegisterGuiLayersEvent.class,
                e -> e.registerAboveAll(Identifier.fromNamespaceAndPath(MODID, "lyrics"), new LyricsHud()));
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.registerCategory(KEYS);
            e.register(SETTINGS);
            e.register(LATER);
            e.register(EARLIER);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            while (SETTINGS.consumeClick()) Minecraft.getInstance().gui.setScreen(new SettingsScreen(null));
            while (EARLIER.consumeClick()) nudge(NUDGE_MS);
            while (LATER.consumeClick()) nudge(-NUDGE_MS);
        });
    }

    static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Positive offsets show lyrics earlier, negative later. */
    private static void nudge(int deltaMs) {
        Settings s = Settings.get();
        s.offsetMs = Math.clamp(s.offsetMs + deltaMs, -5000, 5000);
        Settings.changed();
        Settings.save();
        Minecraft.getInstance().gui.hud.setOverlayMessage(Component.translatable("mcflow.offset", (s.offsetMs > 0 ? "+" : "") + s.offsetMs), false);
    }
}
