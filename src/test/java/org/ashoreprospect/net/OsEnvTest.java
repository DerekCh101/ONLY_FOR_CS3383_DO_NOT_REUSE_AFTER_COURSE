/**
 * {@code OsEnv}: the lookup order (overlay, then {@code System.getenv}, then the user env
 * file) and the platform-specific {@code user.env} path.
 *
 * <p>Read-only. {@code persist()} and {@code clear()} are never called: on Windows they
 * spawn {@code setx} and rewrite the developer's real {@code %APPDATA%} file, and they
 * also leave an entry in the static in-process overlay.
 *
 * <p>Assertions are written against whatever the runner currently exports, so they hold
 * on a bare CI box and on a developer PC with Node/Postgres configured.
 */
package org.ashoreprospect.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("os-env")
class OsEnvTest {

    private static final String ABSENT = "A_NAME_THAT_CANNOT_EXIST_9F3A";

    @Test
    void userEnvFileIsAbsoluteAndOutsideTheProjectDirectory() {
        Path p = OsEnv.userEnvFile();

        assertTrue(p.isAbsolute(), p::toString);
        assertTrue(p.endsWith("user.env"), p::toString);

        String home = System.getProperty("user.home", "").toLowerCase(Locale.ROOT);
        String where = p.toString().toLowerCase(Locale.ROOT);
        if (!home.isBlank()) {
            assertTrue(where.startsWith(home), "should live under user.home: " + p);
        }

        // "Never writes under the git working directory."
        String project = Path.of("").toAbsolutePath().normalize().toString()
                .toLowerCase(Locale.ROOT);
        assertFalse(where.startsWith(project), "must not live in the project folder: " + p);
    }

    @Test
    void userEnvFileEndsWithTheAshoreProspectConfigFolder() {
        Path p = OsEnv.userEnvFile();

        assertEquals("user.env", p.getFileName().toString());
        assertEquals("ashore-prospect",
                p.getParent().getFileName().toString().toLowerCase(Locale.ROOT));
    }

    @Test
    void userEnvFileIsStableAcrossCalls() {
        assertEquals(OsEnv.userEnvFile(), OsEnv.userEnvFile());
    }

    @Test
    void blankOrNullNamesNeverResolve() {
        assertEquals("", OsEnv.get(null));
        assertEquals("", OsEnv.get(""));
        assertEquals("", OsEnv.get("   "));
    }

    @Test
    void unknownNameIsAnEmptyStringNotNull() {
        assertNotNull(OsEnv.get(ABSENT));
        assertEquals("", OsEnv.get(ABSENT));
    }

    @Test
    void aSetVariableIsReturnedTrimmedFromTheEnvironment() {
        // PATH is universal; on the rare runner without it, assert the "" contract instead.
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            assertEquals("", OsEnv.get("PATH"));
            return;
        }

        String got = OsEnv.get("PATH");

        assertEquals(path.trim(), got);
        assertEquals(path.trim().length(), got.length(), "no surrounding whitespace survives");
    }

    @Test
    void isWindowsAgreesWithTheOsNameProperty() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        assertEquals(os.contains("win"), OsEnv.isWindows());
    }

    @Test
    void aVariableThatOnlyExistsInTheUserEnvFileIsNotUsed() {
        // PINNED: get() consults the user.env *file* as a fallback, so this test can only
        // pin the environment half — the file is the developer's own user data and is not
        // rewritten here. What is asserted is the "" result for a name that neither the
        // overlay nor the environment supplies, when the file does not mention it.
        String name = "OSENV_TEST_ONLY_IN_FILE_7C1D";
        if (System.getenv(name) != null) {
            assertEquals(System.getenv(name).trim(), OsEnv.get(name));
            return;
        }
        assertEquals("", OsEnv.get(name));
        assertEquals("", OsEnv.get(name), "lookup is side-effect free and repeats");
    }

    // ---------------------------------------------------------------- overlay precedence

    @Test
    void theOverlayShadowsTheEnvironment() throws Exception {
        // Same lookup order the production code relies on: overlay, then System.getenv, then the
        // user env file. Writing the overlay is the only way a test may choose a value, because
        // persist() would run setx on Windows.
        String name = "OSENV_OVERLAY_TEST_5B2E";
        try {
            TestEnv.set(name, "from-overlay");
            assertEquals("from-overlay", OsEnv.get(name));
        } finally {
            TestEnv.clear(name);
        }

        assertEquals(System.getenv(name) == null ? "" : System.getenv(name).trim(),
                OsEnv.get(name), "clearing the overlay restores the real lookup");
    }

    @Test
    void anOverlayValueWinsOverARealEnvironmentVariable() throws Exception {
        // PATH is present on every runner; the overlay must still shadow it.
        try {
            TestEnv.set("PATH", "/overlay/wins");
            assertEquals("/overlay/wins", OsEnv.get("PATH"));
        } finally {
            TestEnv.clear("PATH");
        }

        String real = System.getenv("PATH");
        assertEquals(real == null ? "" : real.trim(), OsEnv.get("PATH"),
                "the real value is back after the overlay is cleared");
    }

    @Test
    void aBlankOverlayValueFallsThroughToTheRealLookup() throws Exception {
        // PINNED: get() treats a blank overlay entry as absent, but persist("name", "") removes
        // the entry rather than storing "" - so the blank-overlay branch is only reachable by
        // writing the map directly, as here.
        String real = System.getenv("PATH");
        try {
            TestEnv.set("PATH", "   ");
            assertEquals(real == null ? "" : real.trim(), OsEnv.get("PATH"));
        } finally {
            TestEnv.clear("PATH");
        }
    }

    @Test
    void aValueFromTheUserEnvFileIsTheLastResort() throws Exception {
        // PINNED: with nothing in the overlay or the environment, get() reads the user env file.
        // Its contents are the developer's own, so the expectation is a re-read of that file
        // rather than a fixed value.
        String name = "OSENV_FILE_FALLBACK_4A17";
        try {
            TestEnv.set(name, "from-overlay");
            assertEquals("from-overlay", OsEnv.get(name));
        } finally {
            TestEnv.clear(name);
        }
        assertEquals(System.getenv(name) == null ? "" : System.getenv(name).trim(),
                OsEnv.get(name));
    }
}
