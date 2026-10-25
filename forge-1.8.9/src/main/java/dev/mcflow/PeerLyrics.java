package dev.mcflow;

// 1.8.9 port of the shared PeerLyrics: FML networking (SingAlong) and 1.8.9 entity calls

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multiplayer sing-along, client side: shares the line you're on (if you allow it in settings) and shows the lines
 * other players are on as small pills floating above their heads. The networking itself is in {@link SingAlong}.
 */
public final class PeerLyrics {
    /** A line someone's on, when it arrived, and its image once drawn. */
    private static final class Peer {
        final String text;
        final long since = System.currentTimeMillis();
        GpuImage image;

        Peer(String text) {
            this.text = text;
        }
    }

    /** Lines older than this disappear even without an update (the player may have walked out of range). */
    private static final long EXPIRE_MS = 10_000, FADE_MS = 250;

    private static final Map<UUID, Peer> PEERS = new ConcurrentHashMap<>();
    private static String lastSent = "";

    private PeerLyrics() {}

    /** Network thread or main thread: a nearby player's line changed. */
    static void receive(UUID player, String text) {
        Peer old = text.isBlank() ? PEERS.remove(player) : PEERS.put(player, new Peer(text));
        if (old != null && old.image != null) Minecraft.getMinecraft().addScheduledTask(old.image::close); // textures belong to the render thread
    }

    /** Every client tick: tells the server when the line you're on changes. */
    static void tick(Minecraft mc) {
        if (mc.getNetHandler() == null) { // left the world
            lastSent = "";
            SingAlong.serverHasMod = false;
            PEERS.values().forEach(p -> {
                if (p.image != null) p.image.close();
            });
            PEERS.clear();
            return;
        }
        String text = Settings.get().shareLyrics ? currentLine() : "";
        if (!text.equals(lastSent) && SingAlong.serverHasMod) {
            SingAlong.NET.sendToServer(new SingAlong.ShareLine(text));
            lastSent = text;
        }
    }

    /** The lyric line being sung right now, or "" if nothing is playing / between lines. */
    private static String currentLine() {
        if (NowPlaying.track() == null) return "";
        List<Lyrics.Line> lines = Lyrics.current();
        long pos = NowPlaying.positionMs() + Settings.get().offsetMs;
        int i = Lyrics.lastStarted(lines, pos);
        return i >= 0 && pos < lines.get(i).endMs() ? lines.get(i).text() : "";
    }

    /** Draws everyone's line above their head. The pose is in physical screen pixels (see {@link LyricsHud}). */
    static void draw(GuiGraphicsExtractor g, int w, int h, Camera camera) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Settings.get().showOthers || mc.theWorld == null || PEERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        int height = Math.max(18, Math.round(h * 0.028f));
        for (Map.Entry<UUID, Peer> e : PEERS.entrySet()) {
            Peer peer = e.getValue();
            if (now - peer.since > EXPIRE_MS) {
                PEERS.remove(e.getKey(), peer);
                if (peer.image != null) peer.image.close();
                continue;
            }
            EntityPlayer player = mc.theWorld.getPlayerEntityByUUID(e.getKey());
            if (player == null) continue;
            float[] p = LyricsHud.project(camera, new Vec3(player.posX, player.posY + player.getEyeHeight() + 0.9, player.posZ), w, h);
            if (p == null) continue;
            if (peer.image == null) peer.image = new GpuImage(TextArt.pill("♪  " + peer.text, height, Math.round(w * 0.4f), 0, null));
            // shrink with distance like a name tag, but stay readable
            float scale = Math.clamp(p[2] / (h / 10f), 0.35f, 1.1f);
            float fade = Math.min(1, (now - peer.since) / (float) FADE_MS) * Math.min(1, (EXPIRE_MS - (now - peer.since)) / (float) FADE_MS);
            g.pose().pushMatrix();
            g.pose().translate(p[0] - peer.image.width * scale / 2, p[1] - peer.image.height * scale);
            g.pose().scale(scale, scale);
            peer.image.draw(g, 0, 0, Math.round(fade * 255) << 24 | 0xFFFFFF);
            g.pose().popMatrix();
        }
    }
}
