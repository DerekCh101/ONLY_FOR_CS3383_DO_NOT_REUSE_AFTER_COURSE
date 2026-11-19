package org.ashoreprospect.GUI;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedulerControllerDescriptionTest {

    @Test
    void plainDescriptionsRemainOneUncheckedItem() throws Exception {
        assertEquals(List.of(), parsed(null));
        assertEquals(List.of(), parsed(" \n "));
        assertEquals(List.of("[ ] First line\nSecond line"), parsed("  First line\nSecond line  "));
    }

    @Test
    void checklistDescriptionsKeepCompletionAndUnmarkedLines() throws Exception {
        assertEquals(List.of("[x] Done", "[x] Also done", "[ ] Todo", "[ ] Plain line"),
                parsed(" [x] Done \n[X] Also done\n\n [ ] Todo\nPlain line "));
    }

    private static List<String> parsed(String description) throws Exception {
        Method parse = SchedulerController.class.getDeclaredMethod("parseDescLines", String.class);
        parse.setAccessible(true);
        List<?> lines = (List<?>) parse.invoke(null, description);
        Class<?> lineClass = Class.forName("org.ashoreprospect.GUI.SchedulerController$DescLine");
        Field done = lineClass.getDeclaredField("done");
        Field text = lineClass.getDeclaredField("text");
        done.setAccessible(true);
        text.setAccessible(true);
        List<String> result = new ArrayList<>();
        for (Object line : lines) {
            result.add((done.getBoolean(line) ? "[x] " : "[ ] ") + text.get(line));
        }
        return result;
    }
}
