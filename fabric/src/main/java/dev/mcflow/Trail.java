package dev.mcflow;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lyrics as a trail behind a walking player: every word sung goes into that player's queue, and as they move,
 * its letters are laid down one by one along their path. Standing still lays nothing; start walking and the waiting
 * letters pour out. Each letter pops in, drifts up a little and fades on its own, so the trail appears and disappears
 * letter by letter. Yours can be shared ({@link SingAlong#TRAIL}): nearby players with Mc'Flow see it behind you.
 * Main thread only.
 */
final class Trail {
    /** Path length per letter, letter height, and how far behind the feet they're laid (blocks). */
    private static final double SPACING = 0.32, LETTER_BLOCKS = 0.32, LIFT = 0.15;
    /** A letter's life: popping in, staying, fading out. */
    private static final long IN_MS = 120, LIFE_MS = 2600, OUT_MS = 700;
    /** Letters waiting longer than this for their player to move are dropped (a line sung while standing still). */
    private static final long MAX_WAIT_MS = 4000;
    private static final int MAX_LETTERS = 220;

    /** A letter waiting to be laid, and when its word was sung. */
    private record Waiting(char ch, long since) {}

    private record Letter(char ch, Vec3 pos, long born) {}

    /** One player's queue of letters and where the last one was laid (null: start at their feet). */
    private static final class Walker {
        final Deque<Waiting> queue = new ArrayDeque<>();
        Vec3 last;
    }

    private static final Map<UUID, Walker> WALKERS = new HashMap<>();
    private static final List<Letter> LETTERS = new ArrayList<>();
    private static long lastWord = -1;

    private Trail() {}

    /** A nearby player's sung word, for their trail. */
    static void receive(UUID player, String word) {
        if (Settings.get().showOthers) enqueue(player, word);
    }

    private static void enqueue(UUID player, String word) {
        Walker walker = WALKERS.computeIfAbsent(player, k -> new Walker());
        long now = System.currentTimeMillis();
        for (char ch : (word.trim() + " ").toCharArray()) walker.queue.add(new Waiting(ch, now));
    }

    /** Every client tick: queue the word being sung now (and share it), then lay letters behind whoever is walking. */
    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            WALKERS.clear();
            LETTERS.clear();
            return;
        }
        Settings s = Settings.get();
        String word = newWord();
        if (word != null && s.lyricsTrail) {
            enqueue(mc.player.getUUID(), word);
            if (s.trailShared) PeerLyrics.send(SingAlong.TRAIL, word);
        }
        long now = System.currentTimeMillis();
        for (Player p : mc.level.players()) {
            Walker walker = WALKERS.get(p.getUUID());
            if (walker == null) continue;
            while (!walker.queue.isEmpty() && now - walker.queue.peekFirst().since() > MAX_WAIT_MS) walker.queue.pollFirst();
            Vec3 feet = p.getPosition(1).add(0, LIFT, 0);
            if (walker.queue.isEmpty() || walker.last == null) {
                walker.last = feet; // nothing to lay: the next letters start from wherever they are then
                continue;
            }
            Vec3 step = new Vec3(feet.x - walker.last.x, 0, feet.z - walker.last.z);
            double walked = step.length();
            if (walked > 6) walker.last = feet; // teleported: don't string letters across the gap
            while (walked >= SPACING && !walker.queue.isEmpty()) {
                walker.last = walker.last.add(step.normalize().scale(SPACING));
                walker.last = new Vec3(walker.last.x, feet.y, walker.last.z);
                walked -= SPACING;
                char ch = walker.queue.pollFirst().ch();
                if (ch != ' ') LETTERS.add(new Letter(ch, walker.last, now)); // a space is just a gap
            }
        }
        while (LETTERS.size() > MAX_LETTERS) LETTERS.removeFirst();
    }

    /** The word that just started being sung (null if none since the last tick); words are 1-2 word clusters. */
    private static String newWord() {
        if (NowPlaying.track() == null) return null;
        List<Lyrics.Line> lines = Lyrics.current();
        long pos = NowPlaying.positionMs() + Settings.get().offsetMs;
        int i = Lyrics.lastStarted(lines, pos);
        if (i < 0 || pos >= lines.get(i).endMs()) return null;
        List<Lyrics.Word> words = lines.get(i).words();
        int k = words.size() - 1;
        while (k > 0 && words.get(k).startMs() > pos) k--;
        long id = (long) i << 16 | k;
        if (id == lastWord) return null;
        lastWord = id;
        return words.get(k).text();
    }

    /** Render thread, in physical screen pixels: every trail letter in view. {@code fontPx}: the HUD's letter size. */
    static void draw(GuiGraphics g, float partialTick, float fontPx) {
        if (LETTERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Settings s = Settings.get();
        Window window = mc.getWindow();
        int w = window.getWidth(), h = window.getHeight();
        Camera camera = mc.gameRenderer.getMainCamera();
        long now = System.currentTimeMillis();
        LETTERS.removeIf(l -> now - l.born() > LIFE_MS);
        for (Letter l : LETTERS) {
            float age = now - l.born();
            float pop = Math.min(1, age / IN_MS), fade = Math.clamp((LIFE_MS - age) / OUT_MS, 0f, 1f);
            Vec3 at3 = l.pos().add(0, age / 1000.0 * 0.12, 0); // drifts up a little
            float[] at = LyricsHud.project(camera, at3, w, h);
            if (at == null) continue;
            float visible = s.blockDetection ? Occlusion.between(mc.level, camera.position(), at3).visibility() : 1;
            int alpha = Math.round(pop * fade * visible * 255);
            if (alpha < 4) continue;
            GlyphCache.Entry letter = GlyphCache.get(String.valueOf(l.ch()), fontPx);
            float scale = at[2] * (float) LETTER_BLOCKS / fontPx * (0.6f + 0.4f * pop); // grows into place
            int iw = letter.image().width, ih = letter.image().height;
            g.pose().pushMatrix();
            g.pose().translate(at[0], at[1]);
            g.pose().scale(scale, scale);
            if (letter.effects() != null) letter.effects().draw(g, -iw / 2, -ih / 2, alpha << 24 | 0xFFFFFF);
            letter.image().draw(g, -iw / 2, -ih / 2, alpha << 24 | (s.lyricColor & 0xFFFFFF));
            g.pose().popMatrix();
        }
    }
}
