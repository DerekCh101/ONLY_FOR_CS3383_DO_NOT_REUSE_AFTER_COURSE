package com.opwait.ashoreprospect.data

import android.content.Context
import com.opwait.ashoreprospect.domain.VaultCrypto
import com.opwait.ashoreprospect.domain.VaultDeviceWrap
import com.opwait.ashoreprospect.ui.theme.AppThemePack
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("ashore_session")

class SessionStore(private val context: Context) {
    val serverUrl: Flow<String?> = context.dataStore.data.map { it[KEY_SERVER] }
    val token: Flow<String?> = context.dataStore.data.map { it[KEY_TOKEN] }
    val userId: Flow<String?> = context.dataStore.data.map { it[KEY_USER_ID] }
    val username: Flow<String?> = context.dataStore.data.map { it[KEY_USERNAME] }

    suspend fun getServerUrl(): String? = serverUrl.first()
    suspend fun getToken(): String? {
        processMemoryToken?.let { return it }
        val stored = token.first()
        processMemoryToken = stored
        return stored
    }
    suspend fun getUserId(): String? = userId.first()
    suspend fun getUsername(): String? = username.first()
    suspend fun getRememberLogin(): Boolean =
        context.dataStore.data.map { it[KEY_REMEMBER] ?: true }.first()

    suspend fun getIdleMinutes(): Int =
        clampIdleMinutes(context.dataStore.data.map { it[KEY_IDLE_MINUTES] ?: IDLE_DEFAULT }.first())

    suspend fun saveIdleMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_IDLE_MINUTES] = clampIdleMinutes(minutes) }
    }

    suspend fun getThemePack(): String =
        AppThemePack.normalize(context.dataStore.data.map { it[KEY_THEME] ?: AppThemePack.DARK }.first())

    suspend fun saveThemePack(id: String) {
        val pack = AppThemePack.normalize(id)
        context.dataStore.edit { it[KEY_THEME] = pack }
    }

    suspend fun getRandomChallenge(): Boolean =
        context.dataStore.data.map { it[KEY_RANDOM_CHALLENGE] ?: false }.first()

    suspend fun setRandomChallenge(on: Boolean) {
        context.dataStore.edit { it[KEY_RANDOM_CHALLENGE] = on }
    }

    suspend fun getReminderPrefs(): ReminderPrefs {
        val data = context.dataStore.data.first()
        val leads = parseLeads(data[KEY_REMINDER_LEADS] ?: "0,5")
        return ReminderPrefs(
            enabled = data[KEY_REMINDERS] ?: true,
            sound = data[KEY_REMINDER_SOUND] ?: true,
            leads = leads,
        )
    }

    suspend fun saveReminderPrefs(prefs: ReminderPrefs) {
        context.dataStore.edit {
            it[KEY_REMINDERS] = prefs.enabled
            it[KEY_REMINDER_SOUND] = prefs.sound
            it[KEY_REMINDER_LEADS] = prefs.leads.sorted().joinToString(",")
        }
    }

    suspend fun saveServer(url: String) {
        context.dataStore.edit { it[KEY_SERVER] = url.trimEnd('/') }
    }

    suspend fun saveAuth(userId: String, username: String, token: String, remember: Boolean = true) {
        processMemoryToken = token
        context.dataStore.edit {
            it[KEY_USER_ID] = userId
            it[KEY_USERNAME] = username
            it[KEY_REMEMBER] = remember
            if (remember) {
                it[KEY_TOKEN] = token
            } else {
                it.remove(KEY_TOKEN)
                it.remove(KEY_VAULT_WRAP)
            }
        }
    }

    suspend fun saveVaultWrap(packed: String) {
        context.dataStore.edit { it[KEY_VAULT_WRAP] = packed }
    }

    suspend fun getVaultWrap(): String? =
        context.dataStore.data.map { it[KEY_VAULT_WRAP] }.first()

    suspend fun clearVaultWrap() {
        context.dataStore.edit { it.remove(KEY_VAULT_WRAP) }
        VaultDeviceWrap.deleteKey()
    }

    suspend fun rememberedToken(): String? = token.first()

    /** Unwrap the Keystore DEK so signed-in / remember-me screens show plaintext. */
    suspend fun restoreRememberedDek(): Boolean {
        if (!getRememberLogin()) return false
        val packed = getVaultWrap() ?: return false
        val raw = VaultDeviceWrap.unwrap(packed) ?: return false
        return VaultCrypto.restoreKey(raw)
    }

    suspend fun clearAuth() {
        processMemoryToken = null
        context.dataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_USER_ID)
            it.remove(KEY_USERNAME)
            it.remove(KEY_VAULT_WRAP)
        }
        VaultDeviceWrap.deleteKey()
    }

    companion object {
        private val KEY_SERVER = stringPreferencesKey("server_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_REMEMBER = booleanPreferencesKey("remember_login")
        private val KEY_RANDOM_CHALLENGE = booleanPreferencesKey("random_challenge")
        private val KEY_REMINDERS = booleanPreferencesKey("reminders")
        private val KEY_REMINDER_SOUND = booleanPreferencesKey("reminder_sound")
        private val KEY_REMINDER_LEADS = stringPreferencesKey("reminder_leads")
        private val KEY_VAULT_WRAP = stringPreferencesKey("vault_device_wrap")
        private val KEY_IDLE_MINUTES = intPreferencesKey("idle_lock_minutes")
        private val KEY_THEME = stringPreferencesKey("theme_pack")

        /** Emulator → host machine Node default */
        const val DEFAULT_SERVER = "http://10.0.2.2:3456"
        const val IDLE_DEFAULT = 10
        const val IDLE_NEVER = 0
        const val IDLE_MIN = 2
        const val IDLE_MAX = 30

        @Volatile
        private var processMemoryToken: String? = null

        fun clampIdleMinutes(raw: Int): Int {
            if (raw <= 0) return IDLE_NEVER
            return raw.coerceIn(IDLE_MIN, IDLE_MAX)
        }

        val REMINDER_PRESETS = listOf(0, 5, 10, 30, 60, 120, 1440)

        private fun parseLeads(raw: String): Set<Int> =
            raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..20160 }.toSet()
    }
}

data class ReminderPrefs(
    val enabled: Boolean = true,
    val sound: Boolean = true,
    val leads: Set<Int> = setOf(0, 5),
)
