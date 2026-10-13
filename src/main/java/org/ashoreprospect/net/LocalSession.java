package org.ashoreprospect.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Remembered Node session for this PC. Lives under {@code .scheduler/} (gitignored). */
public final class LocalSession {

    private static final Path FILE = Endpoints.dir().resolve("session.txt");

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
