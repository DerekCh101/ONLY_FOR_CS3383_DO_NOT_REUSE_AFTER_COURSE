package org.ashoreprospect.GUI;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessViewLogicTest {

    @Test
    void typewriterOnlySeedsDefaultsForTrulyEmptyData() throws Exception {
        Method blank = TypewriterPane.class.getDeclaredMethod("blank", TypewriterStore.Data.class);
        blank.setAccessible(true);
        TypewriterStore.Data data = new TypewriterStore.Data();
        assertTrue((boolean) blank.invoke(null, (Object) null));
        assertTrue((boolean) blank.invoke(null, data));

        data.diary.put(LocalDate.of(2026, 10, 12), new TypewriterPane.TwNote("Diary", null));
        assertFalse((boolean) blank.invoke(null, data));
        data.diary.clear();
        data.lists.add(new TypewriterPane.TwList("List"));
        assertFalse((boolean) blank.invoke(null, data));
        data.lists.clear();
        data.pagesRoot.children.add(new TypewriterPane.TwFolder("Folder"));
        assertFalse((boolean) blank.invoke(null, data));
        data.pagesRoot.children.clear();
        data.pagesRoot.notes.add(new TypewriterPane.TwNote("Note", null));
        assertFalse((boolean) blank.invoke(null, data));
    }

    @Test
    void accountReminderSummarySeparatesCustomTimesFromPresets() throws Exception {
        Method summary = AccountPane.class.getDeclaredMethod("customLeadSummary", List.class, int[].class);
        summary.setAccessible(true);
        int[] presets = {5, 15, 60};

        assertEquals("Custom times you add show here.", summary.invoke(null, List.of(5, 15), presets));
        assertEquals("Also: 30 min before, 2 hours before",
                summary.invoke(null, List.of(5, 30, 120), presets));
    }

    @Test
    void accountSessionFieldsUseFirstNonemptyServerAlias() throws Exception {
        Method first = AccountPane.class.getDeclaredMethod("first", Map.class, String[].class);
        first.setAccessible(true);
        String[] keys = {"primary", "fallback"};

        assertEquals("device", first.invoke(null, Map.of("primary", "null", "fallback", "device"), keys));
        assertNull(first.invoke(null, Map.of("primary", " ", "fallback", ""), keys));
        assertNull(first.invoke(null, null, keys));
    }
}
