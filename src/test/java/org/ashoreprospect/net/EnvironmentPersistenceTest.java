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
class EnvironmentPersistenceTest {

    private static final String KEY = "OSENV_PERSIST_TEST_91A3";
    private String originalOs;
    private Path envFile;
    private Path serverFile;
    private Path passwordFile;
    private byte[] originalEnv;
    private byte[] originalServer;
    private byte[] originalPassword;
    private boolean snapshotsReady;

    @BeforeEach
    void setUp() throws Exception {
        originalOs = System.getProperty("os.name");
        System.setProperty("os.name", "Linux");
        envFile = OsEnv.userEnvFile();
        serverFile = Endpoints.dir().resolve("server.txt");
        passwordFile = Endpoints.dir().resolve("postgres.pw");

        Path root = Path.of(System.getProperty("scheduler.test.root")).toAbsolutePath().normalize();
        assertTrue(envFile.toAbsolutePath().normalize().startsWith(root.resolve("home")));
        assertTrue(serverFile.toAbsolutePath().normalize().startsWith(root.resolve("work")));
        originalEnv = snapshot(envFile);
        originalServer = snapshot(serverFile);
        originalPassword = snapshot(passwordFile);
        snapshotsReady = true;
        Files.deleteIfExists(envFile);
        Files.deleteIfExists(serverFile);
        Files.deleteIfExists(passwordFile);
        TestEnv.clear(KEY, Endpoints.NODE_ENV, Endpoints.PG_ENV);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            TestEnv.clear(KEY, Endpoints.NODE_ENV, Endpoints.PG_ENV);
            if (snapshotsReady) {
                restore(envFile, originalEnv);
                restore(serverFile, originalServer);
                restore(passwordFile, originalPassword);
            }
        } finally {
            if (originalOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", originalOs);
        }
    }

    @Test
    void persistAndClearUpdateTheOverlayAndPreserveOtherFileEntries() throws Exception {
        Files.createDirectories(envFile.getParent());
        Files.writeString(envFile, "# comment\nOSENV_OTHER_TEST_91A3=keep=equals\ninvalid\n",
                StandardCharsets.UTF_8);

        OsEnv.persist(KEY, "saved");
        assertEquals("saved", OsEnv.get(KEY));
        TestEnv.clear(KEY);
        assertEquals("saved", OsEnv.get(KEY), "the file is used when the overlay is absent");

        OsEnv.persist(KEY, "updated");
        OsEnv.clear(KEY);
        assertEquals("", OsEnv.get(KEY));
        assertEquals("keep=equals", OsEnv.get("OSENV_OTHER_TEST_91A3"));
        assertFalse(Files.readString(envFile).contains(KEY + "="));
    }

    @Test
    void nullAndBlankValuesRemoveEntriesAndInvalidNamesAreIgnored() throws Exception {
        OsEnv.persist(KEY, "saved");
        OsEnv.persist(KEY, null);
        assertEquals("", OsEnv.get(KEY));
        OsEnv.persist(KEY, "saved again");
        OsEnv.persist(KEY, "  ");
        assertEquals("", OsEnv.get(KEY));

        String before = Files.readString(envFile);
        OsEnv.persist(null, "ignored");
        OsEnv.persist(" ", "ignored");
        assertEquals(before, Files.readString(envFile));
    }

    @Test
    void saveAndClearNodeMigrateTheLegacyFile() throws Exception {
        Files.createDirectories(serverFile.getParent());
        Files.writeString(serverFile, "http://legacy.test");

        Endpoints.saveNode(null);
        Endpoints.saveNode("  ");
        assertEquals("http://legacy.test", Endpoints.nodeBase());

        Endpoints.saveNode("  https://node.example///  ");
        assertEquals("https://node.example", Endpoints.nodeBase());
        assertTrue(Endpoints.hasSavedServer());
        assertFalse(Files.exists(serverFile));
        TestEnv.clear(Endpoints.NODE_ENV);
        assertEquals("https://node.example", Endpoints.nodeBase());

        Files.writeString(serverFile, "http://stale.test");
        Endpoints.clearSavedServer();
        assertFalse(Files.exists(serverFile));
        assertFalse(Endpoints.hasSavedServer());
        assertEquals(Endpoints.LOCAL_NODE, Endpoints.nodeBase());
    }

    @Test
    void savePostgresPasswordTrimsAndMigratesTheLegacyFile() throws Exception {
        Files.createDirectories(passwordFile.getParent());
        Files.writeString(passwordFile, "legacy");

        Endpoints.savePostgresPassword("  new-password  ");
        assertEquals("new-password", Endpoints.postgresPassword());
        assertFalse(Files.exists(passwordFile));
        TestEnv.clear(Endpoints.PG_ENV);
        assertEquals("new-password", Endpoints.postgresPassword());

        Endpoints.savePostgresPassword(null);
        Endpoints.savePostgresPassword("  ");
        assertEquals("new-password", Endpoints.postgresPassword());
    }

    @Test
    void ensurePostgresPasswordGeneratesThenReusesTheSavedPassword() {
        String generated = Endpoints.ensurePostgresPassword();
        assertTrue(generated.matches("[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789]{20}"));
        assertEquals(generated, Endpoints.postgresPassword());
        assertEquals(generated, Endpoints.ensurePostgresPassword());
    }

    private static byte[] snapshot(Path file) throws Exception {
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    private static void restore(Path file, byte[] contents) throws Exception {
        if (file == null) return;
        if (contents == null) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent());
            Files.write(file, contents);
        }
    }
}
