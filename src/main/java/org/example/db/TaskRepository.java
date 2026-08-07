package org.example.db;

import org.example.*;

import java.sql.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class TaskRepository {

    private final String url = "jdbc:postgresql://100.113.239.82:5432/Scheduler";
    private final String user = "postgres";
    private final String password = System.getenv("postgres_pw");

    public TaskRepository() {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database(), version()")) {

            if (rs.next()) {
                System.out.println("Database: " + rs.getString(1));
                System.out.println("Version:  " + rs.getString(2));
                System.out.println("Connected OK");
            }
            initialize();
        } catch (Exception e) {
            e.printStackTrace();
        }
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
                    CHECK (
                        state != 'Deadline'
                        OR (deadline_date IS NOT NULL AND deadline_time IS NOT NULL)
                    )
                )
                """);

            st.executeUpdate("""
                ALTER TABLE TASK.tasks
                  ADD COLUMN IF NOT EXISTS lifecycle VARCHAR(20)
                """);
            st.executeUpdate("""
                ALTER TABLE TASK.tasks
                  ADD COLUMN IF NOT EXISTS workflow VARCHAR(20)
                """);
            st.executeUpdate("""
                ALTER TABLE TASK.tasks
                  ADD COLUMN IF NOT EXISTS placement_updated_at TIMESTAMPTZ DEFAULT NOW()
                """);

            st.executeUpdate("""
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'task'
                          AND table_name = 'tasks'
                          AND column_name = 'updated_at'
                    ) THEN
                        UPDATE TASK.tasks
                        SET placement_updated_at = COALESCE(placement_updated_at, updated_at, NOW())
                        WHERE placement_updated_at IS NULL;
                    END IF;
                END $$;
                """);

            st.executeUpdate("""
                UPDATE TASK.tasks SET lifecycle = 'UNSCHEDULED' WHERE lifecycle IS NULL
                """);
            st.executeUpdate("""
                UPDATE TASK.tasks SET workflow = 'UNFINISHED' WHERE workflow IS NULL
                """);
            st.executeUpdate("""
                UPDATE TASK.tasks SET placement_updated_at = NOW() WHERE placement_updated_at IS NULL
                """);
        }
    }

    public void dbAddTask(Task task) throws SQLException {
        String sql = """
            INSERT INTO TASK.tasks (
                id, title, duration, state,
                placed_date, placed_time,
                deadline_date, deadline_time,
                lifecycle, workflow,
                placement_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
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
                placement_updated_at = NOW()
            """;

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            String state = task.getState() == 1 ? "Fixed"
                    : task.getState() == 2 ? "Deadline" : "Flexible";

            TaskLifeCycle life = task.getLifeCycle() != null
                    ? task.getLifeCycle()
                    : TaskLifeCycle.UNSCHEDULED;
            Workflow flow = task.getWorkflow() != null
                    ? task.getWorkflow()
                    : Workflow.UNFINISHED;

            ps.setString(1, task.getID());
            ps.setString(2, task.getTaskName());
            ps.setDouble(3, task.getDurationHours());
            ps.setString(4, state);

            if (task.getPlacedDate() != null) {
                ps.setDate(5, Date.valueOf(task.getPlacedDate()));
            } else {
                ps.setNull(5, Types.DATE);
            }

            if (task.getPlacedTime() != null) {
                ps.setTime(6, Time.valueOf(task.getPlacedTime()));
            } else {
                ps.setNull(6, Types.TIME);
            }

            if (task.getState() == 2 && task.getDeadLineDate() != null) {
                ps.setDate(7, Date.valueOf(task.getDeadLineDate()));
                if (task.getDeadLineTime() != null) {
                    ps.setTime(8, Time.valueOf(task.getDeadLineTime()));
                } else {
                    ps.setNull(8, Types.TIME);
                }
            } else {
                ps.setNull(7, Types.DATE);
                ps.setNull(8, Types.TIME);
            }

            ps.setString(9, life.name());
            ps.setString(10, flow.name());
            ps.executeUpdate();
        }
    }

    public void dbUpdateWorkflow(Task task) throws SQLException {
        String sql = "UPDATE TASK.tasks SET workflow = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Workflow flow = task.getWorkflow() != null ? task.getWorkflow() : Workflow.UNFINISHED;
            ps.setString(1, flow.name());
            ps.setString(2, task.getID());
            ps.executeUpdate();
        }
    }

    public void dbClearTask(Task task) throws SQLException {
        String sql = "DELETE FROM TASK.tasks WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, task.getID());
            ps.executeUpdate();
        }
    }

    public void dbfetchAll(TaskManagementSystem theSystem) throws SQLException {
        String sql = "SELECT * FROM TASK.tasks";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Task task = mapRow(rs);
                LocalDate placedDate = task.getPlacedDate();
                LocalTime placedTime = task.getPlacedTime();
                TaskLifeCycle lifeFromDb = task.getLifeCycle();

                if (placedDate != null && placedTime != null) {
                    theSystem.getGrid().putIfAbsent(placedDate, new Day(theSystem.getBlockedTime()));
                    Day day = theSystem.getGrid().get(placedDate);
                    int slot = AppTimeReference.localTimeToSlot(placedTime);
                    day.placeTask(slot, task);
                    theSystem.getPlacedTasks().add(task);

                    TaskLifeCycle before = task.getLifeCycle();
                    RefreshLifeCycle.refreshLifeCycle(task);
                    if (before != task.getLifeCycle()) {
                        dbAddTask(task);
                    }
                } else if (lifeFromDb == TaskLifeCycle.UNSCHEDULED) {
                    theSystem.getUnscheduledTasks().add(task);
                } else {
                    theSystem.getPendingTasks().add(task);
                }
            }
        }
    }

    public List<Task> fetchUnscheduledTasks() throws SQLException {
        String sql = """
            SELECT * FROM TASK.tasks
            WHERE lifecycle = 'UNSCHEDULED'
               OR placed_date IS NULL
            ORDER BY title
            """;
        List<Task> list = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    private Task mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("title");
        double duration = rs.getDouble("duration");
        String state = rs.getString("state");
        int numberState = "Fixed".equals(state) ? 1
                : "Deadline".equals(state) ? 2 : 3;

        java.sql.Date sqlPlacedDate = rs.getDate("placed_date");
        java.sql.Time sqlPlacedTime = rs.getTime("placed_time");
        LocalDate placedDate = sqlPlacedDate != null ? sqlPlacedDate.toLocalDate() : null;
        LocalTime placedTime = sqlPlacedTime != null ? sqlPlacedTime.toLocalTime() : null;

        java.sql.Date sqlDeadlineDate = rs.getDate("deadline_date");
        java.sql.Time sqlDeadlineTime = rs.getTime("deadline_time");
        LocalDate deadlineDate = sqlDeadlineDate != null ? sqlDeadlineDate.toLocalDate() : null;
        LocalTime deadlineTime = sqlDeadlineTime != null ? sqlDeadlineTime.toLocalTime() : null;

        String lifeStr = rs.getString("lifecycle");
        String flowStr = rs.getString("workflow");
        TaskLifeCycle life = (lifeStr == null || lifeStr.isBlank())
                ? TaskLifeCycle.UNSCHEDULED
                : TaskLifeCycle.valueOf(lifeStr);
        Workflow flow = (flowStr == null || flowStr.isBlank())
                ? Workflow.UNFINISHED
                : Workflow.valueOf(flowStr);

        Task task = Task.loadTaskFromdb(
                id, name, duration, numberState,
                null, null,
                deadlineDate, deadlineTime,
                placedDate, placedTime
        );
        task.setLifeCycle(life);
        task.setWorkflow(flow);

        Timestamp ts = rs.getTimestamp("placement_updated_at");
        if (ts != null) {
            task.setPlacementUpdatedAt(ts.toInstant());
        }

        return task;
    }

    public void dbCleanAll() throws SQLException {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM TASK.tasks");
        }
    }

    public boolean containsIdByDateTime(LocalDate placedDate, LocalTime placedTime) throws SQLException {
        String sql = "SELECT 1 FROM TASK.tasks WHERE placed_date = ? AND placed_time = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(placedDate));
            ps.setTime(2, Time.valueOf(placedTime));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}