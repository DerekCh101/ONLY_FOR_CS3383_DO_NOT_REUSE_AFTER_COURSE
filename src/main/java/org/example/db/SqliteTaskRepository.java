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
 * Local offline cache (scheduler-cache.db).
 * tasks / sync_deletes / sync_meta / blocked_times / sync_deletes_blocked
 * Dates/times stored as TEXT.
 */
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
                    last_updated_at TEXT,
                    user_id TEXT
                )
                """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_deletes (
                    id TEXT PRIMARY KEY,
                    deleted_at TEXT NOT NULL,
                    title TEXT,
                    user_id TEXT
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
            try { st.executeUpdate("ALTER TABLE tasks ADD COLUMN user_id TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE sync_deletes ADD COLUMN title TEXT"); } catch (SQLException ignored) {}
            try { st.executeUpdate("ALTER TABLE sync_deletes ADD COLUMN user_id TEXT"); } catch (SQLException ignored) {}

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS blocked_times (
                    id TEXT PRIMARY KEY,
                    title TEXT,
                    weekday INTEGER,
                    specific_date TEXT,
                    start_time TEXT NOT NULL,
                    end_time TEXT NOT NULL,
                    last_updated_at TEXT NOT NULL,
                    user_id TEXT,
                    is_exception INTEGER NOT NULL DEFAULT 0,
                    CHECK (weekday IS NULL OR (weekday >= 0 AND weekday <= 6)),
                    CHECK (
                        (weekday IS NULL AND specific_date IS NULL)
                        OR (weekday IS NOT NULL AND specific_date IS NULL)
                        OR (weekday IS NULL AND specific_date IS NOT NULL)
                    )
                )
                """);
            try { st.executeUpdate("ALTER TABLE blocked_times ADD COLUMN is_exception INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_deletes_blocked (
                    id TEXT PRIMARY KEY,
                    deleted_at TEXT NOT NULL,
                    title TEXT
                )
                """);
        }
    }

    public boolean existsTaskId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM tasks WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean isDeletedId(String id) throws SQLException {
        if (id == null || id.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sync_deletes WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void wipeAllLocalData() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tasks");
            st.execute("DELETE FROM sync_deletes");
            st.execute("DELETE FROM blocked_times");
            st.execute("DELETE FROM sync_deletes_blocked");
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
                placement_updated_at, last_updated_at,
                user_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                last_updated_at = excluded.last_updated_at,
                user_id = excluded.user_id
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
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "DELETE FROM tasks WHERE id = ?"
                : "DELETE FROM tasks WHERE id = ? AND (user_id = ? OR user_id IS NULL)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            if (uid != null && !uid.isBlank()) ps.setString(2, uid);
            int n = ps.executeUpdate();
            System.out.println("sqlite deleteTaskRow id=" + taskId + " rows=" + n);
        }
    }

    public void unrecordDelete(String taskId) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sync_deletes WHERE id = ?")) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        }
    }

    public void recordDelete(String taskId, Instant deletedAt, String title) throws SQLException {
        String sql = """
            INSERT INTO sync_deletes(id, deleted_at, title, user_id) VALUES(?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                deleted_at = CASE
                    WHEN excluded.deleted_at > sync_deletes.deleted_at THEN excluded.deleted_at
                    ELSE sync_deletes.deleted_at
                END,
                title = COALESCE(excluded.title, sync_deletes.title),
                user_id = COALESCE(excluded.user_id, sync_deletes.user_id)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, deletedAt.toString());
            ps.setString(3, (title != null && !title.isBlank()) ? title : null);
            String uid = currentUserId();
            ps.setString(4, (uid != null && !uid.isBlank()) ? uid : null);
            ps.executeUpdate();
        }
    }

    public void recordDelete(String taskId, Instant deletedAt) throws SQLException {
        recordDelete(taskId, deletedAt, null);
    }

    public void deleteTaskAndLog(Task task) throws SQLException {
        Instant when = Instant.now();
        String title = task.getTaskName();
        deleteTaskRow(task.getID());
        recordDelete(task.getID(), when, title);
    }

    public void deleteAllTasksAndLogEach() throws SQLException {
        List<Task> all = fetchAll();
        Instant when = Instant.now();
        for (Task t : all) {
            deleteTaskRow(t.getID());
            recordDelete(t.getID(), when, t.getTaskName());
        }
    }

    public List<String[]> fetchAllDeletes() throws SQLException {
        String uid = currentUserId();
        List<String[]> list = new ArrayList<>();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT id, deleted_at, title FROM sync_deletes WHERE 1 = 0"
                : "SELECT id, deleted_at, title FROM sync_deletes WHERE user_id = ? OR user_id IS NULL";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String title = rs.getString(3);
                    list.add(new String[]{
                            rs.getString(1),
                            rs.getString(2),
                            title != null ? title : ""
                    });
                }
            }
        }
        return list;
    }

    public Set<String> fetchDeletedIds() throws SQLException {
        Set<String> set = new HashSet<>();
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT id FROM sync_deletes WHERE 1 = 0"
                : "SELECT id FROM sync_deletes WHERE user_id = ? OR user_id IS NULL";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) set.add(rs.getString(1));
            }
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
        String uid = currentUserId();
        List<Task> list = new ArrayList<>();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT * FROM tasks WHERE 1 = 0"
                : "SELECT * FROM tasks WHERE user_id = ? OR user_id IS NULL";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    public Task findById(String id) throws SQLException {
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT * FROM tasks WHERE id = ? AND 1 = 0"
                : "SELECT * FROM tasks WHERE id = ? AND (user_id = ? OR user_id IS NULL)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            if (uid != null && !uid.isBlank()) ps.setString(2, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
                return null;
            }
        }
    }

    public List<Task> fetchUnscheduled() throws SQLException {
        String uid = currentUserId();
        if (uid == null || uid.isBlank()) return new ArrayList<>();
        String sql = """
            SELECT * FROM tasks
            WHERE user_id = ?
              AND (lifecycle = 'UNSCHEDULED' OR placed_date IS NULL)
            ORDER BY title
            """;
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        String uid = currentUserId();
        if (uid == null || uid.isBlank()) return false;
        String sql = "SELECT 1 FROM tasks WHERE placed_date = ? AND placed_time = ? AND user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, placedDate.toString());
            ps.setString(2, placedTime.toString());
            ps.setString(3, uid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void upsertBlocked(BlockedTime b) throws SQLException {
        if (b == null || b.getStartTime() == null || b.getEndTime() == null) {
            System.out.println("sqlite skip blocked missing time id="
                    + (b == null ? "null" : b.getId())
                    + " start=" + (b == null ? null : b.getStartTime())
                    + " end=" + (b == null ? null : b.getEndTime()));
            return;
        }
        String sql = """
            INSERT INTO blocked_times (
                id, title, weekday, specific_date, start_time, end_time,
                last_updated_at, user_id, is_exception
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                weekday = excluded.weekday,
                specific_date = excluded.specific_date,
                start_time = excluded.start_time,
                end_time = excluded.end_time,
                last_updated_at = excluded.last_updated_at,
                user_id = excluded.user_id,
                is_exception = excluded.is_exception
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindBlocked(ps, b);
            ps.executeUpdate();
        }
    }

    public List<BlockedTime> fetchAllBlocked() throws SQLException {
        String uid = currentUserId();
        List<BlockedTime> list = new ArrayList<>();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT * FROM blocked_times WHERE 1 = 0"
                : "SELECT * FROM blocked_times WHERE user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (uid != null && !uid.isBlank()) ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapBlocked(rs));
            }
        }
        return list;
    }

    public BlockedTime findBlockedById(String id) throws SQLException {
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "SELECT * FROM blocked_times WHERE id = ? AND 1 = 0"
                : "SELECT * FROM blocked_times WHERE id = ? AND user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            if (uid != null && !uid.isBlank()) ps.setString(2, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapBlocked(rs);
                return null;
            }
        }
    }

    public void deleteBlockedRow(String id) throws SQLException {
        String uid = currentUserId();
        String sql = (uid == null || uid.isBlank())
                ? "DELETE FROM blocked_times WHERE id = ? AND 1 = 0"
                : "DELETE FROM blocked_times WHERE id = ? AND user_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            if (uid != null && !uid.isBlank()) ps.setString(2, uid);
            ps.executeUpdate();
        }
    }

    public void recordBlockedDelete(String id, Instant deletedAt, String title) throws SQLException {
        String sql = """
            INSERT INTO sync_deletes_blocked(id, deleted_at, title) VALUES(?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                deleted_at = CASE
                    WHEN excluded.deleted_at > sync_deletes_blocked.deleted_at THEN excluded.deleted_at
                    ELSE sync_deletes_blocked.deleted_at
                END,
                title = COALESCE(excluded.title, sync_deletes_blocked.title)
            """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, deletedAt.toString());
            ps.setString(3, (title != null && !title.isBlank()) ? title : null);
            ps.executeUpdate();
        }
    }

    public void deleteBlockedAndLog(BlockedTime b) throws SQLException {
        Instant when = Instant.now();
        deleteBlockedRow(b.getId());
        recordBlockedDelete(b.getId(), when, b.getTitle());
    }

    public List<String[]> fetchAllBlockedDeletes() throws SQLException {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, deleted_at, title FROM sync_deletes_blocked");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String title = rs.getString(3);
                list.add(new String[]{
                        rs.getString(1),
                        rs.getString(2),
                        title != null ? title : ""
                });
            }
        }
        return list;
    }

    public Set<String> fetchBlockedDeletedIds() throws SQLException {
        Set<String> set = new HashSet<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM sync_deletes_blocked");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) set.add(rs.getString(1));
        }
        return set;
    }

    public void purgeBlockedDeletesBefore(Instant cutoff) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sync_deletes_blocked WHERE deleted_at < ?")) {
            ps.setString(1, cutoff.toString());
            ps.executeUpdate();
        }
    }

    private static String currentUserId() {
        try {
            return org.example.net.Api.INSTANCE.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private void bind(PreparedStatement ps, Task task) throws SQLException {
        String state = task.getState() == 1 ? "Fixed"
                : task.getState() == 2 ? "Deadline" : "Flexible";
        TaskLifeCycle life = task.getLifeCycle() != null ? task.getLifeCycle() : TaskLifeCycle.UNSCHEDULED;
        Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
        Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();
        Instant placement = task.getPlacementUpdatedAt();
        if (placement == null && task.getPlacedDate() != null) {
            placement = last;
        }
        String uid = currentUserId();

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
        ps.setString(11, placement != null ? placement.toString() : null);
        ps.setString(12, last.toString());
        ps.setString(13, (uid != null && !uid.isBlank()) ? uid : null);
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

    private void bindBlocked(PreparedStatement ps, BlockedTime b) throws SQLException {
        Instant last = b.getLastUpdatedAt() != null ? b.getLastUpdatedAt() : Instant.now();
        String uid = b.getUserId();
        if (uid == null || uid.isBlank()) uid = currentUserId();
        ps.setString(1, b.getId());
        ps.setString(2, b.getTitle());
        if (b.getWeekday() != null) ps.setInt(3, toWeekdayInt(b.getWeekday()));
        else ps.setNull(3, Types.INTEGER);
        ps.setString(4, b.getSpecificDate() != null ? b.getSpecificDate().toString() : null);
        ps.setString(5, b.getStartTime().toString());
        ps.setString(6, b.getEndTime().toString());
        ps.setString(7, last.toString());
        ps.setString(8, (uid != null && !uid.isBlank()) ? uid : null);
        ps.setInt(9, b.isException() ? 1 : 0);
    }

    private BlockedTime mapBlocked(ResultSet rs) throws SQLException {
        int wd = rs.getInt("weekday");
        Integer weekday = rs.wasNull() ? null : wd;
        BlockedTime b = new BlockedTime(
                rs.getString("id"),
                rs.getString("title"),
                toDayOfWeek(weekday),
                parseDate(rs.getString("specific_date")),
                parseTime(rs.getString("start_time")),
                parseTime(rs.getString("end_time")),
                parseInstant(rs.getString("last_updated_at")),
                rs.getString("user_id")
        );
        try {
            b.setException(rs.getInt("is_exception") != 0);
        } catch (SQLException ignored) {
            b.setException(false);
        }
        return b;
    }

    private static int toWeekdayInt(Object weekday) {
        if (weekday instanceof Integer i) return i;
        if (weekday instanceof java.time.DayOfWeek d) return d.getValue() % 7;
        return Integer.parseInt(weekday.toString());
    }

    private static java.time.DayOfWeek toDayOfWeek(Integer weekday) {
        if (weekday == null) return null;
        if (weekday == 0) return java.time.DayOfWeek.SUNDAY;
        return java.time.DayOfWeek.of(weekday);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() >= 8) t = t.substring(0, 8);
        try {
            return LocalTime.parse(t);
        } catch (Exception e) {
            try {
                return LocalTime.parse(t.substring(0, Math.min(5, t.length())));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}