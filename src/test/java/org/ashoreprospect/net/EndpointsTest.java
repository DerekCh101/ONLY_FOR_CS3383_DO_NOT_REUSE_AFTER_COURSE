/**
 * Endpoint helpers: node URL precedence, host detection, Postgres URL, and the
 * read-from-disk fallbacks.
 *
 * <p>These tests only read. They never call {@code OsEnv.persist()/clear()}: on Windows
 * that runs {@code setx} plus a rewrite of the developer's real {@code user.env}, so a
 * test-derived value would outlive the run.
 *
 * <p>Every expectation is therefore written against whatever the machine currently has
 * configured for {@code SCHEDULER_NODE} / {@code postgres_pw}, and the {@code .scheduler}
 * server/postgres files are snapshotted and restored around each test.
 */
package org.ashoreprospect.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("os-env")
class EndpointsTest {

    private static final Path DIR = Endpoints.dir();
    private static final Path SERVER_FILE = DIR.resolve("server.txt");
    private static final Path PG_FILE = DIR.resolve("postgres.pw");

    private String savedServerFile;
    private String savedPgFile;

    @BeforeEach
    void snapshot() throws Exception {
        savedServerFile = Files.isRegularFile(SERVER_FILE)
                ? Files.readString(SERVER_FILE, StandardCharsets.UTF_8) : null;
        savedPgFile = Files.isRegularFile(PG_FILE)
                ? Files.readString(PG_FILE, StandardCharsets.UTF_8) : null;
        Files.deleteIfExists(SERVER_FILE);
        Files.deleteIfExists(PG_FILE);
    }

    @AfterEach
    void restore() throws Exception {
        Files.deleteIfExists(SERVER_FILE);
        Files.deleteIfExists(PG_FILE);
        if (savedServerFile != null) {
            Files.createDirectories(DIR);
            Files.writeString(SERVER_FILE, savedServerFile, StandardCharsets.UTF_8);
        }
        if (savedPgFile != null) {
            Files.createDirectories(DIR);
            Files.writeString(PG_FILE, savedPgFile, StandardCharsets.UTF_8);
        }
    }

    /** The configured node URL with trailing slashes removed, or the fallback. */
    private static String expectedNodeBase() {
        String env = OsEnv.get(Endpoints.NODE_ENV);
        if (env != null && !env.isBlank()) {
            String url = env;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            return url;
        }
        return Endpoints.LOCAL_NODE;
    }

    // ---------- constants and paths ----------

    @Test
    void dir_isSchedulerUnderTheWorkingDirectory() {
        Path expected = Path.of(System.getProperty("user.dir"), ".scheduler");
        assertEquals(expected.toAbsolutePath().normalize(),
                DIR.toAbsolutePath().normalize());
        assertTrue(DIR.endsWith(".scheduler"));
    }

    @Test
    void hostConstants_areStableAndTheCreatorHostIsStillAPlaceholder() {
        assertEquals("postgres_pw", Endpoints.PG_ENV);
        assertEquals("SCHEDULER_NODE", Endpoints.NODE_ENV);
        assertEquals("http://127.0.0.1:3456", Endpoints.LOCAL_NODE);
        assertTrue(Endpoints.CREATOR_HOST.contains("YOUR-DOMAIN-HERE"));
    }

    // ---------- nodeBase ----------

    @Test
    void nodeBase_usesTheEnvironmentWhenItIsSet() {
        String env = OsEnv.get(Endpoints.NODE_ENV);
        if (env == null || env.isBlank()) {
            assertEquals(Endpoints.LOCAL_NODE, Endpoints.nodeBase());
        } else {
            assertEquals(expectedNodeBase(), Endpoints.nodeBase());
        }
    }

    @Test
    void nodeBase_readsTheLegacyFileAndStripsTrailingSlashes() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(SERVER_FILE, "  https://example.test:3456///  \n",
                StandardCharsets.UTF_8);

        // PINNED: the environment wins over server.txt, so the file only decides the
        // result when no node URL is configured.
        String env = OsEnv.get(Endpoints.NODE_ENV);
        String actual = Endpoints.nodeBase();
        if (env == null || env.isBlank()) {
            assertEquals("https://example.test:3456", actual);
        } else {
            assertEquals(expectedNodeBase(), actual);
        }
        assertTrue(Endpoints.hasSavedServer(), "the file exists now");
    }

    @Test
    void hasSavedServer_isTrueWhenTheFileExistsEvenWithABlankNodeUrl() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(SERVER_FILE, "   \n", StandardCharsets.UTF_8);

        assertTrue(Endpoints.hasSavedServer());
        String env = OsEnv.get(Endpoints.NODE_ENV);
        if (env == null || env.isBlank()) {
            assertEquals(Endpoints.LOCAL_NODE, Endpoints.nodeBase(),
                    "a blank file falls through to the local default");
        }
    }

    // ---------- host detection ----------

    @Test
    void isCreatorHost_isAlwaysFalseWhileTheDomainIsAPlaceholder() {
        assertFalse(Endpoints.isCreatorHost());
    }

    @Test
    void isLocalHost_and_isHostDevice_followTheConfiguredNodeUrl() {
        String base = Endpoints.nodeBase();
        boolean loopback = base.contains("127.0.0.1") || base.contains("localhost");

        assertEquals(loopback, Endpoints.isLocalHost());
        // isHostDevice() is the creator host OR loopback OR one of this PC's own NICs.
        // The first two are the only ones this test can decide, so it only asserts the
        // implication in the loopback direction.
        if (loopback) {
            assertTrue(Endpoints.isHostDevice());
        }
    }

    // ---------- Postgres ----------

    @Test
    void postgresUrl_isAlwaysLoopbackWithTimeouts() {
        assertEquals("jdbc:postgresql://127.0.0.1:5432/Scheduler?connectTimeout=2&socketTimeout=3",
                Endpoints.postgresUrl());
    }

    @Test
    void postgresPassword_matchesTheConfiguredValue() {
        String env = OsEnv.get(Endpoints.PG_ENV);
        String actual = Endpoints.postgresPassword();

        if (env != null && !env.isBlank()) {
            assertEquals(env.trim(), actual);
        } else {
            // No env value: the legacy file (absent here) yields an empty password.
            assertEquals("", actual);
        }
    }

    @Test
    void postgresPassword_fallsBackToTheLegacyFileOnlyWithoutAnEnvValue() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(PG_FILE, "  from-file  \n", StandardCharsets.UTF_8);

        String env = OsEnv.get(Endpoints.PG_ENV);
        String expected = (env == null || env.isBlank()) ? "from-file" : env.trim();
        assertEquals(expected, Endpoints.postgresPassword());
    }

    @Test
    void aBlankLegacyFile_neverSuppliesAPassword() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(PG_FILE, "   \n", StandardCharsets.UTF_8);

        String env = OsEnv.get(Endpoints.PG_ENV);
        String expected = (env == null || env.isBlank()) ? "" : env.trim();
        assertEquals(expected, Endpoints.postgresPassword());
    }
}
