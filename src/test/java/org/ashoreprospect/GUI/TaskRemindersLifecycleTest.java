/**
 * {@code TaskReminders.start()} / {@code stop()}: the timer lifecycle.
 *
 * <p>{@code start()} builds a JavaFX {@code Timeline} and calls {@code Platform.runLater}, so it
 * needs a started toolkit. That works headlessly here - {@code Platform.startup} succeeds with no
 * display and the JavaFX modules come from the build's own module path - and this is the only
 * test class in the suite that starts one.
 *
 * <p>A {@code Timeline} is thread-affine: creating, playing, or stopping it off the FX thread
 * throws {@code Not on FX application thread}. Every call therefore goes through
 * {@link #onFx}, which hops onto the FX thread and waits, rather than being called from the test
 * worker.
 *
 * <p>The 20-second tick itself is not exercised; {@code tick()} is covered by
 * {@link TaskRemindersTickTest}.
 */
package org.ashoreprospect.GUI;

import javafx.application.Platform;
import org.ashoreprospect.AppTimeReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.time.Clock;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ResourceLock("app-clock")
@ResourceLock("javafx-toolkit")
class TaskRemindersLifecycleTest {

    private static boolean startedByThisClass;

    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(() -> { });
            startedByThisClass = true;
        } catch (IllegalStateException alreadyStarted) {
            // The toolkit is a JVM-wide singleton; another class may have started it.
        }
    }

    @AfterAll
    static void stopToolkit() {
        if (startedByThisClass) Platform.exit();
    }

    private Clock originalClock;

    @BeforeEach
    void setUp() throws Exception {
        originalClock = Clock.systemDefaultZone();
        AppTimeReference.setClock(originalClock);
        onFx(() -> {
            TaskReminders.stop();
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(() -> {
            TaskReminders.stop();
            return null;
        });
        AppTimeReference.setClock(originalClock);
    }

    @Test
    void startCreatesTheTimerAndASecondStartKeepsTheSameOne() throws Exception {
        onFx(() -> {
            TaskReminders.start();
            return null;
        });
        Object first = clock();

        onFx(() -> {
            TaskReminders.start();
            return null;
        });

        assertNotNull(first, "start() creates a timer");
        assertEquals(first, clock(), "a second start() is a no-op, not a second timer");
    }

    @Test
    void stopReleasesTheTimerSoItCanBeStartedAgain() throws Exception {
        onFx(() -> {
            TaskReminders.start();
            return null;
        });
        assertNotNull(clock());

        onFx(() -> {
            TaskReminders.stop();
            return null;
        });
        assertNull(clock(), "stop() clears the timer");

        onFx(() -> {
            TaskReminders.start();
            return null;
        });
        assertNotNull(clock(), "and a later start() takes over again");
    }

    @Test
    void stopIsSafeWithoutAPriorStart() throws Exception {
        onFx(() -> {
            TaskReminders.stop();
            TaskReminders.stop();
            return null;
        });

        assertNull(clock());
    }

    /** Runs {@code body} on the FX thread and returns its result, rethrowing any failure. */
    private static <T> T onFx(Callable<T> body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Platform.runLater(() -> {
            try {
                result[0] = body.call();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("the FX thread did not run the task in time");
        }
        if (failure[0] instanceof Exception e) throw e;
        if (failure[0] != null) throw new AssertionError(failure[0]);
        @SuppressWarnings("unchecked")
        T typed = (T) result[0];
        return typed;
    }

    /** The private {@code clock} field, i.e. whether a timer is currently installed. */
    private static Object clock() {
        try {
            Field f = TaskReminders.class.getDeclaredField("clock");
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            throw new AssertionError("cannot read TaskReminders.clock", e);
        }
    }
}
