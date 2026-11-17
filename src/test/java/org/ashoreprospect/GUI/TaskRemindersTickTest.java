/**
 * {@code TaskReminders.tick()}: which placed tasks produce a reminder, when, and - just as
 * important - which do not.
 *
 * <p>{@code tick()} is private, so it is invoked by reflection. It reaches
 * {@code TaskManagementSystem.getInstance()}, {@code AppTimeReference.getCurrentDateTime()}, and
 * {@code LocalSession} preferences; the test controls all three (a fresh singleton with a placed
 * list it owns, a fixed clock, and a snapshotted {@code login.txt}).
 *
 * <p>It also calls {@code SchedulerApp.notifyUser}. That only touches JavaFX when a toolkit
 * exists; with none it falls straight through to {@code showToast}, which returns at once because
 * {@code SchedulerApp} has no stage. No toolkit is started here: {@code Platform.startup} boots
 * native glass/AWT and crashes headless CI runners, so the assertions use the dedupe set (the
 * state a tick leaves behind) and never the toast text.
 *
 * <p>{@code start()} and {@code stop()} are covered by the lifecycle class; this one only drives
 * {@code tick()} directly, so no timer is ever running.
 */
package org.ashoreprospect.GUI;

import org.ashoreprospect.AppTimeReference;
import org.ashoreprospect.Task;
import org.ashoreprospect.TaskLifeCycle;
import org.ashoreprospect.TaskManagementSystem;
import org.ashoreprospect.Workflow;
import org.ashoreprospect.net.Endpoints;
import org.ashoreprospect.net.LocalSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
@ResourceLock("os-env")
class TaskRemindersTickTest {

    /** A fixed local time: Monday 2026-10-12, 09:00 UTC. */
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    private Clock originalClock;
    private Path prefsFile;
    private byte[] savedPrefs;

    @BeforeEach
    void setUp() throws Exception {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(Clock.fixed(NOW, ZONE));

        prefsFile = Endpoints.dir().resolve("login.txt");
        savedPrefs = Files.isRegularFile(prefsFile) ? Files.readAllBytes(prefsFile) : null;
        LocalSession.setReminders(true);
        LocalSession.setReminderLeads(List.of(0, 5));
        LocalSession.setReminderSound(false);

        // A pristine system whose placed list the test owns.
        TaskManagementSystem.resetInstance();
        TaskManagementSystem fresh = TaskManagementSystem.getInstance();
        fresh.getPlacedTasks().clear();
        fresh.getPendingTasks().clear();
        fresh.getUnscheduledTasks().clear();

        sentKeys().clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        sentKeys().clear();
        TaskManagementSystem.resetInstance();
        AppTimeReference.setClock(originalClock);
        if (savedPrefs == null) {
            Files.deleteIfExists(prefsFile);
        } else {
            Files.createDirectories(prefsFile.getParent());
            Files.write(prefsFile, savedPrefs);
        }
    }

    // ---------- what fires ----------

    @Test
    void aTaskStartingInFiveMinutesFiresTheFiveMinuteLead() throws Exception {
        place("soon", LocalTime.of(9, 5), null);

        tick();

        // Both the "now" and "5 min" leads are within reach at five minutes, but only the
        // five-minute one registers: the "now" arm requires mins <= 0.
        assertEquals(List.of("id-soon|" + start(9, 5) + "|5"), keysFor("id-soon", sentKeys()));
    }

    @Test
    void aTaskStartingNowFiresTheNowLeadAndNotTheFiveMinuteOne() throws Exception {
        place("right now", LocalTime.of(9, 0), null);

        tick();

        assertEquals(List.of("id-right now|" + start(9, 0) + "|0"),
                keysFor("id-right now", sentKeys()),
                "at zero minutes the bounded 'now' arm matches and the 5-minute arm needs mins > 0");
    }

    @Test
    void aTaskAlreadyOneMinuteOldStillFiresNow() throws Exception {
        // The "now" window is mins in (-2, 0], so a tick that lands a minute late still reminds.
        place("just started", LocalTime.of(8, 59), null);

        tick();

        assertEquals(List.of("id-just started|" + start(8, 59) + "|0"),
                keysFor("id-just started", sentKeys()));
    }

    @Test
    void theSameLeadIsAnnouncedOnlyOnce() throws Exception {
        // PINNED: each lead is a two-minute window, `lead - 2 < mins <= lead`. At exactly five
        // minutes out only the five-minute lead can match; running the tick again inside the same
        // window must not re-announce it.
        place("once", LocalTime.of(9, 5), null);

        tick();
        tick();

        assertEquals(1, admissionsFor("id-once", sentKeys()),
                "the tick runs twice but the lead is admitted once: " + sentKeys());
    }

    @Test
    void aLeadThatIsNotYetInItsWindowFiresOnNeitherTick() throws Exception {
        // PINNED: the window is bounded, not "any time before". A task one minute out matches
        // neither the "now" lead (needs mins <= 0) nor the five-minute lead (needs mins > 3), so
        // it is silent until the clock reaches four minutes out.
        place("too early", LocalTime.of(9, 1), null);

        tick();
        tick();

        assertEquals(0, admissionsFor("id-too early", sentKeys()),
                "one minute out is outside both configured windows");
    }

    @Test
    void aCustomHourLongLeadFiresAnHourBeforeTheStart() throws Exception {
        LocalSession.setReminderLeads(List.of(60));
        place("in an hour", LocalTime.of(9, 59), null);

        tick();

        assertEquals(List.of("id-in an hour|" + start(9, 59) + "|60"),
                keysFor("id-in an hour", sentKeys()));
    }

    // ---------- what is skipped ----------

    @Test
    void nothingFiresWhenRemindersAreTurnedOff() throws Exception {
        LocalSession.setReminders(false);
        place("soon", LocalTime.of(9, 5), null);

        tick();

        assertEquals(0, admissionsFor("id-soon", sentKeys()),
                "reminders off returns before any task is examined");
    }

    @Test
    void anEmptyLeadListCannotBeSavedSoTheDefaultsComeBack() throws Exception {
        // PINNED: an empty lead list is unrepresentable. joinLeads([]) writes "", and parseLeads
        // reads a blank string back as the default 0,5, so "reminders off" has to be done with the
        // on/off switch, not by clearing the list. tick() therefore never sees an empty list from
        // a saved preference; the isEmpty() guard at its top is only reachable if a caller hands
        // it one directly.
        LocalSession.setReminderLeads(List.of());
        assertEquals(List.of(0, 5), LocalSession.reminderLeads());

        place("soon", LocalTime.of(9, 5), null);
        tick();

        assertEquals(List.of("id-soon|" + start(9, 5) + "|5"), keysFor("id-soon", sentKeys()),
                "the five-minute lead still fires because the defaults were restored");
    }

    @Test
    void nothingFiresWithNoPlacedTasks() throws Exception {
        tick();

        // setUp() empties the placed list, so tick() must return before examining anything. The
        // shared dedupe set may hold other classes' keys, so it is emptied first to prove that
        // this tick adds none of its own.
        assertTrue(sentKeys().isEmpty(), "a tick with nothing placed admits nothing");
    }

    @Test
    void aFinishedTaskIsNotReminded() throws Exception {
        place("done", LocalTime.of(9, 5), Workflow.FINISHED);

        tick();

        assertEquals(0, admissionsFor("id-done", sentKeys()),
                "a finished task must not pop a toast");
    }

    @Test
    void aPastTaskIsNotReminded() throws Exception {
        Task past = task("past", LocalTime.of(9, 5));
        past.setLifeCycle(TaskLifeCycle.PAST);
        TaskManagementSystem.getInstance().getPlacedTasks().add(past);

        tick();

        assertEquals(0, admissionsFor("id-past", sentKeys()));
    }

    @Test
    void aQuickTaskIsNotReminded() throws Exception {
        Task quick = task("quick", LocalTime.of(9, 5));
        quick.setParentId("parent-task");
        TaskManagementSystem.getInstance().getPlacedTasks().add(quick);

        tick();

        assertEquals(0, admissionsFor("id-quick", sentKeys()),
                "a quick sub-item has no start of its own");
    }

    @Test
    void anUnplacedTaskIsNotReminded() throws Exception {
        Task floating = Task.loadTaskFromdb("id-floating", "floating", 1.0, 3,
                null, null, null, null, null, null);
        TaskManagementSystem.getInstance().getPlacedTasks().add(floating);

        tick();

        assertEquals(0, admissionsFor("id-floating", sentKeys()),
                "no placed time means no start to remind about");
    }

    @Test
    void aTaskThirtyMinutesOutIsLeftForALaterTick() throws Exception {
        place("later", LocalTime.of(9, 30), null);

        tick();

        assertEquals(0, admissionsFor("id-later", sentKeys()),
                "30 minutes away is beyond the largest configured lead");
    }

    @Test
    void aTaskMoreThanTwoMinutesInThePastIsNotAnnouncedLate() throws Exception {
        // PINNED: the "now" lead is `mins <= 0 && mins > -2`, so a task the user was not in
        // front of when it started is never announced once it is two minutes old.
        place("missed", LocalTime.of(8, 57), null);

        tick();

        assertEquals(0, admissionsFor("id-missed", sentKeys()));
    }

    // ---------- wiring ----------

    private static void place(String name, LocalTime start, Workflow workflow) {
        Task t = task(name, start);
        if (workflow != null) t.setWorkflow(workflow);
        TaskManagementSystem.getInstance().getPlacedTasks().add(t);
    }

    private static Task task(String name, LocalTime start) {
        Task t = Task.loadTaskFromdb("id-" + name, name, 1.0, 3,
                null, null, null, null, MON, start);
        t.setLifeCycle(TaskLifeCycle.ONGOING);
        t.setWorkflow(Workflow.UNFINISHED);
        return t;
    }

    private static String start(int hour, int minute) {
        return LocalDate.of(2026, 10, 12) + "T" + String.format("%02d:%02d", hour, minute);
    }

    /** The dedupe set, which is the observable side effect of one tick. */
    @SuppressWarnings("unchecked")
    private static Set<String> sentKeys() throws Exception {
        Field f = TaskReminders.class.getDeclaredField("sent");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    private static void tick() throws Exception {
        Method m = TaskReminders.class.getDeclaredMethod("tick");
        m.setAccessible(true);
        try {
            m.invoke(null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw e;
        }
    }

    /**
     * How many keys in the dedupe set belong to {@code taskId}.
     *
     * <p>Keys are {@code id|start|lead}, so a prefix of {@code id|} identifies one task. Counting
     * per task rather than asserting on the whole set keeps these tests valid alongside any
     * reminder a concurrently running poll thread may have added for some other task.
     */
    private static int admissionsFor(String taskId, Set<String> keys) {
        String prefix = taskId + "|";
        int n = 0;
        for (String key : keys) {
            if (key.startsWith(prefix)) n++;
        }
        return n;
    }

    /** The dedupe keys admitted for one task, in whatever order the set yields them. */
    private static List<String> keysFor(String taskId, Set<String> keys) {
        String prefix = taskId + "|";
        List<String> out = new java.util.ArrayList<>();
        for (String key : keys) {
            if (key.startsWith(prefix)) out.add(key);
        }
        java.util.Collections.sort(out);
        return out;
    }
}
