/** Extra steps after a task is created (overlap / placement helpers). */
package org.ashoreprospect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Extension point for later customize-add-task / statistics modules.
 * Register a listener; it receives each newly created Task after a successful add.
 */
public final class AddTaskHooks {

    private static final List<Consumer<Task>> AFTER_ADD = new ArrayList<>();

    private AddTaskHooks() {}

    public static void afterAdd(Consumer<Task> listener) {
        if (listener != null) AFTER_ADD.add(listener);
    }

    public static void fireAfterAdd(Task task) {
        if (task == null) return;
        for (Consumer<Task> l : new ArrayList<>(AFTER_ADD)) {
            try {
                l.accept(task);
            } catch (Exception ignored) {
            }
        }
    }
}
