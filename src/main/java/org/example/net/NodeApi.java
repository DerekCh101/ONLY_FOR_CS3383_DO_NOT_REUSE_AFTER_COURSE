package org.example.net;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NodeApi {

    private static final String BASE = "http://127.0.0.1:3456";

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 8000;
    private static final int IMAGE_READ_MS = 60000;

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

    public List<Map<String, String>> getTasks() throws Exception {
        String json = request("GET", "/tasks", null, true, READ_MS);
        return objectsIn(json, "tasks");
    }

    public List<Map<String, String>> getBlocked() throws Exception {
        String json = request("GET", "/blocked", null, true, READ_MS);
        return objectsIn(json, "blocked");
    }

    public Map<String, String> getTypewriter() throws Exception {
        String json = request("GET", "/typewriter", null, true, READ_MS);
        return firstObject(json);
    }

    public Map<String, String> postTypewriter(String content, String lastUpdatedAt) throws Exception {
        String body = "{\"content\":\"" + escape(content) + "\",\"last_updated_at\":\""
                + escape(lastUpdatedAt == null ? "" : lastUpdatedAt) + "\"}";
        String json = request("POST", "/typewriter", body, true, READ_MS);
        return firstObject(json);
    }

    public boolean typewriterImageExists(String sha) throws Exception {
        if (sha == null || sha.isBlank()) return false;
        try {
            String json = request("GET", "/typewriter/images/" + sha + "?meta=1", null, true, READ_MS);
            String exists = extract(json, "exists");
            String got = extract(json, "sha256");
            return "true".equalsIgnoreCase(exists) || sha.equalsIgnoreCase(got);
        } catch (IllegalStateException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("missing") || m.contains("HTTP 404") || "missing".equals(m)) {
                return false;
            }
            throw e;
        }
    }

    public Map<String, String> getTypewriterImage(String sha) throws Exception {
        String json = request("GET", "/typewriter/images/" + sha, null, true, IMAGE_READ_MS);
        return firstObject(json);
    }

    public Map<String, String> postTypewriterImage(String sha, String name, String base64) throws Exception {
        String body = "{"
                + "\"sha256\":\"" + escape(sha) + "\""
                + ",\"name\":\"" + escape(name == null || name.isBlank() ? "image.png" : name) + "\""
                + ",\"mime\":\"image/png\""
                + ",\"data\":\"" + escape(base64) + "\""
                + "}";
        String json = request("POST", "/typewriter/images", body, true, IMAGE_READ_MS);
        return firstObject(json);
    }

    public Map<String, String> postTask(String jsonBody) throws Exception {
        String json = request("POST", "/tasks", jsonBody, true, READ_MS);
        return firstObject(json);
    }

    public Map<String, String> postBlocked(String jsonBody) throws Exception {
        String json = request("POST", "/blocked", jsonBody, true, READ_MS);
        return firstObject(json);
    }

    public Map<String, String> deleteTask(String id) throws Exception {
        String json = request("DELETE", "/tasks/" + id, null, true, READ_MS);
        return firstObject(json);
    }

    public Map<String, String> deleteBlocked(String id) throws Exception {
        String json = request("DELETE", "/blocked/" + id, null, true, READ_MS);
        return firstObject(json);
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
        String json = request("POST", path, body, false, READ_MS);

        AuthResult r = new AuthResult();
        r.token = first(extract(json, "token"), extract(json, "accessToken"));
        r.userId = first(extract(json, "userId"), extract(json, "id"), extract(json, "user_id"));
        r.username = first(extract(json, "username"), extract(json, "name"), user);
        if (r.userId == null || r.userId.isBlank()) {
            throw new IllegalStateException("login response had no user id");
        }
        return r;
    }

    private String request(String method, String path, String body, boolean auth, int readMs) throws Exception {
        if (auth && (token == null || token.isBlank())) {
            throw new IllegalStateException("login required");
        }

        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(readMs);
        c.setRequestProperty("Accept", "application/json");
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = c.getResponseCode();
        String json = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (path.startsWith("/typewriter/images") || path.equals("/typewriter")) {
            System.out.println(method + " " + path + " HTTP " + code + " len=" + json.length());
        } else {
            System.out.println(method + " " + path + " HTTP " + code + " " + json);
        }

        if (code < 200 || code >= 300) {
            String msg = extract(json, "message");
            if (msg == null) msg = extract(json, "error");
            throw new IllegalStateException(msg != null ? msg : ("HTTP " + code));
        }
        return json;
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
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String first(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
    }

    private static String extract(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        if (json.startsWith("null", p)) return null;
        if (json.charAt(p) == '"') {
            StringBuilder val = new StringBuilder();
            for (int k = p + 1; k < json.length(); k++) {
                char ch = json.charAt(k);
                if (ch == '\\' && k + 1 < json.length()) {
                    char n = json.charAt(++k);
                    if (n == 'n') val.append('\n');
                    else if (n == 'r') val.append('\r');
                    else if (n == 't') val.append('\t');
                    else val.append(n);
                } else if (ch == '"') {
                    return val.toString();
                } else {
                    val.append(ch);
                }
            }
            return null;
        }
        int end = p;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
            end++;
        }
        return json.substring(p, end);
    }

    private static List<Map<String, String>> objectsIn(String json, String arrayKey) {
        List<Map<String, String>> out = new ArrayList<>();
        if (json == null) return out;
        String needle = "\"" + arrayKey + "\"";
        int keyAt = json.indexOf(needle);
        int bracket = json.indexOf('[', keyAt < 0 ? 0 : keyAt);
        if (bracket < 0) return out;
        int i = bracket + 1;
        while (i < json.length()) {
            int open = json.indexOf('{', i);
            if (open < 0) break;
            int close = matchingBrace(json, open);
            if (close < 0) break;
            out.add(fieldsOf(json.substring(open, close + 1)));
            i = close + 1;
        }
        return out;
    }

    private static Map<String, String> firstObject(String json) {
        int open = json == null ? -1 : json.indexOf('{');
        if (open < 0) return new LinkedHashMap<>();
        int close = matchingBrace(json, open);
        if (close < 0) return new LinkedHashMap<>();
        return fieldsOf(json.substring(open, close + 1));
    }

    private static Map<String, String> fieldsOf(String obj) {
        Map<String, String> map = new LinkedHashMap<>();
        if (obj == null) return map;
        int i = 0;
        int n = obj.length();
        while (i < n) {
            while (i < n && obj.charAt(i) != '"') i++;
            if (i >= n) break;
            int q2 = nextUnescapedQuote(obj, i + 1);
            if (q2 < 0) break;
            String key = obj.substring(i + 1, q2);
            int colon = obj.indexOf(':', q2 + 1);
            if (colon < 0) break;
            int[] pos = {colon + 1};
            String val = parseJsonScalar(obj, pos);
            map.put(key, val);
            i = pos[0];
        }
        return map;
    }

    private static String parseJsonScalar(String s, int[] pos) {
        int p = pos[0];
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        if (p >= s.length()) {
            pos[0] = p;
            return null;
        }
        if (s.startsWith("null", p)
                && (p + 4 >= s.length() || !Character.isLetterOrDigit(s.charAt(p + 4)))) {
            pos[0] = p + 4;
            return null;
        }
        if (s.charAt(p) == '"') {
            StringBuilder val = new StringBuilder();
            int k = p + 1;
            while (k < s.length()) {
                char ch = s.charAt(k);
                if (ch == '\\' && k + 1 < s.length()) {
                    char nch = s.charAt(++k);
                    if (nch == 'n') val.append('\n');
                    else if (nch == 'r') val.append('\r');
                    else if (nch == 't') val.append('\t');
                    else val.append(nch);
                    k++;
                } else if (ch == '"') {
                    pos[0] = k + 1;
                    return val.toString();
                } else {
                    val.append(ch);
                    k++;
                }
            }
            pos[0] = k;
            return val.toString();
        }
        int end = p;
        while (end < s.length()) {
            char ch = s.charAt(end);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
            end++;
        }
        pos[0] = end;
        return s.substring(p, end);
    }

    private static int nextUnescapedQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '"' && (i == 0 || s.charAt(i - 1) != '\\')) return i;
        }
        return -1;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && inStr) {
                i++;
                continue;
            }
            if (ch == '"') {
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static final class AuthResult {
        String token;
        String userId;
        String username;
    }
}