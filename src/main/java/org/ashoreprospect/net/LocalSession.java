/** Remember-me token and prefs stored on this PC. */
package org.ashoreprospect.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Remembered Node session for this PC. Lives under {@code .scheduler/} (gitignored). */
public final class LocalSession {

    private static final Path FILE = Endpoints.dir().resolve("session.txt");
    private static final Path PREFS = Endpoints.dir().resolve("login.txt");

    private LocalSession() {}

    public static void save(String userId, String username, String token) {
        if (userId == null || userId.isBlank() || token == null || token.isBlank()) return;
        Properties p = new Properties();
        p.setProperty("userId", userId.trim());
        p.setProperty("username", username == null ? "" : username.trim());
        p.setProperty("token", token.trim());
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "ashore remembered login");
            }
        } catch (IOException e) {
            System.err.println("session save failed: " + e.getMessage());
        }
    }

    public static Data load() {
        if (!Files.isRegularFile(FILE)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String userId = p.getProperty("userId", "").trim();
        String token = p.getProperty("token", "").trim();
        if (userId.isEmpty() || token.isEmpty()) return null;
        return new Data(userId, p.getProperty("username", "").trim(), token);
    }

    public static void savePrefs(boolean remember, String lastUsername) {
        Properties p = prefsOrEmpty();
        p.setProperty("remember", remember ? "true" : "false");
        p.setProperty("lastUser", lastUsername == null ? "" : lastUsername.trim());
        writePrefs(p);
        if (!remember) clear();
    }

    /** Opt-in: random online security check after Remember-me auto-login. */
    public static boolean wantRandomChallenge() {
        Properties p = loadPrefs();
        if (p == null) return false;
        return "true".equalsIgnoreCase(p.getProperty("randomChallenge", "false"));
    }

    public static void setRandomChallenge(boolean on) {
        Properties p = prefsOrEmpty();
        p.setProperty("randomChallenge", on ? "true" : "false");
        writePrefs(p);
    }

    /** Task start reminders. Missing prefs file means on. */
    public static boolean wantReminders() {
        Properties p = loadPrefs();
        if (p == null) return true;
        return !"false".equalsIgnoreCase(p.getProperty("reminders", "true"));
    }

    public static void setReminders(boolean on) {
        Properties p = prefsOrEmpty();
        p.setProperty("reminders", on ? "true" : "false");
        writePrefs(p);
    }

    /** Minutes before start. 0 = when it starts. Default 0 and 5. */
    public static List<Integer> reminderLeads() {
        Properties p = loadPrefs();
        String raw = p == null ? "0,5" : p.getProperty("reminderLeads", "0,5");
        return parseLeads(raw);
    }

    public static void setReminderLeads(List<Integer> leads) {
        Properties p = prefsOrEmpty();
        p.setProperty("reminderLeads", joinLeads(leads));
        writePrefs(p);
    }

    public static boolean wantReminderSound() {
        Properties p = loadPrefs();
        if (p == null) return true;
        return !"false".equalsIgnoreCase(p.getProperty("reminderSound", "true"));
    }

    public static void setReminderSound(boolean on) {
        Properties p = prefsOrEmpty();
        p.setProperty("reminderSound", on ? "true" : "false");
        writePrefs(p);
    }

    /**
     * Idle logout minutes on this device. 0 = never. Default never. Allowed 5–180 when not never.
     */
    public static int idleMinutes() {
        Properties p = loadPrefs();
        if (p == null) return 0;
        String raw = p.getProperty("idleMinutes", "0").trim();
        if ("never".equalsIgnoreCase(raw) || "0".equals(raw) || raw.isBlank()) return 0;
        try {
            int n = Integer.parseInt(raw);
            if (n <= 0) return 0;
            if (n < 5) return 5;
            if (n > 180) return 180;
            return n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Exact-minute bars on top of the rounded occupancy grid. On by default. */
    public static boolean wantRealtimeOverlay() {
        Properties p = loadPrefs();
        if (p == null) return true;
        String v = p.getProperty("realtimeOverlay");
        if (v == null || v.isBlank()) return true;
        return "true".equalsIgnoreCase(v);
    }

    public static void setRealtimeOverlay(boolean on) {
        Properties p = prefsOrEmpty();
        p.setProperty("realtimeOverlay", on ? "true" : "false");
        writePrefs(p);
    }

    public static void setIdleMinutes(int minutes) {
        Properties p = prefsOrEmpty();
        if (minutes <= 0) p.setProperty("idleMinutes", "0");
        else p.setProperty("idleMinutes", String.valueOf(Math.max(5, Math.min(180, minutes))));
        writePrefs(p);
    }

    public static List<Integer> parseLeads(String raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            out.add(0);
            out.add(5);
            return new ArrayList<>(out);
        }
        for (String part : raw.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            try {
                int n = Integer.parseInt(part.trim());
                if (n >= 0 && n <= 20160) out.add(n);
            } catch (NumberFormatException ignored) {
            }
        }
        return new ArrayList<>(out);
    }

    public static String joinLeads(List<Integer> leads) {
        if (leads == null || leads.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Integer n : leads) {
            if (n == null || n < 0 || n > 20160 || !seen.add(n)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        return sb.toString();
    }

    public static boolean wantRemember() {
        Properties p = loadPrefs();
        if (p == null) return true;
        return !"false".equalsIgnoreCase(p.getProperty("remember", "true"));
    }

    public static String lastUsername() {
        Properties p = loadPrefs();
        if (p == null) return "";
        return p.getProperty("lastUser", "").trim();
    }

    private static Properties loadPrefs() {
        if (!Files.isRegularFile(PREFS)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(PREFS)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties prefsOrEmpty() {
        Properties p = loadPrefs();
        return p == null ? new Properties() : p;
    }

    private static void writePrefs(Properties p) {
        try {
            Files.createDirectories(PREFS.getParent());
            try (OutputStream out = Files.newOutputStream(PREFS)) {
                p.store(out, "ashore login prefs");
            }
        } catch (IOException e) {
            System.err.println("login prefs save failed: " + e.getMessage());
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(FILE);
        } catch (IOException ignored) {
        }
    }

    public static final class Data {
        public final String userId;
        public final String username;
        public final String token;

        Data(String userId, String username, String token) {
            this.userId = userId;
            this.username = username;
            this.token = token;
        }
    }
}
