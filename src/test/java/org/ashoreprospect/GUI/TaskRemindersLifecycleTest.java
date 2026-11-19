/**
 * {@code TaskReminders.start()} / {@code stop()}: the timer lifecycle.
 *
 * <p>No JavaFX toolkit is started here. {@code start()} uses an FX {@code Timeline} when one is
 * available, and a daemon thread otherwise; {@code stop()} clears whichever one was installed.
 * {@code Platform.startup} - the previous approach - boots native glass/AWT and crashes the
 * process on a headless CI runner and on macOS runners, so these tests must not depend on it.
 *
 * <p>These tests do not start a toolkit. If another test already started one, they wait for
 * the asynchronous FX cleanup before inspecting the timer fields.
 *
 * <p>The 20-second tick itself is not exercised; {@code tick()} is covered by
 * {@link TaskRemindersTickTest}.
 */
package org.ashoreprospect.GUI;

import javafx.application.Platform;
import org.ashoreprospect.AppTimeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("app-clock")
@ResourceLock("javafx-toolkit")
class TaskRemindersLifecycleTest {

    private Clock originalClock;

    @BeforeEach
    void setUp() throws Exception {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(originalClock);
        stopAndWait();
    }

    @AfterEach
    void tearDown() throws Exception {
        stopAndWait();
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

        stopAndWait();
        assertNull(clock(), "stop() clears the FX timeline");
        assertNull(clockThread(), "stop() clears the fallback thread");

        TaskReminders.start();
        assertNotNull(timer(), "and a later start() takes over again");
    }

    @Test
    void stopIsSafeWithoutAPriorStart() throws Exception {
        stopAndWait();
        stopAndWait();

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

    private static void stopAndWait() throws Exception {
        TaskReminders.stop();
        CountDownLatch done = new CountDownLatch(1);
        try {
            Platform.runLater(done::countDown);
        } catch (IllegalStateException | UnsupportedOperationException | NoClassDefFoundError noToolkit) {
            return;
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "reminder cleanup did not complete");
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
