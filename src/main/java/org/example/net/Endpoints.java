package org.example.net;

public final class Endpoints {
    private Endpoints() {}

    /** Host PC Tailscale IP. Change only if that IP changes. */
    public static final String TAILSCALE_HOST = "100.113.239.82";

    public static boolean isHost() {
        return "host".equalsIgnoreCase(System.getenv("SCHEDULER_ROLE"));
    }

    public static String nodeBase() {
        return isHost()
                ? "http://127.0.0.1:3456"
                : "http://" + TAILSCALE_HOST + ":3456";
    }

    public static String postgresUrl() {
        String host = isHost() ? "127.0.0.1" : TAILSCALE_HOST;
        return "jdbc:postgresql://" + host + ":5432/Scheduler";
    }

    public static String postgresPassword() {
        String pw = System.getenv("postgres_pw");
        return pw == null ? "" : pw;
    }
}