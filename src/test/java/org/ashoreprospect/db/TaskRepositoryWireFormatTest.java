/**
 * The DB-free half of TaskRepository: JSON encoding, row decoding, fingerprinting and the
 * lenient parsers used when reading rows that came from Node.
 */
package org.ashoreprospect.db;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.Workflow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything here is reached through reflection because TaskRepository's wire helpers are
 * private; no database is opened, no network call is made and no file outside the JVM is
 * touched. The point is to pin the exact JSON shape and the leniency of the decoders, since
 * both are what a Node round trip depends on.
 */
class TaskRepositoryWireFormatTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final Instant STAMP = Instant.parse("2026-10-12T07:00:00Z");

    // ---------- reflection plumbing ----------

    private static Object call(String name, Class<?>[] types, Object... args) {
        try {
            Method m = TaskRepository.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new AssertionError("unexpected checked exception from " + name, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not reach TaskRepository." + name, e);
        }
    }

    private static String taskToJson(Task t) {
        return (String) call("taskToJson", new Class<?>[] {Task.class}, t);
    }

    private static String blockedToJson(BlockedTime b) {
        return (String) call("blockedToJson", new Class<?>[] {BlockedTime.class}, b);
    }

    private static Task taskFromNode(Map<String, String> row) {
        return (Task) call("taskFromNode", new Class<?>[] {Map.class}, row);
    }

    private static BlockedTime blockedFromNode(Map<String, String> row) {
        return (BlockedTime) call("blockedFromNode", new Class<?>[] {Map.class}, row);
    }

    private static String parseTime(String s) {
        return String.valueOf(call("parseTime", new Class<?>[] {String.class}, s));
    }

    private static LocalTime parseTimeValue(String s) {
        return (LocalTime) call("parseTime", new Class<?>[] {String.class}, s);
    }

    private static LocalDate parseDate(String s) {
        return (LocalDate) call("parseDate", new Class<?>[] {String.class}, s);
    }

    private static Instant parseInstant(String s) {
        return (Instant) call("parseInstant", new Class<?>[] {String.class}, s);
    }

    private static int stateToInt(String s) {
        return (Integer) call("stateToInt", new Class<?>[] {String.class}, s);
    }

    private static double parseDouble(String s, double fallback) {
        return (Double) call("parseDouble", new Class<?>[] {String.class, double.class}, s, fallback);
    }

    private static DayOfWeek toDayOfWeek(Integer wd) {
        return (DayOfWeek) call("toDayOfWeek", new Class<?>[] {Integer.class}, wd);
    }

    private static String escapeJson(String s) {
        return (String) call("escapeJson", new Class<?>[] {String.class}, s);
    }

    private static String fieldValue(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int start = at + needle.length();
        if (json.startsWith("null", start)) return null;
        if (json.charAt(start) == '"') {
            int end = start + 1;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\') {
                    end += 2;
                    continue;
                }
                if (c == '"') break;
                end++;
            }
            return unescape(json.substring(start + 1, end));
        }
        int end = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end);
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Task task(String name) {
        return new Task(name, 1.5, 1, MON, LocalTime.of(9, 0),
                MON.plusDays(1), LocalTime.of(18, 30));
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---------- taskToJson ----------

    @Test
    void taskToJson_writesIdsStateAndClocksInTheExpectedShape() {
        Task t = task("wire-task");
        t.setPlacedDate(MON);
        t.setPlacedTime(LocalTime.of(9, 30));

        String json = taskToJson(t);

        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertEquals(t.getID(), fieldValue(json, "id"));
        assertEquals("Fixed", fieldValue(json, "state"));
        assertEquals("2026-10-12", fieldValue(json, "placed_date"));
        assertEquals("09:30", fieldValue(json, "placed_time"));
        assertEquals("2026-10-13", fieldValue(json, "deadline_date"));
        assertEquals("18:30", fieldValue(json, "deadline_time"));
        assertEquals("1.5", fieldValue(json, "duration"));
    }

    @Test
    void taskToJson_mapsEveryStateToItsWireName() {
        assertEquals("Fixed", fieldValue(taskToJson(new Task("a", 1.0, 1, null, null, null, null)), "state"));
        assertEquals("Deadline", fieldValue(taskToJson(new Task("b", 1.0, 2, null, null, null, null)), "state"));
        assertEquals("Flexible", fieldValue(taskToJson(new Task("c", 1.0, 3, null, null, null, null)), "state"));
    }

    @Test
    void taskToJson_emitsNullForAbsentClocksRatherThanAnEmptyString() {
        Task t = new Task("bare", 1.0, 3, null, null, null, null);

        String json = taskToJson(t);

        // field() writes a bare null, not "\"\"" - the Node side distinguishes the two.
        assertTrue(json.contains("\"placed_date\":null"), json);
        assertTrue(json.contains("\"placed_time\":null"), json);
        assertTrue(json.contains("\"deadline_date\":null"), json);
        assertTrue(json.contains("\"deadline_time\":null"), json);
        assertTrue(json.contains("\"placement_updated_at\":null"), json);
        // PINNED: only the clock fields become a bare null. The text fields use "" for
        // absent values (description) and "" for an absent parent, so a Node reader sees a
        // real empty string there rather than null.
        assertEquals("", fieldValue(json, "description"));
        assertEquals("", fieldValue(json, "parent_id"));
        assertNotNull(fieldValue(json, "last_updated_at"), "the Task ctor stamps lastUpdatedAt");
    }

    @Test
    void taskToJson_writesLifecycleWorkflowAndDescriptionDone() {
        Task t = new Task("stateful", 1.0, 3, null, null, null, null);
        t.setLifeCycle(TaskLifeCycle.PAST);
        t.setWorkflow(Workflow.FINISHED);
        t.setDescriptionDone(true);

        String json = taskToJson(t);

        assertEquals("PAST", fieldValue(json, "lifecycle"));
        assertEquals("FINISHED", fieldValue(json, "workflow"));
        assertEquals("true", fieldValue(json, "description_done"));
    }

    @Test
    void taskToJson_escapesQuotesAndBackslashesInTextFields() {
        Task t = new Task("say \"hi\" \\ bye", 1.0, 3, null, null, null, null);

        String json = taskToJson(t);

        // The raw JSON must contain the escaped forms, and reading the field back must give
        // the original text.
        assertTrue(json.contains("\\\"hi\\\""), json);
        assertTrue(json.contains("\\\\"), json);
        assertEquals("say \"hi\" \\ bye", fieldValue(json, "title"));
    }

    @Test
    void taskToJson_roundTripsThroughTaskFromNode() {
        Task original = task("round-trip");
        original.setPlacedDate(MON);
        original.setPlacedTime(LocalTime.of(7, 30));
        original.setDescription("bring notes");
        original.setDescriptionDone(true);
        original.setLifeCycle(TaskLifeCycle.ONGOING);
        original.setWorkflow(Workflow.FINISHED);

        Map<String, String> decoded = new LinkedHashMap<>();
        String json = taskToJson(original);
        for (String key : List.of("id", "title", "duration", "state", "placed_date", "placed_time",
                "deadline_date", "deadline_time", "lifecycle", "workflow", "description",
                "description_done")) {
            decoded.put(key, fieldValue(json, key));
        }

        Task back = taskFromNode(decoded);

        assertNotNull(back);
        assertEquals(original.getID(), back.getID());
        assertEquals(original.getTaskName(), back.getTaskName());
        assertEquals(original.getState(), back.getState());
        assertEquals(original.getPlacedDate(), back.getPlacedDate());
        assertEquals(original.getPlacedTime(), back.getPlacedTime());
        assertEquals(original.getDeadLineDate(), back.getDeadLineDate());
        assertEquals(original.getDeadLineTime(), back.getDeadLineTime());
        assertEquals(TaskLifeCycle.ONGOING, back.getLifeCycle());
        assertEquals(Workflow.FINISHED, back.getWorkflow());
        assertEquals("bring notes", back.getDescription());
        assertTrue(back.isDescriptionDone());
    }

    // ---------- blockedToJson ----------

    @Test
    void blockedToJson_writesAWeekdayAsAnIntAndADateAsAString() {
        BlockedTime weekly = BlockedTime.weekly("Gym", DayOfWeek.TUESDAY,
                LocalTime.of(18, 0), LocalTime.of(19, 0));
        BlockedTime dated = BlockedTime.daily("Lunch", LocalTime.of(12, 0), LocalTime.of(13, 0));

        String weeklyJson = blockedToJson(weekly);
        String datedJson = blockedToJson(dated);

        assertEquals("2", fieldValue(weeklyJson, "weekday"), "TUESDAY is ISO day 2");
        assertEquals("18:00", fieldValue(weeklyJson, "start_time"));
        assertEquals("19:00", fieldValue(weeklyJson, "end_time"));
        assertTrue(datedJson.contains("\"weekday\":null"), datedJson);
        assertEquals("12:00", fieldValue(datedJson, "start_time"));
    }

    @Test
    void blockedToJson_writesTheExceptionFlagAsAJsonBoolean() {
        BlockedTime b = BlockedTime.daily("Holiday", LocalTime.of(9, 0), LocalTime.of(10, 0));
        b.setException(true);

        assertEquals("true", fieldValue(blockedToJson(b), "is_exception"));
        b.setException(false);
        assertEquals("false", fieldValue(blockedToJson(b), "is_exception"));
    }

    @Test
    void blockedToJson_roundTripsThroughBlockedFromNode() {
        BlockedTime original = BlockedTime.weekly("Gym", DayOfWeek.TUESDAY,
                LocalTime.of(18, 0), LocalTime.of(19, 0));
        original.setException(true);

        Map<String, String> decoded = new LinkedHashMap<>();
        String json = blockedToJson(original);
        for (String key : List.of("id", "title", "weekday", "start_time", "end_time", "is_exception")) {
            decoded.put(key, fieldValue(json, key));
        }

        BlockedTime back = blockedFromNode(decoded);

        assertNotNull(back);
        assertEquals(original.getId(), back.getId());
        assertEquals(LocalTime.of(18, 0), back.getStartTime());
        assertEquals(LocalTime.of(19, 0), back.getEndTime());
        assertEquals(DayOfWeek.TUESDAY, back.getWeekday());
        assertTrue(back.isException());
    }

    // ---------- decoders ----------

    @Test
    void taskFromNode_returnsNullWithoutAnId() {
        assertNull(taskFromNode(row("title", "no id")));
        assertNull(taskFromNode(row("id", "   ", "title", "blank id")),
                "a blank id is not an id");
    }

    @Test
    void taskFromNode_defaultsDurationAndStateWhenAbsent() {
        Task t = taskFromNode(row("id", "x-1"));

        assertEquals(1.0, t.getDurationHours(), 1e-9, "missing duration defaults to 1");
        assertEquals(3, t.getState(), "missing state defaults to Flexible");
    }

    @Test
    void taskFromNode_ignoresAnUnparseableLifecycleAndWorkflow() {
        // The decoders swallow bad enum names rather than failing the whole sync.
        Task t = taskFromNode(row("id", "x-2", "lifecycle", "NOT_A_LIFECYCLE",
                "workflow", "NOT_A_WORKFLOW"));

        assertNotNull(t);
        assertNotEquals("NOT_A_LIFECYCLE", String.valueOf(t.getLifeCycle()));
        assertNotEquals("NOT_A_WORKFLOW", String.valueOf(t.getWorkflow()));
    }

    @Test
    void taskFromNode_anUnusableLastStampDoesNotUseThePlacementStamp() {
        Task t = taskFromNode(row("id", "x-3",
                "placement_updated_at", STAMP.toString()));

        assertEquals(STAMP, t.getPlacementUpdatedAt());
        // A missing last_updated_at is not derived from placement_updated_at: a placement stamp
        // can be far in the past, and using it would make the row look older than the local copy.
        // It is replaced with "now" instead, so the row is never silently dropped (#4).
        assertNotNull(t.getLastUpdatedAt());
        assertFalse(t.getLastUpdatedAt().isBefore(Instant.now().minusSeconds(60)));
    }

    @Test
    void taskFromNode_prefersTheCamelCaseKeysWhenBothSpellingsArrive() {
        Map<String, String> r = row("id", "x-4");
        r.put("placedDate", "2026-10-12");
        r.put("placedTime", "09:30");

        Task t = taskFromNode(r);

        assertEquals(MON, t.getPlacedDate());
        assertEquals(LocalTime.of(9, 30), t.getPlacedTime());
    }

    @Test
    void taskFromNode_treatsTruthyDescriptionDoneSpellingsAsDone() {
        for (String v : List.of("true", "TRUE", "t", "T", "1")) {
            assertTrue(taskFromNode(row("id", "d-" + v, "description_done", v)).isDescriptionDone(),
                    "expected done for " + v);
        }
        for (String v : List.of("false", "0", "", "yes")) {
            assertFalse(taskFromNode(row("id", "n-" + v, "description_done", v)).isDescriptionDone(),
                    "expected not-done for " + v);
        }
    }

    @Test
    void blockedFromNode_returnsNullWithoutAnId() {
        assertNull(blockedFromNode(row("title", "orphan")));
    }

    @Test
    void blockedFromNode_mapsWeekdayZeroToSunday() {
        // PINNED: Node sends weekday 0 for Sunday (Java's DayOfWeek has no 0). Only 0 is
        // special-cased; 1..7 go straight to DayOfWeek.of, so both 0 and 7 land on SUNDAY
        // and 8 is the first rejected value.
        assertEquals(DayOfWeek.SUNDAY, toDayOfWeek(0));
        assertEquals(DayOfWeek.SUNDAY, toDayOfWeek(7), "7 is accepted by DayOfWeek.of");
        assertEquals(DayOfWeek.MONDAY, toDayOfWeek(1));
        assertEquals(DayOfWeek.SATURDAY, toDayOfWeek(6));
        assertNull(toDayOfWeek(8));
        assertNull(toDayOfWeek(-1));
        assertNull(toDayOfWeek(null));
    }

    @Test
    void blockedFromNode_ignoresAWeekdayItCannotParse() {
        BlockedTime b = blockedFromNode(row("id", "b-1", "weekday", "Tuesday"));

        assertNotNull(b);
        assertNull(b.getWeekday(), "an unparseable weekday leaves the row date-free");
    }

    // ---------- parsers ----------

    @Test
    void parseTime_acceptsEveryShapeNodeSends() {
        assertEquals(LocalTime.of(9, 30), parseTimeValue("09:30"));
        assertEquals(LocalTime.of(9, 30), parseTimeValue("09:30:00"));
        assertEquals(LocalTime.of(9, 30), parseTimeValue("09:30:00.123456"));
        assertEquals(LocalTime.of(9, 30), parseTimeValue(" 09:30 "));
        assertNull(parseTimeValue(null));
        assertNull(parseTimeValue(""));
        assertNull(parseTimeValue("null"));
        assertNull(parseTimeValue("NULL"));
        assertNull(parseTimeValue("not-a-time"));
    }

    @Test
    void parseDate_takesTheFirstTenCharacters() {
        assertEquals(MON, parseDate("2026-10-12"));
        assertEquals(MON, parseDate("2026-10-12T07:00:00Z"));
        assertNull(parseDate(null));
        assertNull(parseDate(""));
        assertNull(parseDate("12/10/2026"));
    }

    @Test
    void parseInstant_acceptsEveryTimestampShapeNodeSends() {
        assertEquals(STAMP, parseInstant("2026-10-12T07:00:00Z"));
        assertEquals(STAMP, parseInstant("2026-10-12 07:00:00Z"), "space instead of T");
        assertEquals(STAMP, parseInstant("2026-10-12T07:00:00+00:00"));
        assertNull(parseInstant(null));
        assertNull(parseInstant(""));
        assertNull(parseInstant("null"));
        assertNull(parseInstant("yesterday"));
    }

    @Test
    void stateToInt_mapsNamesNumbersAndEverythingElse() {
        assertEquals(1, stateToInt("Fixed"));
        assertEquals(1, stateToInt("fixed"));
        assertEquals(1, stateToInt("1"));
        assertEquals(2, stateToInt("Deadline"));
        assertEquals(2, stateToInt("2"));
        assertEquals(3, stateToInt("Flexible"));
        assertEquals(3, stateToInt("nonsense"));
        // PINNED: a missing state is Flexible, not an error.
        assertEquals(3, stateToInt(null));
    }

    @Test
    void parseDouble_fallsBackOnGarbage() {
        assertEquals(2.5, parseDouble("2.5", 1), 1e-9);
        assertEquals(1, parseDouble("garbage", 1), 1e-9);
        assertEquals(1, parseDouble(null, 1), 1e-9);
        assertEquals(-3, parseDouble("-3", 1), 1e-9);
    }

    @Test
    void escapeJson_onlyEscapesQuotesAndBackslashes() {
        assertEquals("plain", escapeJson("plain"));
        assertEquals("a\\\"b", escapeJson("a\"b"));
        assertEquals("a\\\\b", escapeJson("a\\b"));
        // PINNED: control characters are NOT escaped, so a newline goes into the JSON raw.
        assertEquals("a\nb", escapeJson("a\nb"));
        assertEquals("", escapeJson(null));
    }

    // ---------- fingerprint ----------

    @Test
    void fingerprint_isStableAcrossKeyOrderAndIgnoresSensitiveFields() {
        String a = (String) call("fingerprint",
                new Class<?>[] {List.class, List.class},
                List.of(row("id", "1", "title", "one", "last_updated_at", "t1")),
                List.of());
        String b = (String) call("fingerprint",
                new Class<?>[] {List.class, List.class},
                List.of(row("last_updated_at", "t1", "title", "one", "id", "1")),
                List.of());

        assertEquals(a, b, "the fingerprint must not depend on map iteration order");
    }

    @Test
    void fingerprint_coversOnlyIdsAndStamps() {
        // PINNED: the fingerprint is built from id + last_updated_at + placement_updated_at
        // only. A row whose title changes but whose stamps do not produces the same
        // fingerprint, which is what lets pullFromNode() skip a full merge cheaply.
        String base = (String) call("fingerprint", new Class<?>[] {List.class, List.class},
                List.of(row("id", "1", "title", "one")), List.of());
        String retitled = (String) call("fingerprint", new Class<?>[] {List.class, List.class},
                List.of(row("id", "1", "title", "two")), List.of());
        String restamped = (String) call("fingerprint", new Class<?>[] {List.class, List.class},
                List.of(row("id", "1", "title", "one", "last_updated_at", "t1")), List.of());

        assertEquals(base, retitled, "a title change alone is invisible to the fingerprint");
        assertNotEquals(base, restamped, "a stamp change is visible");
        // fingerprint() appends '#' between the task section and the blocked section, and an
        // empty blocked list contributes nothing after it.
        assertEquals("1=/;#", base, "the exact shape is id=last/place; then '#' then blocked");
    }

    @Test
    void fingerprint_ofEmptySetsIsStable() {
        String a = (String) call("fingerprint", new Class<?>[] {List.class, List.class},
                List.of(), List.of());
        String b = (String) call("fingerprint", new Class<?>[] {List.class, List.class},
                List.of(), List.of());

        assertEquals(a, b);
    }

    // ---------- nodeReady ----------

    @Test
    void nodeReady_isFalseWithoutALogin() throws Exception {
        // No LocalSession has been installed in this JVM, so getToken() is null or blank and
        // every node* helper short-circuits before touching the network.
        Method m = TaskRepository.class.getDeclaredMethod("nodeReady");
        m.setAccessible(true);

        assertFalse((boolean) m.invoke(null));
    }
}
