/** Saved server URL, host vs client, and Postgres settings. */
package org.ashoreprospect.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Server URL env: {@code SCHEDULER_NODE} (OS user env, not the project folder).
 * Postgres password env: {@code postgres_pw}.
 * Persist: Windows {@code setx} + user-config {@code user.env}; Linux user-config only.
 * Leftover {@code .scheduler/server.txt} / {@code postgres.pw} are read if still present,
 * then not written again.
 */
public final class Endpoints {

    private Endpoints() {}

    public static final String PG_ENV = "postgres_pw";
    public static final String NODE_ENV = "SCHEDULER_NODE";

    public static final String CREATOR_HOST = "https://YOUR-DOMAIN-HERE";
    public static final String LOCAL_NODE = "http://127.0.0.1:3456";

    private static final Path DIR = Path.of(System.getProperty("user.dir"), ".scheduler");
    private static final Path SERVER_FILE = DIR.resolve("server.txt");
    private static final Path PG_FILE = DIR.resolve("postgres.pw");

    public static Path dir() {
        return DIR;
    }

    public static boolean hasSavedServer() {
        String env = OsEnv.get(NODE_ENV);
        if (env != null && !env.isBlank()) return true;
        return Files.isRegularFile(SERVER_FILE);
    }

    public static String nodeBase() {
        String env = OsEnv.get(NODE_ENV);
        if (env != null && !env.isBlank()) return trimSlash(env);

        String saved = read(SERVER_FILE);
        if (saved != null && !saved.isBlank()) return trimSlash(saved);

        return LOCAL_NODE;
    }

    public static void saveNode(String url) {
        if (url == null || url.isBlank()) return;
        OsEnv.persist(NODE_ENV, trimSlash(url.trim()));
        try {
            Files.deleteIfExists(SERVER_FILE);
        } catch (IOException ignored) {
        }
    }

    public static void clearSavedServer() {
        OsEnv.clear(NODE_ENV);
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

    /**
     * Host vs client for same-timestamp LWW ties only. Newer still always wins.
     *
     * This desktop is the host when it talks to the creator-host Node URL
     * ({@link #isCreatorHost()}), to Node on this machine
     * ({@link #isLocalHost()}), or to a Tailscale / LAN address that is
     * one of this machine’s own NICs. Other installs that point at that
     * Node over Tailscale / LAN are clients.
     *
     * {@link #CREATOR_HOST} is a placeholder today, so {@link #isCreatorHost()}
     * is false until a real domain is set. Localhost / this-machine Node is
     * still host so the tie rule is testable. deviceId and “first row in
     * TASK.devices” are not host signals — deviceId is registry/GC only.
     */
    public static boolean isHostDevice() {
        return isCreatorHost() || isLocalHost() || nodeHostIsThisMachine();
    }

    /** True when SCHEDULER_NODE’s host is an address on this PC (Tailscale, wifi, LAN). */
    static boolean nodeHostIsThisMachine() {
        String host = hostOf(nodeBase());
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if ("127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)) return true;
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return false;
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip != null) {
                        int pct = ip.indexOf('%');
                        if (pct >= 0) ip = ip.substring(0, pct);
                        if (h.equalsIgnoreCase(ip)) return true;
                    }
                    String name = a.getHostName();
                    if (name != null && h.equalsIgnoreCase(name)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String hostOf(String base) {
        if (base == null || base.isBlank()) return null;
        try {
            java.net.URI u = java.net.URI.create(base);
            if (u.getHost() != null && !u.getHost().isBlank()) return u.getHost();
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String postgresUrl() {
        return "jdbc:postgresql://" + jdbcHost() + ":5432/Scheduler?connectTimeout=2&socketTimeout=3";
    }

    public static String postgresPassword() {
        String env = OsEnv.get(PG_ENV);
        if (env != null && !env.isBlank()) return env.trim();
        try {
            if (Files.isRegularFile(PG_FILE)) {
                String s = Files.readString(PG_FILE).trim();
                if (!s.isBlank()) return s;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * First self-host: reuse env postgres_pw if set, else generate,
     * persist OS env {@code postgres_pw}, ALTER local role if possible.
     */
    public static String ensurePostgresPassword() {
        if (isCreatorHost()) return "";

        String pw = postgresPassword();
        if (pw == null || pw.isBlank()) pw = generatePassword();
        savePostgresPassword(pw);
        applyPasswordToLocalPostgres(pw);
        return pw;
    }

    public static void savePostgresPassword(String pw) {
        if (pw == null || pw.isBlank()) return;
        String trimmed = pw.trim();
        OsEnv.persist(PG_ENV, trimmed);
        try {
            Files.deleteIfExists(PG_FILE);
        } catch (IOException ignored) {
        }
    }

    /** JDBC is localhost-only. Tailscale / LAN clients must not open Postgres. */
    private static String jdbcHost() {
        return "127.0.0.1";
    }

    private static void applyPasswordToLocalPostgres(String newPw) {
        String host = jdbcHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) return;
        String envNow = OsEnv.get(PG_ENV);
        String[] guesses = { newPw, envNow, "postgres", "admin", "password", "" };
        for (String guess : guesses) {
            if (guess == null) continue;
            if (tryAlter(guess, newPw)) return;
        }
        System.out.println("Could not ALTER postgres password automatically.");
        System.out.println("In psql or pgAdmin run once:");
        System.out.println("  ALTER USER postgres PASSWORD '" + newPw + "';");
        System.out.println("User env file: " + OsEnv.userEnvFile().toAbsolutePath());
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

}