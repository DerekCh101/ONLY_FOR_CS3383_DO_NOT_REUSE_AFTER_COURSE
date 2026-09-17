package org.example.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Locale;

/**
 * Server URL: {workingDir}/.scheduler/server.txt
 * Postgres password name: postgres_pw
 *   1. {workingDir}/.scheduler/postgres.pw
 *   2. env postgres_pw
 * On first self-host pick the app writes both and on Windows runs setx postgres_pw.
 *
 * Do not put a Tailscale / LAN IP in this class. Real URLs live in server.txt (gitignored).
 */
public final class Endpoints {

    private Endpoints() {}

    public static final String PG_ENV = "postgres_pw";

    public static final String CREATOR_HOST = "https://YOUR-DOMAIN-HERE";
    public static final String LOCAL_NODE = "http://127.0.0.1:3456";

    private static final Path DIR = Path.of(System.getProperty("user.dir"), ".scheduler");
    private static final Path SERVER_FILE = DIR.resolve("server.txt");
    private static final Path PG_FILE = DIR.resolve("postgres.pw");

    public static Path dir() {
        return DIR;
    }

    public static boolean hasSavedServer() {
        return Files.isRegularFile(SERVER_FILE);
    }

    public static String nodeBase() {
        String saved = read(SERVER_FILE);
        if (saved != null && !saved.isBlank()) return trimSlash(saved);

        String env = System.getenv("SCHEDULER_NODE");
        if (env != null && !env.isBlank()) return trimSlash(env);

        return LOCAL_NODE;
    }

    public static void saveNode(String url) {
        if (url == null || url.isBlank()) return;
        write(SERVER_FILE, trimSlash(url.trim()));
    }

    public static void clearSavedServer() {
        try {
            Files.deleteIfExists(SERVER_FILE);
        } catch (IOException ignored) {
        }
    }

    public static boolean isCreatorHost() {
        String n = nodeBase();
        String creator = CREATOR_HOST;
        if (creator == null || creator.isBlank() || creator.contains("YOUR-DOMAIN-HERE")) {
            return false;
        }
        return n.toLowerCase(Locale.ROOT).startsWith("https://")
                && n.startsWith(creator);
    }

    public static boolean isLocalHost() {
        String n = nodeBase();
        return n.contains("127.0.0.1") || n.contains("localhost");
    }

    public static String postgresUrl() {
        return "jdbc:postgresql://" + jdbcHost() + ":5432/Scheduler";
    }

    public static String postgresPassword() {
        try {
            if (Files.isRegularFile(PG_FILE)) {
                String s = Files.readString(PG_FILE).trim();
                if (!s.isBlank()) return s;
            }
        } catch (Exception ignored) {
        }
        String env = System.getenv(PG_ENV);
        return env == null ? "" : env.trim();
    }

    /**
     * First self-host: reuse env postgres_pw if set, else generate,
     * write .scheduler/postgres.pw, persist env name postgres_pw, ALTER local role if possible.
     */
    public static String ensurePostgresPassword() {
        if (isCreatorHost()) return "";

        String pw = read(PG_FILE);
        if (pw == null || pw.isBlank()) {
            String env = System.getenv(PG_ENV);
            if (env != null && !env.isBlank()) pw = env.trim();
            else pw = generatePassword();
            write(PG_FILE, pw);
        }
        persistEnv(pw);
        applyPasswordToLocalPostgres(pw);
        return pw;
    }

    public static void savePostgresPassword(String pw) {
        if (pw == null || pw.isBlank()) return;
        String trimmed = pw.trim();
        write(PG_FILE, trimmed);
        persistEnv(trimmed);
    }

    private static void persistEnv(String pw) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("setx", PG_ENV, pw)
                        .redirectErrorStream(true)
                        .start();
                System.out.println("setx " + PG_ENV + " written (new terminals / after restart)");
            }
        } catch (Exception e) {
            System.out.println("could not setx " + PG_ENV + ": " + e.getMessage());
        }
    }

    private static String jdbcHost() {
        String base = nodeBase();
        if (base.startsWith("https://")) return "127.0.0.1";
        try {
            java.net.URI u = java.net.URI.create(base);
            if (u.getHost() != null && !u.getHost().isBlank()) return u.getHost();
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private static void applyPasswordToLocalPostgres(String newPw) {
        String host = jdbcHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) return;
        String envNow = System.getenv(PG_ENV);
        String[] guesses = { newPw, envNow, "postgres", "admin", "password", "" };
        for (String guess : guesses) {
            if (guess == null) continue;
            if (tryAlter(guess, newPw)) return;
        }
        System.out.println("Could not ALTER postgres password automatically.");
        System.out.println("In psql or pgAdmin run once:");
        System.out.println("  ALTER USER postgres PASSWORD '" + newPw + "';");
        System.out.println("File: " + PG_FILE.toAbsolutePath());
        System.out.println("Env name: " + PG_ENV);
    }

    private static boolean tryAlter(String connectPw, String newPw) {
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
        try (Connection c = DriverManager.getConnection(url, "postgres", connectPw);
             Statement st = c.createStatement()) {
            st.execute("ALTER USER postgres PASSWORD '" + newPw.replace("'", "''") + "'");
            System.out.println("Postgres password applied on localhost");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String generatePassword() {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String read(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(Path p, String value) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("write " + p + " failed: " + e.getMessage());
        }
    }
}