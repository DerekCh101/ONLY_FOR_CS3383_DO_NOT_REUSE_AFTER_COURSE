package org.example.GUI;

import org.example.db.PostgresTypewriterRepository;
import org.example.db.SqliteTypewriterRepository;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

    private static final SqliteTypewriterRepository sqlite =
            new SqliteTypewriterRepository("jdbc:sqlite:scheduler-cache.db");

    private static final PostgresTypewriterRepository postgres = new PostgresTypewriterRepository(
            "jdbc:postgresql://100.113.239.82:5432/Scheduler",
            "postgres",
            System.getenv("postgres_pw")
    );

    private static boolean postgresAvailable;

    static {
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
            return org.example.net.Api.INSTANCE.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    public static Data load() {
        Data empty = new Data();
        String uid = currentUserId();
        if (uid == null || uid.isBlank()) {
            System.out.println("Typewriter load skipped: no user");
            return empty;
        }
        try {
            if (postgresAvailable) {
                Instant remote = null;
                Instant local = sqlite.lastUpdated(uid);
                try {
                    remote = postgres.lastUpdated(uid);
                } catch (SQLException e) {
                    postgresAvailable = false;
                    System.out.println("Typewriter Postgres read failed: " + e.getMessage());
                }
                if (postgresAvailable && remote != null && (local == null || remote.isAfter(local))) {
                    Data pulled = postgres.fetchAll(uid);
                    sqlite.replaceAll(uid, pulled.diary, pulled.lists, pulled.pagesRoot);
                    return pulled;
                }
                Data localData = sqlite.fetchAll(uid);
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
            return sqlite.fetchAll(uid);
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
    }

    public static void addImages(TypewriterPane.TwNote note, String raw) {
        if (note == null || raw == null || raw.isBlank()) return;
        for (String p : raw.split("\\|")) {
            if (p.isBlank()) continue;
            File f = new File(p);
            if (f.isFile()) note.images.add(f);
        }
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

    private static boolean isEmpty(Data data) {
        return data.diary.isEmpty() && data.lists.isEmpty()
                && data.pagesRoot.children.isEmpty() && data.pagesRoot.notes.isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}