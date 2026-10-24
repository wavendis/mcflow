package dev.mcflow;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import org.joml.Matrix3x2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "now playing" pill plus the big word-by-word lyric. Everything is drawn in physical screen pixels from
 * anti-aliased {@link TextArt} images, so it stays sharp at any GUI scale and doesn't look like Minecraft's own UI.
 * How it looks and where it goes comes from {@link Settings}.
 */
public final class LyricsHud implements HudElement {
    /** One letter of a lyric line. */
    private static final class Glyph {
        final GpuImage image;
        final Lyrics.Word word;
        final WordState state;
        /** Part of a noun: pulses with the beat when pulses are set to nouns. */
        final boolean noun;
        /** Part of a sung sound like "oh" or "ah": always pulses with the beat. */
        final boolean vocal;
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

        Glyph(GpuImage image, Lyrics.Word word, WordState state, boolean noun, boolean vocal, int order, float layer, float across, float up, float lx, float ly) {
            this.image = image;
            this.word = word;
            this.state = state;
            this.noun = noun;
            this.vocal = vocal;
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
    }

    /** One lyric line being shown, laid out and pinned where the player was looking when it appeared. */
    private static final class View {
        final Lyrics.Line line;
        final int version, height;
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

        View(Lyrics.Line line, int version, int height) {
            this.line = line;
            this.version = version;
            this.height = height;
        }

        void close() {
            glyphs.forEach(gl -> gl.image.close());
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
    /** 0..1 how far the lyrics are softened because a menu is open; and this frame's dimming and blur radius (px). */
    private float menuFade, dim = 1, blurPx;
    private long menuFrameNanos;
    /** Lines on screen right now: usually one, two while one fades out and the next fades in. */
    private final Map<Lyrics.Line, View> views = new IdentityHashMap<>();

    @Override
    public void render(GuiGraphics g, DeltaTracker deltaTracker) {
        Settings s = Settings.get();
        NowPlaying.Track track = NowPlaying.track();
        List<Lyrics.Line> lines = Lyrics.current();
        long pos = NowPlaying.positionMs() + s.offsetMs;
        updateMenuFade(s);
        boolean playing = NowPlaying.playing();
        if (preview && (track == null || lines.isEmpty())) {
            track = DEMO_TRACK;
            lines = DEMO;
            pos = System.currentTimeMillis() % 16_000;
            playing = true;
        }
        if (track == null) {
            closeViews(List.of());
            return;
        }

        Window window = Minecraft.getInstance().getWindow();
        int w = window.getWidth(), h = window.getHeight();
        float px = 1f / window.getGuiScale();
        g.pose().pushMatrix();
        g.pose().scale(px, px); // 1 unit = 1 physical pixel from here on

        if (s.showPill) drawPill(g, w, h, window.getGuiScale(), track, playing);

        // every line that's fading in, showing or fading out; older ones first so the newest is on top
        List<Lyrics.Line> visible = new ArrayList<>();
        for (Lyrics.Line line : lines) {
            if (line.startMs() - s.fadeMs <= pos && pos < line.endMs() + fadeOutTotal(line, s)) visible.add(line);
        }
        closeViews(visible);
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        visible.sort(Comparator.comparingLong(Lyrics.Line::startMs));
        for (int k = 0; k < visible.size(); k++) {
            Lyrics.Line line = visible.get(k);
            View view = views.get(line);
            if (view == null || view.version != Settings.version() || view.height != h) {
                if (view != null) view.close();
                view = layout(line, camera, w, h);
                views.put(line, view);
            }
            // a newer line is coming in where this one is: slide this one up out of its way (eased) while it fades
            float push = 0;
            if (k + 1 < visible.size()) {
                float t = Math.clamp((pos + s.fadeMs - visible.get(k + 1).startMs()) / (float) Math.max(250, s.fadeMs), 0f, 1f);
                push = (1 - (1 - t) * (1 - t) * (1 - t)) * view.lineHeight;
            }
            drawLine(g, w, h, view, camera, pos, push);
        }
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
        Screen screen = Minecraft.getInstance().screen;
        boolean menu = s.menuBlur && screen != null && !(screen instanceof ChatScreen) && !(screen instanceof SettingsScreen);
        menuFade += ((menu ? 1 : 0) - menuFade) * (1 - (float) Math.exp(-dt / 90));
        dim = 1 - MENU_DIM * menuFade;
        blurPx = Minecraft.getInstance().getWindow().getHeight() * MENU_BLUR * menuFade;
    }

    private static long fadeOutTotal(Lyrics.Line line, Settings s) {
        return s.fadeOutMs + (s.fadeOut == Settings.FadeOut.WORDS ? (line.words().size() - 1) * WORD_OUT_STAGGER_MS : 0);
    }

    /** Frees the textures of lines that are no longer shown. */
    private void closeViews(List<Lyrics.Line> keep) {
        views.entrySet().removeIf(e -> {
            if (keep.stream().anyMatch(l -> l == e.getKey())) return false;
            e.getValue().close();
            return true;
        });
    }

    /** The pill: just what's playing (title · artist), with the beat equalizer on the right. */
    private void drawPill(GuiGraphics g, int w, int h, int guiScale, NowPlaying.Track track, boolean playing) {
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
        String key = text + "|" + height + "|" + w + "|" + Settings.version();
        if (!key.equals(pillKey)) {
            if (pill != null) pill.close();
            pill = new GpuImage(TextArt.pill(text, height, Math.round(w * 0.45f), eqWidth));
            pillKey = key;
        }

        int x = (w - pill.width) / 2;
        // at the bottom it sits above the hotbar (22 GUI pixels tall) instead of on it
        int y = s.pillAtBottom ? h - height - Math.round(32 * guiScale) : Math.round(h * 0.014f);
        pill.drawSoft(g, x, y, Math.round(255 * dim) << 24 | 0xFFFFFF, blurPx, menuFade);
        if (s.equalizer) drawEqualizer(g, x + pill.width - Math.round(height * 0.55f) - eqWidth, y + height / 2, height, eqWidth, playing, dt);
    }

    /** Three bars driven by the music: bass loudness, the beat, and a mix, easing so they bounce rather than flicker. */
    private void drawEqualizer(GuiGraphics g, int eqX, int mid, int height, int eqWidth, boolean playing, float dt) {
        float energy = Beat.energy(), beat = Beat.pulse();
        float[] target = playing ? new float[]{energy, Math.max(beat, energy * 0.6f), energy * 0.5f + beat * 0.6f} : new float[3];
        float ease = 1 - (float) Math.exp(-dt / 60);
        int barW = Math.max(2, eqWidth / 5), color = Math.round(255 * dim) << 24 | (Settings.get().accent & 0xFFFFFF);
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
    private void drawLine(GuiGraphics g, int w, int h, View v, Camera camera, long pos, float push) {
        Settings s = Settings.get();
        Settings.Placement mode = s.placement;
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
                    s.screenY * h + gl.ly - push - turnPitch * h / 90f * gl.layer * lag, 0, 0};
            gl.screen = p;
            occlude(gl, s, camera, world, p != null);
            if (mode == Settings.Placement.SCREEN) {
                gl.view = 1;
            } else {
                // 0 behind the player or off screen; fades near the edges (and, pinned, when you walk into it)
                gl.view = p == null ? 0 : Math.clamp(Math.min(Math.min(p[0], w - p[0]), Math.min(p[1], h - p[1])) / edge, 0f, 1f);
                if (p != null && mode == Settings.Placement.PINNED) gl.view *= (float) Math.clamp((p[3] - NEAR_HIDE) / (NEAR_FULL - NEAR_HIDE), 0, 1);
            }
            gl.state.view = Math.max(gl.state.view, gl.view);
        }

        float fadeIn = Math.max(1, s.fadeMs), fadeOut = Math.max(1, s.fadeOutMs), rise = h * 0.008f;
        for (Glyph gl : v.glyphs) {
            WordState word = gl.state;
            long appearAt = s.reveal == Settings.Reveal.LINE ? v.line.startMs() : gl.word.startMs();
            long leaveAt = v.line.endMs() + (s.fadeOut == Settings.FadeOut.WORDS ? gl.order * WORD_OUT_STAGGER_MS : 0);
            float in = Math.clamp((pos + s.fadeMs - appearAt) / fadeIn, 0f, 1f);
            float out = 1 - Math.clamp((pos - leaveAt) / fadeOut, 0f, 1f);
            float a = in * out;
            if (mode != Settings.Placement.SCREEN) {
                if (a >= 1 && word.view >= 0.5f) word.seen = true;
                // once a shown word has completely left view it stays gone, even if you look back; part-visible words stay
                if (word.seen && word.view <= 0.01f) word.gone = true;
                if (word.gone) continue;
            }
            float[] p = gl.screen;
            a *= gl.view * gl.occlusion; // blocks only hide/tint; they don't count as the word leaving view
            int alpha = Math.round(a * 255 * dim);
            if (alpha < 4) continue;

            // rise into place while appearing, drift up while fading out (screen y points down)
            float lift = s.floatUp ? (1 - in) * rise - (1 - out) * rise : 0;
            int color = gl.tintAmount > 0 ? lerpColor(s.lyricColor, gl.tint, gl.tintAmount) : s.lyricColor;
            float pulse = 1;
            // pop and flash on each beat; sung "oh"/"ah" sounds always do
            if (gl.vocal || s.pulse == Settings.Pulse.ALL || (s.pulse == Settings.Pulse.NOUNS && gl.noun)) {
                float beat = Beat.pulse();
                pulse = 1 + BEAT_GROW * s.pulseStrength * beat;
                color = lerpColor(color, s.pulseColor, Math.min(1, beat * 0.7f * s.pulseStrength));
            }
            float hw = gl.image.width / 2f, hh = gl.image.height / 2f;
            float imageScale = 1; // screen px per letter-image px, to blur by the same screen distance in every mode
            g.pose().pushMatrix();
            switch (mode) {
                case WORLD -> {
                    // follow the curve: the row runs along the sphere's latitude, so aim the letter along it on screen
                    float[] next = project(camera, onSphere(v, camera, gl.across + (float) Math.toRadians(turnYaw) * gl.layer * lag + 0.01f,
                            gl.up - (float) Math.toRadians(turnPitch) * gl.layer * lag + push / v.pixelsPerRadian), w, h);
                    float angle = next == null ? 0 : (float) Math.atan2(next[1] - p[1], next[0] - p[0]);
                    float zoom = Math.clamp(p[2] * (float) SPHERE_RADIUS / v.pixelsPerRadian, 0.2f, 4f) * pulse; // ~1 unless FOV changed
                    g.pose().translate(p[0], p[1] + lift * zoom);
                    g.pose().rotate(angle);
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
                    g.pose().translate(p[0], p[1] + lift * scale);
                    g.pose().mul(new Matrix3x2f(ax, ay, bx, by, 0, 0));
                    imageScale = (float) Math.hypot(bx, by);
                }
                case SCREEN -> {
                    g.pose().translate(p[0], p[1] + lift);
                    g.pose().scale(pulse / RES, pulse / RES);
                    imageScale = pulse / RES;
                }
            }
            gl.image.drawSoft(g, Math.round(-hw), Math.round(-hh), alpha << 24 | (color & 0xFFFFFF), blurPx / Math.max(0.01f, imageScale), menuFade);
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

    private static int lerpColor(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
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
        double x = lx / v.pixelsPerBlock, y = -ly / v.pixelsPerBlock; // blocks along the sign
        Vec3 point = v.anchor.add(v.right.scale(x)).add(v.up.scale(y)).add(v.forward.scale((layer - MEAN_LAYER) * s.parallax * PARALLAX_DEPTH)); // centred on the sign's distance
        Vec3 toSign = v.anchor.subtract(camera.position());
        double distance = Math.abs(toSign.dot(v.forward));
        double bend = s.warp * Math.clamp((WARP_START - distance) / (WARP_START - WARP_FULL), 0, 1);
        if (bend <= 0) return point;
        double fromPlayer = x + toSign.dot(v.right); // sideways distance from the spot on the sign right in front of the player
        return point.subtract(v.forward.scale(Math.min(MAX_BEND, bend * BEND_CURVE * fromPlayer * fromPlayer)));
    }

    private static float wrapDegrees(float degrees) {
        float d = degrees % 360;
        return d >= 180 ? d - 360 : d < -180 ? d + 360 : d;
    }

    /**
     * Lays the line out in screen pixels around its anchor and fixes where it lives: for the sphere, every letter's
     * offset becomes angles in the line's own frame (rows follow rings around {@code up}, spread by 1/cos(up) so letters
     * keep their width); pinned, the offsets are positions on a plane facing where the player looked. Either way the
     * line looks like the flat version when it appears.
     */
    private View layout(Lyrics.Line line, Camera camera, int w, int h) {
        Settings s = Settings.get();
        View v = new View(line, Settings.version(), h);
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
        double margin = w * 0.04, anchorX = s.placement == Settings.Placement.SCREEN ? s.screenX * w : w / 2.0;
        v.rowMinX = -anchorX + margin;
        v.rowMaxX = w - anchorX - margin;

        List<Lyrics.Word> words = line.words();
        float big = h * 0.085f * s.lyricSize, small = h * 0.065f * s.lyricSize;
        int rowGap = Math.round(TextArt.lyricAscent(big) * 1.05f);
        v.lineHeight = rowGap * (s.layout == Settings.Layout.CENTERED ? 2.2f : 1.2f); // only centered stacks two rows
        List<String> tokens = words.stream().flatMap(word -> Arrays.stream(word.text().split(" "))).toList();
        boolean[] nouns = Nouns.find(tokens), vocals = Nouns.vocal(tokens);
        int[] counters = {0, 0}; // [token in the line, word in the line] of the letters being placed
        LetterSink sink = (px, py, word, state, noun, vocal, order, layer, image) -> {
            float up = (float) (-py / v.pixelsPerRadian); // screen y points down
            float across = (float) (px / v.pixelsPerRadian / Math.cos(up));
            v.glyphs.add(new Glyph(image, word, state, noun, vocal, order, layer, across, up, (float) px, (float) py));
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
        return v;
    }

    /** Takes a letter whose centre sits at a layout-pixel offset from the line's anchor (y down). */
    private interface LetterSink {
        void add(double px, double py, Lyrics.Word word, WordState state, boolean noun, boolean vocal, int order, float layer, GpuImage image);
    }

    /**
     * World point to screen pixels: {x, y, pixels per block at that depth, depth in blocks}, or null when it's behind
     * the camera.
     * ponytail: drawn as an overlay, so blocks don't hide it; real in-world rendering would need a level render hook per loader.
     */
    private static float[] project(Camera camera, Vec3 point, int w, int h) {
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
                if (ch.isBlank()) {
                    counters[0]++;
                    continue;
                }
                TextArt.Art art = TextArt.word(ch, fontPx);
                GpuImage image = new GpuImage(art.image());
                double gx = wordX + TextArt.lyricWidth(text.substring(0, k), fontPx) / (double) RES + spacing * k;
                sink.add(gx + (image.width / 2.0 - art.pad()) / RES, top + (image.height / 2.0 - art.pad()) / RES,
                        words.get(i), state, nouns[Math.min(counters[0], nouns.length - 1)], vocals[Math.min(counters[0], vocals.length - 1)], order, layer, image);
            }
            wordX += TextArt.lyricWidth(text + " ", fontPx) / (double) RES + spacing * text.length();
            counters[0]++;
        }
    }
}
