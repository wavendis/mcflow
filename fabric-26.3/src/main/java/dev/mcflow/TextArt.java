package dev.mcflow;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paints the HUD with Java2D: real anti-aliasing and any font, instead of Minecraft's pixel font atlas.
 * Images are made at the screen's physical pixel size and only when their content changes.
 */
public final class TextArt {
    static final Color PILL_BORDER = new Color(255, 255, 255, 28);
    static final Color PILL_TEXT = new Color(230, 230, 240);
    /** Padding at the pill's right end, as a share of its height (the equalizer sits just inside it). */
    static final float PILL_RIGHT_PAD = 0.55f;

    private static final Font CAVEAT = loadFont("/assets/mcflow/caveat.ttf");
    private static final Font SYSTEM_UI = pickUiFont();
    private static final Map<String, Font> FONTS = new ConcurrentHashMap<>();
    private static List<String> families;

    private TextArt() {}

    /** A word image plus where to place it: the glyphs start {@code pad} px in, the next word goes {@code advance} px on. */
    /** {@code effects} is the outline/glow layer (same size and pad, drawn untinted underneath), or null without effects. */
    public record Art(BufferedImage image, BufferedImage effects, int pad, int advance) {}

    /** Lyric word(s) in the chosen font, white (tinted when drawn) with a soft shadow, {@code px} tall. */
    public static Art word(String text, float px) {
        Font font = lyricFont().deriveFont(px);
        FontMetrics fm = metrics(font);
        int pad = Math.round(px * 0.3f);
        int w = fm.stringWidth(text) + pad * 2, h = fm.getHeight() + pad * 2;

        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(mask);
        g.setFont(font);
        g.setColor(Color.BLACK);
        g.drawString(text, pad, pad + fm.getAscent());
        g.dispose();
        BufferedImage shadow = blurAlpha(mask, Math.max(1, Math.round(px / 14)));

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        g = smooth(out);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.clamp(Settings.get().shadow, 0f, 1f)));
        g.drawImage(shadow, 0, Math.round(px / 18), null);
        g.setComposite(AlphaComposite.SrcOver);
        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString(text, pad, pad + fm.getAscent());
        g.dispose();
        return new Art(out, effects(text, font, fm, px, pad, w, h, mask), pad, fm.stringWidth(text + " "));
    }

    /**
     * The outline and glow, on their own layer: the letter itself is tinted when drawn (lyric colour, beat flashes),
     * but these keep their own colours.
     */
    private static BufferedImage effects(String text, Font font, FontMetrics fm, float px, int pad, int w, int h, BufferedImage mask) {
        Settings s = Settings.get();
        if (s.glow <= 0 && s.outline <= 0) return null;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(out);
        if (s.glow > 0) {
            BufferedImage halo = blurAlpha(mask, Math.max(1, Math.round(px * 0.12f)));
            int rgb = glowColor() & 0xFFFFFF;
            float strength = 1.6f * Math.min(1, s.glow);
            int[] glowPixels = pixels(halo);
            for (int i = 0; i < glowPixels.length; i++) glowPixels[i] = Math.min(255, Math.round((glowPixels[i] >>> 24) * strength)) << 24 | rgb;
            g.drawImage(halo, 0, 0, null);
        }
        if (s.outline > 0) {
            java.awt.Shape shape = font.createGlyphVector(g.getFontRenderContext(), text).getOutline(pad, pad + fm.getAscent());
            g.setColor(new Color(s.outlineColor));
            g.setStroke(new java.awt.BasicStroke(px * s.outline, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g.draw(shape);
        }
        g.dispose();
        return out;
    }

    /** Width of lyric text at a font size, to place letters one by one. */
    public static int lyricWidth(String text, float px) {
        return metrics(lyricFont().deriveFont(px)).stringWidth(text);
    }

    /** Glyph height for a lyric font size, to line up rows. */
    public static int lyricAscent(float px) {
        return metrics(lyricFont().deriveFont(px)).getAscent();
    }

    /**
     * The now-playing pill: the track's cover (or an accent dot without one), the text, and {@code eqWidth} px of
     * room on the right for the live equalizer.
     */
    public static BufferedImage pill(String text, int height, int maxTextWidth, int eqWidth, BufferedImage cover) {
        Font font = uiFont().deriveFont(height * 0.42f);
        FontMetrics fm = metrics(font);
        int inset = Math.round(height * 0.14f), art = height - 2 * inset;
        int padX = Math.round(height * 0.55f), dot = Math.round(height * 0.32f), gap = Math.round(height * 0.3f);
        if (cover != null) {
            padX = inset + Math.round(height * 0.04f); // the cover sits close to the rounded end
            dot = art;
        }
        String shown = fit(text, fm, maxTextWidth);
        // the right end always has the same padding: the HUD places the live equalizer from it (PILL_RIGHT_PAD)
        int w = padX + dot + gap + fm.stringWidth(shown) + (eqWidth > 0 ? gap + eqWidth : 0) + Math.round(height * PILL_RIGHT_PAD);

        BufferedImage img = new BufferedImage(w, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, height - 1, height, height);
        g.setColor(new Color(18, 18, 28, Math.round(Math.clamp(Settings.get().pillOpacity, 0f, 1f) * 255)));
        g.fill(shape);
        g.setColor(PILL_BORDER);
        g.draw(shape);
        if (cover != null) {
            java.awt.Shape old = g.getClip();
            g.clip(new RoundRectangle2D.Float(padX, inset, art, art, art * 0.5f, art * 0.5f));
            g.drawImage(cover, padX, inset, art, art, null);
            g.setClip(old);
        } else {
            g.setColor(new Color(Settings.get().accent));
            g.fillOval(padX, (height - dot) / 2, dot, dot);
        }
        g.setFont(font);
        g.setColor(PILL_TEXT);
        g.drawString(shown, padX + dot + gap, (height - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    /**
     * The most striking colour of an image (for an accent picked from album art): pixels weighted by how saturated and
     * bright they are, then nudged to stay vivid and readable on dark backgrounds.
     */
    public static int dominantColor(BufferedImage img) {
        double r = 0, g = 0, b = 0, total = 0;
        int step = Math.max(1, Math.max(img.getWidth(), img.getHeight()) / 48); // ~48x48 samples is plenty
        float[] hsb = new float[3];
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int rgb = img.getRGB(x, y);
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
                double weight = hsb[1] * hsb[1] * hsb[2] + 0.001;
                r += ((rgb >> 16) & 0xFF) * weight;
                g += ((rgb >> 8) & 0xFF) * weight;
                b += (rgb & 0xFF) * weight;
                total += weight;
            }
        }
        Color.RGBtoHSB((int) (r / total), (int) (g / total), (int) (b / total), hsb);
        return Color.HSBtoRGB(hsb[0], Math.clamp(hsb[1] * 1.2f, 0.45f, 0.85f), Math.max(hsb[2], 0.85f)) & 0xFFFFFF;
    }

    /** A soft frame: clear in the middle, {@code color} towards the edges. Drawn small and stretched to the screen. */
    public static BufferedImage vignette(int w, int h, int color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        Color edge = new Color(color);
        // a circular gradient squashed to the image's shape, so it hugs all four edges equally
        g.scale(w / (double) h, 1);
        g.setPaint(new java.awt.RadialGradientPaint(h / 2f, h / 2f, h * 0.72f, new float[]{0, 0.55f, 1},
                new Color[]{new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 0),
                        new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 0),
                        new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 220)}));
        g.fillRect(0, 0, h, h);
        g.dispose();
        return img;
    }

    /** Shortens text with "…" until it fits. */
    private static String fit(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) return text;
        while (text.length() > 1 && fm.stringWidth(text + "…") > maxWidth) text = text.substring(0, text.length() - 1);
        return text.strip() + "…";
    }

    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); // cover art shrinks smoothly
        return g;
    }

    /** Font metrics are asked for per letter while laying out a line; making a Graphics each time was the slow part. */
    private static final Map<Font, FontMetrics> METRICS = new ConcurrentHashMap<>();

    static FontMetrics metrics(Font font) {
        return METRICS.computeIfAbsent(font, f -> {
            Graphics2D g = smooth(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
            FontMetrics fm = g.getFontMetrics(f);
            g.dispose();
            return fm;
        });
    }

    /** The pixels of an ARGB image as a live array (no per-pixel getRGB/setRGB calls). */
    static int[] pixels(BufferedImage img) {
        return ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
    }

    /** Two box-blur passes over the alpha channel, roughly a gaussian. */
    private static BufferedImage blurAlpha(BufferedImage src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] in = pixels(src), a = new int[w * h];
        for (int i = 0; i < a.length; i++) a[i] = in[i] >>> 24;
        for (int pass = 0; pass < 2; pass++) {
            a = boxBlur(a, w, h, r, true);
            a = boxBlur(a, w, h, r, false);
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] o = pixels(out);
        for (int i = 0; i < a.length; i++) o[i] = a[i] << 24;
        return out;
    }

    private static int[] boxBlur(int[] in, int w, int h, int r, boolean horizontal) {
        int[] out = new int[in.length];
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int sum = 0;
            for (int i = -r; i <= r; i++) sum += in[idx(Math.clamp(i, 0, len - 1), line, w, horizontal)];
            for (int i = 0; i < len; i++) {
                out[idx(i, line, w, horizontal)] = sum / (2 * r + 1);
                sum += in[idx(Math.min(i + r + 1, len - 1), line, w, horizontal)] - in[idx(Math.max(i - r, 0), line, w, horizontal)];
            }
        }
        return out;
    }

    private static int idx(int along, int line, int w, boolean horizontal) {
        return horizontal ? line * w + along : along * w + line;
    }

    private static Font loadFont(String path) {
        try (InputStream in = TextArt.class.getResourceAsStream(path)) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            McFlow.LOGGER.warn("Couldn't load {}, using a system font", path, e);
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }

    /** The text glow's colour: the album cover's main colour when colours follow the album art, else the picked one. */
    static int glowColor() {
        Settings s = Settings.get();
        Integer fromArt = s.albumArt && s.accentFromArt ? NowPlaying.coverColor() : null;
        return fromArt != null ? fromArt : s.glowColor;
    }

    /** The lyric font picked in the settings. */
    static Font lyricFont() {
        return font(Settings.get().lyricFont, CAVEAT);
    }

    /** The pill/panel font picked in the settings. */
    static Font uiFont() {
        return font(Settings.get().uiFont, SYSTEM_UI);
    }

    /** A font by family name; the built-in/auto names (and fonts that aren't installed) fall back to {@code fallback}. */
    static Font font(String family, Font fallback) {
        if (family.equals(Settings.BUILT_IN_FONT)) return CAVEAT;
        if (family.equals(Settings.AUTO_FONT) || !families().contains(family)) return fallback;
        return FONTS.computeIfAbsent(family, f -> new Font(f, Font.PLAIN, 12));
    }

    /** Fonts to choose from: the built-in ones first, then everything installed. */
    static synchronized List<String> families() {
        if (families == null) {
            List<String> all = new ArrayList<>(List.of(Settings.BUILT_IN_FONT, Settings.AUTO_FONT));
            all.addAll(List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            families = List.copyOf(all);
        }
        return families;
    }

    /** A clean UI sans from the OS (no bundling needed); Java's logical sans as a last resort. */
    private static Font pickUiFont() {
        Set<String> installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : List.of("Segoe UI Semibold", "Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Roboto")) {
            if (installed.contains(family)) return new Font(family, Font.PLAIN, 12);
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 12);
    }
}
