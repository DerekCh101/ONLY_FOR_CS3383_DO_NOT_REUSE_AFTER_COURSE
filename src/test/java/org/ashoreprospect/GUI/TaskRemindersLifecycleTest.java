/**
 * {@code TaskReminders.start()} / {@code stop()}: the timer lifecycle.
 *
 * <p>No JavaFX toolkit is started here. {@code start()} uses an FX {@code Timeline} when one is
 * available, and a daemon thread otherwise; {@code stop()} clears whichever one was installed.
 * {@code Platform.startup} - the previous approach - boots native glass/AWT and crashes the
 * process on a headless CI runner and on macOS runners, so these tests must not depend on it.
 *
 * <p>Every call therefore runs on the plain test thread, which takes the no-toolkit path. The
 * assertions read the private timer fields, i.e. whether a timer is currently installed.
 *
 * <p>The 20-second tick itself is not exercised; {@code tick()} is covered by
 * {@link TaskRemindersTickTest}.
 */
package org.ashoreprospect.GUI;

import org.ashoreprospect.AppTimeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ResourceLock("app-clock")
class TaskRemindersLifecycleTest {

    private Clock originalClock;

    @BeforeEach
    void setUp() throws Exception {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(originalClock);
        TaskReminders.stop();
    }

    @AfterEach
    void tearDown() throws Exception {
        TaskReminders.stop();
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void startCreatesTheTimerAndASecondStartKeepsTheSameOne() throws Exception {
        TaskReminders.start();
        Object first = timer();
        Object firstThread = clockThread();

        TaskReminders.start();

        assertNotNull(first, "start() creates a timer");
        assertEquals(first, timer(), "a second start() is a no-op, not a second timer");
        assertEquals(firstThread, clockThread(), "no second tick thread either");
    }

    @Test
    void stopReleasesTheTimerSoItCanBeStartedAgain() throws Exception {
        TaskReminders.start();
        assertNotNull(timer());

        TaskReminders.stop();
        assertNull(clock(), "stop() clears the FX timeline");
        assertNull(clockThread(), "stop() clears the fallback thread");

        TaskReminders.start();
        assertNotNull(timer(), "and a later start() takes over again");
    }

    @Test
    void stopIsSafeWithoutAPriorStart() throws Exception {
        TaskReminders.stop();
        TaskReminders.stop();

        assertNull(clock());
        assertNull(clockThread());
    }

    /**
     * Whichever timer {@code start()} installed: the FX {@code Timeline} when a toolkit is up,
     * the fallback thread when it is not. Null means nothing is running.
     */
    private static Object timer() {
        Object timeline = clock();
        return timeline != null ? timeline : clockThread();
    }

    /** The private {@code clock} field, i.e. whether a timer is currently installed. */
    private static Object clock() {
        return field("clock");
    }

    /** The private {@code clockThread} field, i.e. whether the no-toolkit loop is running. */
    private static Object clockThread() {
        return field("clockThread");
    }

    private static Object field(String name) {
        try {
            Field f = TaskReminders.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            throw new AssertionError("cannot read TaskReminders." + name, e);
        }
    }
}
