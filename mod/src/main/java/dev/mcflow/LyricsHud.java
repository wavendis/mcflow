package dev.mcflow;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.joml.Matrix3x2f;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The "now playing" pill plus the big word-by-word lyric. Everything is drawn in physical screen pixels from
 * anti-aliased {@link TextArt} images, so it stays sharp at any GUI scale and doesn't look like Minecraft's own UI.
 * How it looks and where it goes comes from {@link Settings}.
 */
public final class LyricsHud implements GuiLayer {
    /** One letter of a lyric line. */
    private static final class Glyph {
        final GpuImage image;
        /** Outline/glow layer drawn untinted under the letter, or null. */
        GpuImage effects;
        final Lyrics.Word word;
        final WordState state;
        /** Part of a noun: pulses with the beat when pulses are set to nouns. */
        final boolean noun;
        /** Part of a sung sound like "oh" or "ah": always pulses with the beat. */
        final boolean vocal;
        /** Inside (parentheses) or "quotes": {@link Lyrics#IN_PARENS}, {@link Lyrics#IN_QUOTES} or {@link Lyrics#PLAIN}. */
        final int span;
        /** Which word of the line it's in, for fading out word by word. */
        final int order;
        /** Depth layer for parallax: the same for a whole row; staggered rows sit at two depths, other layouts at one. */
        final float layer;
        /** Angles (radians) in the line's own sphere frame, see {@link #onSphere}. */
        final float across, up;
        /** Centre offset from the line's anchor in layout pixels (screen placement, and the pinned plane). */
        final float lx, ly;
        /** This frame: screen position (null if behind) and how visible the spot is (0..1). */
        float[] screen;
        float view;
        /** Blocks between the camera and the letter (smoothed): how much shows through, and its tint. -1 = not measured yet. */
        float occlusion = -1, tintAmount;
        int tint = 0xFFFFFF;

        Glyph(GpuImage image, Lyrics.Word word, WordState state, boolean noun, boolean vocal, int span, int order, float layer, float across, float up, float lx, float ly) {
            this.image = image;
            this.word = word;
            this.state = state;
            this.noun = noun;
            this.vocal = vocal;
            this.span = span;
            this.order = order;
            this.layer = layer;
            this.across = across;
            this.up = up;
            this.lx = lx;
            this.ly = ly;
        }
    }

    /** Shared by a word's letters, so a word only disappears once all of it is out of view. */
    private static final class WordState {
        /** Was fully visible at some point / has since left view entirely (then it never comes back). */
        boolean seen, gone;
        /** This frame: the most visible of its letters. */
        float view;
        /** Layout x of the word's first letter (typewriter animation). */
        float firstX = Float.NaN;
    }

    /** One lyric line being shown, laid out and pinned where the player was looking when it appeared. */
    private static final class View {
        final Lyrics.Line line;
        final int version, height;
        /** Screen width when laid out (for the bend, which is measured against it). */
        int width;
        final List<Glyph> glyphs = new ArrayList<>();
        /**
         * The line's own frame: where it started, its sideways direction and its up direction. On the sphere, rows run
         * around {@code up} like latitude rings on a globe whose pole is 90 degrees off the line, so a line placed
         * straight up or down curves just like one at the horizon instead of wrapping into a tight circle.
         */
        Vec3 forward, right, up;
        /** Where the camera was looking when the line appeared, for turn-based parallax. */
        float yaw0, pitch0;
        /** Pinned: the plane's centre in the world, and layout pixels per block on it. */
        Vec3 anchor;
        float pixelsPerBlock;
        /** Sphere: screen pixels per radian when the line was laid out. */
        float pixelsPerRadian;
        /** How tall the laid-out line is (layout px): how far it moves up to make room for the next one. */
        float lineHeight;
        /** Where rows may go, left and right of the anchor, in layout pixels. */
        double rowMinX, rowMaxX;
        /** PvP mode: a sign floating over the target player (moved there every frame) instead of the normal placement. */
        boolean following;

        View(Lyrics.Line line, int version, int height) {
            this.line = line;
            this.version = version;
            this.height = height;
        }
    }

    /**
     * The sphere's radius. It moves with the player, so this doesn't change the text's size; it only has to be
     * far enough out for the camera's near plane and third-person views.
     */
    private static final double SPHERE_RADIUS = 16;
    /** Share of the screen's height over which letters fade out towards each edge. */
    private static final float EDGE_FADE = 0.12f;
    /** Pinned text fades as you walk into it: gone at NEAR_HIDE blocks in front of the camera, full at NEAR_FULL. */
    private static final double NEAR_HIDE = 0.8, NEAR_FULL = 3.5;
    /** Letter images are drawn at this multiple of their layout size, so they stay crisp when they get bigger. */
    private static final int RES = 2;
    /** Pulsing words grow by this much (times the strength setting) on each beat. */
    private static final float BEAT_GROW = 0.14f;
    /** Pinned warp: starts bending at WARP_START blocks away, fully bent at WARP_FULL; curvature and cap in blocks. */
    private static final double WARP_START = 12, WARP_FULL = 2, BEND_CURVE = 0.08, MAX_BEND = 2.5;
    /** Parallax at full strength: pinned layers this many blocks apart; turning shifts far layers by this share. */
    private static final double PARALLAX_DEPTH = 3;
    private static final float PARALLAX_TURN = 0.25f;
    /** Average depth layer; pinned layers spread around the sign's set distance instead of all moving back. */
    private static final float MEAN_LAYER = 0.9f;
    /** Behind menus: how much the text dims, and how far it blurs (share of screen height). */
    private static final float MENU_DIM = 0.65f, MENU_BLUR = 0.004f;
    /** Lyric text heights as a share of the screen height: first/only row, and the second row when centered. */
    private static final float BIG_TEXT = 0.085f, SMALL_TEXT = 0.065f;
    /** Typewriter: how long each letter takes to type in. */
    private static final float TYPE_MS = 45;
    /** How long the old line takes to flow up out of the new one's way. */
    private static final int PUSH_MS = 500;
    /** On screen, at full bend: how far (share of the screen height) text at the screen's edge sits above/below the middle. */
    private static final float SCREEN_BEND = 0.8f;
    /** PvP mode: the big text's height in blocks, and how far above the target's head the sign floats. */
    private static final double PVP_TEXT_BLOCKS = 0.35, PVP_ABOVE_HEAD = 1.2;
    /** PvP scattered words: how far below the top of the target's head their ring is centred (blocks). */
    private static final double PVP_BODY_MIDDLE = 0.9;
    /** Turn between successive scattered words: spreads any number of them evenly around the circle. */
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
    /** When fading out word by word, each word starts this much after the one before. */
    private static final long WORD_OUT_STAGGER_MS = 70;

    /** Shown while the settings screen is open and nothing with lyrics is playing, so changes can be seen. */
    private static final List<Lyrics.Line> DEMO = Lyrics.parse("""
            [00:00.00] Yellow diamonds in the light
            [00:04.00] Now we're standing side by side
            [00:08.00] As your shadow crosses mine
            [00:12.00] What it takes to come alive""", 16_000);
    private static final NowPlaying.Track DEMO_TRACK = new NowPlaying.Track("Preview", "Mc'Flow", 16_000);
    /** Set by the settings screen while it's open. */
    static volatile boolean preview;

    private GpuImage pill;
    private String pillKey = "";
    private long pillFrameNanos;
    private final float[] eqLevels = new float[3];
    /** Which song/size the letters were last prepared for. */
    private String preparedFor = "";
    /** 0..1 how far the lyrics are softened because a menu is open; and this frame's dimming and blur radius (px). */
    private float menuFade, dim = 1, blurPx;
    private GpuImage glow;
    private int glowColor;
    private long menuFrameNanos;
    /** Lines on screen right now: usually one, two while one fades out and the next fades in. */
    private final Map<Lyrics.Line, View> views = new IdentityHashMap<>();

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Settings s = Settings.get();
        NowPlaying.Track track = NowPlaying.track();
        List<Lyrics.Line> lines = Lyrics.current();
        long pos = NowPlaying.positionMs() + s.offsetMs;
        updateMenuFade(s);
        WorldText.begin();
        boolean playing = NowPlaying.playing();
        if (preview && (track == null || lines.isEmpty())) {
            track = DEMO_TRACK;
            lines = DEMO;
            pos = System.currentTimeMillis() % 16_000;
            playing = true;
        }
        drawPeers(g);
        drawHits(g, deltaTracker.getGameTimeDeltaPartialTick(true));
        GlyphCache.uploadSome(4); // a few per frame keeps the upload work invisible
        if (track != null) prepareLetters(lines, s);
        if (track == null) {
            closeViews(List.of());
            return;
        }

        Window window = Minecraft.getInstance().getWindow();
        int w = window.getWidth(), h = window.getHeight();
        float px = 1f / window.getGuiScale();
        g.pose().pushMatrix();
        g.pose().scale(px, px); // 1 unit = 1 physical pixel from here on

        drawEdgeGlow(g, w, h, s);
        if (s.showPill) drawPill(g, w, h, window.getGuiScale(), track, playing);

        // every line that's fading in, showing or fading out; older ones first so the newest is on top
        List<Lyrics.Line> visible = new ArrayList<>();
        for (Lyrics.Line line : lines) {
            if (line.startMs() - s.fadeMs <= pos && pos < line.endMs() + fadeOutTotal(line, s)) visible.add(line);
        }
        closeViews(visible);
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Player target = Pvp.target();
        visible.sort(Comparator.comparingLong(Lyrics.Line::startMs));
        for (int k = 0; k < visible.size(); k++) {
            Lyrics.Line line = visible.get(k);
            View view = views.get(line);
            // starting or stopping following a target lays the line out again (back to your own placement after a fight)
            if (view == null || view.version != Settings.version() || view.height != h || view.following != (target != null)) {
                view = layout(line, camera, w, h, target != null);
                views.put(line, view);
            }
            if (target != null) follow(view, camera, target.getPosition(deltaTracker.getGameTimeDeltaPartialTick(true)).add(0, target.getBbHeight(), 0));
            // a newer line is coming in where this one is: from the moment it starts showing, this one flows up out of
            // its way while it fades (a gentle ease-out over at least half a second, so it glides rather than jumps)
            float push = 0;
            if (k + 1 < visible.size() && !(view.following && s.pvpScatter)) { // (scattered words just fade where they are)
                float t = Math.clamp((pos + s.fadeMs - visible.get(k + 1).startMs()) / (float) Math.max(PUSH_MS, 2 * s.fadeMs), 0f, 1f);
                push = (1 - (1 - t) * (1 - t)) * view.lineHeight * (s.flowDown ? -1 : 1);
            }
            drawLine(g, w, h, view, camera, pos, push);
        }
        // drop: a quick white flash over everything
        float drop = s.dropEffect ? Beat.drop() : 0;
        int flash = Math.round(drop * drop * drop * 0.3f * s.dropIntensity * 255 * dim);
        if (flash > 2) g.fill(0, 0, w, h, Math.min(255, flash) << 24 | 0xFFFFFF);
        g.pose().popMatrix();
    }

    /** The accent colour: the album cover's main colour when that's switched on, else the one picked in settings. */
    private static int accent() {
        Settings s = Settings.get();
        Integer fromArt = s.albumArt && s.accentFromArt ? NowPlaying.coverColor() : null;
        return fromArt != null ? fromArt : s.accent;
    }

    /** The screen edges glow in the accent colour on beats, much stronger on drops. */
    private void drawEdgeGlow(GuiGraphicsExtractor g, int w, int h, Settings s) {
        float strength = s.edgeGlow * (Beat.pulse() * 0.6f + (s.dropEffect ? Beat.drop() * s.dropIntensity : 0));
        int alpha = Math.round(Math.min(1, strength) * 255 * dim);
        if (alpha < 3) return;
        int accent = accent();
        if (glow == null || glowColor != accent) {
            if (glow != null) glow.close();
            glow = new GpuImage(TextArt.vignette(320, 180, accent)); // small; smooth filtering stretches it
            glowColor = accent;
        }
        g.pose().pushMatrix();
        g.pose().scale(w / (float) glow.width, h / (float) glow.height);
        glow.draw(g, 0, 0, alpha << 24 | 0xFFFFFF);
        g.pose().popMatrix();
    }

    /** Other players' lines above their heads (multiplayer sing-along), even when you're not playing anything. */
    private static void drawPeers(GuiGraphicsExtractor g) {
        Window window = Minecraft.getInstance().getWindow();
        g.pose().pushMatrix();
        g.pose().scale(1f / window.getGuiScale(), 1f / window.getGuiScale());
        PeerLyrics.draw(g, window.getWidth(), window.getHeight(), Minecraft.getInstance().gameRenderer.mainCamera());
        g.pose().popMatrix();
    }

    /** Comic hit effects over the players you hit, in physical pixels like the rest. */
    private static void drawHits(GuiGraphicsExtractor g, float partialTick) {
        Window window = Minecraft.getInstance().getWindow();
        g.pose().pushMatrix();
        g.pose().scale(1f / window.getGuiScale(), 1f / window.getGuiScale());
        HitFx.draw(g, partialTick);
        g.pose().popMatrix();
    }

    /**
     * Eases the "a menu is open" softening in and out. Any screen counts except chat (you may want to read along
     * while typing) and our own settings screen (it's showing a live preview).
     */
    private void updateMenuFade(Settings s) {
        long now = System.nanoTime();
        float dt = menuFrameNanos == 0 ? 16 : Math.clamp((now - menuFrameNanos) / 1e6f, 0, 100);
        menuFrameNanos = now;
        Screen screen = Minecraft.getInstance().gui.screen();
        boolean menu = s.menuBlur && screen != null && !(screen instanceof ChatScreen) && !(screen instanceof SettingsScreen);
        menuFade += ((menu ? 1 : 0) - menuFade) * (1 - (float) Math.exp(-dt / 90));
        dim = 1 - MENU_DIM * menuFade;
        blurPx = Minecraft.getInstance().getWindow().getHeight() * MENU_BLUR * menuFade;
    }

    /**
     * When a song's lyrics (or the text size) change, draws every letter the song uses in the background, so lines
     * can appear without any drawing work.
     */
    private void prepareLetters(List<Lyrics.Line> lines, Settings s) {
        int h = Minecraft.getInstance().getWindow().getHeight();
        String key = System.identityHashCode(lines) + "|" + h + "|" + s.lyricSize + "|" + s.layout + "|" + s.uppercase;
        if (key.equals(preparedFor)) return;
        preparedFor = key;
        List<String> texts = lines.stream().map(l -> s.uppercase ? l.text().toUpperCase(Locale.ROOT) : l.text()).toList();
        java.util.Collection<String> letters = GlyphCache.letters(texts);
        GlyphCache.prepare(letters, h * BIG_TEXT * s.lyricSize * RES);
        if (s.layout == Settings.Layout.CENTERED) GlyphCache.prepare(letters, h * SMALL_TEXT * s.lyricSize * RES);
    }

    private static long fadeOutTotal(Lyrics.Line line, Settings s) {
        return s.fadeOutMs + (s.fadeOut == Settings.FadeOut.WORDS ? (line.words().size() - 1) * WORD_OUT_STAGGER_MS : 0);
    }

    /** Forgets lines that are no longer shown (their letters stay cached in {@link GlyphCache}). */
    private void closeViews(List<Lyrics.Line> keep) {
        views.keySet().removeIf(line -> keep.stream().noneMatch(l -> l == line));
    }

    /** The pill: just what's playing (title · artist), with the beat equalizer on the right. */
    private void drawPill(GuiGraphicsExtractor g, int w, int h, int guiScale, NowPlaying.Track track, boolean playing) {
        Settings s = Settings.get();
        long now = System.nanoTime();
        float dt = pillFrameNanos == 0 ? 16 : Math.clamp((now - pillFrameNanos) / 1e6f, 0, 100);
        pillFrameNanos = now;

        int height = Math.max(24, Math.round(h * 0.036f));
        int eqWidth = s.equalizer ? Math.round(height * 0.5f) : 0;
        // YouTube-style metadata cleaned the same way the lyric search does: "Katy Perry - California Gurls (Lyrics)" by
        // channel "Lost Panda" shows as "California Gurls · Katy Perry"
        String[] song = Lyrics.guesses(track.artist(), track.title()).getFirst();
        String text = song[0].isEmpty() ? song[1] : song[1] + "  ·  " + song[0];
        BufferedImage cover = s.albumArt ? NowPlaying.cover() : null;
        String key = text + "|" + height + "|" + w + "|" + Settings.version() + "|" + System.identityHashCode(cover);
        if (!key.equals(pillKey)) {
            if (pill != null) pill.close();
            pill = new GpuImage(TextArt.pill(text, height, Math.round(w * 0.45f), eqWidth, cover));
            pillKey = key;
        }

        int x = (w - pill.width) / 2;
        // at the bottom it sits above the hotbar (22 GUI pixels tall) instead of on it
        int y = s.pillAtBottom ? h - height - Math.round(32 * guiScale) : Math.round(h * 0.014f);
        pill.drawSoft(g, x, y, Math.round(255 * dim) << 24 | 0xFFFFFF, blurPx, menuFade);
        if (s.equalizer) drawEqualizer(g, x + pill.width - Math.round(height * TextArt.PILL_RIGHT_PAD) - eqWidth, y + height / 2, height, eqWidth, playing, dt);
    }

    /** Three bars driven by the music: bass loudness, the beat, and a mix, easing so they bounce rather than flicker. */
    private void drawEqualizer(GuiGraphicsExtractor g, int eqX, int mid, int height, int eqWidth, boolean playing, float dt) {
        float energy = Beat.energy(), beat = Beat.pulse();
        float[] target = playing ? new float[]{energy, Math.max(beat, energy * 0.6f), energy * 0.5f + beat * 0.6f} : new float[3];
        float ease = 1 - (float) Math.exp(-dt / 60);
        int barW = Math.max(2, eqWidth / 5), color = Math.round(255 * dim) << 24 | (accent() & 0xFFFFFF);
        for (int b = 0; b < 3; b++) {
            eqLevels[b] += (Math.clamp(target[b], 0, 1) - eqLevels[b]) * ease;
            int bh = Math.max(2, Math.round(height * (0.12f + 0.46f * eqLevels[b])));
            int bx = eqX + b * barW * 2;
            g.fill(bx, mid - bh / 2, bx + barW, mid + bh / 2, color);
        }
    }

    /**
     * Draws one line. Around you (sphere): the text is painted on the inside of a sphere centred on the player, keeps
     * its direction when you turn, stays the same size when you walk, and each letter is turned to follow the curve.
     * Pinned: the text is a flat sign standing in the world; each letter is drawn in perspective, so it's seen at an
     * angle from the side and grows as you walk up to it. On screen: it just sits at a fixed spot.
     */
    private void drawLine(GuiGraphicsExtractor g, int w, int h, View v, Camera camera, long pos, float push) {
        Settings s = Settings.get();
        Settings.Placement mode = v.following ? Settings.Placement.PINNED : s.placement;
        boolean world3d = mode == Settings.Placement.PINNED && s.realWorld && WorldText.supported;
        float edge = h * EDGE_FADE;
        // first find where every letter is and how visible each whole word is
        // how far the view has turned since the line appeared (degrees), for parallax; capped so a full spin stays sane
        float turnYaw = Math.clamp(wrapDegrees(camera.yRot() - v.yaw0), -40f, 40f), turnPitch = Math.clamp(camera.xRot() - v.pitch0, -40f, 40f);
        float lag = s.parallax * PARALLAX_TURN;
        for (Glyph gl : v.glyphs) gl.state.view = 0;
        for (Glyph gl : v.glyphs) {
            Vec3 world = switch (mode) {
                // further layers follow the view a little, so they seem further away
                case WORLD -> onSphere(v, camera, gl.across + (float) Math.toRadians(turnYaw) * gl.layer * lag,
                        gl.up - (float) Math.toRadians(turnPitch) * gl.layer * lag + push / v.pixelsPerRadian);
                case PINNED -> onSurface(v, camera, s, gl.lx, gl.ly - push, gl.layer);
                case SCREEN -> null;
            };
            float[] p = world != null ? project(camera, world, w, h)
                    // the world slides the other way when you turn; further layers slide along a little
                    : new float[]{s.screenX * w + gl.lx - turnYaw * h / 90f * gl.layer * lag,
                    s.screenY * h + gl.ly + bendY(s, gl.lx, w, h) - push - turnPitch * h / 90f * gl.layer * lag, 0, 0};
            gl.screen = p;
            if (world3d) gl.occlusion = 1; // the depth buffer hides it now
            else occlude(gl, s, camera, world, p != null);
            if (mode == Settings.Placement.SCREEN) {
                gl.view = 1;
            } else {
                // 0 behind the player or off screen; fades near the edges (and, pinned, when you walk into it)
                gl.view = p == null ? 0 : world3d ? 1 : Math.clamp(Math.min(Math.min(p[0], w - p[0]), Math.min(p[1], h - p[1])) / edge, 0f, 1f);
                if (p != null && mode == Settings.Placement.PINNED && !v.following) gl.view *= (float) Math.clamp((p[3] - NEAR_HIDE) / (NEAR_FULL - NEAR_HIDE), 0, 1);
            }
            gl.state.view = Math.max(gl.state.view, gl.view);
        }

        float fadeIn = Math.max(1, s.fadeMs), fadeOut = Math.max(1, s.fadeOutMs), rise = h * 0.008f;
        for (Glyph gl : v.glyphs) {
            WordState word = gl.state;
            long appearAt = s.reveal == Settings.Reveal.LINE ? v.line.startMs() : gl.word.startMs();
            long leaveAt = v.line.endMs() + (s.fadeOut == Settings.FadeOut.WORDS ? gl.order * WORD_OUT_STAGGER_MS : 0);
            float in = s.appear == Settings.Appear.TYPEWRITER
                    // letter by letter, left to right, each snapping in quickly
                    ? Math.clamp((pos + s.fadeMs - appearAt - (gl.lx - word.firstX) / (h * 0.035f) * TYPE_MS) / TYPE_MS, 0f, 1f)
                    : Math.clamp((pos + s.fadeMs - appearAt) / fadeIn, 0f, 1f);
            float out = 1 - Math.clamp((pos - leaveAt) / fadeOut, 0f, 1f);
            float a = (s.appear == Settings.Appear.BOUNCE ? Math.min(1, in * 2) : in) * out;
            // a glitching letter flickers while it arrives
            float noise = noise(gl, 1);
            if (s.appear == Settings.Appear.GLITCH && in < 1 && noise > 0.2f) a *= 0.25f;
            // (not while following a target: you spin around in a fight, and the words should be there when you look back)
            if (mode != Settings.Placement.SCREEN && !v.following) {
                if (a >= 1 && word.view >= 0.5f) word.seen = true;
                // once a shown word has completely left view it stays gone, even if you look back; part-visible words stay
                if (word.seen && word.view <= 0.01f) word.gone = true;
                if (word.gone) continue;
            }
            float[] p = gl.screen;
            a *= gl.view * gl.occlusion; // blocks only hide/tint; they don't count as the word leaving view
            a *= spanOpacity(s, gl.span);
            int alpha = Math.round(a * 255 * dim);
            if (alpha < 4) continue;

            // rise into place while appearing, drift up while fading out (screen y points down)
            float lift = s.floatUp ? (1 - in) * rise - (1 - out) * rise : 0;
            int base = spanColor(s, gl.span);
            int color = gl.tintAmount > 0 ? lerpColor(base, gl.tint, gl.tintAmount) : base;
            float pulse = 1;
            float drop = s.dropEffect ? Beat.drop() * s.dropIntensity : 0;
            pulse *= 1 + 0.25f * drop; // everything pops on a drop
            // shake on a drop: each letter jitters on its own, a new offset every frame
            float shake = drop * h * 0.012f, seed = gl.lx * 0.37f + gl.ly * 0.11f + System.nanoTime() / 16_000_000f;
            float shakeX = shake * (float) Math.sin(seed * 12.9898), shakeY = shake * (float) Math.sin(seed * 78.233);
            // pop and flash on each beat; sung "oh"/"ah" sounds always do
            if (gl.vocal || s.pulse == Settings.Pulse.ALL || (s.pulse == Settings.Pulse.NOUNS && gl.noun)) {
                float beat = Beat.pulse();
                pulse *= 1 + BEAT_GROW * s.pulseStrength * beat;
                color = lerpColor(color, s.pulseColor, Math.min(1, beat * 0.7f * s.pulseStrength));
            }
            // the chosen animations, as extra offset (screen px), size and turn on top of everything else
            float k = 1 - out, extraX = 0, extraY = 0, extraTurn = 0, extraSize = 1;
            switch (s.appear) {
                case BOUNCE -> extraSize *= easeOutBack(in);
                case POP -> extraSize *= 1 + 0.6f * (1 - in) * (1 - in);
                case GLITCH -> {
                    extraX += (1 - in) * h * 0.02f * noise(gl, 2);
                    extraY += (1 - in) * h * 0.006f * noise(gl, 3);
                }
                default -> {}
            }
            long now = System.currentTimeMillis();
            switch (s.idle) {
                case WAVE -> extraY += (float) Math.sin(now * 0.005 + gl.lx * 0.02) * h * 0.004f;
                case FLOAT -> extraY += (float) Math.sin(now * 0.0025 + gl.order * 1.3) * h * 0.006f;
                default -> {}
            }
            if (v.following && s.pvpScatter) { // scattered PvP words drift about, each word on its own slow loop
                double t = now * 0.0012 + gl.order * 2.1;
                extraX += (float) Math.sin(t) * h * 0.008f;
                extraY += (float) Math.cos(t * 1.3) * h * 0.006f;
            }
            switch (s.exit) {
                case SCATTER -> { // letters fly apart from the middle of the line
                    extraX += Math.signum(gl.lx) * k * h * 0.12f * (0.6f + 0.4f * Math.abs(still(gl, 4)));
                    extraY -= k * h * 0.05f * still(gl, 5);
                    extraTurn += k * 1.2f * still(gl, 6);
                }
                case FALL -> {
                    extraY += k * k * h * 0.25f;
                    extraTurn += k * 0.6f * still(gl, 7);
                }
                case BURN -> {
                    extraSize *= 1 - 0.4f * k;
                    extraY -= k * h * 0.03f;
                    color = lerpColor(color, 0xFF7A2E, Math.min(1, k * 1.6f)); // glows orange as it burns away
                }
                default -> {}
            }
            pulse *= Math.max(0.01f, extraSize);
            shakeX += extraX;
            shakeY += extraY;
            float hw = gl.image.width / 2f, hh = gl.image.height / 2f;
            if (world3d) {
                queueInWorld(v, gl, camera, s, hw / RES * pulse, hh / RES * pulse, gl.lx + shakeX, gl.ly - push + shakeY + lift,
                        extraTurn, alpha, color);
                continue;
            }
            float imageScale = 1; // screen px per letter-image px, to blur by the same screen distance in every mode
            g.pose().pushMatrix();
            switch (mode) {
                case WORLD -> {
                    // follow the curve: the row runs along the sphere's latitude, so aim the letter along it on screen
                    float[] next = project(camera, onSphere(v, camera, gl.across + (float) Math.toRadians(turnYaw) * gl.layer * lag + 0.01f,
                            gl.up - (float) Math.toRadians(turnPitch) * gl.layer * lag + push / v.pixelsPerRadian), w, h);
                    float angle = next == null ? 0 : (float) Math.atan2(next[1] - p[1], next[0] - p[0]);
                    float zoom = Math.clamp(p[2] * (float) SPHERE_RADIUS / v.pixelsPerRadian, 0.2f, 4f) * pulse; // ~1 unless FOV changed
                    g.pose().translate(p[0] + shakeX, p[1] + shakeY + lift * zoom);
                    g.pose().rotate(angle + extraTurn);
                    g.pose().scale(zoom / RES, zoom / RES);
                    imageScale = zoom / RES;
                }
                case PINNED -> {
                    // perspective: map the letter's image onto where its sideways and down edges land on screen
                    // (taken from the surface, so letters follow the bend when it warps)
                    float[] side = project(camera, onSurface(v, camera, s, gl.lx + hw / RES, gl.ly - push, gl.layer), w, h);
                    float[] down = project(camera, onSurface(v, camera, s, gl.lx, gl.ly - push + hh / RES, gl.layer), w, h);
                    if (side == null || down == null) {
                        g.pose().popMatrix();
                        continue;
                    }
                    float ax = (side[0] - p[0]) / hw * pulse, ay = (side[1] - p[1]) / hw * pulse;
                    float bx = (down[0] - p[0]) / hh * pulse, by = (down[1] - p[1]) / hh * pulse;
                    float scale = (float) Math.hypot(bx, by) * RES; // screen px per layout px here
                    g.pose().translate(p[0] + shakeX, p[1] + shakeY + lift * scale);
                    g.pose().rotate(extraTurn);
                    g.pose().mul(new Matrix3x2f(ax, ay, bx, by, 0, 0));
                    imageScale = (float) Math.hypot(bx, by);
                }
                case SCREEN -> {
                    g.pose().translate(p[0] + shakeX, p[1] + shakeY + lift);
                    // letters lean along the bend, like text on an arc
                    float slope = bendY(s, gl.lx + 1, w, h) - bendY(s, gl.lx, w, h);
                    g.pose().rotate((float) Math.atan(slope) + extraTurn);
                    g.pose().scale(pulse / RES, pulse / RES);
                    imageScale = pulse / RES;
                }
            }
            float blur = blurPx / Math.max(0.01f, imageScale);
            if (gl.effects != null) gl.effects.drawSoft(g, Math.round(-hw), Math.round(-hh), alpha << 24 | 0xFFFFFF, blur, menuFade);
            gl.image.drawSoft(g, Math.round(-hw), Math.round(-hh), alpha << 24 | (color & 0xFFFFFF), blur, menuFade);
            g.pose().popMatrix();
        }
    }

    /**
     * Updates how much of a letter shows through the blocks in front of it (block detection setting), eased over a
     * few frames so letters don't flicker at block edges.
     */
    private static void occlude(Glyph gl, Settings s, Camera camera, Vec3 world, boolean onScreen) {
        Occlusion.Result r = Occlusion.CLEAR;
        if (s.blockDetection && world != null && onScreen && Minecraft.getInstance().level != null) {
            r = Occlusion.between(Minecraft.getInstance().level, camera.position(), world);
        }
        if (gl.occlusion < 0) { // first look: no easing in
            gl.occlusion = r.visibility();
            gl.tintAmount = r.tintAmount();
        } else {
            gl.occlusion += (r.visibility() - gl.occlusion) * 0.35f;
            gl.tintAmount += (r.tintAmount() - gl.tintAmount) * 0.35f;
        }
        if (r.tintAmount() > 0) gl.tint = r.tint();
    }

    /** Text colour for a letter: its own colour inside (parentheses) or "quotes" when that style is on. */
    private static int spanColor(Settings s, int span) {
        if (span == Lyrics.IN_PARENS && s.parenStyle) return s.parenColor;
        if (span == Lyrics.IN_QUOTES && s.quoteStyle) return s.quoteColor;
        return s.lyricColor;
    }

    private static float spanOpacity(Settings s, int span) {
        if (span == Lyrics.IN_PARENS && s.parenStyle) return s.parenOpacity;
        if (span == Lyrics.IN_QUOTES && s.quoteStyle) return s.quoteOpacity;
        return 1;
    }

    /** Overshoots past 1 and settles back, for a bouncy arrival. */
    private static float easeOutBack(float t) {
        float c = 1.7f, u = t - 1;
        return 1 + (c + 1) * u * u * u + c * u * u;
    }

    /** A per-letter random number in -1..1 that changes every frame (flicker, jitter). */
    private static float noise(Glyph gl, int salt) {
        return (float) Math.sin((gl.lx * 0.37f + gl.ly * 0.11f + salt * 17) * 12.9898 + System.nanoTime() / 16_000_000L * 78.233);
    }

    /** A per-letter random number in -1..1 that stays the same (so a scattering letter keeps its direction). */
    private static float still(Glyph gl, int salt) {
        double v = Math.sin((gl.lx * 0.37 + gl.ly * 0.11 + salt * 17) * 12.9898) * 43758.5453;
        return (float) (2 * (v - Math.floor(v)) - 1);
    }

    private static int lerpColor(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
    }

    /**
     * Queues a pinned letter as a real quad in the world: its corners (half size {@code hw} x {@code hh} layout px,
     * turned by {@code turn}) are put on the sign's surface, so warp, parallax and the animations all carry over.
     */
    private static void queueInWorld(View v, Glyph gl, Camera camera, Settings s, float hw, float hh, float cx, float cy,
                                     float turn, int alpha, int color) {
        float cos = (float) Math.cos(turn), sin = (float) Math.sin(turn);
        float[][] offsets = {{-hw, -hh}, {-hw, hh}, {hw, hh}, {hw, -hh}}; // top left, bottom left, bottom right, top right
        Vec3[] c = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            float dx = offsets[i][0] * cos - offsets[i][1] * sin, dy = offsets[i][0] * sin + offsets[i][1] * cos;
            c[i] = onSurface(v, camera, s, cx + dx, cy + dy, gl.layer);
        }
        if (gl.effects != null) WorldText.add(gl.effects.id(), c[0], c[1], c[2], c[3], alpha << 24 | 0xFFFFFF);
        // the letter a hair in front of its glow/outline, so they don't flicker against each other
        Vec3 nudge = camera.position().subtract(c[0]).normalize().scale(0.004);
        WorldText.add(gl.image.id(), c[0].add(nudge), c[1].add(nudge), c[2].add(nudge), c[3].add(nudge), alpha << 24 | (color & 0xFFFFFF));
    }

    /** Point on the sphere {@code across} radians to the line's right and {@code up} radians above it (line's own frame). */
    private static Vec3 onSphere(View v, Camera camera, float across, float up) {
        Vec3 ring = v.forward.scale(Math.cos(across)).add(v.right.scale(Math.sin(across)));
        Vec3 dir = ring.scale(Math.cos(up)).add(v.up.scale(Math.sin(up)));
        return camera.position().add(dir.scale(SPHERE_RADIUS));
    }

    /**
     * Point on the pinned sign at a layout-pixel offset from its centre (y down). Further parallax layers sit behind
     * the sign. When the player comes close the sign bends around them (edges curling toward the player, more the
     * closer they are), capped at {@link #MAX_BEND} blocks so it never folds over.
     */
    private static Vec3 onSurface(View v, Camera camera, Settings s, double lx, double ly, float layer) {
        // PvP: the sign over the target arches like the on-screen text (same shape, in the same layout pixels)
        if (v.following && !s.pvpScatter) ly += bendY(s, (float) lx, v.width, v.height);
        double x = lx / v.pixelsPerBlock, y = -ly / v.pixelsPerBlock; // blocks along the sign
        Vec3 point = v.anchor.add(v.right.scale(x)).add(v.up.scale(y)).add(v.forward.scale((layer - MEAN_LAYER) * s.parallax * PARALLAX_DEPTH)); // centred on the sign's distance
        Vec3 toSign = v.anchor.subtract(camera.position());
        double distance = Math.abs(toSign.dot(v.forward));
        double bend = v.following ? 0 : s.warp * Math.clamp((WARP_START - distance) / (WARP_START - WARP_FULL), 0, 1);
        if (bend <= 0) return point;
        double fromPlayer = x + toSign.dot(v.right); // sideways distance from the spot on the sign right in front of the player
        return point.subtract(v.forward.scale(Math.min(MAX_BEND, bend * BEND_CURVE * fromPlayer * fromPlayer)));
    }

    /**
     * On screen: how far down (screen px) a letter {@code lx} px from the line's centre moves for the bend setting.
     * A parabola through the middle: positive bends the ends down (an arch), negative bends them up (a smile).
     */
    private static float bendY(Settings s, float lx, int w, int h) {
        float x = lx / (w / 2f);
        return s.screenBend * SCREEN_BEND * h * x * x;
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360;
        return d >= 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    /**
     * PvP mode: puts the line's sign over the target's head ({@code head} = top of their hitbox), bobbing gently and
     * turned to face you, at a fixed size in blocks. Rows still sit left/right of the centre, so staggered lines flank
     * them. With scattered words the sign's centre is the middle of their body instead, for the words to surround.
     */
    private static void follow(View v, Camera camera, Vec3 head) {
        double bob = Math.sin(System.currentTimeMillis() * 0.002) * 0.08;
        v.anchor = head.add(0, (Settings.get().pvpScatter ? -PVP_BODY_MIDDLE : PVP_ABOVE_HEAD) + bob, 0);
        Vec3 toSign = v.anchor.subtract(camera.position());
        if (toSign.lengthSqr() < 1e-4) return;
        v.forward = toSign.normalize();
        Vec3 level = new Vec3(-v.forward.z, 0, v.forward.x); // to the right, kept level so the text never tilts sideways
        if (level.lengthSqr() < 1e-6) return; // straight above or below: keep last frame's sideways direction
        v.right = level.normalize();
        v.up = v.right.cross(v.forward).normalize();
        v.pixelsPerBlock = (float) (v.height * BIG_TEXT * Settings.get().lyricSize / PVP_TEXT_BLOCKS);
    }

    /**
     * PvP scattered words: moves each word cluster (at most two words, see {@link Lyrics}) to its own random spot on a
     * ring around the target's body, in layout pixels of the sign. Successive clusters go round by the golden angle (plus
     * a little jitter), so they spread all around the player instead of bunching up on one side.
     */
    private static void scatter(View v, int h, Settings s) {
        double perBlock = h * BIG_TEXT * s.lyricSize / PVP_TEXT_BLOCKS;
        Map<WordState, float[]> centres = new IdentityHashMap<>(); // {sum x, sum y, letters} per word
        for (Glyph gl : v.glyphs) {
            float[] c = centres.computeIfAbsent(gl.state, k -> new float[3]);
            c[0] += gl.lx;
            c[1] += gl.ly;
            c[2]++;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble(Math.PI * 2);
        Map<WordState, float[]> shifts = new IdentityHashMap<>();
        List<Glyph> moved = new ArrayList<>(v.glyphs.size());
        for (Glyph gl : v.glyphs) {
            float[] shift = shifts.get(gl.state);
            if (shift == null) { // the word's first letter: pick its spot
                angle += GOLDEN_ANGLE + r.nextDouble(-0.4, 0.4);
                double across = Math.cos(angle) * r.nextDouble(1.0, 1.6) * perBlock, down = Math.sin(angle) * r.nextDouble(0.7, 1.15) * perBlock;
                float[] c = centres.get(gl.state);
                shift = new float[]{(float) (across - c[0] / c[2]), (float) (down - c[1] / c[2])};
                shifts.put(gl.state, shift);
                gl.state.firstX += shift[0];
            }
            moved.add(new Glyph(gl.image, gl.word, gl.state, gl.noun, gl.vocal, gl.span, gl.order, gl.layer, gl.across, gl.up,
                    gl.lx + shift[0], gl.ly + shift[1]));
            moved.getLast().effects = gl.effects;
        }
        v.glyphs.clear();
        v.glyphs.addAll(moved);
    }

    /**
     * Lays the line out in screen pixels around its anchor and fixes where it lives: for the sphere, every letter's
     * offset becomes angles in the line's own frame (rows follow rings around {@code up}, spread by 1/cos(up) so letters
     * keep their width); pinned, the offsets are positions on a plane facing where the player looked. Either way the
     * line looks like the flat version when it appears.
     */
    private View layout(Lyrics.Line line, Camera camera, int w, int h, boolean following) {
        Settings s = Settings.get();
        View v = new View(line, Settings.version(), h);
        v.width = w;
        v.following = following;
        float yaw = camera.yRot() + s.worldYaw;
        v.forward = Vec3.directionFromRotation(Math.clamp(camera.xRot() - s.worldRaise, -89.9f, 89.9f), yaw);
        v.right = Vec3.directionFromRotation(0, yaw + 90); // stays level, so text isn't tilted sideways
        v.up = v.right.cross(v.forward).normalize();
        // scale measured straight ahead, which is always on screen even when the line starts off to the side
        float[] ahead = project(camera, camera.position().add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(SPHERE_RADIUS)), w, h);
        v.pixelsPerRadian = (ahead != null ? ahead[2] : h / 10f) * (float) SPHERE_RADIUS;
        v.pixelsPerBlock = v.pixelsPerRadian / Math.max(1, s.pinDistance); // so it starts the same size on screen
        v.anchor = camera.position().add(v.forward.scale(s.pinDistance));
        v.yaw0 = camera.yRot();
        v.pitch0 = camera.xRot();

        // room for rows left/right of the anchor: the screen minus a margin, seen from where the line starts
        double margin = w * 0.04, anchorX = s.placement == Settings.Placement.SCREEN && !following ? s.screenX * w : w / 2.0;
        v.rowMinX = -anchorX + margin;
        v.rowMaxX = w - anchorX - margin;

        List<Lyrics.Word> words = line.words();
        float big = h * BIG_TEXT * s.lyricSize, small = h * SMALL_TEXT * s.lyricSize;
        int rowGap = Math.round(TextArt.lyricAscent(big) * 1.05f);
        v.lineHeight = rowGap * (s.layout == Settings.Layout.CENTERED ? 2.2f : 1.2f); // only centered stacks two rows
        List<String> tokens = words.stream().flatMap(word -> Arrays.stream(word.text().split(" "))).toList();
        boolean[] nouns = Nouns.find(tokens), vocals = Nouns.vocal(tokens);
        // [token in the line, word in the line] of the letters being placed, then [paren depth, in quotes] (Lyrics.spanStep)
        int[] counters = {0, 0, 0, 0};
        LetterSink sink = (px, py, word, state, noun, vocal, span, order, layer, image) -> {
            float up = (float) (-py / v.pixelsPerRadian); // screen y points down
            float across = (float) (px / v.pixelsPerRadian / Math.cos(up));
            if (Float.isNaN(state.firstX)) state.firstX = (float) px;
            v.glyphs.add(new Glyph(image, word, state, noun, vocal, span, order, layer, across, up, (float) px, (float) py));
        };
        switch (s.layout) {
            case SINGLE -> placeRow(v, words, big, 0, 0, -rowGap / 2, MEAN_LAYER, nouns, vocals, counters, sink);
            case CENTERED -> {
                int split = Lyrics.rowSplit(words);
                placeRow(v, words.subList(0, split), big, 0, 0, -rowGap / 2, MEAN_LAYER, nouns, vocals, counters, sink);
                placeRow(v, words.subList(split, words.size()), small, 0, 0, rowGap / 2, MEAN_LAYER, nouns, vocals, counters, sink);
            }
            case STAGGERED -> {
                // one row ends exactly `gap` left of the centre, the other starts exactly `gap` right of it
                int split = Lyrics.rowSplit(words), gap = Math.round(w * s.rowSpread);
                int firstSide = s.firstRowRight ? 1 : -1;
                // side by side, the two rows sit at different parallax depths
                // same size and same level: one line split into two halves around the centre
                placeRow(v, words.subList(0, split), big, firstSide * gap, firstSide, -rowGap / 2, 0.7f, nouns, vocals, counters, sink);
                placeRow(v, words.subList(split, words.size()), big, -firstSide * gap, -firstSide, -rowGap / 2, 1.1f, nouns, vocals, counters, sink);
            }
        }
        if (following && s.pvpScatter) scatter(v, h, s);
        return v;
    }

    /** Takes a letter whose centre sits at a layout-pixel offset from the line's anchor (y down). */
    private interface LetterSink {
        void add(double px, double py, Lyrics.Word word, WordState state, boolean noun, boolean vocal, int span, int order, float layer, GpuImage image);
    }

    /**
     * World point to screen pixels: {x, y, pixels per block at that depth, depth in blocks}, or null when it's behind
     * the camera.
     * ponytail: drawn as an overlay, so blocks don't hide it; real in-world rendering would need a level render hook per loader.
     */
    static float[] project(Camera camera, Vec3 point, int w, int h) {
        Vec3 forward = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        double depth = point.subtract(camera.position()).dot(forward);
        if (depth < 0.5) return null;
        GameRenderer renderer = Minecraft.getInstance().gameRenderer;
        Vec3 a = renderer.projectPointToScreen(point);
        Vector3f up = camera.rotation().transform(new Vector3f(0, 1, 0));
        Vec3 b = renderer.projectPointToScreen(point.add(up.x, up.y, up.z));
        float x = (float) (a.x + 1) / 2 * w, y = (float) (1 - a.y) / 2 * h;
        float ppb = (float) Math.hypot((b.x - a.x) / 2 * w, (b.y - a.y) / 2 * h);
        return new float[]{x, y, ppb, (float) depth};
    }

    /**
     * Lays a row out word by word, then letter by letter (keeping the font's spacing plus the letter-spacing
     * setting), placed by {@code x}/{@code side} but kept inside the screen.
     */
    private void placeRow(View v, List<Lyrics.Word> words, float size, int x, int side, int top, float rowLayer, boolean[] nouns, boolean[] vocals, int[] counters, LetterSink sink) {
        Settings s = Settings.get();
        float fontPx = size * RES; // images are RES x the layout size; positions below stay in layout pixels
        double spacing = s.letterSpacing * size; // layout px added after each letter
        List<String> texts = words.stream().map(word -> s.uppercase ? word.text().toUpperCase(Locale.ROOT) : word.text()).toList();
        double advance = texts.stream().mapToDouble(t -> TextArt.lyricWidth(t + " ", fontPx) / (double) RES + spacing * t.length()).sum();
        // what you see ends at the last letter, not after the space (and letter spacing) that follows it
        double rowWidth = advance - TextArt.lyricWidth(" ", fontPx) / (double) RES - spacing;
        // x is the row's centre (side 0), where its last letter ends (side -1), or where its first letter starts (side 1)
        double wanted = side == 0 ? x : side < 0 ? x - rowWidth / 2 : x + rowWidth / 2;
        // keep the whole row on screen: a wide spread pushes rows against the edges instead of past them
        double center = rowWidth >= v.rowMaxX - v.rowMinX ? (v.rowMinX + v.rowMaxX) / 2
                : Math.clamp(wanted, v.rowMinX + rowWidth / 2, v.rowMaxX - rowWidth / 2);
        double wordX = center - rowWidth / 2;
        for (int i = 0; i < words.size(); i++) {
            String text = texts.get(i);
            WordState state = new WordState();
            int order = counters[1]++;
            float layer = rowLayer; // whole rows move together
            for (int k = 0; k < text.length(); k++) {
                String ch = text.substring(k, k + 1);
                int[] spanState = {counters[2], counters[3]};
                int span = Lyrics.spanStep(text.charAt(k), spanState);
                counters[2] = spanState[0];
                counters[3] = spanState[1];
                if (ch.isBlank()) {
                    counters[0]++;
                    continue;
                }
                GlyphCache.Entry letter = GlyphCache.get(ch, fontPx); // usually already drawn in the background
                GpuImage image = letter.image();
                double gx = wordX + TextArt.lyricWidth(text.substring(0, k), fontPx) / (double) RES + spacing * k;
                sink.add(gx + (image.width / 2.0 - letter.pad()) / RES, top + (image.height / 2.0 - letter.pad()) / RES,
                        words.get(i), state, nouns[Math.min(counters[0], nouns.length - 1)], vocals[Math.min(counters[0], vocals.length - 1)], span, order, layer, image);
                v.glyphs.getLast().effects = letter.effects();
            }
            wordX += TextArt.lyricWidth(text + " ", fontPx) / (double) RES + spacing * text.length();
            counters[0]++;
        }
    }
}
