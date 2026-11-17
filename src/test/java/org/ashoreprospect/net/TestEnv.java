package org.ashoreprospect.net;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Test-only control over the environment as {@link Endpoints} / {@link OsEnv} see it.
 *
 * <p>{@code OsEnv.persist()/clear()} must never be called from a test: on Windows they run
 * {@code setx} and rewrite the user's roaming {@code ashore-prospect/user.env}, so a test-derived
 * value would outlive the run. {@link #set} writes the in-process overlay instead, which production
 * code reads first and which touches nothing outside this JVM.
 *
 * <p>The overlay is not enough for every branch. {@code Endpoints} has file fallbacks that are
 * only reachable when no environment value exists, and a test cannot remove one: this JVM does not
 * open {@code java.util} or {@code java.base} to the test module, so {@code System.getenv}'s
 * backing map is read-only and {@code OsEnv.persist("", "")} is forbidden. {@link #select} exists
 * to let those tests state that limit and {@code assumeTrue} on it, rather than assert a file
 * fallback that this machine will never take.
 *
 * <p>Both are process-wide, so callers must clear what they set in {@code @AfterEach} and hold
 * {@code @ResourceLock("os-env")}.
 */
final class TestEnv {

    private TestEnv() {}

    static void set(String name, String value) throws Exception {
        Map<String, String> overlay = overlay();
        if (value == null) {
            overlay.remove(name);
        } else {
            overlay.put(name, value);
        }
    }

    /** Clears the overlay only. The OS environment is left exactly as the caller found it. */
    static void clear(String... names) throws Exception {
        for (String name : names) set(name, null);
    }

    /**
     * What this machine and this JVM currently answer for {@code name}, ignoring the overlay.
     *
     * <p>This is the value production code would see with no test overlay, so a test can check
     * whether its own file fallback is reachable here instead of assuming it is.
     */
    static String select(String name) throws Exception {
        Map<String, String> overlay = overlay();
        String saved = overlay.remove(name);
        try {
            return OsEnv.get(name);
        } finally {
            if (saved != null) overlay.put(name, saved);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> overlay() throws Exception {
        Field f = OsEnv.class.getDeclaredField("OVERLAY");
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }
}
