package dev.mcflow;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class McFlow implements ClientModInitializer {
    public static final String MODID = "mcflow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    private static final int NUDGE_MS = 100;

    @Override
    public void onInitializeClient() {
        Settings.load();
        NowPlaying.start();
        Beat.start();
        ClientPlayNetworking.registerGlobalReceiver(SingAlong.PeerLine.TYPE, (line, ctx) -> PeerLyrics.receive(line));
        PeerLyrics.sender = ClientPlayNetworking::send;
        PeerLyrics.canSend = ClientPlayNetworking::canSend;
        // pinned lyrics as real objects in the world ("Render in the world (3D)")
        WorldText.supported = true;
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> WorldText.render(ctx.matrices(), ctx.consumers()::getBuffer,
                Minecraft.getInstance().gameRenderer.getMainCamera().position()));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MODID, "lyrics"), new LyricsHud());

        KeyMapping.Category keys = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MODID, "lyrics"));
        KeyMapping settings = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mcflow.settings", InputConstants.KEY_K, keys));
        KeyMapping later = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mcflow.later", InputConstants.KEY_LBRACKET, keys));
        KeyMapping earlier = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mcflow.earlier", InputConstants.KEY_RBRACKET, keys));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            WorldFx.tick(mc);
            PeerLyrics.tick(mc);
            while (settings.consumeClick()) mc.setScreen(new SettingsScreen(null));
            while (earlier.consumeClick()) nudge(NUDGE_MS);
            while (later.consumeClick()) nudge(-NUDGE_MS);
        });
    }

    static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** Positive offsets show lyrics earlier, negative later. */
    private static void nudge(int deltaMs) {
        Settings s = Settings.get();
        s.offsetMs = Math.clamp(s.offsetMs + deltaMs, -5000, 5000);
        Settings.changed();
        Settings.save();
        Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("mcflow.offset", (s.offsetMs > 0 ? "+" : "") + s.offsetMs), false);
    }
}
