package dev.mcflow;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Runs a bundled PowerShell script in the background and feeds each line it prints to a consumer. Windows only. */
final class Helper {
    private static final List<Process> RUNNING = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> RUNNING.forEach(Process::destroy)));
    }

    private Helper() {}

    /** Starts {@code assets/mcflow/<script>}; returns false when not on Windows. */
    static boolean start(String script, Consumer<String> onLine) {
        return start(script, onLine, problem -> {});
    }

    /**
     * Like {@link #start(String, Consumer)}; {@code onProblem} hears, in words, why the script isn't working when
     * PowerShell can't be started or closes without printing anything (blocked by antivirus or a Windows policy...).
     */
    static boolean start(String script, Consumer<String> onLine, Consumer<String> onProblem) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return false;
        Thread t = new Thread(() -> run(script, onLine, onProblem), "McFlow " + script);
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Keeps the helper running if it ever dies. */
    private static void run(String script, Consumer<String> onLine, Consumer<String> onProblem) {
        while (true) {
            try (InputStream in = Helper.class.getResourceAsStream("/assets/mcflow/" + script)) {
                // A visible script file run with -File, not -EncodedCommand: antivirus (Avast) blocks/kills
                // hidden encoded PowerShell as a malware pattern.
                Path file = McFlow.configDir().resolve("mcflow/" + script);
                Files.createDirectories(file.getParent());
                Files.write(file, in.readAllBytes());
                // PowerShell's own error messages, kept for the player to send along when detection doesn't work
                Path errors = file.resolveSibling(script.replace(".ps1", "-errors.log"));
                // absolute path: PATH isn't guaranteed to contain PowerShell (e.g. some launchers)
                String powershell = System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
                // (Java 8 way of getting the game's process id: the 1.8.9 build runs on Java 8)
                String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                Process p;
                try {
                    p = new ProcessBuilder(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", file.toString(),
                            "-ParentPid", pid)
                            .redirectError(ProcessBuilder.Redirect.to(errors.toFile()))
                            .start();
                } catch (Exception e) {
                    onProblem.accept("couldn't start PowerShell (" + e.getMessage() + ")");
                    throw e;
                }
                RUNNING.add(p);
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                boolean printed = false;
                for (String line; (line = reader.readLine()) != null; ) {
                    printed = true;
                    onLine.accept(line);
                }
                RUNNING.remove(p);
                int code = p.waitFor();
                if (!printed) onProblem.accept(whyItStopped(errors, code));
                McFlow.LOGGER.warn("{} exited with code {}, restarting{}", script, code, printed ? "" : ": " + whyItStopped(errors, code));
            } catch (Exception e) {
                McFlow.LOGGER.error("{} failed, restarting", script, e);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Why PowerShell closed before saying anything: the first line of its error output (the message; the lines after
     * it point at the script), or a guess when it said nothing at all, which is what being killed by antivirus looks like.
     */
    static String whyItStopped(Path errors, int code) {
        try {
            for (String line : new String(Files.readAllBytes(errors)).split("\\R")) { // PowerShell writes errors in the system's code page
                String message = line.trim();
                if (!message.isEmpty() && !message.startsWith("At ") && !message.startsWith("+")) return "PowerShell stopped: " + message;
            }
        } catch (Exception ignored) {
            // no error file: fall through to the guess
        }
        return "PowerShell keeps closing (exit code " + code + "): antivirus or a Windows policy may be blocking it";
    }

    /** Self-check: java -ea -cp <classes> dev.mcflow.Helper */
    public static void main(String[] args) throws Exception {
        Path errors = Files.createTempFile("mcflow", ".log");
        Files.write(errors, "Unable to find type [Windows.Media.Control.Foo].\r\nAt C:\\x\\nowplaying.ps1:18 char:9\r\n+ $null = [Windows...\r\n".getBytes());
        assert whyItStopped(errors, 1).equals("PowerShell stopped: Unable to find type [Windows.Media.Control.Foo].") : whyItStopped(errors, 1);
        Files.write(errors, new byte[0]);
        assert whyItStopped(errors, -1).startsWith("PowerShell keeps closing (exit code -1)") : whyItStopped(errors, -1);
        Files.delete(errors);
        assert whyItStopped(errors, 1).startsWith("PowerShell keeps closing") : "missing file";
        System.out.println("ok");
    }
}
