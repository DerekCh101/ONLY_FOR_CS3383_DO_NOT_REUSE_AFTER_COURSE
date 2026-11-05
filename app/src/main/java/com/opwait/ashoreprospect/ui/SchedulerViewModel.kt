package com.opwait.ashoreprospect.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opwait.ashoreprospect.data.ReminderPrefs
import com.opwait.ashoreprospect.data.SessionStore
import com.opwait.ashoreprospect.data.TaskRepository
import com.opwait.ashoreprospect.data.remote.MeResponse
import com.opwait.ashoreprospect.data.remote.SessionDto
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.CarryUnfinishedPast
import com.opwait.ashoreprospect.domain.BlockedTime
import com.opwait.ashoreprospect.domain.BlockedTimePeriod
import com.opwait.ashoreprospect.domain.DayGrid
import com.opwait.ashoreprospect.domain.NestedTasks
import com.opwait.ashoreprospect.domain.RefreshLifeCycle
import com.opwait.ashoreprospect.domain.SchedulerSession
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.TaskLifeCycle
import com.opwait.ashoreprospect.domain.TaskPlacement
import com.opwait.ashoreprospect.domain.TaskStateKind
import com.opwait.ashoreprospect.domain.VaultCrypto
import com.opwait.ashoreprospect.domain.VaultDeviceWrap
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.TypewriterBundle
import com.opwait.ashoreprospect.domain.TypewriterHtml
import com.opwait.ashoreprospect.domain.ValidateAddTaskInput
import com.opwait.ashoreprospect.ui.theme.AppThemePack
import com.opwait.ashoreprospect.domain.Workflow
import com.opwait.ashoreprospect.reminders.TaskReminderScheduler
import com.opwait.ashoreprospect.widget.CalendarWidgetRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class MainTab {
    SCHEDULER,
    TYPEWRITER,
    ;

    fun widgetLabel(dirty: Boolean = false): String = when (this) {
        SCHEDULER -> "Scheduler"
        TYPEWRITER -> if (dirty) "Typewriter *" else "Typewriter"
    }
}

data class PendingAccountSwitch(
    val userId: String,
    val username: String,
    val token: String,
)

data class SchedulerUiState(
    val sessionReady: Boolean = false,
    val hasSavedServer: Boolean = false,
    val serverUrl: String = SessionStore.DEFAULT_SERVER,
    val username: String? = null,
    val loggedIn: Boolean = false,
    val rememberLogin: Boolean = true,
    val pendingAccountSwitch: PendingAccountSwitch? = null,
    val weekOffset: Int = 0,
    val monthOffset: Int = 0,
    val monthMode: Boolean = false,
    val tab: MainTab = MainTab.SCHEDULER,
    val tasks: List<Task> = emptyList(),
    val blocked: List<BlockedTime> = emptyList(),
    val typewriter: TypewriterBundle = TypewriterBundle(),
    val typewriterRev: Int = 0,
    val selectedTask: Task? = null,
    val nowSlot: Int = AppTimeReference.currentSlotIndex(),
    val error: String? = null,
    val notice: String? = null,
    val loading: Boolean = false,
    val previewImageName: String? = null,
    val previewImageBytes: ByteArray? = null,
    val typewriterDirty: Boolean = false,
    val nowJump: Int = 1,
    /** Slot to scroll to on nowJump. -1 means the current now-line slot. */
    val scrollSlot: Int = -1,
    val focusDate: LocalDate? = AppTimeReference.currentColumnDate(),
    val account: MeResponse? = null,
    val pendingChallenge: String? = null,
    val forgotNotice: String? = null,
    val reminderPrefs: ReminderPrefs = ReminderPrefs(),
    val randomChallenge: Boolean = false,
    val sessions: List<SessionDto> = emptyList(),
    val vaultUnlocked: Boolean = false,
    val vaultNeedsUnlock: Boolean = false,
    val hostReachable: Boolean? = null,
    val themePack: String = AppThemePack.DARK,
)

class SchedulerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TaskRepository(app)
    private val session = SessionStore(app)
    private val schedulerSession = SchedulerSession()
    private val blockedPeriod = BlockedTimePeriod()
    private var pollJob: Job? = null
    private var twLocalJob: Job? = null
    private var twHostJob: Job? = null
    private var twEditGeneration: Long = 0L
    private var twLastEditAtMs: Long = 0L
    /** Generation last successfully POSTed to the host. Dirty until this catches [twEditGeneration]. */
    private var twHostAckGeneration: Long = 0L
    private var clockJob: Job? = null
    private var lastOnline: Boolean? = null
    private var expiringBlocked: Boolean = false
    private var pendingUnlockPassword: String? = null
    private var pendingOpenTaskId: String? = null
    private var imageScrubStarted: Boolean = false
    private val connectivity: ConnectivityManager =
        getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishOnline(true)
        override fun onLost(network: Network) = publishOnline(false)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            publishOnline(online)
        }
    }

    private val _ui = MutableStateFlow(SchedulerUiState())
    val ui: StateFlow<SchedulerUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val savedUrl = session.getServerUrl()
            val url = savedUrl ?: SessionStore.DEFAULT_SERVER
            val remember = session.getRememberLogin()
            val tok = session.getToken()
            val uid = session.getUserId()
            val name = session.getUsername()
            val reminders = session.getReminderPrefs()
            val randomCheck = session.getRandomChallenge()
            val themePack = session.getThemePack()
            AppThemePack.apply(themePack)
            if (remember && !tok.isNullOrBlank() && !uid.isNullOrBlank()) {
                restoreRememberedVault()
                persistRememberedVault()
                val local = repo.loadLocalWorkspace()
                applyWorkspace(local.tasks, local.blocked)
                val tw = runCatching { repo.loadTypewriter() }.getOrElse { TypewriterBundle() }
                val readable = VaultCrypto.unlocked() ||
                    !VaultCrypto.stillSealed(local.tasks, local.blocked, tw)
                _ui.update {
                    it.copy(
                        sessionReady = true,
                        hasSavedServer = savedUrl != null,
                        serverUrl = url,
                        loggedIn = true,
                        username = name,
                        rememberLogin = true,
                        loading = false,
                        reminderPrefs = reminders,
                        randomChallenge = randomCheck,
                        themePack = themePack,
                        typewriter = tw,
                        typewriterRev = it.typewriterRev + 1,
                        vaultUnlocked = readable,
                        vaultNeedsUnlock = !readable,
                        nowJump = it.nowJump + 1,
                        weekOffset = 0,
                        focusDate = AppTimeReference.currentColumnDate(),
                        scrollSlot = -1,
                        monthMode = false,
                    )
                }
                startPoll()
                runCatching { refreshFromServer(silent = true) }
                checkAccountDeleted()
                maybeSecurityChallenge()
                scrubExistingTypewriterImages()
            } else {
                _ui.update {
                    it.copy(
                        sessionReady = true,
                        hasSavedServer = savedUrl != null,
                        serverUrl = url,
                        username = name,
                        rememberLogin = remember,
                        reminderPrefs = reminders,
                        randomChallenge = randomCheck,
                        themePack = themePack,
                    )
                }
            }
            pingHost()
        }
        clockJob = viewModelScope.launch {
            while (isActive) {
                val slot = AppTimeReference.currentSlotIndex()
                _ui.update { st -> if (st.nowSlot == slot) st else st.copy(nowSlot = slot) }
                delay(1000)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                pingHost()
                delay(4000)
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }

    private fun publishOnline(online: Boolean) {
        val prev = lastOnline
        lastOnline = online
        if (prev == null || prev == online) {
            if (online && prev != null) checkAccountDeleted()
            return
        }
        _ui.update {
            it.copy(notice = if (online) "Online" else "Offline — using local cache")
        }
        if (online) checkAccountDeleted()
    }

    fun saveServer(url: String) {
        viewModelScope.launch {
            repo.saveServer(url)
            _ui.update {
                it.copy(serverUrl = url.trimEnd('/'), hasSavedServer = true, error = null)
            }
            pingHost()
        }
    }

    fun setRememberLogin(on: Boolean) {
        _ui.update { it.copy(rememberLogin = on) }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            AppThemePack.toggle()
            val pack = AppThemePack.id
            session.saveThemePack(pack)
            _ui.update { it.copy(themePack = pack) }
            CalendarWidgetRefresh.updateAllAsync(getApplication())
        }
    }

    private fun pingHost() {
        viewModelScope.launch {
            val ok = repo.pingHost()
            _ui.update { it.copy(hostReachable = ok) }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            val remember = _ui.value.rememberLogin
            pendingUnlockPassword = password
            _ui.update { it.copy(loading = true, error = null, pendingAccountSwitch = null) }
            runCatching {
                repo.login(username, password, remember)
            }.onSuccess {
                enterWorkspace(username)
            }.onFailure { e ->
                handleAuthFailure(e)
            }
        }
    }

    fun register(
        username: String,
        password: String,
        email: String,
        question: String,
        answer: String,
    ) {
        viewModelScope.launch {
            val remember = _ui.value.rememberLogin
            pendingUnlockPassword = password
            _ui.update { it.copy(loading = true, error = null, pendingAccountSwitch = null) }
            runCatching {
                repo.register(username, password, email, question, answer, remember)
            }.onSuccess {
                enterWorkspace(username)
            }.onFailure { e ->
                handleAuthFailure(e)
            }
        }
    }

    fun requestPasswordReset(username: String, email: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, forgotNotice = null) }
            runCatching {
                repo.requestPasswordReset(username, email)
            }.onSuccess {
                _ui.update {
                    it.copy(
                        loading = false,
                        forgotNotice = FORGOT_GENERIC,
                    )
                }
            }.onFailure { e ->
                _ui.update {
                    it.copy(loading = false, error = e.message ?: "request failed")
                }
            }
        }
    }

    fun resetPassword(token: String, answer: String, newPassword: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                repo.resetPassword(token, answer, newPassword)
            }.onSuccess {
                _ui.update {
                    it.copy(
                        loading = false,
                        error = null,
                        notice = "Password reset. Sign in with the new password.",
                        forgotNotice = null,
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(loading = false, error = e.message ?: "reset failed") }
            }
        }
    }

    fun clearLocalCache() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                repo.wipeLocalWorkspaceKeepAuth()
                val snap = repo.pullFromNode()
                val tw = repo.loadTypewriter()
                Triple(snap, tw, Unit)
            }.onSuccess { (snap, tw, _) ->
                applyWorkspace(snap.tasks, snap.blocked)
                _ui.update {
                    it.copy(
                        loading = false,
                        typewriter = tw,
                        typewriterRev = it.typewriterRev + 1,
                        typewriterDirty = false,
                        selectedTask = null,
                        notice = "This phone’s cache was cleared and reloaded from the server.",
                        error = null,
                    )
                }
            }.onFailure { e ->
                applyWorkspace(emptyList(), emptyList())
                _ui.update {
                    it.copy(
                        loading = false,
                        typewriter = TypewriterBundle(),
                        typewriterRev = it.typewriterRev + 1,
                        typewriterDirty = false,
                        selectedTask = null,
                        error = e.message ?: "Could not reload after clearing cache",
                    )
                }
            }
        }
    }

    fun deviceLabel(): String = repo.deviceLabel()

    fun openAccount() {
        if (_ui.value.typewriterDirty) saveTypewriter()
        loadAccount()
    }

    fun loadAccount() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.fetchMe() }
                .onSuccess { me ->
                    _ui.update { it.copy(loading = false, account = me, error = null) }
                    loadSessions()
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not load account") }
                }
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            runCatching { repo.listSessions() }
                .onSuccess { rows -> _ui.update { it.copy(sessions = rows) } }
        }
    }

    fun revokeOtherSessions() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.revokeOtherSessions() }
                .onSuccess {
                    _ui.update { it.copy(loading = false, notice = "Other devices were signed out") }
                    loadSessions()
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not revoke sessions") }
                }
        }
    }

    fun revokeSession(sessionId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.revokeSession(sessionId) }
                .onSuccess { resp ->
                    if (resp.current == true) {
                        pollJob?.cancel()
                        if (_ui.value.typewriterDirty) {
                            runCatching { repo.saveTypewriter(_ui.value.typewriter) }
                        }
                        VaultCrypto.lock()
                        repo.logout()
                        _ui.value = SchedulerUiState(
                            sessionReady = true,
                            hasSavedServer = true,
                            serverUrl = _ui.value.serverUrl,
                            username = _ui.value.username,
                            rememberLogin = _ui.value.rememberLogin,
                            notice = "This phone was signed out.",
                        )
                    } else {
                        _ui.update { it.copy(loading = false, notice = "Session revoked") }
                        loadSessions()
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not revoke session") }
                }
        }
    }

    fun deleteAccount(password: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.deleteAccount(password) }
                .onSuccess {
                    pollJob?.cancel()
                    _ui.value = SchedulerUiState(
                        sessionReady = true,
                        hasSavedServer = true,
                        serverUrl = _ui.value.serverUrl,
                        rememberLogin = _ui.value.rememberLogin,
                        notice = "Account deleted. Local cache on this phone was cleared.",
                    )
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not delete account") }
                }
        }
    }

    fun saveEmail(email: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.saveEmail(email) }
                .onSuccess {
                    _ui.update { it.copy(loading = false, notice = "Email saved") }
                    loadAccount()
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not save email") }
                }
        }
    }

    fun saveUsername(username: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.saveUsername(username) }
                .onSuccess {
                    _ui.update {
                        it.copy(loading = false, notice = "Username updated", username = username.trim())
                    }
                    loadAccount()
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not save username") }
                }
        }
    }

    fun setRandomChallenge(on: Boolean) {
        viewModelScope.launch {
            session.setRandomChallenge(on)
            _ui.update { it.copy(randomChallenge = on) }
        }
    }

    fun saveReminderPrefs(prefs: ReminderPrefs) {
        viewModelScope.launch {
            session.saveReminderPrefs(prefs)
            _ui.update { it.copy(reminderPrefs = prefs, notice = "Notification prefs saved on this phone") }
            syncReminders()
        }
    }

    fun saveSecurity(question: String, answer: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.saveSecurity(question, answer) }
                .onSuccess {
                    _ui.update { it.copy(loading = false, notice = "Security question saved") }
                    loadAccount()
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not save security") }
                }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.changePassword(oldPassword, newPassword) }
                .onSuccess {
                    if (VaultCrypto.unlocked()) {
                        val uid = session.getUserId()
                        if (!uid.isNullOrBlank()) {
                            withContext(Dispatchers.Default) { VaultCrypto.unlock(uid, newPassword) }
                            for (t in _ui.value.tasks) repo.upsertTask(t)
                            for (b in _ui.value.blocked) repo.upsertBlocked(b)
                            runCatching { repo.saveTypewriter(_ui.value.typewriter) }
                            persistRememberedVault()
                        }
                    }
                    _ui.update {
                        it.copy(
                            loading = false,
                            notice = "Password changed. Other devices were signed out.",
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = e.message ?: "could not change password") }
                }
        }
    }

    fun submitChallenge(answer: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            runCatching { repo.challengeAnswer(answer) }
                .onSuccess {
                    _ui.update { it.copy(loading = false, pendingChallenge = null) }
                }
                .onFailure {
                    failChallenge()
                }
        }
    }

    private suspend fun failChallenge() {
        pollJob?.cancel()
        pendingUnlockPassword = null
        VaultCrypto.lock()
        repo.wipeLocalCacheAndLogout()
        _ui.value = SchedulerUiState(
            sessionReady = true,
            hasSavedServer = true,
            serverUrl = _ui.value.serverUrl,
            rememberLogin = _ui.value.rememberLogin,
            error = "Wrong answer. Local cache on this phone was cleared.",
        )
    }

    private fun checkAccountDeleted() {
        viewModelScope.launch {
            val gone = runCatching { repo.isRemoteAccountDeleted() }.getOrDefault(false)
            if (!gone) return@launch
            pollJob?.cancel()
            pendingUnlockPassword = null
            VaultCrypto.lock()
            repo.wipeLocalCacheAndLogout()
            _ui.value = SchedulerUiState(
                sessionReady = true,
                hasSavedServer = true,
                serverUrl = _ui.value.serverUrl,
                rememberLogin = _ui.value.rememberLogin,
                error = "This account was deleted. Local cache on this phone was cleared.",
            )
        }
    }

    private fun maybeSecurityChallenge() {
        if (!_ui.value.rememberLogin) return
        if (!_ui.value.randomChallenge) return
        if (Random.nextInt(100) >= 18) return
        viewModelScope.launch {
            val pair = repo.challengeQuestion() ?: return@launch
            val (hasSecurity, question) = pair
            if (!hasSecurity || question.isBlank()) return@launch
            _ui.update { it.copy(pendingChallenge = question) }
        }
    }

    private fun handleAuthFailure(e: Throwable) {
        if (e is TaskRepository.AccountSwitchException) {
            _ui.update {
                it.copy(
                    loading = false,
                    pendingAccountSwitch = PendingAccountSwitch(e.userId, e.username, e.token),
                    error = null,
                )
            }
        } else {
            _ui.update { it.copy(loading = false, error = e.message ?: "login failed") }
        }
    }

    fun clearForgotNotice() {
        _ui.update { it.copy(forgotNotice = null) }
    }

    fun confirmAccountSwitch() {
        val pending = _ui.value.pendingAccountSwitch ?: return
        viewModelScope.launch {
            VaultCrypto.lock()
            session.clearVaultWrap()
            _ui.update { it.copy(loading = true, pendingAccountSwitch = null, error = null) }
            runCatching {
                repo.commitLogin(
                    pending.userId,
                    pending.username,
                    pending.token,
                    wipe = true,
                    remember = _ui.value.rememberLogin,
                )
            }.onSuccess {
                enterWorkspace(pending.username)
            }.onFailure { e ->
                _ui.update { it.copy(loading = false, error = e.message ?: "login failed") }
            }
        }
    }

    fun cancelAccountSwitch() {
        pendingUnlockPassword = null
        _ui.update { it.copy(pendingAccountSwitch = null) }
    }

    fun unlockVault(password: String) {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val uid = session.getUserId()
            if (uid.isNullOrBlank()) {
                _ui.update { it.copy(loading = false, error = "Sign in first") }
                return@launch
            }
            val previous = VaultCrypto.exportKey()
            runCatching {
                withContext(Dispatchers.Default) { VaultCrypto.unlock(uid, password) }
                val snap = repo.loadLocalWorkspace()
                applyWorkspace(snap.tasks, snap.blocked)
                repo.loadTypewriter()
            }.onSuccess { tw ->
                val sealed = VaultCrypto.stillSealed(_ui.value.tasks, _ui.value.blocked, tw)
                if (sealed && previous != null) {
                    VaultCrypto.restoreKey(previous)
                    val snap = repo.loadLocalWorkspace()
                    applyWorkspace(snap.tasks, snap.blocked)
                    val restored = repo.loadTypewriter()
                    _ui.update {
                        it.copy(
                            loading = false,
                            typewriter = restored,
                            typewriterRev = it.typewriterRev + 1,
                            typewriterDirty = false,
                            notice = null,
                            vaultUnlocked = VaultCrypto.unlocked(),
                            vaultNeedsUnlock = VaultCrypto.stillSealed(it.tasks, it.blocked, restored),
                        )
                    }
                    return@launch
                }
                if (sealed) {
                    VaultCrypto.lock()
                } else if (VaultCrypto.active()) {
                    persistRememberedVault()
                }
                _ui.update {
                    it.copy(
                        loading = false,
                        typewriter = tw,
                        typewriterRev = it.typewriterRev + 1,
                        typewriterDirty = false,
                        notice = null,
                        vaultUnlocked = VaultCrypto.unlocked(),
                        vaultNeedsUnlock = sealed,
                    )
                }
            }.onFailure { e ->
                if (previous != null) VaultCrypto.restoreKey(previous)
                _ui.update { it.copy(loading = false, error = e.message ?: "Could not load notes") }
            }
        }
    }

    private suspend fun restoreRememberedVault(): Boolean = session.restoreRememberedDek()

    private suspend fun persistRememberedVault() {
        if (!_ui.value.rememberLogin && !session.getRememberLogin()) {
            session.clearVaultWrap()
            return
        }
        val raw = VaultCrypto.exportKey() ?: return
        val packed = VaultDeviceWrap.wrap(raw) ?: return
        session.saveVaultWrap(packed)
    }

    private suspend fun applyPendingVaultPassword() {
        val uid = session.getUserId() ?: return
        val pw = pendingUnlockPassword
        pendingUnlockPassword = null
        if (pw.isNullOrBlank()) return
        withContext(Dispatchers.Default) { VaultCrypto.unlock(uid, pw) }
        persistRememberedVault()
    }

    private fun enterWorkspace(username: String) {
        viewModelScope.launch {
            applyPendingVaultPassword()
            val snap = runCatching { repo.pullFromNode() }.getOrElse { repo.loadLocalWorkspace() }
            applyWorkspace(snap.tasks, snap.blocked)
            val tw = runCatching { repo.loadTypewriter() }.getOrNull() ?: TypewriterBundle()
            _ui.update {
                it.copy(
                    loggedIn = true,
                    username = username,
                    loading = false,
                    pendingAccountSwitch = null,
                    error = null,
                    nowJump = it.nowJump + 1,
                    weekOffset = 0,
                    monthOffset = 0,
                    monthMode = false,
                    focusDate = AppTimeReference.currentColumnDate(),
                    scrollSlot = -1,
                    typewriter = tw,
                    typewriterRev = it.typewriterRev + 1,
                    typewriterDirty = false,
                    vaultUnlocked = VaultCrypto.unlocked(),
                    vaultNeedsUnlock = false,
                    notice = null,
                )
            }
            startPoll()
            pingHost()
            checkAccountDeleted()
            scrubExistingTypewriterImages()
        }
    }

    private fun scrubExistingTypewriterImages() {
        if (imageScrubStarted) return
        imageScrubStarted = true
        viewModelScope.launch {
            runCatching { repo.scrubTypewriterImages(_ui.value.typewriter) }
                .onSuccess { (bundle, n) ->
                    if (n <= 0) return@onSuccess
                    _ui.update {
                        it.copy(
                            typewriter = bundle.copy(),
                            typewriterRev = it.typewriterRev + 1,
                            notice = "Removed location and camera data from $n photo${if (n == 1) "" else "s"}",
                        )
                    }
                    saveTypewriter()
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            pollJob?.cancel()
            if (_ui.value.typewriterDirty) {
                runCatching { repo.saveTypewriter(_ui.value.typewriter) }
            }
            pendingUnlockPassword = null
            imageScrubStarted = false
            VaultCrypto.lock()
            repo.logout()
            CalendarWidgetRefresh.updateAllAsync(getApplication())
            _ui.value = SchedulerUiState(
                sessionReady = true,
                hasSavedServer = true,
                serverUrl = _ui.value.serverUrl,
                username = _ui.value.username,
                rememberLogin = _ui.value.rememberLogin,
                hostReachable = _ui.value.hostReachable,
            )
        }
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                // Dirty / unacked draft: do not pull host Typewriter over phone words.
                // Clean Typewriter tab is fine — HtmlEditorField refuses reload while focused/typing.
                if (typewriterBusy()) continue
                runCatching { refreshFromServer(silent = true) }
            }
        }
    }

    /** Flush local Typewriter draft + host push when the app goes to background. */
    fun onAppPaused() {
        if (_ui.value.typewriterDirty || twHostAckGeneration < twEditGeneration) {
            saveTypewriter()
        }
    }

    /** Catch up with the host after returning — never while a draft is dirty / unacked. */
    fun onAppResumed() {
        if (!_ui.value.loggedIn) return
        if (typewriterBusy()) return
        viewModelScope.launch {
            runCatching { refreshFromServer(silent = true) }
        }
    }

    fun refreshFromServer(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _ui.update { it.copy(loading = true) }
            try {
                val snap = repo.pullFromNode()
                applyWorkspace(snap.tasks, snap.blocked)
                if (!typewriterBusy()) {
                    pullTypewriterIfClean()
                }
                _ui.update {
                    it.copy(
                        hostReachable = true,
                        loading = if (silent) it.loading else false,
                        error = if (silent) it.error else null,
                    )
                }
            } catch (e: Exception) {
                val local = repo.loadLocalWorkspace()
                applyWorkspace(local.tasks, local.blocked)
                _ui.update {
                    it.copy(
                        hostReachable = false,
                        loading = if (silent) it.loading else false,
                        error = if (silent) it.error else e.message,
                    )
                }
            }
        }
    }

    private fun applyWorkspace(tasks: List<Task>, blocked: List<BlockedTime>) {
        for (t in tasks) RefreshLifeCycle.refresh(t)
        blockedPeriod.replaceAll(blocked)
        schedulerSession.blockedPeriod.replaceAll(blocked)
        schedulerSession.reloadFromTasks(tasks)
        _ui.update { st ->
            val selected = st.selectedTask?.let { cur -> tasks.find { it.id == cur.id } }
            st.copy(
                tasks = tasks.toList(),
                blocked = blocked.toList(),
                selectedTask = selected,
                vaultUnlocked = VaultCrypto.unlocked(),
                vaultNeedsUnlock = false,
            )
        }
        TaskReminderScheduler.schedule(getApplication(), tasks, _ui.value.reminderPrefs)
        CalendarWidgetRefresh.updateAllAsync(getApplication())
        expirePastBlocked(blocked)
        tryOpenPendingTask()
    }

    fun openFromNotification(taskId: String) {
        if (taskId.isBlank()) return
        pendingOpenTaskId = taskId
        tryOpenPendingTask()
    }

    private fun tryOpenPendingTask() {
        val id = pendingOpenTaskId ?: return
        val task = _ui.value.tasks.find { it.id == id } ?: return
        pendingOpenTaskId = null
        val parent = NestedTasks.mainTask(_ui.value.tasks, task)
        _ui.update { it.copy(tab = MainTab.SCHEDULER, selectedTask = parent) }
        val date = AppTimeReference.columnDateFor(parent.placedDate, parent.placedTime)
            ?: parent.placedDate
            ?: parent.deadlineDate
        if (date != null) jumpToDate(date)
    }

    private fun expirePastBlocked(blocked: List<BlockedTime>) {
        val gone = blocked.filter { it.isDatedExpired() }
        if (gone.isEmpty() || expiringBlocked) return
        expiringBlocked = true
        viewModelScope.launch {
            try {
                for (row in gone) repo.deleteBlocked(row)
                refreshFromServer(silent = true)
            } finally {
                expiringBlocked = false
            }
        }
    }

    private fun syncReminders() {
        TaskReminderScheduler.schedule(
            getApplication(),
            _ui.value.tasks,
            _ui.value.reminderPrefs,
        )
    }

    fun refreshReminderAlarms() {
        if (!_ui.value.loggedIn) return
        syncReminders()
    }

    fun stepWeek(delta: Int) {
        _ui.update {
            val week = it.weekOffset + delta
            val monday = AppTimeReference.currentWeekStart().plusDays(week * 7L)
            val thisWeek = week == 0
            it.copy(
                weekOffset = week,
                monthMode = false,
                focusDate = if (thisWeek) AppTimeReference.currentColumnDate() else monday,
                scrollSlot = if (thisWeek) -1 else 0,
                nowJump = it.nowJump + 1,
            )
        }
    }

    fun stepMonth(delta: Int) {
        _ui.update { it.copy(monthOffset = it.monthOffset + delta) }
    }

    fun setMonthMode(month: Boolean) {
        _ui.update {
            if (month) {
                it.copy(monthMode = true)
            } else {
                val monday = AppTimeReference.currentWeekStart().plusDays(it.weekOffset * 7L)
                val thisWeek = it.weekOffset == 0
                it.copy(
                    monthMode = false,
                    focusDate = if (thisWeek) AppTimeReference.currentColumnDate() else monday,
                    scrollSlot = if (thisWeek) -1 else 0,
                    nowJump = it.nowJump + 1,
                )
            }
        }
    }

    fun setTab(tab: MainTab) {
        val prev = _ui.value.tab
        _ui.update { it.copy(tab = tab) }
        if (tab == MainTab.TYPEWRITER) {
            if (!typewriterBusy()) loadTypewriter()
            return
        }
        if (prev == MainTab.TYPEWRITER) {
            // Left Typewriter: flush local + host; pull only after host ack (not while dirty).
            viewModelScope.launch {
                if (_ui.value.typewriterDirty || twHostAckGeneration < twEditGeneration) {
                    persistTypewriterNow()
                }
                if (!typewriterBusy()) runCatching { refreshFromServer(silent = true) }
            }
        }
    }

    fun jumpToDate(date: LocalDate) {
        val today = AppTimeReference.currentColumnDate()
        val monday = AppTimeReference.currentWeekStart()
        val target = AppTimeReference.getStartOfWeek(date)
        val weeks = java.time.temporal.ChronoUnit.WEEKS.between(monday, target).toInt()
        val slot = if (date == today) {
            AppTimeReference.currentSlotIndex()
        } else {
            firstPlacedSlotOn(_ui.value.tasks, date) ?: 0
        }
        _ui.update {
            it.copy(
                monthMode = false,
                weekOffset = weeks,
                scrollSlot = slot,
                focusDate = date,
                nowJump = it.nowJump + 1,
            )
        }
    }

    fun goToToday() {
        _ui.update {
            it.copy(
                weekOffset = 0,
                monthOffset = 0,
                monthMode = false,
                scrollSlot = -1,
                focusDate = AppTimeReference.currentColumnDate(),
                nowJump = it.nowJump + 1,
            )
        }
    }

    /** Desktop Jump: zero the current view’s offset. Month stays on this 06:00 column month. */
    fun goToNow() {
        _ui.update {
            it.copy(
                weekOffset = 0,
                monthOffset = 0,
                monthMode = it.monthMode,
                scrollSlot = -1,
                focusDate = AppTimeReference.currentColumnDate(),
                nowJump = it.nowJump + 1,
            )
        }
    }

    fun selectTask(task: Task?) {
        val resolved = task?.let { NestedTasks.mainTask(_ui.value.tasks, it) }
        _ui.update { it.copy(selectedTask = resolved) }
    }

    fun toggleFinish(task: Task) {
        viewModelScope.launch {
            val parent = NestedTasks.mainTask(_ui.value.tasks, task)
            if (parent.placedDate == null || parent.placedTime == null) return@launch
            val next = if (parent.workflow == Workflow.FINISHED) {
                Workflow.UNFINISHED
            } else {
                Workflow.FINISHED
            }
            val affiliated = listOf(parent) + NestedTasks.ofParent(_ui.value.tasks, parent)
            val stamped = affiliated.map { row ->
                row.workflow = next
                row.touchLastUpdated()
                row.copy(workflow = next, lastUpdatedAt = row.lastUpdatedAt)
            }
            for (row in stamped) repo.upsertTask(row)
            val byId = stamped.associateBy { it.id }
            _ui.update { st ->
                st.copy(
                    tasks = st.tasks.map { t -> byId[t.id] ?: t },
                    selectedTask = byId[parent.id] ?: st.selectedTask,
                )
            }
            syncReminders()
            runCatching { repo.pullFromNode() }
                .onSuccess { snap ->
                    applyWorkspace(snap.tasks, snap.blocked)
                    _ui.update { st ->
                        st.copy(selectedTask = st.tasks.find { it.id == parent.id } ?: byId[parent.id])
                    }
                }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            val parent = NestedTasks.mainTask(_ui.value.tasks, task)
            for (q in NestedTasks.ofParent(_ui.value.tasks, parent)) {
                repo.deleteTask(q)
            }
            repo.deleteTask(parent)
            selectTask(null)
            refreshFromServer(silent = true)
        }
    }

    fun saveIdentity(task: Task, title: String, durationHours: Double) {
        viewModelScope.launch {
            val current = _ui.value.tasks.find { it.id == task.id } ?: task
            val nextTitle = if (VaultSeal.isSealed(current.title)) current.title else title.trim()
            if (nextTitle.isBlank()) {
                _ui.update { it.copy(error = "Title is required") }
                return@launch
            }
            val mins = AppTimeReference.hoursToMinutes(durationHours)
            val hours = mins / 60.0
            val currentMins = kotlin.math.round(current.durationMinutes).toInt()
            if (nextTitle == current.title && currentMins == mins) return@launch
            blockedPeriod.replaceAll(_ui.value.blocked)
            val fitErr = TaskPlacement.durationChangeError(current, hours, _ui.value.tasks, blockedPeriod)
            if (fitErr != null) {
                _ui.update { it.copy(error = fitErr) }
                return@launch
            }
            val durationChanged = currentMins != mins
            val snapshot = current.copy(
                title = nextTitle,
                durationMinutes = mins.toDouble(),
            )
            if (durationChanged) snapshot.touchPlacementUpdated() else snapshot.touchLastUpdated()
            RefreshLifeCycle.refresh(snapshot)
            repo.upsertTask(snapshot)
            _ui.update { st ->
                st.copy(
                    tasks = st.tasks.map { t -> if (t.id == snapshot.id) snapshot else t },
                    selectedTask = if (st.selectedTask?.id == snapshot.id) snapshot else st.selectedTask,
                    error = null,
                )
            }
            syncReminders()
        }
    }

    fun saveDeadline(task: Task, date: LocalDate?, time: LocalTime?) {
        viewModelScope.launch {
            if (!task.hasDeadline()) return@launch
            val current = _ui.value.tasks.find { it.id == task.id } ?: task
            if (date == null || time == null) {
                _ui.update { it.copy(error = "Deadline needs a date and time") }
                return@launch
            }
            if (current.deadlineDate == date && current.deadlineTime == time) return@launch
            val due = LocalDateTime.of(date, time)
            if (!due.isAfter(AppTimeReference.getCurrentDateTime())) {
                _ui.update { it.copy(error = "Cannot set deadline in the past.") }
                return@launch
            }
            if (current.placedDate != null && current.placedTime != null) {
                val start = LocalDateTime.of(current.placedDate, current.placedTime)
                val finish = start.plusMinutes(
                    maxOf(1, kotlin.math.round(current.durationMinutes).toLong()),
                )
                if (finish.isAfter(due)) {
                    _ui.update { it.copy(error = "Duration would pass the deadline") }
                    return@launch
                }
            }
            val snapshot = current.copy(deadlineDate = date, deadlineTime = time)
            snapshot.touchLastUpdated()
            repo.upsertTask(snapshot)
            _ui.update { st ->
                st.copy(
                    tasks = st.tasks.map { t -> if (t.id == snapshot.id) snapshot else t },
                    selectedTask = if (st.selectedTask?.id == snapshot.id) snapshot else st.selectedTask,
                    error = null,
                )
            }
            syncReminders()
        }
    }

    fun placeTaskAt(task: Task, date: LocalDate, time: LocalTime) {
        viewModelScope.launch {
            val current = _ui.value.tasks.find { it.id == task.id } ?: task
            if (current.placedDate == date && current.placedTime == time) return@launch
            blockedPeriod.replaceAll(_ui.value.blocked)
            val err = TaskPlacement.placeError(
                current, date, time, _ui.value.tasks, blockedPeriod,
            )
            if (err != null) {
                _ui.update { it.copy(error = err) }
                return@launch
            }
            val snapshot = current.copy(
                placedDate = date,
                placedTime = time,
                startingDate = if (current.isFixedTime()) date else current.startingDate,
                startingTime = if (current.isFixedTime()) time else current.startingTime,
            )
            snapshot.touchPlacementUpdated()
            RefreshLifeCycle.refresh(snapshot)
            repo.upsertTask(snapshot)
            _ui.update { st ->
                st.copy(
                    tasks = st.tasks.map { t -> if (t.id == snapshot.id) snapshot else t },
                    selectedTask = if (st.selectedTask?.id == snapshot.id) snapshot else st.selectedTask,
                    error = null,
                    notice = "Placed ${VaultSeal.displayTitle(snapshot.title)}",
                )
            }
            syncReminders()
        }
    }

    fun unschedule(task: Task) {
        viewModelScope.launch {
            if (task.isFixedTime()) {
                _ui.update { it.copy(error = "Fixed tasks stay on the grid.") }
                return@launch
            }
            val current = _ui.value.tasks.find { it.id == task.id } ?: task
            if (current.placedDate == null || current.placedTime == null) return@launch
            val snapshot = current.copy(
                placedDate = null,
                placedTime = null,
                lifeCycle = TaskLifeCycle.UNSCHEDULED,
            )
            snapshot.touchPlacementUpdated()
            RefreshLifeCycle.refresh(snapshot)
            repo.upsertTask(snapshot)
            _ui.update { st ->
                st.copy(
                    tasks = st.tasks.map { t -> if (t.id == snapshot.id) snapshot else t },
                    selectedTask = if (st.selectedTask?.id == snapshot.id) snapshot else st.selectedTask,
                    error = null,
                )
            }
            syncReminders()
        }
    }

    fun saveDescription(task: Task, encoded: String, allDone: Boolean) {
        viewModelScope.launch {
            val current = _ui.value.tasks.find { it.id == task.id } ?: task
            val snapshot = current.copy(
                description = encoded,
                descriptionDone = allDone,
            )
            snapshot.touchLastUpdated()
            repo.upsertTask(snapshot)
            _ui.update { st ->
                st.copy(
                    tasks = st.tasks.map { t -> if (t.id == snapshot.id) snapshot else t },
                    selectedTask = if (st.selectedTask?.id == snapshot.id) snapshot else st.selectedTask,
                )
            }
        }
    }

    fun addTask(
        title: String,
        durationHours: Double,
        state: TaskStateKind,
        startDate: LocalDate?,
        startTime: LocalTime?,
        deadlineDate: LocalDate?,
        deadlineTime: LocalTime?,
        placedDate: LocalDate?,
        placedTime: LocalTime?,
        description: String = "",
        descriptionDone: Boolean = false,
    ) {
        viewModelScope.launch {
            if (!ValidateAddTaskInput.validate(
                    title, durationHours, state,
                    startDate, startTime, deadlineDate, deadlineTime,
                )
            ) {
                _ui.update { it.copy(error = "Invalid task fields") }
                return@launch
            }
            val task = Task.newTask(
                title.trim(), durationHours, state,
                startDate, startTime, deadlineDate, deadlineTime,
            )
            task.description = description
            task.descriptionDone = descriptionDone
            val placeDate = placedDate ?: startDate
            val placeTime = placedTime ?: startTime
            if (placeDate != null && placeTime != null) {
                task.placedDate = placeDate
                task.placedTime = placeTime
            }
            val startClock = when {
                placedDate != null && placedTime != null -> LocalDateTime.of(placedDate, placedTime)
                task.isFixedTime() && startDate != null && startTime != null ->
                    LocalDateTime.of(startDate, startTime)
                else -> null
            }
            val now = AppTimeReference.getCurrentDateTime()
            val nowFloor = now.withSecond(0).withNano(0)
            if (startClock != null && startClock.isBefore(nowFloor)) {
                _ui.update { it.copy(error = "Cannot create a task in the past.") }
                return@launch
            }
            if (task.hasDeadline() && deadlineDate != null && deadlineTime != null) {
                val due = LocalDateTime.of(deadlineDate, deadlineTime)
                if (!due.isAfter(now)) {
                    _ui.update { it.copy(error = "Cannot set deadline in the past.") }
                    return@launch
                }
            }
            // Occupancy against live task list (same rules as drag/place), not a stale DayGrid.
            blockedPeriod.replaceAll(_ui.value.blocked)
            schedulerSession.blockedPeriod.replaceAll(_ui.value.blocked)
            when {
                placeDate != null && placeTime != null -> {
                    val err = TaskPlacement.placeError(
                        task, placeDate, placeTime, _ui.value.tasks, blockedPeriod,
                    )
                    if (err != null) {
                        _ui.update { it.copy(error = err) }
                        return@launch
                    }
                    task.placedDate = placeDate
                    task.placedTime = placeTime
                    val column = AppTimeReference.columnDateFor(placeDate, placeTime)!!
                    val slot = AppTimeReference.localTimeToSlot(placeTime)
                    schedulerSession.daysGrid.putIfAbsent(column, DayGrid())
                    val day = schedulerSession.daysGrid.getValue(column)
                    day.placeTask(slot, task)
                    schedulerSession.placedTasks.add(task)
                }
                task.isFlexible() || task.hasDeadline() -> {
                    schedulerSession.pendingTasks.add(task)
                    schedulerSession.updateSchedule()
                    if (task !in schedulerSession.placedTasks) {
                        _ui.update { it.copy(error = "Could not schedule task — no free slot") }
                        return@launch
                    }
                }
                else -> {
                    _ui.update { it.copy(error = "Invalid task fields") }
                    return@launch
                }
            }
            RefreshLifeCycle.refresh(task)
            task.touchPlacementUpdated()
            val nodeErr = repo.upsertTask(task)
            refreshFromServer(silent = true)
            if (nodeErr != null) {
                _ui.update { it.copy(error = nodeErr) }
            }
        }
    }

    fun runScheduler() {
        viewModelScope.launch {
            val (prepared, carried) = CarryUnfinishedPast.prepare(_ui.value.tasks)
            schedulerSession.blockedPeriod.replaceAll(_ui.value.blocked)
            schedulerSession.reloadFromTasks(prepared)
            schedulerSession.updateSchedule()
            for (t in schedulerSession.placedTasks + schedulerSession.unscheduledTasks) {
                RefreshLifeCycle.refresh(t)
                repo.upsertTask(t)
            }
            refreshFromServer(silent = true)
            val unscheduled = schedulerSession.unscheduledTasks.size
            _ui.update {
                it.copy(
                    notice = buildString {
                        append("Scheduler ran")
                        if (carried > 0) append(" — carried $carried unfinished past")
                        append(
                            if (unscheduled == 0) " — all tasks placed"
                            else " — $unscheduled still unscheduled",
                        )
                    },
                )
            }
        }
    }

    fun attachTypewriterImage(
        diaryDate: String?,
        pageId: String?,
        name: String,
        mime: String,
        bytes: ByteArray,
    ) {
        viewModelScope.launch {
            runCatching { repo.uploadTypewriterImage(name, mime, bytes) }
                .onSuccess { ref ->
                    val bundle = _ui.value.typewriter
                    when {
                        diaryDate != null -> bundle.diaryFor(diaryDate).images.add(ref)
                        pageId != null -> bundle.pages.firstOrNull { it.id == pageId }?.images?.add(ref)
                    }
                    _ui.update {
                        it.copy(
                            typewriter = bundle.copy(),
                            typewriterRev = it.typewriterRev + 1,
                            notice = "Attached ${ref.name}",
                            error = null,
                        )
                    }
                    saveTypewriter()
                }
                .onFailure { e ->
                    _ui.update { it.copy(error = e.message ?: "Image upload failed") }
                }
        }
    }

    fun addBlocked(rows: List<BlockedTime>) {
        viewModelScope.launch {
            runCatching {
                val keep = rows.filterNot { it.isDatedExpired() }
                if (keep.isEmpty()) error("Cannot add a blocked time in the past.")
                for (row in keep) {
                    blockedPeriod.conflictMessage(row)?.let { error(it) }
                    repo.upsertBlocked(row)
                    blockedPeriod.add(row)
                }
            }.onSuccess {
                refreshFromServer(silent = true)
                _ui.update { it.copy(error = null) }
            }.onFailure { e ->
                _ui.update { it.copy(error = e.message ?: "Blocked time rejected") }
            }
        }
    }

    fun deleteBlocked(row: BlockedTime) {
        viewModelScope.launch {
            repo.deleteBlocked(row)
            refreshFromServer(silent = true)
        }
    }

    private fun typewriterBusy(): Boolean {
        if (_ui.value.typewriterDirty) return true
        if (twHostAckGeneration < twEditGeneration) return true
        return System.currentTimeMillis() - twLastEditAtMs < 2500L
    }

    private suspend fun pullTypewriterIfClean() {
        if (typewriterBusy()) return
        runCatching { repo.loadTypewriter() }.onSuccess { applyPulledTypewriter(it) }
    }

    fun loadTypewriter() {
        viewModelScope.launch {
            if (typewriterBusy()) return@launch
            runCatching { repo.loadTypewriter() }.onSuccess { applyPulledTypewriter(it) }
        }
    }

    private fun applyPulledTypewriter(bundle: TypewriterBundle) {
        // Never replace an open / unsynced draft with host HTML.
        if (typewriterBusy()) return
        val current = _ui.value.typewriter
        if (TypewriterHtml.sameStoredContent(current, bundle)) {
            if (bundle.lastUpdatedAt != null && current.lastUpdatedAt != bundle.lastUpdatedAt) {
                current.lastUpdatedAt = bundle.lastUpdatedAt
            }
            return
        }
        if (current.hasSubstance() && !bundle.hasSubstance()) return
        val currentTs = runCatching { java.time.Instant.parse(current.lastUpdatedAt) }.getOrNull()
        val remoteTs = runCatching { java.time.Instant.parse(bundle.lastUpdatedAt) }.getOrNull()
        // Clean phone: host wins when newer or same stamp (draft stamps no longer inflate past host).
        if (remoteTs == null) return
        if (currentTs != null && remoteTs.isBefore(currentTs)) return
        _ui.update {
            it.copy(
                typewriter = bundle,
                typewriterRev = it.typewriterRev + 1,
                typewriterDirty = false,
                vaultUnlocked = VaultCrypto.unlocked(),
                vaultNeedsUnlock = false,
            )
        }
        twHostAckGeneration = twEditGeneration
    }

    /**
     * Local-first Typewriter: update in-memory draft immediately, write SQLite soon,
     * POST to host after that, clear dirty only when the host accepts this generation.
     *
     * Do not gate on [TypewriterHtml.sameStoredContent]: diary/page rows are mutable and
     * [TypewriterBundle.copy] is shallow, so WebView edits mutate the live UI state first.
     * Comparing then looks identical and used to skip SQLite + host entirely.
     */
    fun updateTypewriter(bundle: TypewriterBundle) {
        twEditGeneration += 1
        twLastEditAtMs = System.currentTimeMillis()
        // Do not bump typewriterRev on every key — that recomposes the whole Typewriter chrome
        // and races the WebView. Structural callers bump rev themselves when needed.
        _ui.update {
            it.copy(typewriter = bundle, typewriterDirty = true)
        }
        scheduleTypewriterPersist()
    }

    private fun scheduleTypewriterPersist() {
        val generation = twEditGeneration
        twLocalJob?.cancel()
        twHostJob?.cancel()
        twLocalJob = viewModelScope.launch {
            delay(400)
            if (generation != twEditGeneration) return@launch
            persistTypewriterLocalOnly()
        }
        twHostJob = viewModelScope.launch {
            delay(750)
            if (generation != twEditGeneration) return@launch
            pushTypewriterHostUntilAck()
        }
    }

    fun saveTypewriter() {
        twLocalJob?.cancel()
        twHostJob?.cancel()
        viewModelScope.launch { persistTypewriterNow() }
    }

    private suspend fun persistTypewriterLocalOnly() {
        if (!_ui.value.typewriterDirty && twHostAckGeneration >= twEditGeneration) return
        val snapshot = _ui.value.typewriter
        runCatching { repo.persistTypewriterLocal(snapshot) }
            .onFailure { e -> _ui.update { it.copy(error = e.message) } }
    }

    private suspend fun pushTypewriterHostUntilAck() {
        val generation = twEditGeneration
        val snapshot = _ui.value.typewriter
        runCatching {
            repo.persistTypewriterLocal(snapshot)
            repo.pushTypewriterToHost(snapshot)
        }.onSuccess { pushed ->
            if (!pushed) return@onSuccess
            if (generation != twEditGeneration) return@onSuccess
            twHostAckGeneration = generation
            _ui.update { st -> st.copy(error = null, typewriterDirty = false) }
        }.onFailure { e ->
            // Keep dirty — next edit / pause / leave-tab will retry. Soft notice only.
            _ui.update { it.copy(error = e.message) }
        }
    }

    /** Flush local + host; clear dirty only after host ack for this generation. */
    private suspend fun persistTypewriterNow() {
        if (!_ui.value.typewriterDirty && twHostAckGeneration >= twEditGeneration) return
        val generation = twEditGeneration
        val snapshot = _ui.value.typewriter
        runCatching {
            repo.persistTypewriterLocal(snapshot)
            repo.pushTypewriterToHost(snapshot)
        }.onSuccess { pushed ->
            if (!pushed) return@onSuccess
            if (generation != twEditGeneration) return@onSuccess
            twHostAckGeneration = generation
            _ui.update { st -> st.copy(error = null, typewriterDirty = false) }
        }.onFailure { e -> _ui.update { it.copy(error = e.message) } }
    }

    fun previewTypewriterImage(sha: String) {
        viewModelScope.launch {
            runCatching { repo.downloadTypewriterImage(sha) }
                .onSuccess { (name, bytes) ->
                    _ui.update { it.copy(previewImageName = name, previewImageBytes = bytes, error = null) }
                }
                .onFailure { e ->
                    _ui.update { it.copy(error = e.message ?: "Could not load image") }
                }
        }
    }

    fun clearImagePreview() {
        _ui.update { it.copy(previewImageName = null, previewImageBytes = null) }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    fun clearNotice() {
        _ui.update { it.copy(notice = null) }
    }

    private fun firstPlacedSlotOn(tasks: List<Task>, columnDate: LocalDate): Int? {
        var best: Int? = null
        for (t in tasks) {
            if (t.isQuick()) continue
            val pd = t.placedDate ?: continue
            val pt = t.placedTime ?: continue
            val col = AppTimeReference.columnDateFor(pd, pt) ?: continue
            if (col != columnDate) continue
            val slot = AppTimeReference.localTimeToSlot(pt)
            if (best == null || slot < best) best = slot
        }
        return best
    }

    companion object {
        const val FORGOT_GENERIC =
            "If that account exists, a reset token was prepared. Check email and Spam. " +
                "The host PC Node terminal always printed the token and the question if mail is late."
    }
}
