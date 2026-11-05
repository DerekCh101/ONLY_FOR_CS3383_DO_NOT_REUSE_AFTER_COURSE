/** Persist secrets in the OS user environment, not in the project folder. */
package org.ashoreprospect.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detect OS, then persist:
 * <ul>
 *   <li>Windows: {@code setx} into the current-user environment (no {@code /M}),
 *       plus {@code %APPDATA%\\ashore-prospect\\user.env} so Node can read it
 *       without a logoff.</li>
 *   <li>Linux: {@code ~/.config/ashore-prospect/user.env} (or {@code $XDG_CONFIG_HOME}).</li>
 * </ul>
 * Never writes under the git working directory.
 * The current JVM also keeps an in-process overlay so this run sees the value
 * immediately; restart Node after save so it reloads.
 */
public final class OsEnv {

    private static final Map<String, String> OVERLAY = new ConcurrentHashMap<>();

    private OsEnv() {}

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    public static String get(String name) {
        if (name == null || name.isBlank()) return "";
        String overlay = OVERLAY.get(name);
        if (overlay != null && !overlay.isBlank()) return overlay;
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) return env.trim();
        String fromFile = userFileGet(name);
        return fromFile == null ? "" : fromFile;
    }

    public static void persist(String name, String value) {
        if (name == null || name.isBlank()) return;
        String v = value == null ? "" : value;
        if (v.isBlank()) OVERLAY.remove(name);
        else OVERLAY.put(name, v);
        userFilePut(name, v);
        if (isWindows()) windowsSetx(name, v);
    }

    public static void clear(String name) {
        persist(name, "");
    }

    /** User-config env file. Never the project folder. */
    public static Path userEnvFile() {
        if (isWindows()) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null && !appdata.isBlank()) {
                return Path.of(appdata, "ashore-prospect", "user.env");
            }
            return Path.of(System.getProperty("user.home", "."), "AppData", "Roaming",
                    "ashore-prospect", "user.env");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home", "."), ".config");
        return base.resolve("ashore-prospect").resolve("user.env");
    }

    private static void windowsSetx(String name, String value) {
        try {
            new ProcessBuilder("setx", name, value == null ? "" : value)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            System.err.println("setx " + name + " failed: " + e.getMessage());
        }
    }

    private static String userFileGet(String name) {
        String v = userFileLoad().get(name);
        return v == null ? "" : v;
    }

    private static void userFilePut(String name, String value) {
        Map<String, String> all = userFileLoad();
        if (value == null || value.isBlank()) all.remove(name);
        else all.put(name, value);
        Path p = userEnvFile();
        try {
            Files.createDirectories(p.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Ashore Prospect user environment. Outside the git repo.\n");
            for (Map.Entry<String, String> e : all.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("user env write failed: " + e.getMessage());
        }
    }

    private static Map<String, String> userFileLoad() {
        Map<String, String> out = new LinkedHashMap<>();
        Path p = userEnvFile();
        if (!Files.isRegularFile(p)) return out;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int i = s.indexOf('=');
                if (i < 1) continue;
                out.put(s.substring(0, i).trim(), s.substring(i + 1));
            }
        } catch (IOException ignored) {
        }
        return out;
    }
}
