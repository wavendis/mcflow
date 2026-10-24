package dev.mcflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Tracks what Windows' media controls (Spotify, browsers, ...) report as playing, via a PowerShell {@link Helper}. */
public final class NowPlaying {
    public record Track(String title, String artist, long durationMs) {}

    private static volatile Track track;
    private static volatile long positionMs;
    private static volatile long sampledAt;
    private static volatile boolean playing;
    /** The track's cover (null if it has none) and its most striking colour, for an automatic accent. */
    private static volatile BufferedImage cover;
    private static volatile Integer coverColor;
    private static String coverPath;
    /** Apps with a media session right now (Windows media IDs), and the source choice last written for the helper. */
    private static volatile List<String> apps = List.of();
    private static String writtenSource;

    private NowPlaying() {}

    public static Track track() {
        return track;
    }

    /** Current playback position, interpolated between the helper's samples. */
    public static long positionMs() {
        return playing ? positionMs + (System.currentTimeMillis() - sampledAt) : positionMs;
    }

    /** Tells the helper which app to follow (it reads source.txt next to its script every poll). */
    private static void writeSource() {
        String source = Settings.get().musicSource;
        if (source.equals(writtenSource)) return;
        try {
            Files.writeString(McFlow.configDir().resolve("mcflow/source.txt"), source);
            writtenSource = source;
        } catch (Exception e) {
            McFlow.LOGGER.warn("Couldn't save the music source choice", e);
        }
    }

    /** Sends "play" (toggle), "next" or "previous" to the followed app; the helper picks it up within 50 ms. */
    public static void control(String command) {
        try {
            Path dir = McFlow.configDir().resolve("mcflow"), tmp = dir.resolve("command.tmp");
            Files.writeString(tmp, command);
            // moved into place whole, so the helper never reads half a file
            Files.move(tmp, dir.resolve("command.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            McFlow.LOGGER.warn("Couldn't send media command {}", command, e);
        }
    }

    public static List<String> apps() {
        return apps;
    }

    /** A readable name for a Windows media ID: "Spotify.exe" -> "Spotify", "308046B0AF4A39CB" -> "Firefox"... */
    public static String appName(String id) {
        String name = id.substring(Math.max(id.lastIndexOf('\\'), id.lastIndexOf('!')) + 1).replaceFirst("(?i)\\.exe$", "");
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "308046b0af4a39cb" -> "Firefox";
            case "msedge" -> "Edge";
            case "chrome" -> "Chrome";
            case "opera" -> "Opera";
            default -> name.isEmpty() ? id : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        };
    }

    public static BufferedImage cover() {
        return cover;
    }

    public static Integer coverColor() {
        return coverColor;
    }

    public static boolean playing() {
        return playing;
    }

    public static void start() {
        if (!Helper.start("nowplaying.ps1", NowPlaying::update)) {
            McFlow.LOGGER.warn("Now-playing detection only works on Windows");
        }
    }

    private static void update(String line) {
        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        if (o.has("apps")) apps = o.getAsJsonArray("apps").asList().stream().map(e -> e.getAsString()).toList();
        writeSource();
        if (!o.has("title")) {
            track = null;
            playing = false;
            return;
        }
        Track t = new Track(o.get("title").getAsString(), o.get("artist").getAsString(), o.get("dur").getAsLong());
        if (!t.equals(track)) {
            track = t;
            Lyrics.fetch(t);
        }
        positionMs = o.get("pos").getAsLong();
        sampledAt = System.currentTimeMillis();
        playing = o.get("playing").getAsBoolean();
        String art = o.has("art") && !o.get("art").isJsonNull() ? o.get("art").getAsString() : null;
        if (!Objects.equals(art, coverPath)) {
            coverPath = art;
            BufferedImage image = null;
            try {
                if (art != null) image = ImageIO.read(new File(art));
            } catch (Exception e) {
                McFlow.LOGGER.warn("Couldn't read cover art {}", art, e);
            }
            cover = image;
            coverColor = image == null ? null : TextArt.dominantColor(image);
        }
    }
}
