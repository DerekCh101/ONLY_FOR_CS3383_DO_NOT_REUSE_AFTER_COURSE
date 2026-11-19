package org.ashoreprospect.support;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class FxTestSupport implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.create(FxTestSupport.class))
                .getOrComputeIfAbsent("toolkit", key -> new Toolkit(), Toolkit.class);
    }

    public static <T> T call(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    public static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (call(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("JavaFX action did not complete");
    }

    public static <T extends Node> List<T> findAll(Node root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(root, type, seen, matches);
        return matches;
    }

    private static <T extends Node> void collect(Node node, Class<T> type,
                                                  Set<Node> seen, List<T> matches) {
        if (node == null || !seen.add(node)) return;
        if (type.isInstance(node)) matches.add(type.cast(node));
        if (node instanceof ScrollPane scroll) collect(scroll.getContent(), type, seen, matches);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, type, seen, matches);
        }
    }

    private static final class Toolkit implements ExtensionContext.Store.CloseableResource {
        private Toolkit() {
            CountDownLatch started = new CountDownLatch(1);
            Runnable ready = () -> {
                Platform.setImplicitExit(false);
                started.countDown();
            };
            try {
                Platform.startup(ready);
            } catch (IllegalStateException alreadyStarted) {
                Platform.runLater(ready);
            }
            try {
                if (!started.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("JavaFX toolkit did not start");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting JavaFX", e);
            }
        }

        @Override
        public void close() {
            Platform.exit();
        }
    }
}
