/** Host-only SMTP settings for password-reset mail. */
package org.ashoreprospect.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Host-only SMTP. Host/user/from/pass live in OS user env
 * ({@code SMTP_HOST}, {@code SMTP_PORT}, {@code SMTP_USER}, {@code SMTP_FROM}, {@code SMTP_PASS}).
 * Windows uses {@code setx}; Linux/Windows also write a user-config {@code user.env}
 * outside the git repo so Node can read it. Leftover {@code .scheduler/smtp.*} is read once.
 */
public final class SmtpSettings {

    private SmtpSettings() {}

    public static Path file() {
        return Endpoints.dir().resolve("smtp.txt");
    }

    public static Path envFile() {
        return Endpoints.dir().resolve("smtp.env");
    }

    public static boolean configured() {
        Map<String, String> m = load();
        return !blank(m.get("host")) && !blank(m.get("user")) && hasPass();
    }

    public static boolean hasPass() {
        return !blank(passValue());
    }

    public static Map<String, String> load() {
        Map<String, String> file = loadLegacyFile();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("host", first(OsEnv.get("SMTP_HOST"), file.get("host"), "smtp.gmail.com"));
        out.put("port", first(OsEnv.get("SMTP_PORT"), file.get("port"), "587"));
        out.put("user", first(OsEnv.get("SMTP_USER"), file.get("user"), ""));
        out.put("pass", passValue());
        out.put("from", first(OsEnv.get("SMTP_FROM"), file.get("from"), ""));
        return out;
    }

    public static void save(String host, String port, String user, String pass, String from)
            throws IOException {
        String h = nz(host).isBlank() ? "smtp.gmail.com" : nz(host);
        String po = nz(port).isBlank() ? "587" : nz(port);
        String u = nz(user);
        String f = nz(from).isBlank() ? u : nz(from);
        String pw = nz(pass).replaceAll("\\s+", "");
        if (pw.isBlank()) pw = passValue().replaceAll("\\s+", "");
        OsEnv.persist("SMTP_HOST", h);
        OsEnv.persist("SMTP_PORT", po);
        OsEnv.persist("SMTP_USER", u);
        OsEnv.persist("SMTP_FROM", f);
        if (!pw.isBlank()) OsEnv.persist("SMTP_PASS", pw);
        Files.deleteIfExists(file());
        Files.deleteIfExists(envFile());
    }

    public static void clear() throws IOException {
        OsEnv.clear("SMTP_HOST");
        OsEnv.clear("SMTP_PORT");
        OsEnv.clear("SMTP_USER");
        OsEnv.clear("SMTP_FROM");
        OsEnv.clear("SMTP_PASS");
        Files.deleteIfExists(file());
        Files.deleteIfExists(envFile());
    }

    private static String passValue() {
        String env = OsEnv.get("SMTP_PASS");
        if (!blank(env)) return env.replaceAll("\\s+", "");
        String leftover = readLegacyEnvKey("SMTP_PASS");
        if (!blank(leftover)) return leftover.replaceAll("\\s+", "");
        return nz(loadLegacyFile().get("pass")).replaceAll("\\s+", "");
    }

    private static Map<String, String> loadLegacyFile() {
        Map<String, String> out = new LinkedHashMap<>();
        Path p = file();
        if (!Files.isRegularFile(p)) return out;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int i = s.indexOf('=');
                if (i < 1) continue;
                out.put(s.substring(0, i).trim().toLowerCase(Locale.ROOT), s.substring(i + 1).trim());
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static String readLegacyEnvKey(String key) {
        Path p = envFile();
        if (!Files.isRegularFile(p)) return "";
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.startsWith(key + "=")) continue;
                return s.substring(key.length() + 1).trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private static String first(String... vals) {
        for (String v : vals) {
            if (!blank(v)) return v.trim();
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
