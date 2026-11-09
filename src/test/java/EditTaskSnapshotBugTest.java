
import org.ashoreprospect.Command.EditTask;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskManagementSystem;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

public class EditTaskSnapshotBugTest {

    @Test
    void snapshotShouldContainAllUndoCriticalState() throws Exception {
        Task task = new Task(
            "Demo task",
            1.5,
            2,
            LocalDate.now(),
            LocalTime.of(9, 0),
            LocalDate.now().plusDays(1),
            LocalTime.of(17, 0)
        );
        task.setPlacedDate(LocalDate.now());
        task.setPlacedTime(LocalTime.of(9, 30));
        task.setDescription("original description");
        task.setDescriptionDone(true);

        EditTask.Snapshot snapshot = EditTask.Snapshot.of(task);

        assertFieldExists(snapshot, "name");
        assertFieldExists(snapshot, "durationHours");
        assertFieldExists(snapshot, "deadlineDate");
        assertFieldExists(snapshot, "deadlineTime");
        assertFieldExists(snapshot, "description");
        assertFieldExists(snapshot, "descriptionDone");
        assertFieldExists(snapshot, "placedDate");
        assertFieldExists(snapshot, "placedTime");

        assertEquals(task.getTaskName(), snapshot.name);
        assertEquals(task.getDurationHours(), snapshot.durationHours);
        assertEquals(task.getDeadLineDate(), snapshot.deadlineDate);
        assertEquals(task.getDeadLineTime(), snapshot.deadlineTime);
        assertEquals(task.getDescription(), snapshot.description);
        assertEquals(task.isDescriptionDone(), snapshot.descriptionDone);
        assertEquals(task.getPlacedDate(), getFieldValue(snapshot, "placedDate"));
        assertEquals(task.getPlacedTime(), getFieldValue(snapshot, "placedTime"));
    }

    @Test
    void undoShouldRestoreOriginalPlacedTaskState() throws Exception {
        TaskManagementSystem.resetInstance();
        TaskManagementSystem sys = TaskManagementSystem.getInstance();

        Task task = new Task(
            "Original task",
            1.5,
            2,
            LocalDate.now(),
            LocalTime.of(9, 0),
            LocalDate.now().plusDays(1),
            LocalTime.of(17, 0)
        );
        task.setPlacedDate(LocalDate.now());
        task.setPlacedTime(LocalTime.of(9, 30));
        sys.getPlacedTasks().add(task);

        EditTask.Snapshot before = EditTask.Snapshot.of(task);
        EditTask.Snapshot after = new EditTask.Snapshot();
        after.name = "Edited task";
        after.durationHours = 2.0;
        after.deadlineDate = LocalDate.now().plusDays(2);
        after.deadlineTime = LocalTime.of(18, 0);
        after.description = "updated description";
        after.descriptionDone = false;

        EditTask edit = new EditTask(task, after, true);
        edit.execute();
        edit.undo();

        assertEquals(before.name, task.getTaskName());
        assertEquals(before.durationHours, task.getDurationHours());
        assertEquals(before.deadlineDate, task.getDeadLineDate());
        assertEquals(before.deadlineTime, task.getDeadLineTime());
        assertEquals(before.description, task.getDescription());
        assertEquals(before.descriptionDone, task.isDescriptionDone());
        assertEquals(LocalDate.now(), task.getPlacedDate());
        assertEquals(LocalTime.of(9, 30), task.getPlacedTime());
    }

    private static void assertFieldExists(Object target, String fieldName) throws Exception {
        assertNotNull(
            EditTask.Snapshot.class.getDeclaredField(fieldName),
            "Snapshot should include field '" + fieldName + "' for complete undo/redo state"
        );
    }

    private static Object getFieldValue(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}