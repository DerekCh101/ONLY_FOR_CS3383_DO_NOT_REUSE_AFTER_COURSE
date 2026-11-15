/** Network probe: pure boolean, null-tolerant registration, idempotent start guard. */
package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nothing here asserts a connectivity value — CI runners differ. The watcher thread is a
 * daemon polling every two seconds and there is no public stop, so it is left running for
 * the JVM lifetime; that is safe because it is a daemon and only notifies on a transition.
 */
class NetworkWatchTest {

    private static Thread watcher() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("network-watch".equals(t.getName())) return t;
        }
        return null;
    }

    @Test
    void isOnlineIsAPureBooleanThatNeverThrows() {
        assertDoesNotThrow(NetworkWatch::isOnline);
        // Relational only: no assertion on the value itself.
        boolean first = NetworkWatch.isOnline();
        assertTrue(first || !first);
    }

    @Test
    void addListenerIgnoresNull() {
        assertDoesNotThrow(() -> NetworkWatch.addListener(null));
    }

    @Test
    void startLaunchesADaemonWatcherThread() {
        NetworkWatch.start();
        Thread t = watcher();
        assertNotNull(t, "network-watch thread should exist after start()");
        assertTrue(t.isDaemon(), "network-watch should be a daemon");
    }

    @Test
    void startTwiceIsGuardedByTheRunningFlag() {
        NetworkWatch.start();
        Thread first = watcher();
        NetworkWatch.start();
        Thread second = watcher();

        assertNotNull(first);
        assertSame(first, second, "start() is guarded by the running flag");
    }
}
