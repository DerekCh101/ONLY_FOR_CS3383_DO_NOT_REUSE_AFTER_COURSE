/** Vault AES-GCM helpers: seal/open round trips, password checks, and key lifecycle. */
package org.ashoreprospect.crypto;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.net.PasswordRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vault state is static and its files live under {@code .scheduler/} relative to the
 * process working directory, which cannot be changed at runtime. Every test therefore
 * locks the shared "vault" resource and wipes {@code .scheduler/vault*} before and
 * after, leaving the developer's own vault untouched.
 */
@ResourceLock("vault")
class VaultTest {

    private static final String UID = "user-1";
    private static final String PASSWORD = "Str0ng#Pass";
    private static final String NEW_PASSWORD = "N3w#Password";

    private static final Path VAULT_FILE = Path.of(".scheduler", "vault.txt");
    private static final Path DEVICE_FILE = Path.of(".scheduler", "vault.device");

    @BeforeEach
    void cleanSlate() {
        wipe();
    }

    @AfterEach
    void leaveClean() {
        wipe();
    }

    private static void wipe() {
        Vault.lock();
        Vault.eraseLocalKeys();
        deleteRecursively(VAULT_FILE);
        deleteRecursively(DEVICE_FILE);
    }

    private static void enable() throws Exception {
        Vault.enable(UID, PASSWORD);
    }

    // ---------- seal() ----------

    @Test
    void sealed_detectsThePrefixIncludingNull() {
        assertTrue(Vault.sealed(Vault.PREFIX + "abc"));
        assertFalse(Vault.sealed("plain"));
        assertFalse(Vault.sealed(""));
        assertFalse(Vault.sealed(null));
        assertFalse(Vault.sealed("ap1.lowercase"));
        assertEquals("AP1.", Vault.PREFIX);
    }

    @Test
    void seal_isANoOpWhileEncryptionIsOff() {
        assertFalse(Vault.enabled());
        assertFalse(Vault.wanted());
        assertFalse(Vault.unlocked());

        assertEquals("plain", Vault.seal("plain"));
        assertEquals("", Vault.seal(null));
        assertEquals("", Vault.seal(""));
    }

    @Test
    void enable_thenSeal_producesPrefixedCiphertext() throws Exception {
        enable();

        assertTrue(Vault.enabled());
        assertTrue(Vault.wanted());
        assertTrue(Vault.unlocked());

        String sealed = Vault.seal("Secret title");

        assertTrue(sealed.startsWith(Vault.PREFIX));
        assertNotEquals("Secret title", sealed);
        assertFalse(sealed.contains("Secret"));
    }

    @Test
    void open_roundTripsASealedString() throws Exception {
        enable();
        String sealed = Vault.seal("Secret title");

        assertEquals("Secret title", Vault.open(sealed));
    }

    @Test
    void seal_isIdempotentOnAlreadySealedInput() throws Exception {
        enable();
        String sealed = Vault.seal("Secret title");

        assertEquals(sealed, Vault.seal(sealed));
    }

    @Test
    void theSamePlaintextSealsToDifferentCiphertexts() throws Exception {
        enable();
        String first = Vault.seal("same text");
        String second = Vault.seal("same text");

        assertNotEquals(first, second);
        assertEquals("same text", Vault.open(first));
        assertEquals("same text", Vault.open(second));
    }

    @Test
    void open_whileLockedReturnsTheCiphertext() throws Exception {
        enable();
        String sealed = Vault.seal("Secret title");

        Vault.lock();
        assertFalse(Vault.unlocked());

        assertEquals(sealed, Vault.open(sealed));
    }

    @Test
    void open_ofTamperedOrForeignCiphertextReturnsItUnchanged() throws Exception {
        enable();
        String sealed = Vault.seal("Secret title");

        // Flip the IV byte so the GCM tag check fails.
        String body = sealed.substring(Vault.PREFIX.length());
        byte[] raw = java.util.Base64.getUrlDecoder().decode(body);
        raw[0] ^= 0x01;
        String tampered = Vault.PREFIX
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertEquals(tampered, Vault.open(tampered));

        // Too short to hold an IV plus a tag.
        assertEquals(Vault.PREFIX + "AAAA", Vault.open(Vault.PREFIX + "AAAA"));
        // Not valid base64 at all.
        assertEquals(Vault.PREFIX + "!!!!", Vault.open(Vault.PREFIX + "!!!!"));
    }

    @Test
    void open_passesPlainValuesThrough() {
        assertEquals("plain", Vault.open("plain"));
        assertEquals("", Vault.open(null));
        assertEquals("", Vault.open(""));
    }

    // ---------- enable / disable ----------

    @Test
    void enable_rejectsABlankUserIdAndAShortPassword() {
        assertThrows(IllegalStateException.class, () -> Vault.enable(null, PASSWORD));
        assertThrows(IllegalStateException.class, () -> Vault.enable("   ", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> Vault.enable(UID, "short"));
        assertThrows(IllegalArgumentException.class, () -> Vault.enable(UID, null));
        assertFalse(Vault.enabled());
    }

    @Test
    void disable_withTheWrongPasswordIsRejected() throws Exception {
        enable();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Vault.disable(UID, "Wr0ng#Pass"));
        assertEquals("Password does not match the encryption key.", error.getMessage());
        assertTrue(Vault.enabled());
    }

    @Test
    void disable_withTheRightPasswordTurnsEncryptionOff() throws Exception {
        enable();

        Vault.disable(UID, PASSWORD);
        assertFalse(Vault.wanted());
        assertFalse(Vault.enabled());

        Vault.finishDisable();
        assertFalse(Vault.wanted());
        assertFalse(Vault.unlocked());
        assertEquals("now plain", Vault.seal("now plain"));
    }

    @Test
    void disable_requiresAPassword() throws Exception {
        enable();

        assertThrows(IllegalArgumentException.class, () -> Vault.disable(UID, ""));
        assertThrows(IllegalArgumentException.class, () -> Vault.disable(UID, null));
    }

    // ---------- rekey ----------

    @Test
    void rekey_switchesTheLiveKeyAndResealedDataUsesIt() throws Exception {
        enable();
        String sealedBefore = Vault.seal("before rekey");

        Vault.rekey(UID, PASSWORD, NEW_PASSWORD);

        // The live key is now the new password's key.
        assertTrue(Vault.enabled());
        String sealedAfter = Vault.seal("after rekey");
        assertEquals("after rekey", Vault.open(sealedAfter));

        // PINNED: rekey() overwrites the canary and deviceWrap with the new key but
        // leaves existing AP1. ciphertext untouched, so rows sealed before the rekey
        // are no longer readable (callers must rewrite them).
        assertEquals(sealedBefore, Vault.open(sealedBefore));

        // The old password no longer unlocks the vault.
        Vault.lock();
        Vault.unlockWithPassword(UID, PASSWORD);
        assertFalse(Vault.unlocked());

        // The new password does.
        Vault.lock();
        Vault.unlockWithPassword(UID, NEW_PASSWORD);
        assertTrue(Vault.unlocked());
        assertEquals("after rekey", Vault.open(sealedAfter));
    }

    @Test
    void rekey_rejectsAWeakNewPassword() throws Exception {
        enable();
        String sealed = Vault.seal("still here");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Vault.rekey(UID, PASSWORD, "weak"));
        assertEquals(PasswordRules.HINT, error.getMessage());

        // The key is unchanged.
        assertEquals("still here", Vault.open(sealed));
    }

    @Test
    void rekey_rejectsTheWrongCurrentPassword() throws Exception {
        enable();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Vault.rekey(UID, "Wr0ng#Pass", NEW_PASSWORD));
        assertEquals("Current password does not match encryption.", error.getMessage());
    }

    @Test
    void rekey_doesNothingWhileEncryptionIsOff() throws Exception {
        Vault.rekey(UID, PASSWORD, NEW_PASSWORD);

        assertFalse(Vault.enabled());
        assertFalse(Vault.wanted());
    }

    // ---------- persisted state ----------

    @Test
    void eraseLocalKeys_dropsTheFilesAndTheFlags() throws Exception {
        enable();
        assertTrue(Files.isRegularFile(VAULT_FILE));
        assertTrue(Files.isRegularFile(DEVICE_FILE));

        Vault.eraseLocalKeys();

        assertFalse(Files.exists(VAULT_FILE));
        assertFalse(Files.exists(DEVICE_FILE));
        assertFalse(Vault.wanted());
        assertFalse(Vault.unlocked());
        assertFalse(Vault.enabled());
    }

    @Test
    void lock_clearsTheKeyButKeepsTheFile() throws Exception {
        enable();

        Vault.lock();

        assertFalse(Vault.unlocked());
        assertFalse(Vault.enabled());
        assertTrue(Files.isRegularFile(VAULT_FILE));
    }

    @Test
    void unlockRemembered_reopensADeviceWrapSealedEarlier() throws Exception {
        enable();
        String sealed = Vault.seal("remembered");
        Vault.lock();
        assertFalse(Vault.unlocked());

        Vault.unlockRemembered(UID);

        assertTrue(Vault.unlocked());
        assertEquals("remembered", Vault.open(sealed));

        Vault.unlockRemembered(null);
        Vault.unlockRemembered("   ");
    }

    @Test
    void unlockWithPassword_rejectsABadPasswordViaTheCanary() throws Exception {
        enable();
        String sealed = Vault.seal("canary protected");

        Vault.lock();
        Vault.unlockWithPassword(UID, "Wr0ng#Pass");

        // The stored canary cannot be opened, so the derived key is discarded.
        assertFalse(Vault.unlocked());
        assertEquals(sealed, Vault.open(sealed));
    }

    @Test
    void unlockWithPassword_ignoresBlankInput() {
        Vault.unlockWithPassword(null, PASSWORD);
        Vault.unlockWithPassword(UID, "");
        Vault.unlockWithPassword(" ", " ");

        assertFalse(Vault.unlocked());
    }

    // ---------- noteSealedRemote / open helpers ----------

    @Test
    void noteSealedRemote_turnsSealingOnAfterOpeningRemoteCiphertext() throws Exception {
        enable();
        String sealed = Vault.seal("remote row");

        // PINNED: finishDisable() writes enabled=false but writes no canary/deviceWrap
        // because the DEK is cleared first, so a later unlock cannot restore the key and
        // `wanted` stays false until the account is unlocked again via enable().
        Vault.disable(UID, PASSWORD);
        Vault.finishDisable();
        Vault.lock();
        assertFalse(Vault.unlocked());

        Vault.unlockWithPassword(UID, PASSWORD);
        assertTrue(Vault.unlocked());
        assertFalse(Vault.wanted());
        assertFalse(Vault.enabled());

        // Opening remote ciphertext flips `wanted` on for subsequent writes.
        assertEquals("remote row", Vault.open(sealed));
        assertTrue(Vault.enabled());

        // Calling it again is a no-op.
        Vault.noteSealedRemote();
        assertTrue(Vault.wanted());
    }

    @Test
    void noteSealedRemote_doesNothingWhileLocked() {
        Vault.noteSealedRemote();
        assertFalse(Vault.wanted());
    }

    @Test
    void openTask_decryptsNameAndDescriptionInPlace() throws Exception {
        enable();
        Task task = Task.loadTaskFromdb("id-1",
                Vault.seal("sealed name"), 1.0, 3,
                null, null, null, null, null, null);
        task.setDescription(Vault.seal("sealed description"));

        Vault.openTask(task);

        assertEquals("sealed name", task.getTaskName());
        assertEquals("sealed description", task.getDescription());

        // Null is safe.
        Vault.openTask(null);
    }

    @Test
    void openBlocked_decryptsTheTitleInPlace() throws Exception {
        enable();
        BlockedTime blocked = BlockedTime.daily(
                Vault.seal("sealed lunch"), LocalTime.of(12, 0), LocalTime.of(13, 0));

        Vault.openBlocked(blocked);

        assertEquals("sealed lunch", blocked.getTitle());
        Vault.openBlocked(null);
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
