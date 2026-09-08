package org.example.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class NodeApi {

    // copy the old host:port exactly
    private static final String BASE = "http://127.0.0.1:3456";

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 8000;

    private String token;
    private String userId;
    private String username;

    public void register(String user, String password) throws Exception {
        AuthResult r = postAuth("/register", user, password);
        apply(r);
    }

    public void login(String user, String password) throws Exception {
        AuthResult r = postAuth("/login", user, password);
        apply(r);
    }

    public void logout() {
        token = null;
        userId = null;
        username = null;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getToken() {
        return token;
    }

    private void apply(AuthResult r) {
        this.token = r.token;
        this.userId = r.userId;
        this.username = r.username;
    }

    private AuthResult postAuth(String path, String user, String password) throws Exception {
        String url = BASE + path;
        System.out.println("auth POST " + url);

        String body = "{\"username\":\"" + escape(user) + "\",\"password\":\"" + escape(password) + "\"}";

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        String json = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        System.out.println("auth HTTP " + code + " " + json);

        if (code < 200 || code >= 300) {
            String msg = extract(json, "message");
            if (msg == null) msg = extract(json, "error");
            throw new IllegalStateException(msg != null ? msg : ("HTTP " + code));
        }

        AuthResult r = new AuthResult();
        r.token = first(extract(json, "token"), extract(json, "accessToken"));
        r.userId = first(extract(json, "userId"), extract(json, "id"), extract(json, "user_id"));
        r.username = first(extract(json, "username"), extract(json, "name"), user);
        if (r.userId == null || r.userId.isBlank()) {
            throw new IllegalStateException("login response had no user id");
        }
        return r;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String first(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
    }

    /** tiny extractor for "key":"value" — enough for this login payload */
    private static String extract(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static final class AuthResult {
        String token;
        String userId;
        String username;
    }
}