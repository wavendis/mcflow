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

    private static final Font CAVEAT = loadFont("/assets/mcflow/caveat.ttf");
    private static final Font SYSTEM_UI = pickUiFont();
    private static final Map<String, Font> FONTS = new ConcurrentHashMap<>();
    private static List<String> families;

    private TextArt() {}

    /** A word image plus where to place it: the glyphs start {@code pad} px in, the next word goes {@code advance} px on. */
    public record Art(BufferedImage image, int pad, int advance) {}

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
        return new Art(out, pad, fm.stringWidth(text + " "));
    }

    /** Width of lyric text at a font size, to place letters one by one. */
    public static int lyricWidth(String text, float px) {
        return metrics(lyricFont().deriveFont(px)).stringWidth(text);
    }

    /** Glyph height for a lyric font size, to line up rows. */
    public static int lyricAscent(float px) {
        return metrics(lyricFont().deriveFont(px)).getAscent();
    }

    /** The now-playing pill: accent dot, the text, and {@code eqWidth} px of room on the right for the live equalizer. */
    public static BufferedImage pill(String text, int height, int maxTextWidth, int eqWidth) {
        Font font = uiFont().deriveFont(height * 0.42f);
        FontMetrics fm = metrics(font);
        int padX = Math.round(height * 0.55f), dot = Math.round(height * 0.32f), gap = Math.round(height * 0.3f);
        String shown = fit(text, fm, maxTextWidth);
        int w = padX + dot + gap + fm.stringWidth(shown) + (eqWidth > 0 ? gap + eqWidth : 0) + padX;

        BufferedImage img = new BufferedImage(w, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, height - 1, height, height);
        g.setColor(new Color(18, 18, 28, Math.round(Math.clamp(Settings.get().pillOpacity, 0f, 1f) * 255)));
        g.fill(shape);
        g.setColor(PILL_BORDER);
        g.draw(shape);
        g.setColor(new Color(Settings.get().accent));
        g.fillOval(padX, (height - dot) / 2, dot, dot);
        g.setFont(font);
        g.setColor(PILL_TEXT);
        g.drawString(shown, padX + dot + gap, (height - fm.getHeight()) / 2 + fm.getAscent());
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
        return g;
    }

    static FontMetrics metrics(Font font) {
        Graphics2D g = smooth(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        FontMetrics fm = g.getFontMetrics(font);
        g.dispose();
        return fm;
    }

    /** Two box-blur passes over the alpha channel, roughly a gaussian. */
    private static BufferedImage blurAlpha(BufferedImage src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h];
        for (int i = 0; i < a.length; i++) a[i] = src.getRGB(i % w, i / w) >>> 24;
        for (int pass = 0; pass < 2; pass++) {
            a = boxBlur(a, w, h, r, true);
            a = boxBlur(a, w, h, r, false);
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < a.length; i++) out.setRGB(i % w, i / w, a[i] << 24);
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
