package dev.mcflow;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Letter images shared by every lyric line. Drawing a letter (Java2D text, blurred shadow, outline/glow) and
 * uploading it is slow, so each one is made once per look; a song's letters are drawn ahead of time on a background
 * thread and uploaded a few per frame, so a new line appearing costs almost nothing.
 * Everything except the background drawing happens on the render thread.
 */
final class GlyphCache {
    /** A letter's texture, its outline/glow layer (or null), and where the glyph starts inside it. */
    record Entry(GpuImage image, GpuImage effects, int pad) {}

    /** A letter drawn in the background, waiting to be uploaded; {@code look} is what it was drawn with. */
    private record Drawn(String key, String look, TextArt.Art art) {}

    /** More than this many letters cached means something unusual (huge alphabets); start over rather than grow. */
    private static final int MAX_ENTRIES = 600;

    private static final Map<String, Entry> READY = new HashMap<>();
    private static final Queue<Drawn> DRAWN = new ConcurrentLinkedQueue<>();
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Mc'Flow letters");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** What the cached images were drawn with; when it changes they're thrown away. */
    private static String look = "";

    private GlyphCache() {}

    /** The settings that change how a letter looks (not where it goes or how it moves). */
    private static String look() {
        Settings s = Settings.get();
        // the glow colour only matters while there is a glow (it can follow the album cover, which changes every song)
        return s.lyricFont + "|" + s.shadow + "|" + s.outline + "|" + s.outlineColor + "|" + s.glow + "|" + (s.glow > 0 ? TextArt.glowColor() : 0);
    }

    private static String key(String ch, float fontPx) {
        return ch + "|" + Math.round(fontPx);
    }

    /** A letter at a font size, drawing it right now if it wasn't prepared in time. */
    static Entry get(String ch, float fontPx) {
        checkLook();
        String key = key(ch, fontPx);
        Entry e = READY.get(key);
        if (e == null) {
            e = upload(TextArt.word(ch, fontPx));
            READY.put(key, e);
        }
        return e;
    }

    /** Starts drawing these letters in the background (the ones not already cached or on their way). */
    static void prepare(Collection<String> letters, float fontPx) {
        checkLook();
        String forLook = look;
        for (String ch : letters) {
            String key = key(ch, fontPx);
            if (READY.containsKey(key) || !QUEUED.add(key)) continue;
            WORKER.execute(() -> {
                if (forLook.equals(look())) DRAWN.add(new Drawn(key, forLook, TextArt.word(ch, fontPx)));
                else QUEUED.remove(key); // the look changed while waiting; it'll be asked for again
            });
        }
    }

    /** Each frame: uploads up to {@code max} letters drawn in the background, spreading the GPU work out. */
    static void uploadSome(int max) {
        checkLook();
        for (int i = 0; i < max; i++) {
            Drawn d = DRAWN.poll();
            if (d == null) return;
            QUEUED.remove(d.key());
            if (d.look().equals(look) && !READY.containsKey(d.key())) READY.put(d.key(), upload(d.art()));
        }
    }

    /** Every distinct non-space character in these texts. */
    static Collection<String> letters(Collection<String> texts) {
        Set<String> out = new LinkedHashSet<>();
        for (String text : texts) {
            text.codePoints().filter(c -> !Character.isWhitespace(c)).forEach(c -> out.add(Character.toString(c)));
        }
        return out;
    }

    private static Entry upload(TextArt.Art art) {
        return new Entry(new GpuImage(art.image()), art.effects() == null ? null : new GpuImage(art.effects()), art.pad());
    }

    private static void checkLook() {
        String now = look();
        if (now.equals(look) && READY.size() < MAX_ENTRIES) return;
        look = now;
        READY.values().forEach(e -> {
            e.image().close();
            if (e.effects() != null) e.effects().close();
        });
        READY.clear();
        QUEUED.clear();
    }
}
