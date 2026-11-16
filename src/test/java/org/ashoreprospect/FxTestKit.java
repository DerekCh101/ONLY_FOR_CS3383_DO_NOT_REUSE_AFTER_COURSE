/**
 * Starts the JavaFX toolkit inside the test JVM so GUI classes can be exercised headlessly.
 *
 * <p>{@code Platform.startup} may only be called once per JVM; every later call throws
 * {@link IllegalStateException}. This helper therefore latches the first successful start and
 * lets every other test class reuse it. {@code setImplicitExit(false)} is essential: without it
 * closing the last {@code Stage} shuts the toolkit down and every following test fails with
 * "Toolkit not initialized".
 *
 * <p>Threading: JavaFX nodes must only be touched on the FX application thread. Use
 * {@link #onFx(ThrowingRunnable)} to run a block there and have any thrown exception re-thrown on
 * the test thread, so JUnit reports the real failure instead of a swallowed one.
 */
package org.ashoreprospect;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class FxTestKit {

    /** How long to wait for a toolkit operation before failing loudly instead of hanging. */
    private static final long TIMEOUT_SECONDS = 30;

    private static boolean started;

    private FxTestKit() {}

    /** A body that may throw, runnable on the FX thread. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Idempotently start the JavaFX toolkit. Safe to call from every test class; only the first
     * call actually starts anything.
     */
    public static synchronized void ensureStarted() throws Exception {
        if (started) return;
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // Another test class (or a previous run in the same JVM) beat us to it.
            ready.countDown();
        }
        if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX toolkit did not start within "
                    + TIMEOUT_SECONDS + "s");
        }
        // Keep the toolkit alive when a test closes its last window.
        Platform.setImplicitExit(false);
        started = true;
    }

    /** True once {@link #ensureStarted()} has completed successfully in this JVM. */
    public static synchronized boolean isStarted() {
        return started;
    }

    /**
     * Run {@code body} on the FX application thread and wait for it to finish.
     *
     * @throws Exception whatever {@code body} threw, unwrapped from the FX thread
     */
    public static void onFx(ThrowingRunnable body) throws Exception {
        ensureStarted();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task did not finish within "
                    + TIMEOUT_SECONDS + "s");
        }
        Throwable t = failure.get();
        if (t == null) return;
        if (t instanceof Exception e) throw e;
        if (t instanceof Error e) throw e;
        throw new IllegalStateException(t);
    }
}
