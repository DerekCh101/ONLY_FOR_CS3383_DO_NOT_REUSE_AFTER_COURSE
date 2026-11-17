package org.ashoreprospect.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Overlay-driven tests for {@code Endpoints}: the branches that depend on {@code SCHEDULER_NODE}
 * and {@code postgres_pw} become reachable because the tests write {@link OsEnv}'s in-process
 * overlay (see {@link TestEnv}) instead of the OS environment.
 *
 * <p>Nothing here calls {@code OsEnv.persist()/clear()}, {@code Endpoints.saveNode},
 * {@code clearSavedServer}, or {@code ensurePostgresPassword}: on Windows those run {@code setx}
 * and rewrite the developer's real {@code user.env} ({@code ensurePostgresPassword} would also
 * try to {@code ALTER USER postgres}). They stay documented and unexecuted.
 *
 * <p>The file-only fallbacks are a special case. {@code nodeBase()} and {@code hasSavedServer()}
 * consult the environment before the {@code .scheduler} files, so on a machine that has
 * {@code SCHEDULER_NODE} set they cannot be reached at all. This JVM does not open
 * {@code java.util} to the test module, so a test cannot remove that value either. Those
 * assertions are written as "the file is used <em>only</em> when no env value exists", which is
 * true on every machine, and the two file-only tests {@code assumeTrue} on the isolation.
 *
 * <p>{@code .scheduler/server.txt} and {@code .scheduler/postgres.pw} are snapshotted and
 * restored, and the overlay is cleared in {@code @AfterEach} because it is process-wide.
 */
@ResourceLock("os-env")
class EndpointsOverlayTest {

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
        TestEnv.clear(Endpoints.NODE_ENV, Endpoints.PG_ENV);
    }

    @AfterEach
    void restore() throws Exception {
        TestEnv.clear(Endpoints.NODE_ENV, Endpoints.PG_ENV);
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

    /** True when the machine has no {@code SCHEDULER_NODE}, so the file fallback is reachable. */
    private static boolean nodeIsUnconfiguredByThisMachine() throws Exception {
        return TestEnv.select(Endpoints.NODE_ENV).isBlank();
    }

    private static boolean passwordIsUnconfiguredByThisMachine() throws Exception {
        return TestEnv.select(Endpoints.PG_ENV).isBlank();
    }

    private static void writeServerFile(String content) throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(SERVER_FILE, content, StandardCharsets.UTF_8);
    }

    private static void writePgFile(String content) throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(PG_FILE, content, StandardCharsets.UTF_8);
    }

    // ---------- nodeBase precedence ----------

    @Test
    void nodeBase_prefersTheEnvironmentOverTheLegacyFile() throws Exception {
        writeServerFile("https://from-file.test");
        TestEnv.set(Endpoints.NODE_ENV, "https://from-env.test");

        assertEquals("https://from-env.test", Endpoints.nodeBase(),
                "a configured SCHEDULER_NODE wins over a leftover server.txt");
    }

    @Test
    void nodeBase_stripsEveryTrailingSlashFromTheEnvironment() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, "https://from-env.test///");

        assertEquals("https://from-env.test", Endpoints.nodeBase());
    }

    @Test
    void nodeBase_stripsEveryTrailingSlashFromTheLegacyFile() throws Exception {
        assumeTrue(nodeIsUnconfiguredByThisMachine(),
                "this machine has SCHEDULER_NODE set and java.util is not open to the test module");
        writeServerFile("  https://from-file.test:3456///  \n");

        assertEquals("https://from-file.test:3456", Endpoints.nodeBase(),
                "with no env value, the file decides and is trimmed");
    }

    @Test
    void nodeBase_fallsBackToTheLocalDefaultWhenNothingIsConfigured() throws Exception {
        assumeTrue(nodeIsUnconfiguredByThisMachine(),
                "this machine has SCHEDULER_NODE set and java.util is not open to the test module");

        assertEquals(Endpoints.LOCAL_NODE, Endpoints.nodeBase());

        writeServerFile("    \n");
        assertEquals(Endpoints.LOCAL_NODE, Endpoints.nodeBase(),
                "a blank file also falls through to the local default");
    }

    @Test
    void nodeBase_ofTrailingSlashesOnly_isAnEmptyString() throws Exception {
        // PINNED: trimSlash() does not stop at an empty string, so "/" becomes "" and
        // NodeApi then builds a relative URL. Reachable only through an env value.
        TestEnv.set(Endpoints.NODE_ENV, "/");
        assertEquals("", Endpoints.nodeBase());
    }

    @Test
    void nodeBase_ignoresAFileThatOnlyExistsAlongsideAConfiguredUrl() throws Exception {
        // The invariant that holds on every machine: a leftover server.txt never overrides an
        // environment value. This is the file-vs-env precedence rule proven without hiding.
        writeServerFile("https://from-file.test");
        TestEnv.set(Endpoints.NODE_ENV, "https://from-env.test");

        assertTrue(Endpoints.hasSavedServer());
        assertEquals("https://from-env.test", Endpoints.nodeBase());
    }

    // ---------- hasSavedServer ----------

    @Test
    void hasSavedServer_seesTheEnvironmentWithNoFile() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, "https://somewhere.test");

        assertTrue(Endpoints.hasSavedServer());
        assertFalse(Files.exists(SERVER_FILE));
    }

    @Test
    void hasSavedServer_falseWhenNeitherExists() throws Exception {
        assumeTrue(nodeIsUnconfiguredByThisMachine(),
                "this machine has SCHEDULER_NODE set and java.util is not open to the test module");

        assertFalse(Endpoints.hasSavedServer());
    }

    @Test
    void hasSavedServer_isTrueForABlankFile() throws Exception {
        // PINNED: hasSavedServer() only checks that the file exists, so a file containing
        // nothing but whitespace still counts as "saved" even though nodeBase() ignores it.
        assumeTrue(nodeIsUnconfiguredByThisMachine(),
                "this machine has SCHEDULER_NODE set and java.util is not open to the test module");
        writeServerFile("   \n");

        assertTrue(Endpoints.hasSavedServer());
        assertEquals(Endpoints.LOCAL_NODE, Endpoints.nodeBase());
    }

    // ---------- host detection ----------

    @Test
    void isCreatorHost_isFalseForEveryUrlWhileTheDomainIsAPlaceholder() throws Exception {
        // CREATOR_HOST still contains YOUR-DOMAIN-HERE, so isCreatorHost() returns early and is
        // false even for an https:// URL. That guard is what keeps the untestable
        // "real domain matches" branch harmless; if a domain is filled in, this test must change.
        assertTrue(Endpoints.CREATOR_HOST.contains("YOUR-DOMAIN-HERE"));

        TestEnv.set(Endpoints.NODE_ENV, "https://anything.test");
        assertFalse(Endpoints.isCreatorHost());
        assertFalse(Endpoints.isHostDevice(), "not creator, not loopback, not a NIC address");
    }

    @Test
    void isLocalHost_acceptsBothLoopbackSpellingsAndRejectsALanHost() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, "http://127.0.0.1:3456");
        assertTrue(Endpoints.isLocalHost());

        TestEnv.set(Endpoints.NODE_ENV, "http://localhost:3456");
        assertTrue(Endpoints.isLocalHost());

        TestEnv.set(Endpoints.NODE_ENV, "http://mydevbox:3456");
        assertFalse(Endpoints.isLocalHost());
    }

    @Test
    void isHostDevice_isTrueForLoopbackNodeUrls() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, Endpoints.LOCAL_NODE);
        assertTrue(Endpoints.isHostDevice());
    }

    @Test
    void nodeHostIsThisMachine_isTrueForTheLoopbackLiterals() throws Exception {
        for (String base : new String[]{"http://127.0.0.1:3456", "http://localhost:3456"}) {
            TestEnv.set(Endpoints.NODE_ENV, base);
            assertTrue(Endpoints.nodeHostIsThisMachine(), base);
        }
    }

    @Test
    void nodeHostIsThisMachine_neverRecognisesAnIpv6LoopbackLiteral() throws Exception {
        // PINNED: hostOf() returns URI.getHost(), which keeps the brackets for an IPv6 literal.
        // "::1".equals("[::1]") is false, so the literal arm at Endpoints:113 can only be reached
        // by a differently-shaped URL - and the NIC loop's host addresses are also bracketed, so
        // no IPv6 address matches either. An IPv6-only local Node therefore reads as a client.
        TestEnv.set(Endpoints.NODE_ENV, "http://[::1]:3456");

        assertEquals("[::1]", java.net.URI.create(Endpoints.nodeBase()).getHost());
        assertFalse(Endpoints.nodeHostIsThisMachine());
        assertFalse(Endpoints.isHostDevice());
    }

    @Test
    void nodeHostIsThisMachine_matchesThisMachinesOwnNicAddress() throws Exception {
        String own = firstNonLoopbackAddress();
        assumeTrue(own != null, "no non-loopback NIC on this machine");
        TestEnv.set(Endpoints.NODE_ENV, "http://" + own + ":3456");

        assertTrue(Endpoints.nodeHostIsThisMachine(), own);
        // The same address is not "localhost", so this is the NIC-scan branch, not the literal.
        assertFalse(Endpoints.isLocalHost());
    }

    @Test
    void nodeHostIsThisMachine_isFalseForANonAddress() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, "not a url at all");

        assertFalse(Endpoints.nodeHostIsThisMachine(),
                "hostOf() returns null for an unparseable base, which ends the check early");
        assertFalse(Endpoints.isHostDevice());
    }

    @Test
    void nodeHostIsThisMachine_isFalseForAnUnknownRemoteHost() throws Exception {
        TestEnv.set(Endpoints.NODE_ENV, "http://some.remote.example:3456");

        assertFalse(Endpoints.nodeHostIsThisMachine());
    }

    // ---------- Postgres ----------

    @Test
    void postgresPassword_prefersTheEnvironmentOverTheLegacyFile() throws Exception {
        writePgFile("  from-file  \n");
        TestEnv.set(Endpoints.PG_ENV, " from-env \n");

        assertEquals("from-env", Endpoints.postgresPassword());
    }

    @Test
    void postgresPassword_ignoresAFileThatOnlyExistsAlongsideAnEnvValue() throws Exception {
        writePgFile("  from-file  \n");
        TestEnv.set(Endpoints.PG_ENV, "from-env");

        assertEquals("from-env", Endpoints.postgresPassword(),
                "the file never beats a configured password");
    }

    @Test
    void postgresPassword_fallsBackToTheFileWhenTheMachineHasNone() throws Exception {
        assumeTrue(passwordIsUnconfiguredByThisMachine(),
                "this machine has postgres_pw set and java.util is not open to the test module");
        writePgFile("  from-file  \n");

        assertEquals("from-file", Endpoints.postgresPassword());
    }

    @Test
    void postgresPassword_isEmptyWhenNothingIsConfigured() throws Exception {
        assumeTrue(passwordIsUnconfiguredByThisMachine(),
                "this machine has postgres_pw set and java.util is not open to the test module");

        assertEquals("", Endpoints.postgresPassword());
    }

    @Test
    void postgresPassword_aBlankFileSuppliesNothing() throws Exception {
        assumeTrue(passwordIsUnconfiguredByThisMachine(),
                "this machine has postgres_pw set and java.util is not open to the test module");
        writePgFile("   \n");

        assertEquals("", Endpoints.postgresPassword());
    }

    @Test
    void postgresUrl_isALoopbackUrlWithShortTimeouts() {
        // jdbcHost() is hard-coded to 127.0.0.1: Tailscale / LAN clients must not open Postgres.
        assertEquals("jdbc:postgresql://127.0.0.1:5432/Scheduler?connectTimeout=2&socketTimeout=3",
                Endpoints.postgresUrl());
    }

    @Test
    void dir_isTheSchedulerFolderUnderTheWorkingDirectory() {
        assertEquals(Path.of(System.getProperty("user.dir"), ".scheduler").toAbsolutePath()
                .normalize(), Endpoints.dir().toAbsolutePath().normalize());
    }

    private static String firstNonLoopbackAddress() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    int pct = ip == null ? -1 : ip.indexOf('%');
                    if (pct >= 0) ip = ip.substring(0, pct);
                    if (ip != null && !ip.isBlank() && !ip.contains(":")) return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
