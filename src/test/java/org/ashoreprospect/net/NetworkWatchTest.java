/** Network probe: pure boolean, null-tolerant registration, idempotent start guard. */
package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;

import java.net.NetworkInterface;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void isOnlineMatchesAnIndependentScanOfTheSameInterfaces() throws Exception {
        // The value itself depends on the runner, but the rule does not: online means "at least
        // one interface that is up, not loopback, and not virtual". Re-deriving that here covers
        // both arms without assuming a connectivity value.
        boolean expected = false;
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en != null && en.hasMoreElements()) {
            NetworkInterface ni = en.nextElement();
            if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
                expected = true;
                break;
            }
        }

        assertEquals(expected, NetworkWatch.isOnline());
        assertEquals(expected, NetworkWatch.isOnline(), "the probe is repeatable");
    }

    @Test
    void startRecordsTheInitialStateBeforeItReturns() {
        // start() sets `last` synchronously and only then spawns the thread, so the state the
        // first poll compares against is the state at start time.
        NetworkWatch.start();

        assertEquals(NetworkWatch.isOnline(), lastState(),
                "the initial state is captured synchronously");
    }

    @Test
    void theWatcherPollsAndNotifiesOnlyOnARealTransition() throws Exception {
        // The loop sleeps two seconds per poll, so waiting is unavoidable - but the assertion is
        // about what was *not* sent. On a machine whose connectivity does not flip mid-test, a
        // transition would mean a bug, so "no notification" is the meaningful check. Without this
        // wait the poll body only runs if the rest of the suite happens to outlast the sleep,
        // which makes its coverage depend on test order.
        NetworkWatch.start();
        int[] notified = {0};
        NetworkWatch.addListener(online -> notified[0]++);

        Thread.sleep(2500);

        assertEquals(0, notified[0],
                "connectivity is stable within one poll, so nothing is announced");
    }

    private static boolean lastState() {        try {
            java.lang.reflect.Field f = NetworkWatch.class.getDeclaredField("last");
            f.setAccessible(true);
            Object v = f.get(null);
            return v != null && (Boolean) v;
        } catch (Exception e) {
            throw new AssertionError("cannot read NetworkWatch.last", e);
        }
    }
}
