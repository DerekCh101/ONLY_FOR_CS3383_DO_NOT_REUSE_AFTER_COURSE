package com.opwait.ashoreprospect.domain

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Same AP1. AES-GCM / PBKDF2-HMAC-SHA256 as JavaFX `org.ashoreprospect.crypto.Vault`
 * and `server/vault.js`. Do not invent a second format.
 */
object VaultCrypto {
    private const val ITER = 120_000
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    @Volatile
    private var dek: SecretKey? = null

    @Volatile
    private var active: Boolean = false

    fun unlocked(): Boolean = dek != null

    /** True after this session decrypted at least one AP1. field — then writes re-seal. */
    fun active(): Boolean = active && dek != null

    fun lock() {
        dek = null
        active = false
    }

    fun exportKey(): ByteArray? = dek?.encoded?.copyOf()

    fun restoreKey(raw: ByteArray): Boolean {
        if (raw.size != 32) return false
        dek = SecretKeySpec(raw, "AES")
        active = true
        return true
    }

    fun unlock(userId: String, password: String): Boolean {
        if (userId.isBlank() || password.isBlank()) return false
        dek = derive(userId, password)
        active = dek != null
        return dek != null
    }

    /** Tests / turning writes on after a successful decrypt. */
    fun enableWrites() {
        if (dek != null) active = true
    }

    fun open(value: String?): String {
        if (value == null) return ""
        if (!VaultSeal.isSealed(value)) return value
        val key = dek ?: return value
        return try {
            val all = Base64.getUrlDecoder().decode(value.substring(VaultSeal.PREFIX.length))
            if (all.size <= IV_LEN) return value
            val iv = all.copyOfRange(0, IV_LEN)
            val ct = all.copyOfRange(IV_LEN, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = String(c.doFinal(ct), StandardCharsets.UTF_8)
            active = true
            plain
        } catch (_: Exception) {
            value
        }
    }

    fun seal(plain: String?): String {
        if (!active()) return plain.orEmpty()
        if (plain.isNullOrEmpty()) return ""
        if (VaultSeal.isSealed(plain)) return plain
        val key = dek ?: return plain
        return try {
            val iv = ByteArray(IV_LEN)
            SecureRandom().nextBytes(iv)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ct = c.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val buf = ByteBuffer.allocate(iv.size + ct.size).put(iv).put(ct).array()
            VaultSeal.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
        } catch (_: Exception) {
            plain
        }
    }

    fun openTask(task: Task) {
        task.title = open(task.title)
        task.category = open(task.category)
        task.subcategory = open(task.subcategory)
        task.description = open(task.description)
    }

    fun openBlocked(row: BlockedTime) {
        row.title = open(row.title)
    }

    fun openBundle(bundle: TypewriterBundle) {
        for (e in bundle.diary) {
            e.title = open(e.title)
            e.body = open(e.body)
        }
        for (p in bundle.pages) {
            p.title = open(p.title)
            p.body = open(p.body)
        }
        for (list in bundle.lists) {
            list.title = open(list.title)
            for (item in list.items) item.text = open(item.text)
        }
    }

    fun sealedCopy(task: Task): Task {
        if (!active()) return task
        return task.copy(
            title = seal(task.title),
            description = seal(task.description),
            category = seal(task.category),
            subcategory = seal(task.subcategory),
        )
    }

    fun sealedBlocked(row: BlockedTime): BlockedTime {
        if (!active()) return row
        return row.copy(title = seal(row.title))
    }

    fun stillSealed(tasks: List<Task>, blocked: List<BlockedTime>, bundle: TypewriterBundle): Boolean {
        if (tasks.any { VaultSeal.isSealed(it.title) || VaultSeal.isSealed(it.description) ||
                VaultSeal.isSealed(it.category) || VaultSeal.isSealed(it.subcategory) }
        ) {
            return true
        }
        if (blocked.any { VaultSeal.isSealed(it.title) }) return true
        if (bundle.diary.any { VaultSeal.isSealed(it.title) || VaultSeal.isSealed(it.body) }) return true
        if (bundle.pages.any { VaultSeal.isSealed(it.title) || VaultSeal.isSealed(it.body) }) return true
        return bundle.lists.any {
            VaultSeal.isSealed(it.title) || it.items.any { item -> VaultSeal.isSealed(item.text) }
        }
    }

    private fun derive(uid: String, password: String): SecretKey {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(("AP1|$uid").toByteArray(StandardCharsets.UTF_8))
        val spec = PBEKeySpec(password.toCharArray(), salt, ITER, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }
}
