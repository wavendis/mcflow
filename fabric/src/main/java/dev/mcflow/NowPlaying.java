package dev.mcflow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Tracks what Windows' media controls (Spotify, browsers, ...) report as playing, via a PowerShell {@link Helper}. */
public final class NowPlaying {
    public record Track(String title, String artist, long durationMs) {}

    private static volatile Track track;
    private static volatile long positionMs;
    private static volatile long sampledAt;
    private static volatile boolean playing;

    private NowPlaying() {}

    public static Track track() {
        return track;
    }

    /** Current playback position, interpolated between the helper's samples. */
    public static long positionMs() {
        return playing ? positionMs + (System.currentTimeMillis() - sampledAt) : positionMs;
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
    }
}
