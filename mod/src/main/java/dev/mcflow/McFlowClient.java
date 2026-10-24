package dev.mcflow;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Everything Mc'Flow does on the client: lyrics, HUD, settings, keys. */
@Mod(value = McFlow.MODID, dist = Dist.CLIENT)
public class McFlowClient {
    private static final int NUDGE_MS = 100;
    private static final KeyMapping.Category KEYS = new KeyMapping.Category(Identifier.fromNamespaceAndPath(McFlow.MODID, "lyrics"));
    private static final KeyMapping SETTINGS = new KeyMapping("key.mcflow.settings", InputConstants.KEY_K, KEYS);
    private static final KeyMapping LATER = new KeyMapping("key.mcflow.later", InputConstants.KEY_LBRACKET, KEYS);
    private static final KeyMapping EARLIER = new KeyMapping("key.mcflow.earlier", InputConstants.KEY_RBRACKET, KEYS);
    private static final KeyMapping PLAY = new KeyMapping("key.mcflow.play", InputConstants.KEY_M, KEYS);
    private static final KeyMapping PREVIOUS = new KeyMapping("key.mcflow.previous", InputConstants.KEY_COMMA, KEYS);
    private static final KeyMapping NEXT = new KeyMapping("key.mcflow.next", InputConstants.KEY_PERIOD, KEYS);

    public McFlowClient(IEventBus modBus, ModContainer container) {
        Settings.load();
        // "Config" button in the Mods list
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));
        PeerLyrics.sender = payload -> ClientPacketDistributor.sendToServer(payload);
        PeerLyrics.canSend = type -> {
            var connection = Minecraft.getInstance().getConnection();
            return connection != null && NetworkRegistry.hasChannel(connection, type.id());
        };
        modBus.addListener(FMLClientSetupEvent.class, e -> {
            NowPlaying.start();
            Beat.start();
        });
        modBus.addListener(RegisterGuiLayersEvent.class,
                e -> e.registerAboveAll(Identifier.fromNamespaceAndPath(McFlow.MODID, "lyrics"), new LyricsHud()));
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.registerCategory(KEYS);
            e.register(SETTINGS);
            e.register(LATER);
            e.register(EARLIER);
            e.register(PLAY);
            e.register(PREVIOUS);
            e.register(NEXT);
        });
        // fires on the client too when you hit someone (and in singleplayer also on the server thread, which we skip)
        NeoForge.EVENT_BUS.addListener(AttackEntityEvent.class, e -> {
            if (e.getEntity().level().isClientSide()) Pvp.onAttack(e.getTarget());
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            WorldFx.tick(mc);
            PeerLyrics.tick(mc);
            while (SETTINGS.consumeClick()) mc.gui.setScreen(new SettingsScreen(null));
            while (EARLIER.consumeClick()) nudge(NUDGE_MS);
            while (LATER.consumeClick()) nudge(-NUDGE_MS);
            while (PLAY.consumeClick()) NowPlaying.control("play");
            while (PREVIOUS.consumeClick()) NowPlaying.control("previous");
            while (NEXT.consumeClick()) NowPlaying.control("next");
        });
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
