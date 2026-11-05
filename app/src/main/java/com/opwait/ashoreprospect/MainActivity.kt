package com.opwait.ashoreprospect

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.LocalTime
import com.opwait.ashoreprospect.ui.theme.AppThemePack
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.ui.AccountScreen
import com.opwait.ashoreprospect.ui.AddTaskDialog
import com.opwait.ashoreprospect.ui.AddTaskPrefill
import com.opwait.ashoreprospect.ui.BlockedEditorSheet
import com.opwait.ashoreprospect.ui.BlockedSlotPrefill
import com.opwait.ashoreprospect.ui.ForgotPasswordScreen
import com.opwait.ashoreprospect.ui.LoginScreen
import com.opwait.ashoreprospect.ui.MainSchedulerScreen
import com.opwait.ashoreprospect.ui.RegisterScreen
import com.opwait.ashoreprospect.ui.MainTab
import com.opwait.ashoreprospect.ui.SchedulerViewModel
import com.opwait.ashoreprospect.ui.SlotPlaceDialog
import com.opwait.ashoreprospect.ui.SecurityChallengeDialog
import com.opwait.ashoreprospect.ui.ServerScreen
import com.opwait.ashoreprospect.reminders.ReminderNotifier
import com.opwait.ashoreprospect.ui.theme.ashoreprospectTheme

class MainActivity : ComponentActivity() {
    private val openTaskId = MutableStateFlow<String?>(null)
    private val openColumnDate = MutableStateFlow<String?>(null)
    private val openAddTask = MutableStateFlow(false)
    private val openAddDurationHours = MutableStateFlow<Double?>(null)
    private val openAddTimeIso = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderNotifier.ensureChannel(this)
        readOpenExtras(intent)
        enableEdgeToEdge()
        setContent {
            ashoreprospectTheme(darkTheme = AppThemePack.isDark) {
                val vm: SchedulerViewModel = viewModel()
                val state by vm.ui.collectAsState()
                var route by remember { mutableStateOf<AppRoute?>(null) }
                var showAdd by remember { mutableStateOf(false) }
                var addPrefill by remember { mutableStateOf<AddTaskPrefill?>(null) }
                var showSlotPick by remember { mutableStateOf(false) }
                var showBlocked by remember { mutableStateOf(false) }
                var blockedPrefill by remember { mutableStateOf<BlockedSlotPrefill?>(null) }
                val notifPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                val fromNotif by openTaskId.collectAsState()
                LaunchedEffect(state.loggedIn) {
                    if (state.loggedIn && Build.VERSION.SDK_INT >= 33) {
                        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(fromNotif, state.loggedIn, state.sessionReady) {
                    val id = fromNotif ?: return@LaunchedEffect
                    if (!state.sessionReady || !state.loggedIn) return@LaunchedEffect
                    vm.openFromNotification(id)
                    openTaskId.value = null
                }
                val fromWidgetDate by openColumnDate.collectAsState()
                LaunchedEffect(fromWidgetDate, state.loggedIn, state.sessionReady) {
                    val iso = fromWidgetDate ?: return@LaunchedEffect
                    if (!state.sessionReady || !state.loggedIn) return@LaunchedEffect
                    runCatching { LocalDate.parse(iso) }.getOrNull()?.let { vm.jumpToDate(it) }
                    openColumnDate.value = null
                }
                val fromWidgetAdd by openAddTask.collectAsState()
                LaunchedEffect(fromWidgetAdd, state.loggedIn, state.sessionReady) {
                    if (!fromWidgetAdd) return@LaunchedEffect
                    if (!state.sessionReady || !state.loggedIn) return@LaunchedEffect
                    vm.setTab(MainTab.SCHEDULER)
                    val iso = intent.getStringExtra(EXTRA_OPEN_COLUMN_DATE)
                        ?: openColumnDate.value
                    val date = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                        ?: AppTimeReference.currentColumnDate()
                    val time = openAddTimeIso.value
                        ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                        ?: if (date == AppTimeReference.currentColumnDate()) {
                            AppTimeReference.getCurrentTime().withSecond(0).withNano(0)
                        } else {
                            LocalTime.of(9, 0)
                        }
                    addPrefill = AddTaskPrefill(
                        date = date,
                        time = time,
                        durationHours = openAddDurationHours.value,
                    )
                    showAdd = true
                    openAddTask.value = false
                    openAddDurationHours.value = null
                    openAddTimeIso.value = null
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
                LaunchedEffect(lifecycleState, state.loggedIn) {
                    if (lifecycleState == Lifecycle.State.RESUMED && state.loggedIn) {
                        vm.refreshReminderAlarms()
                        vm.onAppResumed()
                    }
                }
                DisposableEffect(lifecycleOwner, state.loggedIn) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_PAUSE && state.loggedIn) {
                            vm.onAppPaused()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(state.sessionReady, state.loggedIn, state.hasSavedServer) {
                    if (!state.sessionReady) return@LaunchedEffect
                    route = when {
                        state.loggedIn -> AppRoute.MAIN
                        state.hasSavedServer -> AppRoute.LOGIN
                        else -> AppRoute.SERVER
                    }
                }

                BackHandler(enabled = route == AppRoute.ACCOUNT) {
                    vm.clearError()
                    vm.clearNotice()
                    route = AppRoute.MAIN
                }
                BackHandler(enabled = route == AppRoute.REGISTER || route == AppRoute.FORGOT) {
                    vm.clearError()
                    vm.clearForgotNotice()
                    route = AppRoute.LOGIN
                }
                BackHandler(enabled = route == AppRoute.LOGIN && state.hasSavedServer) {
                    route = AppRoute.SERVER
                }
                BackHandler(enabled = route == AppRoute.MAIN && state.tab == MainTab.TYPEWRITER) {
                    vm.setTab(MainTab.SCHEDULER)
                }

                Box(Modifier.fillMaxSize()) {
                when (route) {
                    null -> Box(
                        Modifier.fillMaxSize().background(SchedulerColors.DarkChrome),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = SchedulerColors.Cream)
                    }
                    AppRoute.SERVER -> ServerScreen(
                        initialUrl = state.serverUrl,
                        onContinue = { url ->
                            vm.saveServer(url)
                            route = AppRoute.LOGIN
                        },
                    )
                    AppRoute.LOGIN -> {
                        if (state.loggedIn) {
                            route = AppRoute.MAIN
                        } else {
                            LoginScreen(
                                serverUrl = state.serverUrl,
                                loading = state.loading,
                                error = state.error,
                                notice = state.notice,
                                lastUsername = state.username,
                                rememberLogin = state.rememberLogin,
                                hostReachable = state.hostReachable,
                                onRememberLogin = vm::setRememberLogin,
                                onLogin = vm::login,
                                onRegister = {
                                    vm.clearError()
                                    route = AppRoute.REGISTER
                                },
                                onForgot = {
                                    vm.clearError()
                                    vm.clearForgotNotice()
                                    route = AppRoute.FORGOT
                                },
                                onChangeServer = { route = AppRoute.SERVER },
                            )
                        }
                    }
                    AppRoute.REGISTER -> {
                        if (state.loggedIn) {
                            route = AppRoute.MAIN
                        } else {
                            RegisterScreen(
                                serverUrl = state.serverUrl,
                                loading = state.loading,
                                error = state.error,
                                lastUsername = state.username,
                                rememberLogin = state.rememberLogin,
                                onRememberLogin = vm::setRememberLogin,
                                onRegister = vm::register,
                                onBack = {
                                    vm.clearError()
                                    route = AppRoute.LOGIN
                                },
                            )
                        }
                    }
                    AppRoute.FORGOT -> {
                        if (state.notice?.contains("Password reset") == true) {
                            route = AppRoute.LOGIN
                        }
                        ForgotPasswordScreen(
                            loading = state.loading,
                            error = state.error,
                            notice = state.forgotNotice,
                            lastUsername = state.username,
                            onSend = vm::requestPasswordReset,
                            onReset = vm::resetPassword,
                            onBack = {
                                vm.clearError()
                                vm.clearForgotNotice()
                                route = AppRoute.LOGIN
                            },
                        )
                    }
                    AppRoute.ACCOUNT -> {
                        if (!state.loggedIn) {
                            route = AppRoute.LOGIN
                        } else {
                            AccountScreen(
                                account = state.account,
                                loading = state.loading,
                                error = state.error,
                                notice = state.notice,
                                reminderPrefs = state.reminderPrefs,
                                randomChallenge = state.randomChallenge,
                                sessions = state.sessions,
                                onSaveUsername = vm::saveUsername,
                                onSaveEmail = vm::saveEmail,
                                onSaveSecurity = vm::saveSecurity,
                                onChangePassword = vm::changePassword,
                                onRandomChallenge = vm::setRandomChallenge,
                                onSaveReminders = vm::saveReminderPrefs,
                                onLoadSessions = vm::loadSessions,
                                onRevokeSession = vm::revokeSession,
                                onRevokeOthers = vm::revokeOtherSessions,
                                onDeleteAccount = vm::deleteAccount,
                                onClearCache = vm::clearLocalCache,
                                deviceLabel = vm.deviceLabel(),
                                serverUrl = state.serverUrl,
                                hostReachable = state.hostReachable,
                                onToggleTheme = vm::toggleTheme,
                                onBack = {
                                    vm.clearError()
                                    vm.clearNotice()
                                    route = AppRoute.MAIN
                                },
                            )
                        }
                    }
                    AppRoute.MAIN -> MainSchedulerScreen(
                        state = state,
                        onPrev = {
                            if (state.monthMode) vm.stepMonth(-1) else vm.stepWeek(-1)
                        },
                        onNext = {
                            if (state.monthMode) vm.stepMonth(1) else vm.stepWeek(1)
                        },
                        onToggleMonth = { vm.setMonthMode(!state.monthMode) },
                        onAddTask = {
                            addPrefill = null
                            showAdd = true
                        },
                        onRunScheduler = vm::runScheduler,
                        onBlocked = {
                            blockedPrefill = null
                            showBlocked = true
                        },
                        onBlockedSlot = { columnDate, slot ->
                            val date = AppTimeReference.calendarDateForSlot(columnDate, slot)
                                ?: columnDate
                            blockedPrefill = BlockedSlotPrefill(
                                startSlot = slot,
                                date = date,
                                weekday = columnDate.dayOfWeek,
                            )
                            showBlocked = true
                        },
                        onLogout = {
                            vm.logout()
                            route = AppRoute.LOGIN
                        },
                        onAccount = {
                            vm.clearError()
                            vm.clearNotice()
                            vm.openAccount()
                            route = AppRoute.ACCOUNT
                        },
                        onTab = vm::setTab,
                        onTaskClick = vm::selectTask,
                        onDismissTask = { vm.selectTask(null) },
                        onFinish = vm::toggleFinish,
                        onDelete = vm::deleteTask,
                        onSaveDescription = vm::saveDescription,
                        onSaveIdentity = vm::saveIdentity,
                        onSaveDeadline = vm::saveDeadline,
                        onMovePlacement = vm::placeTaskAt,
                        onUnschedule = vm::unschedule,
                        onJumpToDate = vm::jumpToDate,
                        onGoToday = vm::goToToday,
                        onGoNow = vm::goToNow,
                        onRefresh = { vm.refreshFromServer() },
                        onEmptySlot = { columnDate, slot ->
                            val date = AppTimeReference.calendarDateForSlot(columnDate, slot)
                                ?: columnDate
                            val slotTime = AppTimeReference.slotToLocalTime(slot)
                            val now = AppTimeReference.getCurrentTime().withSecond(0).withNano(0)
                            val time =
                                if (columnDate == AppTimeReference.currentColumnDate() &&
                                    slot == AppTimeReference.currentSlotIndex()
                                ) {
                                    now
                                } else {
                                    slotTime
                                }
                            addPrefill = AddTaskPrefill(
                                date = date,
                                time = time,
                            )
                            showAdd = true
                        },
                        onClearError = vm::clearError,
                        onClearNotice = vm::clearNotice,
                        onTypewriterChange = vm::updateTypewriter,
                        onAttachTypewriterImage = vm::attachTypewriterImage,
                        onPreviewTypewriterImage = vm::previewTypewriterImage,
                        onClearImagePreview = vm::clearImagePreview,
                    )
                }

                if (showSlotPick && addPrefill != null && state.loggedIn) {
                    SlotPlaceDialog(
                        prefill = addPrefill!!,
                        unscheduled = state.tasks.filter { it.isUnscheduledPending() },
                        onAddNew = { showSlotPick = false; showAdd = true },
                        onPlace = { task ->
                            val slot = addPrefill!!
                            showSlotPick = false
                            addPrefill = null
                            vm.placeTaskAt(task, slot.date, slot.time)
                        },
                        onDismiss = {
                            showSlotPick = false
                            addPrefill = null
                        },
                    )
                }
                if (showAdd && state.loggedIn) {
                    AddTaskDialog(
                        prefill = addPrefill,
                        onDismiss = {
                            showAdd = false
                            addPrefill = null
                        },
                        onSave = { title, dur, kind, sd, st, dd, dt, pd, pt, desc, descDone ->
                            showAdd = false
                            addPrefill = null
                            vm.clearError()
                            vm.addTask(title, dur, kind, sd, st, dd, dt, pd, pt, desc, descDone)
                        },
                    )
                }
                if (showBlocked && state.loggedIn) {
                    BlockedEditorSheet(
                        blocked = state.blocked,
                        error = state.error,
                        onAdd = vm::addBlocked,
                        onDelete = vm::deleteBlocked,
                        onDismiss = {
                            showBlocked = false
                            blockedPrefill = null
                        },
                        prefill = blockedPrefill,
                    )
                }
                state.pendingChallenge?.let { question ->
                    SecurityChallengeDialog(
                        question = question,
                        loading = state.loading,
                        error = state.error,
                        onSubmit = vm::submitChallenge,
                    )
                }
                val pending = state.pendingAccountSwitch
                if (pending != null) {
                    AlertDialog(
                        onDismissRequest = vm::cancelAccountSwitch,
                        title = { Text("Replace local cache?") },
                        text = {
                            Text(
                                "Account ${pending.username} is not the one stored on this phone. " +
                                    "Continuing deletes the previous account’s offline tasks on this device.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = vm::confirmAccountSwitch) { Text("Replace") }
                        },
                        dismissButton = {
                            TextButton(onClick = vm::cancelAccountSwitch) { Text("Cancel") }
                        },
                    )
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenExtras(intent)
    }

    private fun readOpenExtras(intent: Intent) {
        openTaskId.value = intent.getStringExtra(EXTRA_OPEN_TASK_ID)
        openColumnDate.value = intent.getStringExtra(EXTRA_OPEN_COLUMN_DATE)
        if (intent.getBooleanExtra(EXTRA_ADD_TASK, false)) {
            openAddTask.value = true
            openAddDurationHours.value = if (intent.hasExtra(EXTRA_ADD_DURATION_HOURS)) {
                intent.getDoubleExtra(EXTRA_ADD_DURATION_HOURS, 1.0)
            } else {
                null
            }
            openAddTimeIso.value = intent.getStringExtra(EXTRA_ADD_TIME)
        }
    }

    companion object {
        const val EXTRA_OPEN_TASK_ID = "open_task_id"
        const val EXTRA_OPEN_COLUMN_DATE = "open_column_date_iso"
        const val EXTRA_ADD_TASK = "add_task"
        const val EXTRA_ADD_DURATION_HOURS = "add_duration_hours"
        /** ISO local time `HH:mm` or `HH:mm:ss` for the add-task dialog start clock. */
        const val EXTRA_ADD_TIME = "add_time_iso"
    }
}

private enum class AppRoute { SERVER, LOGIN, REGISTER, FORGOT, ACCOUNT, MAIN }
