package org.example.db;

import org.example.*;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local offline cache on this machine (one file, e.g. scheduler-cache.db).
 * Works when Postgres/Tailscale is down. TaskRepository syncs this with Postgres when online.
 *
 * Tables (no schema name — SQLite is simpler):
 *   tasks        – calendar tasks on this device
 *   sync_deletes – ids deleted here (pushed to server later)
 *   sync_meta    – small key/value (last sync time, etc.)
 */
public class SqliteTaskRepository {

    /** JDBC URL, e.g. jdbc:sqlite:scheduler-cache.db */
    private final String url;

    /**
     * Stores the file URL. Does not create the file until connect/initialize.
     */
    public SqliteTaskRepository(String url) {
        this.url = url;
    }

    /**
     * Opens a connection to the local SQLite file (creates file if missing).
     * Always close with try-with-resources.
     */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /**
     * Creates tables if they do not exist.
     * ALTER in try/catch: older cache files may lack new columns; SQLite has limited IF NOT EXISTS for columns.
     */
    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    duration REAL,
                    placed_date TEXT,
                    placed_time TEXT,
                    state TEXT NOT NULL,
                    deadline_date TEXT,
                    deadline_time TEXT,
                    lifecycle TEXT,
                    workflow TEXT,
                    placement_updated_at TEXT,
                    last_updated_at TEXT
                )
                """);
            // Same idea as Postgres sync_deletes, but local-only until synced
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_deletes (
                    id TEXT PRIMARY KEY,
                    deleted_at TEXT NOT NULL
                )
                """);
            // Tiny settings store: key → value strings
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
                """);
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN last_updated_at TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN placement_updated_at TEXT"); } catch (SQLException ignored) {}
        }
    }

    /**
     * Nuclear local reset (stale device policy).
     * Clears tasks and delete log; clears sync timestamps so next online run does a full pull.
     * Does not delete the whole .db file.
     */
    public void wipeAllLocalData() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tasks");
            st.execute("DELETE FROM sync_deletes");
            st.execute("DELETE FROM sync_meta WHERE key IN ('last_postgres_sync_at','last_delete_sync_at')");
        }
    }

    /**
     * Writes one meta key (insert or replace).
     * Example keys: last_postgres_sync_at, last_delete_sync_at.
     */
    public void setMeta(String key, String value) throws SQLException {
        String sql = """
            INSERT INTO sync_meta(key, value) VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /**
     * Reads one meta value, or null if the key was never set.
     */
    public String getMeta(String key) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM sync_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        }
    }

    /**
     * Insert task or update if id already exists (UPSERT) into local cache.
     * Dates/times stored as text (ISO strings) because SQLite has fewer native types.
     */
    public void upsert(Task task) throws SQLException {
        String sql = """
            INSERT INTO tasks (
                id, title, duration, state,
                placed_date, placed_time,
                deadline_date, deadline_time,
                lifecycle, workflow,
                placement_updated_at, last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                duration = excluded.duration,
                state = excluded.state,
                placed_date = excluded.placed_date,
                placed_time = excluded.placed_time,
                deadline_date = excluded.deadline_date,
                deadline_time = excluded.deadline_time,
                lifecycle = excluded.lifecycle,
                workflow = excluded.workflow,
                placement_updated_at = excluded.placement_updated_at,
                last_updated_at = excluded.last_updated_at
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, task);
            ps.executeUpdate();
        }
    }

    /**
     * Updates only workflow + last_updated_at locally (e.g. mark finished).
     */
    public void updateWorkflow(Task task) throws SQLException {
        String sql = "UPDATE tasks SET workflow = ?, last_updated_at = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
            Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();
            ps.setString(1, flow.name());
            ps.setString(2, last.toString());
            ps.setString(3, task.getID());
            ps.executeUpdate();
        }
    }

    /**
     * Deletes the task row only. Does not write sync_deletes.
     * Used when applying a remote delete, or inside deleteTaskAndLog.
     */
    public void deleteTaskRow(String taskId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        }
    }

    /**
     * Records "this id was deleted at deletedAt" in local sync_deletes.
     * Keeps the later timestamp if the id was already logged.
     */
    public void recordDelete(String taskId, Instant deletedAt) throws SQLException {
        String sql = """
            INSERT INTO sync_deletes(id, deleted_at) VALUES(?, ?)
            ON CONFLICT(id) DO UPDATE SET
                deleted_at = CASE
                    WHEN excluded.deleted_at > sync_deletes.deleted_at THEN excluded.deleted_at
                    ELSE sync_deletes.deleted_at
                END
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, deletedAt.toString());
            ps.executeUpdate();
        }
    }

    /**
     * User deleted a task while offline (or always): remove row + log id for later Postgres push.
     */
    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when);
    }

    /**
     * Clear-all: every local task removed and each id written to sync_deletes.
     */
    public void deleteAllTasksAndLogEach() throws SQLException {
        List<Task> all = fetchAll();
        Instant when = Instant.now();
        for (Task t : all) {
            deleteTaskRow(t.getID());
            recordDelete(t.getID(), when);
        }
    }

    /**
     * All local delete-log rows as String[]{ id, deletedAtIso }.
     * TaskRepository pushes these to Postgres when online.
     */
    public List<String[]> fetchAllDeletes() throws SQLException {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT id, deleted_at FROM sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        return list;
    }

    /**
     * Just the set of deleted task ids (fast membership tests during merge/load).
     */
    public Set<String> fetchDeletedIds() throws SQLException {
        Set<String> set = new HashSet<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(rs.getString(1));
        }
        return set;
    }

    /**
     * Drops local delete-log rows older than cutoff (after GC policy says it is safe).
     */
    public void purgeDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sync_deletes WHERE deleted_at < ?")) {
            ps.setString(1, cutoff.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Loads every local task into Java objects.
     */
    public List<Task> fetchAll() throws SQLException {
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /**
     * One task by id, or null if not in local cache.
     * Used during merge: "do we already have this remote id?"
     */
    public Task findById(String id) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
                return null;
            }
        }
    }

    /**
     * Tasks that look unscheduled (side panel): lifecycle flag or no placed date.
     */
    public List<Task> fetchUnscheduled() throws SQLException {
        String sql = """
            SELECT * FROM tasks
            WHERE lifecycle = 'UNSCHEDULED' OR placed_date IS NULL
            ORDER BY title
            """;
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    /**
     * True if some local task already sits on that date+time (simple conflict helper).
     */
    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        String sql = "SELECT 1 FROM tasks WHERE placed_date = ? AND placed_time = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, placedDate.toString());
            ps.setString(2, placedTime.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Fills INSERT/UPSERT placeholders from a Task.
     * Private — SQLite gets strings for dates/times/instants.
     */
    private void bind(PreparedStatement ps, Task task) throws SQLException {
        String state = task.getState() == 1 ? "Fixed"
                : task.getState() == 2 ? "Deadline" : "Flexible";
        TaskLifeCycle life = task.getLifeCycle() != null ? task.getLifeCycle() : TaskLifeCycle.UNSCHEDULED;
        Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
        Instant placement = task.getPlacementUpdatedAt() != null ? task.getPlacementUpdatedAt() : Instant.now();
        Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();

        ps.setString(1, task.getID());
        ps.setString(2, task.getTaskName());
        ps.setDouble(3, task.getDurationHours());
        ps.setString(4, state);
        ps.setString(5, task.getPlacedDate() != null ? task.getPlacedDate().toString() : null);
        ps.setString(6, task.getPlacedTime() != null ? task.getPlacedTime().toString() : null);
        if (task.getState() == 2 && task.getDeadLineDate() != null) {
            ps.setString(7, task.getDeadLineDate().toString());
            ps.setString(8, task.getDeadLineTime() != null ? task.getDeadLineTime().toString() : null);
        } else {
            ps.setString(7, null);
            ps.setString(8, null);
        }
        ps.setString(9, life.name());
        ps.setString(10, flow.name());
        ps.setString(11, placement.toString());
        ps.setString(12, last.toString());
    }

    /**
     * One SQL row → Task. Private helper for fetchAll / findById / fetchUnscheduled.
     */
    private Task mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("title");
        double duration = rs.getDouble("duration");
        String state = rs.getString("state");
        int numberState = "Fixed".equals(state) ? 1 : "Deadline".equals(state) ? 2 : 3;

        LocalDate placedDate = parseDate(rs.getString("placed_date"));
        LocalTime placedTime = parseTime(rs.getString("placed_time"));
        LocalDate deadlineDate = parseDate(rs.getString("deadline_date"));
        LocalTime deadlineTime = parseTime(rs.getString("deadline_time"));

        String lifeStr = rs.getString("lifecycle");
        String flowStr = rs.getString("workflow");
        TaskLifeCycle life = (lifeStr == null || lifeStr.isBlank())
                ? TaskLifeCycle.UNSCHEDULED : TaskLifeCycle.valueOf(lifeStr);
        Workflow flow = (flowStr == null || flowStr.isBlank())
                ? Workflow.UNFINISHED : Workflow.valueOf(flowStr);

        Task task = Task.loadTaskFromdb(
                id, name, duration, numberState,
                null, null,
                deadlineDate, deadlineTime,
                placedDate, placedTime
        );
        task.setLifeCycle(life);
        task.setWorkflow(flow);

        Instant p = parseInstant(rs.getString("placement_updated_at"));
        Instant l = parseInstant(rs.getString("last_updated_at"));
        if (p != null) task.setPlacementUpdatedAt(p);
        if (l != null) task.setLastUpdatedAt(l);
        else if (p != null) task.setLastUpdatedAt(p);
        return task;
    }

    /** Parse ISO date text, or null if empty. */
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }

    /** Parse ISO time text, or null if empty. */
    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalTime.parse(s);
    }

    /** Parse ISO-8601 instant text, or null if empty. */
    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        return Instant.parse(s);
    }
}