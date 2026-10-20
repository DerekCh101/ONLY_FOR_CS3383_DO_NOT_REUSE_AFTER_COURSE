/** HTTP client for login, tasks, typewriter, and account against Node. */
package org.ashoreprospect.net;

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
import org.ashoreprospect.crypto.Vault;

public class NodeApi {

    private static final int CONNECT_MS = 1500;
    private static final int READ_MS = 2500;
    private static final int AUTH_READ_MS = 12000;
    private static final int IMAGE_READ_MS = 60000;

    private String token;
    private String userId;
    private String username;

    public void register(String user, String password) throws Exception {
        throw new IllegalStateException("register needs email and a security question");
    }

    public void login(String user, String password) throws Exception {
        applySession(authenticate(false, user, password));
    }

    public AuthResult authenticate(boolean register, String user, String password) throws Exception {
        if (register) {
            throw new IllegalStateException("register needs email and a security question");
        }
        return postAuth("/login", user, password, null, null, null);
    }

    public AuthResult registerAccount(String user, String password, String email,
                                      String question, String answer) throws Exception {
        return postAuth("/register", user, password, email, question, answer);
    }

    public void applySession(AuthResult r) {
        applySession(r, true);
    }

    public void applySession(AuthResult r, boolean remember) {
        apply(r);
        LocalSession.savePrefs(remember, r.username);
        if (remember) {
            LocalSession.save(r.userId, r.username, r.token);
        } else {
            LocalSession.clear();
        }
    }

    public boolean restoreSession() {
        if (!LocalSession.wantRemember()) return false;
        LocalSession.Data saved = LocalSession.load();
        if (saved == null) return false;
        this.token = saved.token;
        this.userId = saved.userId;
        this.username = saved.username;
        return true;
    }

    public void logout() {
        token = null;
        userId = null;
        username = null;
        LocalSession.clear();
        Vault.lock();
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
        List<Map<String, String>> out = objectsIn(json, "tasks");
        out.addAll(objectsIn(json, "nested"));
        return out;
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
            String json = request("GET", "/typewriter/images/" + sha + "?meta=1", null, true, IMAGE_READ_MS);
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
        return postTypewriterImage(sha, name, base64, "diary", "image/png");
    }

    public Map<String, String> postTypewriterImage(String sha, String name, String base64,
                                                   String kind, String mime) throws Exception {
        String ext = name == null ? "" : name.toLowerCase();
        String resolvedMime = mime;
        if (resolvedMime == null || resolvedMime.isBlank()) {
            if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) resolvedMime = "image/jpeg";
            else if (ext.endsWith(".gif")) resolvedMime = "image/gif";
            else if (ext.endsWith(".webp")) resolvedMime = "image/webp";
            else resolvedMime = "image/png";
        }
        String folder = "pages".equalsIgnoreCase(kind) ? "pages" : "diary";
        String body = "{"
                + "\"sha256\":\"" + escape(sha) + "\""
                + ",\"name\":\"" + escape(name == null || name.isBlank() ? "image.png" : name) + "\""
                + ",\"mime\":\"" + escape(resolvedMime) + "\""
                + ",\"kind\":\"" + escape(folder) + "\""
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

    public Map<String, String> getMe() throws Exception {
        return firstObject(request("GET", "/me", null, true, READ_MS));
    }

    public Map<String, String> deleteAccount(String password) throws Exception {
        String body = "{\"password\":\"" + escape(password == null ? "" : password) + "\"}";
        return firstObject(request("POST", "/me/delete", body, true, AUTH_READ_MS));
    }

    public boolean isAccountDeleted(String userId) {
        if (userId == null || userId.isBlank()) return false;
        try {
            String q = java.net.URLEncoder.encode(userId.trim(), StandardCharsets.UTF_8);
            String json = request("GET", "/account-status?userId=" + q, null, false, READ_MS);
            return "true".equalsIgnoreCase(first(extract(json, "deleted")));
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, String> saveEmail(String email) throws Exception {
        String body = "{\"email\":\"" + escape(email == null ? "" : email) + "\"}";
        return firstObject(request("POST", "/me/email", body, true, READ_MS));
    }

    public Map<String, String> saveUsername(String username) throws Exception {
        String body = "{\"username\":\"" + escape(username == null ? "" : username) + "\"}";
        Map<String, String> out = firstObject(request("POST", "/me/username", body, true, READ_MS));
        String next = out.get("username");
        if (next != null && !next.isBlank()) {
            this.username = next;
            if (LocalSession.wantRemember() && token != null) {
                LocalSession.save(userId, next, token);
            }
        }
        return out;
    }

    public List<Map<String, String>> getSessions() throws Exception {
        String json = request("GET", "/sessions", null, true, READ_MS);
        return objectsIn(json, "sessions");
    }

    public Map<String, String> revokeOtherSessions() throws Exception {
        return firstObject(request("POST", "/sessions/revoke-others", "{}", true, READ_MS));
    }

    public Map<String, String> revokeSession(String sessionId) throws Exception {
        return firstObject(request("DELETE", "/sessions/" + sessionId, null, true, READ_MS));
    }

    public Map<String, String> saveSecurity(String question, String answer) throws Exception {
        String body = "{\"question\":\"" + escape(question) + "\",\"answer\":\"" + escape(answer) + "\"}";
        return firstObject(request("POST", "/me/security", body, true, READ_MS));
    }

    public Map<String, String> changePassword(String oldPassword, String newPassword) throws Exception {
        String body = "{\"oldPassword\":\"" + escape(oldPassword)
                + "\",\"newPassword\":\"" + escape(newPassword) + "\"}";
        return firstObject(request("POST", "/change-password", body, true, READ_MS));
    }

    public Map<String, String> challengeQuestion() throws Exception {
        return firstObject(request("GET", "/challenge-question", null, true, READ_MS));
    }

    public Map<String, String> challengeAnswer(String answer) throws Exception {
        String body = "{\"answer\":\"" + escape(answer) + "\"}";
        return firstObject(request("POST", "/challenge-answer", body, true, READ_MS));
    }

    public Map<String, String> forgotPassword(String username, String email) throws Exception {
        String body = "{\"username\":\"" + escape(username) + "\",\"email\":\"" + escape(email) + "\"}";
        return firstObject(request("POST", "/forgot", body, false, AUTH_READ_MS));
    }

    public Map<String, String> resetPassword(String token, String answer, String newPassword) throws Exception {
        String body = "{\"token\":\"" + escape(token)
                + "\",\"answer\":\"" + escape(answer)
                + "\",\"newPassword\":\"" + escape(newPassword) + "\"}";
        return firstObject(request("POST", "/reset", body, false, AUTH_READ_MS));
    }

    private void apply(AuthResult r) {
        this.token = r.token;
        this.userId = r.userId;
        this.username = r.username;
    }

    private AuthResult postAuth(String path, String user, String password,
                                String email, String question, String answer) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"username\":\"").append(escape(user))
                .append("\",\"password\":\"").append(escape(password))
                .append("\",\"device\":\"").append(escape(deviceLabel())).append('"');
        if (email != null) {
            body.append(",\"email\":\"").append(escape(email)).append('"');
        }
        if (question != null) {
            body.append(",\"question\":\"").append(escape(question)).append('"');
        }
        if (answer != null) {
            body.append(",\"answer\":\"").append(escape(answer)).append('"');
        }
        body.append('}');
        String json = request("POST", path, body.toString(), false, READ_MS);

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

        HttpURLConnection c = (HttpURLConnection) new URL(Endpoints.nodeBase() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(readMs);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", deviceLabel());
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

        try {
            int code = c.getResponseCode();
            String json = read(code >= 400 ? c.getErrorStream() : c.getInputStream());

            if (code < 200 || code >= 300) {
                String msg = extract(json, "message");
                if (msg == null) msg = extract(json, "error");
                throw new IllegalStateException(friendlyError(path, code, msg));
            }
            return json;
        } catch (java.net.SocketTimeoutException e) {
            if (path != null && path.contains("forgot")) {
                throw new IllegalStateException(
                        "Timed out waiting for mail. The token and question are on the host Node terminal.");
            }
            throw e;
        }
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

    static String friendlyError(String path, int code, String msg) {
        String m = msg == null ? "" : msg.trim();
        String low = m.toLowerCase();
        boolean pw = path != null && path.contains("password");
        if (pw && (code == 404 || low.contains("not found") || low.contains("no route"))) {
            return "Change password is not on this Node yet. Restart Node on the host PC "
                    + "(the process listening on port 3456), then try again.";
        }
        if (pw && (low.contains("current password") || (code == 401 && low.contains("wrong")))) {
            return "Current password is wrong.";
        }
        if (pw && (low.contains("min 8") || low.contains("too short"))) {
            return "New password must be at least 8 characters.";
        }
        if (path != null && path.contains("/me/delete") && code == 401) {
            return "Password is wrong.";
        }
        if (code == 401 && (low.contains("login") || low.isBlank())) {
            return "Your login expired. Sign in again, then retry.";
        }
        if (code == 429 || low.contains("too many image")) {
            return "Too many image uploads just now. Wait a minute, then retry.";
        }
        if (code == 404 || "not found".equalsIgnoreCase(m)) {
            return "The server does not have " + path
                    + ". Restart Node on the host PC after updating, then try again.";
        }
        return m.isBlank() ? ("Server error " + code) : m;
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
        if (json == null || arrayKey == null) return out;
        String needle = "\"" + arrayKey + "\"";
        int keyAt = json.indexOf(needle);
        if (keyAt < 0) return out;
        int bracket = json.indexOf('[', keyAt + needle.length());
        if (bracket < 0) return out;
        int end = matchingBracket(json, bracket);
        if (end < 0) end = json.length();
        int i = bracket + 1;
        while (i < end) {
            int open = json.indexOf('{', i);
            if (open < 0 || open >= end) break;
            int close = matchingBrace(json, open);
            if (close < 0 || close >= end) break;
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
        return matchingPair(s, open, '{', '}');
    }

    private static int matchingBracket(String s, int open) {
        return matchingPair(s, open, '[', ']');
    }

    private static int matchingPair(String s, int open, char a, char b) {
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
            if (ch == a) depth++;
            else if (ch == b) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public static String deviceLabel() {
        String os = System.getProperty("os.name", "Java");
        String host = "pc";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        return "JavaFX · " + os + " · " + host;
    }

    public static final class AuthResult {
        public String token;
        public String userId;
        public String username;
    }
}