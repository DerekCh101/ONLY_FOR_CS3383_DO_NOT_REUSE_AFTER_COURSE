/**
 * {@code SmtpSettings}: the five-key shape, defaults, legacy-file parsing, and the
 * precedence rules between env, {@code smtp.env}, and {@code smtp.txt}.
 *
 * <p>Read-only. {@code save()} and {@code clear()} are never called: on Windows they run
 * {@code setx} and rewrite the real {@code user.env} under {@code %APPDATA%}.
 *
 * <p>The expectations are written against whatever the machine exports for the
 * {@code SMTP_*} names, so they hold on a developer PC that has SMTP configured as well
 * as on a bare CI runner.
 */
package org.ashoreprospect.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("os-env")
class SmtpSettingsTest {

    private static final Path TXT = SmtpSettings.file();
    private static final Path ENV = SmtpSettings.envFile();

    private String savedTxt;
    private String savedEnv;

    @BeforeEach
    void snapshot() throws Exception {
        savedTxt = Files.isRegularFile(TXT) ? Files.readString(TXT, StandardCharsets.UTF_8) : null;
        savedEnv = Files.isRegularFile(ENV) ? Files.readString(ENV, StandardCharsets.UTF_8) : null;
        Files.deleteIfExists(TXT);
        Files.deleteIfExists(ENV);
    }

    @AfterEach
    void restore() throws Exception {
        Files.deleteIfExists(TXT);
        Files.deleteIfExists(ENV);
        if (savedTxt != null) Files.writeString(TXT, savedTxt, StandardCharsets.UTF_8);
        if (savedEnv != null) Files.writeString(ENV, savedEnv, StandardCharsets.UTF_8);
    }

    private static void writeTxt(String body) throws Exception {
        Files.createDirectories(TXT.getParent());
        Files.writeString(TXT, body, StandardCharsets.UTF_8);
    }

    private static void writeEnv(String body) throws Exception {
        Files.createDirectories(ENV.getParent());
        Files.writeString(ENV, body, StandardCharsets.UTF_8);
    }

    /** True when the runner (or the developer's user.env) supplies this SMTP value. */
    private static boolean supplied(String key) {
        return !OsEnv.get(key).isBlank();
    }

    @Test
    void loadAlwaysReturnsTheFiveKeysInOrder() {
        Map<String, String> m = SmtpSettings.load();
        assertEquals(java.util.List.of("host", "port", "user", "pass", "from"),
                new java.util.ArrayList<>(m.keySet()));
    }

    @Test
    void defaultsAreGmailAnd587WhenNothingIsConfigured() {
        Map<String, String> m = SmtpSettings.load();
        if (!supplied("SMTP_HOST")) assertEquals("smtp.gmail.com", m.get("host"));
        if (!supplied("SMTP_PORT")) assertEquals("587", m.get("port"));
        if (!supplied("SMTP_USER")) assertEquals("", m.get("user"));
        if (!supplied("SMTP_FROM")) assertEquals("", m.get("from"));
    }

    @Test
    void commentAndMalformedLinesInTheLegacyFileAreIgnored() throws Exception {
        writeTxt("# a comment\n"
                + "no-equals-sign\n"
                + "=emptykey\n"
                + "\n"
                + "HOST=smtp.example.test\n");
        // The three junk lines contribute nothing; the real key still lands.
        Map<String, String> m = SmtpSettings.load();
        if (!supplied("SMTP_HOST")) assertEquals("smtp.example.test", m.get("host"));
        assertFalse(m.containsKey("no-equals-sign"));
    }

    @Test
    void keysAreLowerCasedAndValuesTrimmed() throws Exception {
        writeTxt("  HOST =  a.b  \n  PORT =  2525  \n");
        Map<String, String> m = SmtpSettings.load();
        if (!supplied("SMTP_HOST")) assertEquals("a.b", m.get("host"));
        if (!supplied("SMTP_PORT")) assertEquals("2525", m.get("port"));
    }

    @Test
    void smtpEnvFileWinsOverThePassKeyInSmtpTxt() throws Exception {
        writeTxt("pass=from-txt\n");
        writeEnv("SMTP_PASS=envelope\n");

        String pass = SmtpSettings.load().get("pass");
        // The real environment still outranks both; assert the file ranking otherwise.
        if (supplied("SMTP_PASS")) assertEquals(OsEnv.get("SMTP_PASS"), pass);
        else assertEquals("envelope", pass);
    }

    @Test
    void passFallsBackToTheLegacyTxtKeyWhenNoEnvFileExists() throws Exception {
        writeTxt("pass=legacy-pass\n");

        String pass = SmtpSettings.load().get("pass");
        if (supplied("SMTP_PASS")) assertEquals(OsEnv.get("SMTP_PASS"), pass);
        else assertEquals("legacy-pass", pass);
        assertTrue(SmtpSettings.hasPass(), "a legacy pass still counts as configured input");
    }

    @Test
    void whitespaceInsideThePasswordIsStripped() throws Exception {
        // PINNED: the app masks the 16-character Gmail app password as 4x4 groups, so the
        // spaces must go before it is used. Applied at both env-file and txt-file level.
        writeEnv("SMTP_PASS=ab cd\r\n");
        writeTxt("pass=ef\n");

        String pass = SmtpSettings.load().get("pass");
        if (!supplied("SMTP_PASS")) {
            // The env-file value is "ab cd", stripped to "abcd".
            assertEquals("abcd", pass);
        }
    }

    @Test
    void configuredRequiresHostUserAndPass() throws Exception {
        writeTxt("host=h\nuser=\npass=p\n");
        if (!supplied("SMTP_HOST") && !supplied("SMTP_USER") && !supplied("SMTP_PASS")) {
            assertFalse(SmtpSettings.configured(), "blank user is not configured");
        }

        writeTxt("host=h\nuser=u\npass=\n");
        if (!supplied("SMTP_PASS")) {
            assertFalse(SmtpSettings.configured(), "blank pass is not configured");
        }
    }

    @Test
    void hasPassAgreesWithConfiguredOnThePassConditionAlone() throws Exception {
        writeTxt("host=h\nuser=u\npass=p\n");
        if (!supplied("SMTP_PASS")) {
            assertTrue(SmtpSettings.hasPass());
            assertTrue(SmtpSettings.configured());
        }

        writeTxt("host=h\nuser=u\n");
        Files.deleteIfExists(ENV);
        if (!supplied("SMTP_PASS")) {
            assertFalse(SmtpSettings.hasPass());
            assertFalse(SmtpSettings.configured());
        }
    }

    @Test
    void bothFilesResolveInsideTheSchedulerDirectory() {
        assertEquals(Endpoints.dir(), SmtpSettings.file().getParent().toAbsolutePath());
        assertEquals(Endpoints.dir(), SmtpSettings.envFile().getParent().toAbsolutePath());
        assertEquals("smtp.txt", SmtpSettings.file().getFileName().toString());
        assertEquals("smtp.env", SmtpSettings.envFile().getFileName().toString());
    }
}
