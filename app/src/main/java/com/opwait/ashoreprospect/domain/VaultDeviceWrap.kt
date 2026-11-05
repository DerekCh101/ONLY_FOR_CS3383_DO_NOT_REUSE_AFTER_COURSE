package com.opwait.ashoreprospect.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device wrap for the Vault DEK (desktop `vault.device`). Not the account password. */
object VaultDeviceWrap {
    private const val ALIAS = "ashore_vault_wrap_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun wrap(raw: ByteArray): String? = runCatching {
        val iv = ByteArray(IV_LEN)
        java.security.SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deviceKey(), GCMParameterSpec(TAG_BITS, iv))
        val ct = c.doFinal(raw)
        val packed = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(ct, 0, packed, iv.size, ct.size)
        Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
    }.getOrNull()

    fun unwrap(packed: String): ByteArray? = runCatching {
        val all = Base64.getUrlDecoder().decode(packed)
        if (all.size <= IV_LEN) return@runCatching null
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(TAG_BITS, iv))
        c.doFinal(ct)
    }.getOrNull()

    fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(ALIAS)
        }
    }

    private fun deviceKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
