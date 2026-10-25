package dev.mcflow;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Asks GitHub once per game whether a newer Mc'Flow release is out, for the settings panel's "New release available"
 * badge. Quietly does nothing when offline, rate-limited, or when this build doesn't know its own version.
 */
final class UpdateCheck {
    static final String RELEASES = "https://github.com/wavendis/mcflow/releases";
    private static final String LATEST = "https://api.github.com/repos/wavendis/mcflow/releases/latest";

    /** The newer release's tag (e.g. "v1.4.0"), or null while unknown / when this is the latest. */
    private static volatile String newer;
    private static boolean started;

    private UpdateCheck() {}

    /** Starts the check in the background, the first time only. */
    static synchronized void start() {
        if (started) return;
        started = true;
        String current = currentVersion();
        if (current == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection c = Net.open(LATEST);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;
                try (InputStream in = c.getInputStream()) {
                    JsonElement tag = new JsonParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject().get("tag_name");
                    if (tag != null && compare(tag.getAsString(), current) > 0) newer = tag.getAsString();
                }
            } catch (Exception e) {
                McFlow.LOGGER.info("Couldn't check for a new Mc'Flow release: {}", e.toString());
            }
        });
    }

    static String newer() {
        return newer;
    }

    /** Opens the releases page in the default browser (Java's own browse call doesn't work inside Minecraft). */
    static void openReleases() {
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", RELEASES).start();
        } catch (Exception e) {
            McFlow.LOGGER.warn("Couldn't open {}", RELEASES, e);
        }
    }

    /** This build's version, written into assets/mcflow/version.txt by each loader's build; null if it isn't there. */
    private static String currentVersion() {
        try (InputStream in = UpdateCheck.class.getResourceAsStream("/assets/mcflow/version.txt")) {
            if (in == null) return null;
            String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return v.isEmpty() || v.contains("$") ? null : v; // "${version}": not filled in by the build
        } catch (Exception e) {
            return null;
        }
    }

    /** Compares versions like "v1.10.0" and "1.9.2" number by number (a leading "v" and any "-suffix" are ignored). */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[-+]")[0].split("\\."), y = b.replaceFirst("^[vV]", "").split("[-+]")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void main(String[] args) {
        assert compare("v1.4.0", "1.3.0") > 0;
        assert compare("v1.10.0", "1.9.2") > 0 : "numbers, not text";
        assert compare("v1.3.0", "1.3.0") == 0;
        assert compare("1.3", "1.3.0") == 0;
        assert compare("v1.3.0-beta", "1.3.1") < 0;
        System.out.println("ok");
    }
}
