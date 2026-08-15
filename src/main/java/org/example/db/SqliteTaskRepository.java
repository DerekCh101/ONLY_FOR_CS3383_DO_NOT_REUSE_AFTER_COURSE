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

public class SqliteTaskRepository {

    private final String url;

    public SqliteTaskRepository(String url) {
        this.url = url;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

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
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_deletes (
                    id TEXT PRIMARY KEY,
                    deleted_at TEXT NOT NULL
                )
                """);
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

    /** Nuclear local reset (stale device). */
    public void wipeAllLocalData() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tasks");
            st.execute("DELETE FROM sync_deletes");
            // keep sync_meta device-related keys optional — clear sync cursors
            st.execute("DELETE FROM sync_meta WHERE key IN ('last_postgres_sync_at','last_delete_sync_at')");
        }
    }

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

    public void deleteTaskRow(String taskId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        }
    }

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

    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when);
    }

    public void deleteAllTasksAndLogEach() throws SQLException {
        List<Task> all = fetchAll();
        Instant when = Instant.now();
        for (Task t : all) {
            deleteTaskRow(t.getID());
            recordDelete(t.getID(), when);
        }
    }

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

    public Set<String> fetchDeletedIds() throws SQLException {
        Set<String> set = new HashSet<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM sync_deletes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(rs.getString(1));
        }
        return set;
    }

    public void purgeDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sync_deletes WHERE deleted_at < ?")) {
            ps.setString(1, cutoff.toString());
            ps.executeUpdate();
        }
    }

    public List<Task> fetchAll() throws SQLException {
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM tasks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

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

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalDate.parse(s);
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        return LocalTime.parse(s);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        return Instant.parse(s);
    }
}