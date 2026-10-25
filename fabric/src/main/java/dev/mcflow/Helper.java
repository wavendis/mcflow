package dev.mcflow;

import java.io.BufferedReader;
import java.io.File;
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
        if (!System.getProperty("os.name").toLowerCase().contains("win")) return false;
        Thread t = new Thread(() -> run(script, onLine), "McFlow " + script);
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Keeps the helper running if it ever dies. */
    private static void run(String script, Consumer<String> onLine) {
        while (true) {
            try (InputStream in = Helper.class.getResourceAsStream("/assets/mcflow/" + script)) {
                // A visible script file run with -File, not -EncodedCommand: antivirus (Avast) blocks/kills
                // hidden encoded PowerShell as a malware pattern.
                Path file = McFlow.configDir().resolve("mcflow/" + script);
                Files.createDirectories(file.getParent());
                Files.write(file, in.readAllBytes());
                // absolute path: PATH isn't guaranteed to contain PowerShell (e.g. some launchers)
                String powershell = System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
                // (Java 8 ways of getting the game's process id and discarding stderr: the 1.8.9 build runs on Java 8)
                String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                Process p = new ProcessBuilder(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", file.toString(),
                        "-ParentPid", pid)
                        .redirectError(ProcessBuilder.Redirect.to(new File("NUL")))
                        .start();
                RUNNING.add(p);
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null; ) {
                    onLine.accept(line);
                }
                RUNNING.remove(p);
                McFlow.LOGGER.warn("{} exited with code {}, restarting", script, p.waitFor());
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
}
