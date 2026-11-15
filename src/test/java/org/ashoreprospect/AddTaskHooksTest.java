/** After-add listeners: fan-out, ordering, null tolerance, and copy-on-fire. */
package org.ashoreprospect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AddTaskHooks} keeps a JVM-wide static listener list with no way to remove a
 * listener, so every test registers handlers that no-op outside their own run and the
 * class runs under its own resource lock.
 */
@ResourceLock("add-task-hooks")
class AddTaskHooksTest {

    private static Task task(String name) {
        return new Task(name, 1, 1, LocalDate.of(2026, 10, 12), LocalTime.of(9, 0),
                null, null);
    }

    @AfterEach
    void nothingToClean() {
        // Listeners cannot be unregistered; each test gates its own listener on a flag.
    }

    @Test
    void listenerReceivesTheExactTaskInstance() {
        List<Task> got = new ArrayList<>();
        AddTaskHooks.afterAdd(task -> {
            if (got.size() < 1 && "hook-identity".equals(task.getTaskName())) got.add(task);
        });

        Task t = task("hook-identity");
        AddTaskHooks.fireAfterAdd(t);

        assertEquals(1, got.size());
        assertSame(t, got.get(0));
    }

    @Test
    void twoListenersFireInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        AddTaskHooks.afterAdd(t -> {
            if ("hook-order".equals(t.getTaskName())) order.add("first");
        });
        AddTaskHooks.afterAdd(t -> {
            if ("hook-order".equals(t.getTaskName())) order.add("second");
        });

        AddTaskHooks.fireAfterAdd(task("hook-order"));

        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void firingANullTaskNotifiesNobody() {
        boolean[] hit = {false};
        AddTaskHooks.afterAdd(t -> hit[0] = true);

        assertDoesNotThrow(() -> AddTaskHooks.fireAfterAdd(null));

        assertTrue(!hit[0]);
    }

    @Test
    void aThrowingListenerDoesNotStopTheNextOne() {
        List<String> order = new ArrayList<>();
        AddTaskHooks.afterAdd(t -> {
            if ("hook-throw".equals(t.getTaskName())) {
                order.add("thrower");
                throw new IllegalStateException("boom");
            }
        });
        AddTaskHooks.afterAdd(t -> {
            if ("hook-throw".equals(t.getTaskName())) order.add("survivor");
        });

        assertDoesNotThrow(() -> AddTaskHooks.fireAfterAdd(task("hook-throw")));

        assertEquals(List.of("thrower", "survivor"), order);
    }

    @Test
    void listenersRegisteredMidFireWaitForTheNextFire() {
        // PINNED: fireAfterAdd iterates a copy of the list, so a listener added during
        // the fire is not called by that same fire.
        List<String> late = new ArrayList<>();
        boolean[] registered = {false};
        AddTaskHooks.afterAdd(t -> {
            if (!"hook-late".equals(t.getTaskName()) || registered[0]) return;
            registered[0] = true;
            late.add("outer");
            AddTaskHooks.afterAdd(inner -> {
                if ("hook-late".equals(inner.getTaskName())) late.add("inner");
            });
        });

        AddTaskHooks.fireAfterAdd(task("hook-late"));
        assertEquals(List.of("outer"), late);

        // The second fire runs the original listener plus the one registered mid-fire.
        AddTaskHooks.fireAfterAdd(task("hook-late"));
        assertEquals(List.of("outer", "inner"), late);
    }
}
