/**
 * Remember-me token file and the {@code login.txt} prefs: parsing, clamping, defaults,
 * and the remember/clear interaction.
 *
 * <p>{@code LocalSession.FILE} / {@code PREFS} are static finals resolved from
 * {@code Endpoints.dir()} at class-init, so every test snapshots the real files and puts
 * them back. {@code login.txt} is user data on a developer machine; it is never left
 * deleted.
 */
package org.ashoreprospect.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("session-prefs")
@ResourceLock("os-env")
class LocalSessionTest {

    private static final Path DIR = Endpoints.dir();
    private static final Path SESSION = DIR.resolve("session.txt");
    private static final Path PREFS = DIR.resolve("login.txt");

    private String savedSession;
    private String savedPrefs;

    @BeforeEach
    void snapshot() throws Exception {
        savedSession = Files.isRegularFile(SESSION)
                ? Files.readString(SESSION, StandardCharsets.UTF_8) : null;
        savedPrefs = Files.isRegularFile(PREFS)
                ? Files.readString(PREFS, StandardCharsets.UTF_8) : null;
        Files.deleteIfExists(SESSION);
        Files.deleteIfExists(PREFS);
    }

    @AfterEach
    void restore() throws Exception {
        Files.deleteIfExists(SESSION);
        Files.deleteIfExists(PREFS);
        if (savedPrefs != null) {
            Files.createDirectories(DIR);
            Files.writeString(PREFS, savedPrefs, StandardCharsets.UTF_8);
        }
        if (savedSession != null) {
            Files.createDirectories(DIR);
            Files.writeString(SESSION, savedSession, StandardCharsets.UTF_8);
        }
    }

    private static void writePrefs(String body) throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(PREFS, body, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- parseLeads

    @Test
    void parseLeadsDefaultsToZeroAndFive() {
        assertEquals(List.of(0, 5), LocalSession.parseLeads(null));
        assertEquals(List.of(0, 5), LocalSession.parseLeads(""));
        assertEquals(List.of(0, 5), LocalSession.parseLeads("   "));
    }

    @Test
    void parseLeadsKeepsOrderAndDropsDuplicates() {
        assertEquals(List.of(0, 5, 10), LocalSession.parseLeads("0,5,10"));
        assertEquals(List.of(5, 0), LocalSession.parseLeads("5,0,5"));
        assertEquals(List.of(7), LocalSession.parseLeads("7,7,7"));
    }

    @Test
    void parseLeadsClipsToTheTwoWeekWindow() {
        assertEquals(List.of(0, 20160), LocalSession.parseLeads("-1,0,20160,20161"));
        assertEquals(List.of(5), LocalSession.parseLeads("-30,5,99999"));
    }

    @Test
    void parseLeadsSkipsJunkWithoutAbortingTheRest() {
        assertEquals(List.of(5), LocalSession.parseLeads("x,5"));
        assertEquals(List.of(3, 4), LocalSession.parseLeads("3,,abc,4"));
    }

    @Test
    void parseLeadsSplitsOnAnyWhitespace() {
        assertEquals(List.of(0, 5, 10), LocalSession.parseLeads("0 5\t10"));
        assertEquals(List.of(1, 2, 3), LocalSession.parseLeads(" 1 , 2 ;3".replace(';', ' ')));
    }

    // ---------------------------------------------------------------- joinLeads

    @Test
    void joinLeadsOfNullAndEmptyIsBlank() {
        assertEquals("", LocalSession.joinLeads(null));
        assertEquals("", LocalSession.joinLeads(List.of()));
    }

    @Test
    void joinLeadsDropsNullsOutOfRangeAndDuplicates() {
        assertEquals("0,5,20160", LocalSession.joinLeads(
                java.util.Arrays.asList(0, 5, null, -1, 20161, 0, 20160)));
        assertEquals("20160", LocalSession.joinLeads(List.of(20160)));
    }

    @Test
    void joinThenParseRoundTrips() {
        List<Integer> leads = List.of(0, 5, 15, 60, 1440, 20160);
        assertEquals(leads, LocalSession.parseLeads(LocalSession.joinLeads(leads)));
    }

    // ---------------------------------------------------------------- idle minutes

    @Test
    void idleMinutesWithoutAPrefsFileIsZero() {
        assertEquals(0, LocalSession.idleMinutes());
    }

    @Test
    void idleMinutesIsClampedIntoFiveTo180() throws Exception {
        writePrefs("idleMinutes=5\n");
        assertEquals(5, LocalSession.idleMinutes());
        writePrefs("idleMinutes=80\n");
        assertEquals(80, LocalSession.idleMinutes());
        writePrefs("idleMinutes=180\n");
        assertEquals(180, LocalSession.idleMinutes());

        writePrefs("idleMinutes=1\n");
        assertEquals(5, LocalSession.idleMinutes());
        writePrefs("idleMinutes=500\n");
        assertEquals(180, LocalSession.idleMinutes());
    }

    @Test
    void idleMinutesTreatsNeverZeroBlankAndJunkAsOff() throws Exception {
        writePrefs("idleMinutes=never\n");
        assertEquals(0, LocalSession.idleMinutes());
        writePrefs("idleMinutes=0\n");
        assertEquals(0, LocalSession.idleMinutes());
        writePrefs("idleMinutes=   \n");
        assertEquals(0, LocalSession.idleMinutes());
        writePrefs("idleMinutes=abc\n");
        assertEquals(0, LocalSession.idleMinutes());
        writePrefs("idleMinutes=-5\n");
        assertEquals(0, LocalSession.idleMinutes());
    }

    // ---------------------------------------------------------------- defaults

    @Test
    void missingPrefsFileMeansOnExceptRandomChallenge() {
        assertTrue(LocalSession.wantReminders());
        assertTrue(LocalSession.wantReminderSound());
        assertTrue(LocalSession.wantRealtimeOverlay());
        assertTrue(LocalSession.wantRemember());
        assertFalse(LocalSession.wantRandomChallenge());
        assertEquals(List.of(0, 5), LocalSession.reminderLeads());
        assertEquals("", LocalSession.lastUsername());
    }

    @Test
    void prefsFileMissingASingleKeyFallsBackToItsOwnDefault() throws Exception {
        writePrefs("lastUser=will\n");
        assertTrue(LocalSession.wantReminders());
        assertTrue(LocalSession.wantReminderSound());
        assertTrue(LocalSession.wantRealtimeOverlay());
        assertTrue(LocalSession.wantRemember());
        assertFalse(LocalSession.wantRandomChallenge());
        assertEquals("will", LocalSession.lastUsername());
    }

    // ---------------------------------------------------------------- setters

    @Test
    void everyBooleanSetterRoundTripsThroughTheFile() {
        LocalSession.setReminders(false);
        assertFalse(LocalSession.wantReminders());
        LocalSession.setReminders(true);
        assertTrue(LocalSession.wantReminders());

        LocalSession.setReminderSound(false);
        assertFalse(LocalSession.wantReminderSound());

        LocalSession.setRealtimeOverlay(false);
        assertFalse(LocalSession.wantRealtimeOverlay());

        LocalSession.setRandomChallenge(true);
        assertTrue(LocalSession.wantRandomChallenge());
    }

    @Test
    void setIdleMinutesStoresTheClampedValue() throws Exception {
        LocalSession.setIdleMinutes(3);
        assertEquals("5", prop("idleMinutes"));
        assertEquals(5, LocalSession.idleMinutes());

        LocalSession.setIdleMinutes(999);
        assertEquals("180", prop("idleMinutes"));

        LocalSession.setIdleMinutes(0);
        assertEquals("0", prop("idleMinutes"));
        assertEquals(0, LocalSession.idleMinutes());

        LocalSession.setIdleMinutes(-4);
        assertEquals("0", prop("idleMinutes"));
    }

    @Test
    void reminderLeadsRoundTripAndBlankStaysBlank() throws Exception {
        LocalSession.setReminderLeads(List.of(0, 30, 120));
        assertEquals(List.of(0, 30, 120), LocalSession.reminderLeads());

        LocalSession.setReminderLeads(List.of());
        assertEquals("", prop("reminderLeads"));
        // PINNED: an empty stored value is *not* the "no file" default; parseLeads("")
        // returns [0, 5], so clearing the list restores the default pair.
        assertEquals(List.of(0, 5), LocalSession.reminderLeads());
    }

    @Test
    void settersPreserveTheOtherKeysInTheSameFile() throws Exception {
        writePrefs("lastUser=keep-me\nremember=true\n");
        LocalSession.setRealtimeOverlay(false);

        assertEquals("keep-me", LocalSession.lastUsername());
        assertTrue(LocalSession.wantRemember());
        assertFalse(LocalSession.wantRealtimeOverlay());
    }

    @Test
    void savePrefsStoresRememberAndLastUser() throws Exception {
        LocalSession.savePrefs(true, "  will  ");
        assertEquals("true", prop("remember"));
        assertEquals("will", LocalSession.lastUsername());

        LocalSession.savePrefs(false, null);
        assertEquals("false", prop("remember"));
        assertEquals("", LocalSession.lastUsername());
        assertFalse(LocalSession.wantRemember());
    }

    // ---------------------------------------------------------------- token file

    @Test
    void saveThenLoadRoundTripsEverythingTrimmed() {
        LocalSession.save("  u-1  ", "  will  ", "  t0k  ");
        LocalSession.Data d = LocalSession.load();

        assertNotNull(d);
        assertEquals("u-1", d.userId);
        assertEquals("will", d.username);
        assertEquals("t0k", d.token);
    }

    @Test
    void saveWithBlankUserOrTokenIsANoOp() {
        LocalSession.save(null, "will", "t0k");
        LocalSession.save("   ", "will", "t0k");
        LocalSession.save("u-1", "will", null);
        LocalSession.save("u-1", "will", "   ");

        assertNull(LocalSession.load());
        assertFalse(Files.exists(SESSION));
    }

    @Test
    void loadIsNullWithoutAFileOrWithIncompleteProperties() throws Exception {
        assertNull(LocalSession.load());

        Files.createDirectories(DIR);
        Files.writeString(SESSION, "userId=u-1\nusername=will\ntoken=   \n",
                StandardCharsets.UTF_8);
        assertNull(LocalSession.load());

        Files.writeString(SESSION, "userId=\nusername=will\ntoken=t0k\n",
                StandardCharsets.UTF_8);
        assertNull(LocalSession.load());
    }

    @Test
    void loadToleratesAMissingUsernameKey() throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(SESSION, "userId=u-1\ntoken=t0k\n", StandardCharsets.UTF_8);

        LocalSession.Data d = LocalSession.load();
        assertNotNull(d);
        assertEquals("", d.username);
    }

    // ---------------------------------------------------------------- clear

    @Test
    void clearDeletesTheTokenButNeverThePrefs() throws Exception {
        LocalSession.save("u-1", "will", "t0k");
        LocalSession.savePrefs(true, "will");
        assertTrue(Files.exists(SESSION));
        assertTrue(Files.exists(PREFS));

        LocalSession.clear();

        assertFalse(Files.exists(SESSION));
        assertTrue(Files.exists(PREFS), "login.txt is user prefs, not session state");
        assertEquals("will", LocalSession.lastUsername());
    }

    @Test
    void clearIsSafeWhenNoFileExists() {
        LocalSession.clear();
        LocalSession.clear();
        assertNull(LocalSession.load());
    }

    @Test
    void savePrefsRememberTrueKeepsAnExistingToken() {
        LocalSession.save("u-1", "will", "t0k");

        LocalSession.savePrefs(true, "will");

        assertNotNull(LocalSession.load());
    }

    @Test
    void savePrefsRememberFalseRoutesThroughClear() {
        LocalSession.save("u-1", "will", "t0k");

        LocalSession.savePrefs(false, "will");

        assertNull(LocalSession.load());
        assertTrue(Files.exists(PREFS));
    }

    private static String prop(String key) throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = Files.newInputStream(PREFS)) {
            p.load(in);
        }
        return p.getProperty(key);
    }
}
