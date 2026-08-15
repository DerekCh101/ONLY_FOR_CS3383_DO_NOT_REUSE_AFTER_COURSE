package org.example.db;

import org.example.*;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class PostgresTaskRepository {

    private final String url;
    private final String user;
    private final String password;

    public PostgresTaskRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public void initialize() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            st.execute("CREATE SCHEMA IF NOT EXISTS TASK");

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS TASK.tasks (
                    id VARCHAR(36) PRIMARY KEY,
                    title VARCHAR(100),
                    duration double precision,
                    placed_date DATE,
                    placed_time TIME,
                    state VARCHAR(20) NOT NULL CHECK (state IN ('Fixed', 'Deadline', 'Flexible')),
                    deadline_date DATE,
                    deadline_time TIME,
                    lifecycle VARCHAR(20),
                    workflow VARCHAR(20),
                    placement_updated_at TIMESTAMPTZ DEFAULT NOW(),
                    last_updated_at TIMESTAMPTZ DEFAULT NOW(),
                    CHECK (
                        state != 'Deadline'
                        OR (deadline_date IS NOT NULL AND deadline_time IS NOT NULL)
                    )
                )
                """);

            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS workflow VARCHAR(20)");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS placement_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("ALTER TABLE TASK.tasks ADD COLUMN IF NOT EXISTS last_updated_at TIMESTAMPTZ DEFAULT NOW()");
            st.executeUpdate("UPDATE TASK.tasks SET lifecycle = 'UNSCHEDULED' WHERE lifecycle IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET workflow = 'UNFINISHED' WHERE workflow IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET placement_updated_at = NOW() WHERE placement_updated_at IS NULL");
            st.executeUpdate("UPDATE TASK.tasks SET last_updated_at = COALESCE(last_updated_at, placement_updated_at, NOW()) WHERE last_updated_at IS NULL");
        }
    }

    public void upsert(Task task) throws SQLException {
        String sql = """
            INSERT INTO TASK.tasks (
                id, title, duration, state,
                placed_date, placed_time,
                deadline_date, deadline_time,
                lifecycle, workflow,
                placement_updated_at, last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                duration = EXCLUDED.duration,
                state = EXCLUDED.state,
                placed_date = EXCLUDED.placed_date,
                placed_time = EXCLUDED.placed_time,
                deadline_date = EXCLUDED.deadline_date,
                deadline_time = EXCLUDED.deadline_time,
                lifecycle = EXCLUDED.lifecycle,
                workflow = EXCLUDED.workflow,
                placement_updated_at = EXCLUDED.placement_updated_at,
                last_updated_at = EXCLUDED.last_updated_at
            """;

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, task);
            ps.executeUpdate();
        }
    }

    public void updateWorkflow(Task task) throws SQLException {
        String sql = "UPDATE TASK.tasks SET workflow = ?, last_updated_at = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
            Instant last = task.getLastUpdatedAt() != null ? task.getLastUpdatedAt() : Instant.now();
            ps.setString(1, flow.name());
            ps.setTimestamp(2, Timestamp.from(last));
            ps.setString(3, task.getID());
            ps.executeUpdate();
        }
    }

    public void delete(Task task) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM TASK.tasks WHERE id = ?")) {
            ps.setString(1, task.getID());
            ps.executeUpdate();
        }
    }

    public void deleteAll() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM TASK.tasks");
        }
    }

    public List<Task> fetchAll() throws SQLException {
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM TASK.tasks");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
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

        if (task.getPlacedDate() != null) ps.setDate(5, Date.valueOf(task.getPlacedDate()));
        else ps.setNull(5, Types.DATE);

        if (task.getPlacedTime() != null) ps.setTime(6, Time.valueOf(task.getPlacedTime()));
        else ps.setNull(6, Types.TIME);

        if (task.getState() == 2 && task.getDeadLineDate() != null) {
            ps.setDate(7, Date.valueOf(task.getDeadLineDate()));
            if (task.getDeadLineTime() != null) ps.setTime(8, Time.valueOf(task.getDeadLineTime()));
            else ps.setNull(8, Types.TIME);
        } else {
            ps.setNull(7, Types.DATE);
            ps.setNull(8, Types.TIME);
        }

        ps.setString(9, life.name());
        ps.setString(10, flow.name());
        ps.setTimestamp(11, Timestamp.from(placement));
        ps.setTimestamp(12, Timestamp.from(last));
    }

    private Task mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("title");
        double duration = rs.getDouble("duration");
        String state = rs.getString("state");
        int numberState = "Fixed".equals(state) ? 1 : "Deadline".equals(state) ? 2 : 3;

        Date sqlPlacedDate = rs.getDate("placed_date");
        Time sqlPlacedTime = rs.getTime("placed_time");
        LocalDate placedDate = sqlPlacedDate != null ? sqlPlacedDate.toLocalDate() : null;
        LocalTime placedTime = sqlPlacedTime != null ? sqlPlacedTime.toLocalTime() : null;

        Date sqlDeadlineDate = rs.getDate("deadline_date");
        Time sqlDeadlineTime = rs.getTime("deadline_time");
        LocalDate deadlineDate = sqlDeadlineDate != null ? sqlDeadlineDate.toLocalDate() : null;
        LocalTime deadlineTime = sqlDeadlineTime != null ? sqlDeadlineTime.toLocalTime() : null;

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

        Timestamp p = rs.getTimestamp("placement_updated_at");
        if (p != null) task.setPlacementUpdatedAt(p.toInstant());
        Timestamp l = rs.getTimestamp("last_updated_at");
        if (l != null) task.setLastUpdatedAt(l.toInstant());
        else if (p != null) task.setLastUpdatedAt(p.toInstant());

        return task;
    }
}