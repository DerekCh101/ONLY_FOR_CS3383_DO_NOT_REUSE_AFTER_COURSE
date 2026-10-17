/** Host-only SMTP settings for password-reset mail. */
package org.ashoreprospect.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Host-only SMTP. Host/user/from live in {@code .scheduler/smtp.txt}.
 * The App password is written to {@code .scheduler/smtp.env} so Node can read it.
 * No Windows {@code setx} / registry write (Kaspersky flags that).
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
        if (!blank(System.getenv("SMTP_PASS"))) return true;
        String fromEnvFile = readEnvKey("SMTP_PASS");
        return !blank(fromEnvFile) || !blank(load().get("pass"));
    }

    public static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("host", "smtp.gmail.com");
        out.put("port", "587");
        out.put("user", "");
        out.put("pass", "");
        out.put("from", "");
        Path p = file();
        if (!Files.isRegularFile(p)) return out;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int i = s.indexOf('=');
                if (i < 1) continue;
                out.put(s.substring(0, i).trim().toLowerCase(), s.substring(i + 1).trim());
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static void save(String host, String port, String user, String pass, String from)
            throws IOException {
        Files.createDirectories(file().getParent());
        String h = nz(host).isBlank() ? "smtp.gmail.com" : nz(host);
        String po = nz(port).isBlank() ? "587" : nz(port);
        String u = nz(user);
        String f = nz(from).isBlank() ? u : nz(from);
        String pw = nz(pass).replaceAll("\\s+", "");
        if (pw.isBlank()) {
            pw = nz(readEnvKey("SMTP_PASS")).replaceAll("\\s+", "");
            if (pw.isBlank()) pw = nz(System.getenv("SMTP_PASS")).replaceAll("\\s+", "");
        }
        Files.writeString(file(),
                "host=" + h + "\nport=" + po + "\nuser=" + u + "\nfrom=" + f + "\n",
                StandardCharsets.UTF_8);
        if (!pw.isBlank()) {
            Files.writeString(envFile(), "SMTP_PASS=" + pw + "\n", StandardCharsets.UTF_8);
        }
    }

    public static void clear() throws IOException {
        Files.deleteIfExists(file());
        Files.deleteIfExists(envFile());
    }

    private static String readEnvKey(String key) {
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

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
