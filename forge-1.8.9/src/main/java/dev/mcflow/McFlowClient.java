package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/** Everything Mc'Flow does on the 1.8.9 client: lyrics, HUD, settings, keys, PvP. */
final class McFlowClient {
    private static final int NUDGE_MS = 100;
    private static final String KEYS = "key.category.mcflow.lyrics";
    private static final KeyBinding SETTINGS = new KeyBinding("key.mcflow.settings", Keyboard.KEY_K, KEYS);
    private static final KeyBinding LATER = new KeyBinding("key.mcflow.later", Keyboard.KEY_LBRACKET, KEYS);
    private static final KeyBinding EARLIER = new KeyBinding("key.mcflow.earlier", Keyboard.KEY_RBRACKET, KEYS);
    private static final KeyBinding PLAY = new KeyBinding("key.mcflow.play", Keyboard.KEY_M, KEYS);
    private static final KeyBinding PREVIOUS = new KeyBinding("key.mcflow.previous", Keyboard.KEY_COMMA, KEYS);
    private static final KeyBinding NEXT = new KeyBinding("key.mcflow.next", Keyboard.KEY_PERIOD, KEYS);

    private final LyricsHud hud = new LyricsHud();
    private final GuiGraphicsExtractor graphics = new GuiGraphicsExtractor();

    private McFlowClient() {}

    static void init() {
        Settings.load();
        NowPlaying.start();
        Beat.start();
        for (KeyBinding key : new KeyBinding[]{SETTINGS, LATER, EARLIER, PLAY, PREVIOUS, NEXT}) ClientRegistry.registerKeyBinding(key);
        McFlowClient client = new McFlowClient();
        MinecraftForge.EVENT_BUS.register(client.new ForgeEvents());
        FMLCommonHandler.instance().bus().register(client.new GameEvents()); // ticks live on FML's bus in 1.8.9
    }

    /** Rendering and attacks (Forge's event bus). */
    public final class ForgeEvents {
        /** After the HUD is drawn: the lyrics, pill and effects, on top. */
        @SubscribeEvent
        public void onOverlay(RenderGameOverlayEvent.Post event) {
            if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.disableAlpha(); // smooth fades: no cut-off of faint pixels
            GlStateManager.disableDepth();
            hud.render(graphics, new DeltaTracker(event.partialTicks));
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            GlStateManager.color(1, 1, 1, 1);
        }

        /** End of the world render: remember the view for projecting, and draw pinned 3D text. */
        @SubscribeEvent
        public void onWorldDrawn(RenderWorldLastEvent event) {
            Camera.capture(event.partialTicks);
            WorldText.render();
        }

        /** Fires on both client and server in singleplayer; only the client's matters here. */
        @SubscribeEvent
        public void onAttack(AttackEntityEvent event) {
            if (event.entityPlayer.worldObj.isRemote) Pvp.onAttack(event.target);
        }
    }

    /** Client ticks (FML's event bus): keys, beat particles, sing-along. */
    public final class GameEvents {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getMinecraft();
            WorldFx.tick(mc);
            PeerLyrics.tick(mc);
            while (SETTINGS.isPressed()) mc.displayGuiScreen(new SettingsScreen(null));
            while (EARLIER.isPressed()) nudge(NUDGE_MS);
            while (LATER.isPressed()) nudge(-NUDGE_MS);
            while (PLAY.isPressed()) NowPlaying.control("play");
            while (PREVIOUS.isPressed()) NowPlaying.control("previous");
            while (NEXT.isPressed()) NowPlaying.control("next");
        }
    }

    /** Positive offsets show lyrics earlier, negative later. */
    private static void nudge(int deltaMs) {
        Settings s = Settings.get();
        s.offsetMs = Math.clamp(s.offsetMs + deltaMs, -5000, 5000);
        Settings.changed();
        Settings.save();
        Minecraft.getMinecraft().ingameGUI.setRecordPlaying(I18n.format("mcflow.offset", (s.offsetMs > 0 ? "+" : "") + s.offsetMs), false);
    }
}
