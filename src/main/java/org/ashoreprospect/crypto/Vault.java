/** Optional AES-GCM for titles, descriptions, and Typewriter text (AP1. prefix). */
package org.ashoreprospect.crypto;

import org.ashoreprospect.BlockedTime;
import org.ashoreprospect.Task;
import org.ashoreprospect.db.DeviceConfig;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional AES-GCM for titles, descriptions, blocked titles, and Typewriter text.
 * Ciphertext is prefixed {@code AP1.} so SQLite / Postgres / Node can store it as TEXT.
 * The data-encryption key is derived from the account password + user id (same key on every
 * device that uses the same password). Remember-me unwraps a copy wrapped with this PC's
 * device secret.
 */
public final class Vault {

    public static final String PREFIX = "AP1.";
    private static final int ITER = 120_000;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final Path FILE = Path.of(".scheduler", "vault.txt");
    private static final Path DEVICE = Path.of(".scheduler", "vault.device");

    private static final Object LOCK = new Object();
    private static volatile boolean wanted;
    private static volatile SecretKey dek;
    private static volatile String userId;

    private Vault() {}

    public static boolean wanted() {
        return wanted;
    }

    public static boolean unlocked() {
        return dek != null;
    }

    public static boolean enabled() {
        return wanted && dek != null;
    }

    public static boolean sealed(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    public static void lock() {
        synchronized (LOCK) {
            dek = null;
            userId = null;
        }
    }

    /** Drop local vault files after the account is gone so this PC does not keep the old key. */
    public static void eraseLocalKeys() {
        synchronized (LOCK) {
            wanted = false;
            dek = null;
            userId = null;
            try {
                Files.deleteIfExists(FILE);
                Files.deleteIfExists(DEVICE);
            } catch (Exception ignored) {
            }
        }
    }

    public static void unlockWithPassword(String uid, String password) {
        if (uid == null || uid.isBlank() || password == null || password.isBlank()) return;
        synchronized (LOCK) {
            loadFile();
            userId = uid;
            if (!wanted) return;
            dek = derive(uid, password);
            if (!canaryOk()) dek = null;
            else writeFile(uid, true);
        }
    }

    public static void unlockRemembered(String uid) {
        if (uid == null || uid.isBlank()) return;
        synchronized (LOCK) {
            loadFile();
            userId = uid;
            if (!wanted) return;
            String wrap = readMap().get("deviceWrap");
            if (wrap == null || wrap.isBlank()) return;
            try {
                byte[] raw = unwrap(deviceKey(), wrap);
                dek = new SecretKeySpec(raw, "AES");
                if (!canaryOk()) dek = null;
            } catch (Exception e) {
                dek = null;
                System.out.println("Vault remember-me unwrap failed");
            }
        }
    }

    public static void enable(String uid, String password) throws Exception {
        if (uid == null || uid.isBlank()) throw new IllegalStateException("Sign in first.");
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Account password required (min 8).");
        }
        synchronized (LOCK) {
            userId = uid;
            dek = derive(uid, password);
            wanted = true;
            writeFile(uid, true);
        }
    }

    public static void disable(String uid, String password) throws Exception {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Account password required to turn encryption off.");
        }
        synchronized (LOCK) {
            SecretKey check = derive(uid, password);
            if (dek != null && !sameKey(dek, check)) {
                throw new IllegalArgumentException("Password does not match the encryption key.");
            }
            dek = check;
            wanted = false;
        }
    }

    /** Call after plaintext rewrite when turning encryption off. */
    public static void finishDisable() {
        synchronized (LOCK) {
            wanted = false;
            dek = null;
            writeFile(userId, false);
        }
    }

    public static void rekey(String uid, String oldPassword, String newPassword) throws Exception {
        if (!wanted) return;
        String weak = org.ashoreprospect.net.PasswordRules.reject(newPassword);
        if (weak != null) {
            throw new IllegalArgumentException(weak);
        }
        synchronized (LOCK) {
            SecretKey oldKey = derive(uid, oldPassword);
            if (dek != null && !sameKey(dek, oldKey)) {
                throw new IllegalArgumentException("Current password does not match encryption.");
            }
            userId = uid;
            dek = derive(uid, newPassword);
            wanted = true;
            writeFile(uid, true);
        }
    }

    public static String seal(String plain) {
        if (!enabled()) return plain == null ? "" : plain;
        if (plain == null || plain.isEmpty()) return "";
        if (sealed(plain)) return plain;
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public static String open(String value) {
        if (value == null) return "";
        if (!sealed(value)) return value;
        if (dek == null) return value;
        try {
            byte[] all = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            if (all.length <= IV_LEN) return value;
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    public static void openTask(Task t) {
        if (t == null) return;
        t.setTaskName(open(t.getTaskName()));
        t.setDescription(open(t.getDescription()));
    }

    public static void openBlocked(BlockedTime b) {
        if (b == null) return;
        b.setTitle(open(b.getTitle()));
    }

    private static SecretKey derive(String uid, String password) {
        try {
            byte[] salt = MessageDigest.getInstance("SHA-256")
                    .digest(("AP1|" + uid).getBytes(StandardCharsets.UTF_8));
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITER, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("key derive failed", e);
        }
    }

    private static boolean sameKey(SecretKey a, SecretKey b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getEncoded(), b.getEncoded());
    }

    private static boolean canaryOk() {
        String canary = readMap().get("canary");
        if (canary == null || canary.isBlank()) return true;
        return "ashore".equals(open(canary));
    }

    private static void loadFile() {
        wanted = false;
        Map<String, String> map = readMap();
        wanted = "true".equalsIgnoreCase(map.getOrDefault("enabled", "false"));
    }

    private static Map<String, String> readMap() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(FILE)) return map;
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static void writeFile(String uid, boolean on) {
        try {
            Files.createDirectories(FILE.getParent());
            String wrap = "";
            if (on && dek != null) {
                wrap = wrap(deviceKey(), dek.getEncoded());
            }
            String canary = (on && dek != null) ? seal("ashore") : "";
            String body = "enabled=" + (on ? "true" : "false") + "\n"
                    + "user=" + (uid == null ? "" : uid) + "\n"
                    + "iter=" + ITER + "\n"
                    + "deviceWrap=" + wrap + "\n"
                    + "canary=" + canary + "\n"
                    + "device=" + DeviceConfig.getOrCreateDeviceId() + "\n";
            Files.writeString(FILE, body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("Vault file write failed: " + e.getMessage());
        }
    }

    private static byte[] deviceKey() throws Exception {
        if (Files.isRegularFile(DEVICE)) {
            byte[] raw = Files.readAllBytes(DEVICE);
            if (raw.length == 32) return raw;
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        Files.createDirectories(DEVICE.getParent());
        Files.write(DEVICE, raw);
        return raw;
    }

    private static String wrap(byte[] key, byte[] secret) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(secret);
        ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
        buf.put(iv);
        buf.put(ct);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array());
    }

    private static byte[] unwrap(byte[] key, String packed) throws Exception {
        byte[] all = Base64.getUrlDecoder().decode(packed);
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[all.length - IV_LEN];
        System.arraycopy(all, 0, iv, 0, IV_LEN);
        System.arraycopy(all, IV_LEN, ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return c.doFinal(ct);
    }

    static {
        try {
            loadFile();
        } catch (Exception ignored) {
        }
    }
}
