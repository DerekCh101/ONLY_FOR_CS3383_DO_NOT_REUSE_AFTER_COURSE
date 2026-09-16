package org.example.GUI;

import org.example.db.PostgresTypewriterRepository;
import org.example.db.SqliteTypewriterRepository;
import org.example.net.Api;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TypewriterStore {

    private TypewriterStore() {}

    public static final class Data {
        public final Map<LocalDate, TypewriterPane.TwNote> diary = new LinkedHashMap<>();
        public final List<TypewriterPane.TwList> lists = new ArrayList<>();
        public final TypewriterPane.TwFolder pagesRoot = new TypewriterPane.TwFolder("Pages");
    }

    private static final File IMAGE_DIR = new File("typewriter-images");

    private static final SqliteTypewriterRepository sqlite =
            new SqliteTypewriterRepository("jdbc:sqlite:scheduler-cache.db");

    private static final PostgresTypewriterRepository postgres = new PostgresTypewriterRepository(
            "jdbc:postgresql://127.0.0.1:5432/Scheduler",
            "postgres",
            System.getenv("postgres_pw")
    );

    private static boolean postgresAvailable;

    static {
        IMAGE_DIR.mkdirs();
        try {
            sqlite.initialize();
            System.out.println("Typewriter SQLite ready");
        } catch (Exception e) {
            System.out.println("Typewriter SQLite init failed: " + e.getMessage());
        }
        try {
            postgres.initialize();
            postgresAvailable = true;
            System.out.println("Typewriter Postgres ready");
        } catch (Exception e) {
            postgresAvailable = false;
            System.out.println("Typewriter Postgres offline: " + e.getMessage());
        }
    }

    private static String currentUserId() {
        try {
            return Api.INSTANCE.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean nodeReady() {
        return Api.INSTANCE.getToken() != null && !Api.INSTANCE.getToken().isBlank();
    }

    public static boolean hasContent(TypewriterPane.TwNote n) {
        if (n == null) return false;
        if (n.body != null && !n.body.isBlank()) return true;
        return n.images != null && !n.images.isEmpty();
    }

    public static int pruneEmptyDiary(Map<LocalDate, TypewriterPane.TwNote> diary) {
        if (diary == null) return 0;
        int n = 0;
        Iterator<Map.Entry<LocalDate, TypewriterPane.TwNote>> it = diary.entrySet().iterator();
        while (it.hasNext()) {
            if (!hasContent(it.next().getValue())) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public static Data load() {
        Data empty = new Data();
        String uid = currentUserId();
        if (uid == null || uid.isBlank()) {
            System.out.println("Typewriter load skipped: no user");
            return empty;
        }
        try {
            Data localData = sqlite.fetchAll(uid);
            pruneEmptyDiary(localData.diary);
            Instant local = sqlite.lastUpdated(uid);

            if (nodeReady()) {
                try {
                    Map<String, String> remoteRow = Api.INSTANCE.getTypewriter();
                    Instant remoteTs = parseInstant(first(remoteRow, "last_updated_at", "lastUpdatedAt"));
                    Data remoteData = parseBundle(first(remoteRow, "content"));
                    pruneEmptyDiary(remoteData.diary);
                    if (remoteTs != null && (local == null || remoteTs.isAfter(local))) {
                        persistLocalAndServer(uid, remoteData);
                        System.out.println("Typewriter pulled from Node into SQLite+Postgres");
                        return sqlite.fetchAll(uid);
                    }
                    if (!isEmpty(localData) && (remoteTs == null || (local != null && local.isAfter(remoteTs)))) {
                        Api.INSTANCE.postTypewriter(
                                toBundle(localData),
                                local != null ? local.toString() : Instant.now().toString()
                        );
                    }
                } catch (Exception e) {
                    System.out.println("Typewriter Node load failed: " + e.getMessage());
                }
            }

            if (postgresAvailable) {
                Instant remote = null;
                try {
                    remote = postgres.lastUpdated(uid);
                } catch (SQLException e) {
                    postgresAvailable = false;
                    System.out.println("Typewriter Postgres read failed: " + e.getMessage());
                }
                if (postgresAvailable && remote != null && (local == null || remote.isAfter(local))) {
                    Data pulled = postgres.fetchAll(uid);
                    pruneEmptyDiary(pulled.diary);
                    if (!isEmpty(pulled) || isEmpty(localData)) {
                        sqlite.replaceAll(uid, pulled.diary, pulled.lists, pulled.pagesRoot);
                        return pulled;
                    }
                    System.out.println("Typewriter kept local; Postgres row was newer but empty");
                }
                if (postgresAvailable && !isEmpty(localData)
                        && (remote == null || (local != null && local.isAfter(remote)))) {
                    try {
                        postgres.replaceAll(uid, localData.diary, localData.lists, localData.pagesRoot);
                    } catch (SQLException e) {
                        postgresAvailable = false;
                        System.out.println("Typewriter push failed: " + e.getMessage());
                    }
                }
                return localData;
            }
            return localData;
        } catch (Exception ex) {
            System.out.println("Typewriter load skipped: " + ex.getMessage());
            ex.printStackTrace();
            return empty;
        }
    }

    public static void save(Map<LocalDate, TypewriterPane.TwNote> diary,
                            List<TypewriterPane.TwList> lists,
                            TypewriterPane.TwFolder pagesRoot) {
        String uid = currentUserId();
        if (uid == null || uid.isBlank()) {
            System.out.println("Typewriter save skipped: no user");
            return;
        }
        pruneEmptyDiary(diary);
        try {
            sqlite.replaceAll(uid, diary, lists, pagesRoot);
        } catch (Exception ex) {
            System.out.println("Typewriter SQLite save failed: " + ex.getMessage());
            ex.printStackTrace();
            return;
        }
        if (postgresAvailable) {
            try {
                postgres.replaceAll(uid, diary, lists, pagesRoot);
            } catch (Exception ex) {
                postgresAvailable = false;
                System.out.println("Typewriter Postgres save failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
        if (nodeReady()) {
            try {
                Data data = new Data();
                data.diary.putAll(diary);
                data.lists.addAll(lists);
                copyFolder(pagesRoot, data.pagesRoot);
                Instant local = sqlite.lastUpdated(uid);
                Api.INSTANCE.postTypewriter(
                        toBundle(data),
                        local != null ? local.toString() : Instant.now().toString()
                );
            } catch (Exception ex) {
                System.out.println("Typewriter Node save failed: " + ex.getMessage());
            }
        }
    }

    private static void persistLocalAndServer(String uid, Data data) throws Exception {
        pruneEmptyDiary(data.diary);
        sqlite.replaceAll(uid, data.diary, data.lists, data.pagesRoot);
        if (postgresAvailable) {
            try {
                postgres.replaceAll(uid, data.diary, data.lists, data.pagesRoot);
            } catch (SQLException e) {
                postgresAvailable = false;
                System.out.println("Typewriter Postgres write after Node pull failed: " + e.getMessage());
            }
        }
    }

    public static void addImages(TypewriterPane.TwNote note, String raw) {
        if (note == null || raw == null || raw.isBlank()) return;
        applyImageField(note, raw);
    }

    public static String joinImages(TypewriterPane.TwNote note) {
        if (note == null || note.images.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (File f : note.images) {
            if (sb.length() > 0) sb.append('|');
            sb.append(f.getAbsolutePath());
        }
        return sb.toString();
    }

    public static void rebuildPages(Connection c, String uid, TypewriterPane.TwFolder root,
                                    boolean postgres) throws SQLException {
        String sql = postgres
                ? "SELECT id, parent_id, kind, title, body, images FROM TASK.tw_pages WHERE user_id = ? ORDER BY pos"
                : "SELECT id, parent_id, kind, title, body, images FROM tw_pages WHERE user_id = ? ORDER BY pos";
        Map<String, TypewriterPane.TwFolder> folders = new LinkedHashMap<>();
        Map<String, String> parentOf = new LinkedHashMap<>();
        folders.put("root", root);
        List<Object[]> notes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    String parent = rs.getString(2);
                    String kind = rs.getString(3);
                    parentOf.put(id, parent == null || parent.isBlank() ? "root" : parent);
                    if ("folder".equals(kind)) {
                        folders.put(id, new TypewriterPane.TwFolder(id, nz(rs.getString(4))));
                    } else {
                        TypewriterPane.TwNote note = new TypewriterPane.TwNote(id, nz(rs.getString(4)), null);
                        note.body = nz(rs.getString(5));
                        addImages(note, rs.getString(6));
                        notes.add(new Object[]{id, note});
                    }
                }
            }
        }
        for (Map.Entry<String, TypewriterPane.TwFolder> e : folders.entrySet()) {
            if ("root".equals(e.getKey())) continue;
            TypewriterPane.TwFolder parent = folders.getOrDefault(parentOf.get(e.getKey()), root);
            parent.children.add(e.getValue());
        }
        for (Object[] row : notes) {
            String id = (String) row[0];
            TypewriterPane.TwNote note = (TypewriterPane.TwNote) row[1];
            TypewriterPane.TwFolder parent = folders.getOrDefault(parentOf.get(id), root);
            parent.notes.add(note);
        }
    }

    public static void savePagesPg(Connection c, String uid, TypewriterPane.TwFolder folder,
                                   String parentId, int[] pos) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO TASK.tw_pages(id,user_id,parent_id,kind,title,body,images,pos) VALUES(?,?,?,?,?,?,?,?)")) {
            writePages(ps, uid, folder, parentId, pos);
        }
    }

    public static void savePagesSqlite(Connection c, String uid, TypewriterPane.TwFolder folder,
                                       String parentId, int[] pos) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tw_pages(id,user_id,parent_id,kind,title,body,images,pos) VALUES(?,?,?,?,?,?,?,?)")) {
            writePages(ps, uid, folder, parentId, pos);
        }
    }

    private static void writePages(PreparedStatement ps, String uid, TypewriterPane.TwFolder folder,
                                   String parentId, int[] pos) throws SQLException {
        for (TypewriterPane.TwFolder child : folder.children) {
            ps.setString(1, child.id);
            ps.setString(2, uid);
            ps.setString(3, parentId);
            ps.setString(4, "folder");
            ps.setString(5, child.title);
            ps.setString(6, "");
            ps.setString(7, "");
            ps.setInt(8, pos[0]++);
            ps.executeUpdate();
            writePages(ps, uid, child, child.id, pos);
        }
        for (TypewriterPane.TwNote note : folder.notes) {
            ps.setString(1, note.id);
            ps.setString(2, uid);
            ps.setString(3, parentId);
            ps.setString(4, "note");
            ps.setString(5, note.title);
            ps.setString(6, note.body);
            ps.setString(7, joinImages(note));
            ps.setInt(8, pos[0]++);
            ps.executeUpdate();
        }
    }

    private static void copyFolder(TypewriterPane.TwFolder from, TypewriterPane.TwFolder to) {
        to.title = from.title;
        to.children.clear();
        to.notes.clear();
        for (TypewriterPane.TwFolder child : from.children) {
            TypewriterPane.TwFolder copy = new TypewriterPane.TwFolder(child.id, child.title);
            copyFolder(child, copy);
            to.children.add(copy);
        }
        to.notes.addAll(from.notes);
    }

    private static String toBundle(Data data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"diary\":[");
        boolean first = true;
        for (Map.Entry<LocalDate, TypewriterPane.TwNote> e : data.diary.entrySet()) {
            if (!hasContent(e.getValue())) continue;
            if (!first) sb.append(',');
            first = false;
            appendNote(sb, e.getValue(), e.getKey());
        }
        sb.append("],\"lists\":[");
        first = true;
        for (TypewriterPane.TwList list : data.lists) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{");
            field(sb, "id", list.id, true);
            field(sb, "title", list.title, false);
            sb.append(",\"items\":[");
            boolean firstItem = true;
            for (TypewriterPane.TwItem item : list.items) {
                if (!firstItem) sb.append(',');
                firstItem = false;
                sb.append("{");
                field(sb, "text", item.text, true);
                sb.append(",\"done\":").append(item.done ? "true" : "false");
                sb.append('}');
            }
            sb.append("]}");
        }
        sb.append("],\"pages\":[");
        StringBuilder pages = new StringBuilder();
        flattenPages(pages, data.pagesRoot, null, new int[]{0});
        sb.append(pages);
        sb.append("]}");
        return sb.toString();
    }

    private static void appendNote(StringBuilder sb, TypewriterPane.TwNote n, LocalDate date) {
        sb.append("{");
        field(sb, "date", date == null ? "" : date.toString(), true);
        field(sb, "id", n.id, false);
        field(sb, "title", n.title, false);
        field(sb, "body", n.body, false);
        sb.append(",\"images\":").append(imagesToJson(n));
        sb.append('}');
    }

    private static void flattenPages(StringBuilder sb, TypewriterPane.TwFolder folder,
                                     String parentId, int[] pos) {
        boolean needComma = sb.length() > 0;
        for (TypewriterPane.TwFolder child : folder.children) {
            if (needComma) sb.append(',');
            needComma = true;
            sb.append("{");
            field(sb, "id", child.id, true);
            field(sb, "parent_id", parentId, false);
            field(sb, "kind", "folder", false);
            field(sb, "title", child.title, false);
            field(sb, "body", "", false);
            sb.append(",\"images\":[]");
            sb.append(",\"pos\":").append(pos[0]++);
            sb.append('}');
            flattenPages(sb, child, child.id, pos);
        }
        for (TypewriterPane.TwNote note : folder.notes) {
            if (needComma) sb.append(',');
            needComma = true;
            sb.append("{");
            field(sb, "id", note.id, true);
            field(sb, "parent_id", parentId, false);
            field(sb, "kind", "note", false);
            field(sb, "title", note.title, false);
            field(sb, "body", note.body, false);
            sb.append(",\"images\":").append(imagesToJson(note));
            sb.append(",\"pos\":").append(pos[0]++);
            sb.append('}');
        }
    }

    private static String imagesToJson(TypewriterPane.TwNote note) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (note != null) {
            for (File f : note.images) {
                byte[] bytes = readBytes(f);
                if (bytes == null) continue;
                String sha = sha256Hex(bytes);
                pushImage(sha, f.getName(), bytes);
                if (!first) sb.append(',');
                first = false;
                sb.append("{");
                field(sb, "id", sha, true);
                field(sb, "name", f.getName(), false);
                sb.append('}');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static void applyImageField(TypewriterPane.TwNote note, String raw) {
        if (note == null || raw == null || raw.isBlank()) return;
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            for (String obj : objectsIn(trimmed, null)) {
                String name = nz(extract(obj, "name"));
                String embedded = extract(obj, "data");
                String sha = firstNonBlank(extract(obj, "id"), extract(obj, "sha256"));
                File file = null;
                if (embedded != null && !embedded.isBlank()) {
                    file = writeCachedImage(sha != null ? sha : note.id, embedded);
                } else if (sha != null && !sha.isBlank()) {
                    file = fileFromSha(sha, name);
                }
                if (file != null) note.images.add(file);
            }
            return;
        }
        for (String p : raw.split("\\|")) {
            if (p.isBlank()) continue;
            File f = new File(p);
            if (f.isFile()) note.images.add(f);
        }
    }

    private static File fileFromSha(String sha, String name) {
        File cached = new File(IMAGE_DIR, sha + ".png");
        if (cached.isFile()) return cached;
        if (!nodeReady()) return null;
        try {
            Map<String, String> row = Api.INSTANCE.getTypewriterImage(sha);
            String b64 = first(row, "data");
            return writeCachedImage(sha, b64);
        } catch (Exception e) {
            System.out.println("Typewriter image pull failed: " + sha + " " + e.getMessage());
            return null;
        }
    }

    private static void pushImage(String sha, String name, byte[] bytes) {
        if (!nodeReady() || sha == null || bytes == null) return;
        try {
            IMAGE_DIR.mkdirs();
            File cached = new File(IMAGE_DIR, sha + ".png");
            if (!cached.isFile()) {
                Files.write(cached.toPath(), bytes);
            }
            if (Api.INSTANCE.typewriterImageExists(sha)) {
                System.out.println("Typewriter image already on Node: " + sha);
                return;
            }
            Api.INSTANCE.postTypewriterImage(sha, name, Base64.getEncoder().encodeToString(bytes));
            System.out.println("Typewriter image uploaded: " + sha);
        } catch (Exception e) {
            System.out.println("Typewriter image push failed: " + e.getMessage());
        }
    }

    private static File writeCachedImage(String id, String b64) {
        if (id == null || id.isBlank() || b64 == null || b64.isBlank()) return null;
        try {
            IMAGE_DIR.mkdirs();
            File out = new File(IMAGE_DIR, id + ".png");
            Files.write(out.toPath(), Base64.getDecoder().decode(b64));
            return out;
        } catch (Exception e) {
            System.out.println("Typewriter image write failed: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readBytes(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            System.out.println("Typewriter image read failed: " + f + " " + e.getMessage());
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
    }

    private static Data parseBundle(String json) {
        Data data = new Data();
        if (json == null || json.isBlank()) return data;
        for (String obj : objectsIn(json, "diary")) {
            LocalDate day = parseDate(extract(obj, "date"));
            if (day == null) continue;
            TypewriterPane.TwNote note = new TypewriterPane.TwNote(
                    nz(extract(obj, "id")), nz(extract(obj, "title")), day);
            note.body = nz(extract(obj, "body"));
            applyImageField(note, imageSlice(obj));
            if (hasContent(note)) data.diary.put(day, note);
        }
        for (String obj : objectsIn(json, "lists")) {
            TypewriterPane.TwList list = new TypewriterPane.TwList(
                    nz(extract(obj, "id")), nz(extract(obj, "title")));
            for (String itemObj : objectsIn(obj, "items")) {
                TypewriterPane.TwItem item = new TypewriterPane.TwItem(nz(extract(itemObj, "text")));
                String done = extract(itemObj, "done");
                item.done = "true".equalsIgnoreCase(done) || "1".equals(done);
                list.items.add(item);
            }
            data.lists.add(list);
        }
        Map<String, TypewriterPane.TwFolder> folders = new LinkedHashMap<>();
        Map<String, String> parentOf = new LinkedHashMap<>();
        folders.put("root", data.pagesRoot);
        List<Object[]> notes = new ArrayList<>();
        for (String obj : objectsIn(json, "pages")) {
            String id = nz(extract(obj, "id"));
            String parent = extract(obj, "parent_id");
            parentOf.put(id, parent == null || parent.isBlank() ? "root" : parent);
            if ("folder".equals(extract(obj, "kind"))) {
                folders.put(id, new TypewriterPane.TwFolder(id, nz(extract(obj, "title"))));
            } else {
                TypewriterPane.TwNote note = new TypewriterPane.TwNote(id, nz(extract(obj, "title")), null);
                note.body = nz(extract(obj, "body"));
                applyImageField(note, imageSlice(obj));
                notes.add(new Object[]{id, note});
            }
        }
        for (Map.Entry<String, TypewriterPane.TwFolder> e : folders.entrySet()) {
            if ("root".equals(e.getKey())) continue;
            TypewriterPane.TwFolder parent = folders.getOrDefault(parentOf.get(e.getKey()), data.pagesRoot);
            parent.children.add(e.getValue());
        }
        for (Object[] row : notes) {
            String id = (String) row[0];
            TypewriterPane.TwNote note = (TypewriterPane.TwNote) row[1];
            TypewriterPane.TwFolder parent = folders.getOrDefault(parentOf.get(id), data.pagesRoot);
            parent.notes.add(note);
        }
        return data;
    }

    private static String imageSlice(String obj) {
        int key = obj.indexOf("\"images\"");
        if (key < 0) return extract(obj, "images");
        int colon = obj.indexOf(':', key);
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) p++;
        if (p >= obj.length()) return null;
        if (obj.charAt(p) == '[') {
            int depth = 0;
            boolean inStr = false;
            for (int i = p; i < obj.length(); i++) {
                char ch = obj.charAt(i);
                if (ch == '\\' && inStr) {
                    i++;
                    continue;
                }
                if (ch == '"') {
                    inStr = !inStr;
                    continue;
                }
                if (inStr) continue;
                if (ch == '[') depth++;
                else if (ch == ']') {
                    depth--;
                    if (depth == 0) return obj.substring(p, i + 1);
                }
            }
        }
        return extract(obj, "images");
    }

    private static void field(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append('"').append(':');
        if (value == null) sb.append("null");
        else sb.append('"').append(escape(value)).append('"');
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
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
        if (p >= json.length() || json.startsWith("null", p)) return null;
        if (json.charAt(p) != '"') {
            int end = p;
            while (end < json.length()) {
                char ch = json.charAt(end);
                if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
                end++;
            }
            return json.substring(p, end);
        }
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

    private static List<String> objectsIn(String json, String arrayKey) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        int bracket;
        if (arrayKey == null) {
            bracket = json.indexOf('[');
        } else {
            String needle = "\"" + arrayKey + "\"";
            int keyAt = json.indexOf(needle);
            bracket = json.indexOf('[', keyAt < 0 ? 0 : keyAt);
        }
        if (bracket < 0) return out;
        int i = bracket + 1;
        while (i < json.length()) {
            while (i < json.length() && json.charAt(i) != '{' && json.charAt(i) != ']') i++;
            if (i >= json.length() || json.charAt(i) == ']') break;
            int close = matchingBrace(json, i);
            if (close < 0) break;
            out.add(json.substring(i, close + 1));
            i = close + 1;
        }
        return out;
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

    private static String first(Map<String, String> row, String... keys) {
        if (row == null) return null;
        for (String k : keys) {
            String v = row.get(k);
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return null;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEmpty(Data data) {
        return data.diary.isEmpty() && data.lists.isEmpty()
                && data.pagesRoot.children.isEmpty() && data.pagesRoot.notes.isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}