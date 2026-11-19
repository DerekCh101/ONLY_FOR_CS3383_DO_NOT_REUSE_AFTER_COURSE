package org.ashoreprospect.GUI;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskDialogsTimeTest {

    @Test
    void durationHandlesMidnightAndFullDay() throws Exception {
        Method duration = TaskDialogs.class.getDeclaredMethod("minutesBetween", LocalTime.class, LocalTime.class);
        duration.setAccessible(true);

        assertEquals(75, duration.invoke(null, LocalTime.of(9, 15), LocalTime.of(10, 30)));
        assertEquals(45, duration.invoke(null, LocalTime.of(23, 30), LocalTime.of(0, 15)));
        assertEquals(1440, duration.invoke(null, LocalTime.NOON, LocalTime.NOON));
    }

    @Test
    void quickEventTimeMustFallInsideItsParentEvenAcrossMidnight() throws Exception {
        Method withinParent = TaskDialogs.class.getDeclaredMethod("instantInParent",
                LocalDateTime.class, LocalDateTime.class, LocalTime.class);
        withinParent.setAccessible(true);
        LocalDateTime start = LocalDateTime.of(2026, 10, 12, 23, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 13, 1, 0);

        assertEquals(start, withinParent.invoke(null, start, end, LocalTime.of(23, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 13, 0, 30),
                withinParent.invoke(null, start, end, LocalTime.of(0, 30)));
        assertNull(withinParent.invoke(null, start, end, LocalTime.of(1, 0)));
        assertNull(withinParent.invoke(null, start, end, LocalTime.of(22, 30)));
        assertNull(withinParent.invoke(null, start, end, null));
    }

    @Test
    void editableTimeAcceptsMinutesAndRejectsOutOfRangeValues() throws Exception {
        Method parse = TaskDialogs.class.getDeclaredMethod("parseSlotCombo", String.class);
        parse.setAccessible(true);
        TaskDialogs dialogs = new TaskDialogs(null, null, null);

        assertEquals(LocalTime.of(18, 31), parse.invoke(dialogs, " 18:31 "));
        assertEquals(LocalTime.of(0, 5), parse.invoke(dialogs, "+00:05"));
        for (String invalid : new String[]{"24:00", "12:60", "not a time"}) {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> parse.invoke(dialogs, invalid));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }
}
