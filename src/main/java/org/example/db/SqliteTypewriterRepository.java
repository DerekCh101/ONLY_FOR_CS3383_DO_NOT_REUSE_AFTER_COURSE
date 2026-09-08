package org.example.db;

import org.example.GUI.TypewriterPane;
import org.example.GUI.TypewriterStore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SqliteTypewriterRepository {

    private final String url;

    public SqliteTypewriterRepository(String url) {
        this.url = url;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tw_diary (
                    user_id TEXT NOT NULL,
                    day TEXT NOT NULL,
                    title TEXT,
                    body TEXT,
                    images TEXT,
                    PRIMARY KEY (user_id, day)
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tw_lists (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    title TEXT,
                    pos INTEGER
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tw_list_items (
                    list_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    pos INTEGER NOT NULL,
                    text TEXT,
                    done INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (list_id, pos)
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tw_pages (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    parent_id TEXT,
                    kind TEXT NOT NULL,
                    title TEXT,
                    body TEXT,
                    images TEXT,
                    pos INTEGER
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tw_meta (
                    user_id TEXT PRIMARY KEY,
                    last_updated_at TEXT NOT NULL
                )
                """);
        }
    }

    public Instant lastUpdated(String uid) throws SQLException {
        if (uid == null || uid.isBlank()) return null;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_updated_at FROM tw_meta WHERE user_id = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String s = rs.getString(1);
                if (s == null || s.isBlank()) return null;
                return Instant.parse(s);
            }
        }
    }

    public TypewriterStore.Data fetchAll(String uid) throws SQLException {
        TypewriterStore.Data data = new TypewriterStore.Data();
        if (uid == null || uid.isBlank()) return data;
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT day, title, body, images FROM tw_diary WHERE user_id = ?")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate day = LocalDate.parse(rs.getString(1));
                        TypewriterPane.TwNote note = new TypewriterPane.TwNote(rs.getString(2), day);
                        note.body = nz(rs.getString(3));
                        TypewriterStore.addImages(note, rs.getString(4));
                        data.diary.put(day, note);
                    }
                }
            }
            Map<String, TypewriterPane.TwList> byId = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, title FROM tw_lists WHERE user_id = ? ORDER BY pos")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TypewriterPane.TwList list = new TypewriterPane.TwList(rs.getString(1), rs.getString(2));
                        byId.put(list.id, list);
                        data.lists.add(list);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT list_id, text, done FROM tw_list_items WHERE user_id = ? ORDER BY pos")) {
                ps.setString(1, uid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TypewriterPane.TwList list = byId.get(rs.getString(1));
                        if (list == null) continue;
                        TypewriterPane.TwItem item = new TypewriterPane.TwItem(nz(rs.getString(2)));
                        item.done = rs.getInt(3) != 0;
                        list.items.add(item);
                    }
                }
            }
            TypewriterStore.rebuildPages(conn, uid, data.pagesRoot, false);
        }
        return data;
    }

    public void replaceAll(String uid,
                           Map<LocalDate, TypewriterPane.TwNote> diary,
                           List<TypewriterPane.TwList> lists,
                           TypewriterPane.TwFolder pagesRoot) throws SQLException {
        if (uid == null || uid.isBlank()) return;
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            wipeUser(conn, uid);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tw_diary(user_id,day,title,body,images) VALUES(?,?,?,?,?)")) {
                for (TypewriterPane.TwNote note : diary.values()) {
                    if (note.date == null) continue;
                    if (blank(note.body) && blank(note.title) && note.images.isEmpty()) continue;
                    ps.setString(1, uid);
                    ps.setString(2, note.date.toString());
                    ps.setString(3, note.title);
                    ps.setString(4, note.body);
                    ps.setString(5, TypewriterStore.joinImages(note));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement lp = conn.prepareStatement(
                    "INSERT INTO tw_lists(id,user_id,title,pos) VALUES(?,?,?,?)");
                 PreparedStatement ip = conn.prepareStatement(
                         "INSERT INTO tw_list_items(list_id,user_id,pos,text,done) VALUES(?,?,?,?,?)")) {
                int li = 0;
                for (TypewriterPane.TwList list : lists) {
                    lp.setString(1, list.id);
                    lp.setString(2, uid);
                    lp.setString(3, list.title);
                    lp.setInt(4, li++);
                    lp.addBatch();
                    int ii = 0;
                    for (TypewriterPane.TwItem item : list.items) {
                        ip.setString(1, list.id);
                        ip.setString(2, uid);
                        ip.setInt(3, ii++);
                        ip.setString(4, item.text);
                        ip.setInt(5, item.done ? 1 : 0);
                        ip.addBatch();
                    }
                }
                lp.executeBatch();
                ip.executeBatch();
            }
            int[] pos = {0};
            TypewriterStore.savePagesSqlite(conn, uid, pagesRoot, "root", pos);
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO tw_meta(user_id, last_updated_at) VALUES(?, ?)
                    ON CONFLICT(user_id) DO UPDATE SET last_updated_at = excluded.last_updated_at
                    """)) {
                ps.setString(1, uid);
                ps.setString(2, Instant.now().toString());
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    public void wipeUser(String uid) throws SQLException {
        if (uid == null || uid.isBlank()) return;
        try (Connection conn = connect()) {
            wipeUser(conn, uid);
        }
    }

    private static void wipeUser(Connection conn, String uid) throws SQLException {
        try (PreparedStatement a = conn.prepareStatement("DELETE FROM tw_diary WHERE user_id = ?");
             PreparedStatement b = conn.prepareStatement("DELETE FROM tw_list_items WHERE user_id = ?");
             PreparedStatement c = conn.prepareStatement("DELETE FROM tw_lists WHERE user_id = ?");
             PreparedStatement d = conn.prepareStatement("DELETE FROM tw_pages WHERE user_id = ?");
             PreparedStatement e = conn.prepareStatement("DELETE FROM tw_meta WHERE user_id = ?")) {
            a.setString(1, uid); a.executeUpdate();
            b.setString(1, uid); b.executeUpdate();
            c.setString(1, uid); c.executeUpdate();
            d.setString(1, uid); d.executeUpdate();
            e.setString(1, uid); e.executeUpdate();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}