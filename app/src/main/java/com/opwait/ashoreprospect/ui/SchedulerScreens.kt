package com.opwait.ashoreprospect.ui

import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.AuthRules
import com.opwait.ashoreprospect.domain.DescriptionChecklist
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.BlockedTime
import com.opwait.ashoreprospect.domain.BlockedTimePeriod
import com.opwait.ashoreprospect.domain.MonthCellTasks
import com.opwait.ashoreprospect.domain.NestedTasks
import com.opwait.ashoreprospect.domain.TaskDue
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.TaskLifeCycle
import com.opwait.ashoreprospect.domain.TaskStateKind
import com.opwait.ashoreprospect.domain.Workflow
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import com.opwait.ashoreprospect.ui.theme.schedulerCreamFieldColors
import com.opwait.ashoreprospect.ui.theme.schedulerOutlinedFieldColors
import com.opwait.ashoreprospect.ui.theme.schedulerPrimaryButtonColors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AddTaskPrefill(
    val date: LocalDate,
    val time: LocalTime,
    val state: TaskStateKind = TaskStateKind.FIXED,
    /** When set (e.g. from the home-screen widget), seeds duration fields. */
    val durationHours: Double? = null,
)

@Composable
fun ServerScreen(
    initialUrl: String,
    onContinue: (String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SchedulerColors.DarkChrome)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Node server", color = SchedulerColors.Cream, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue(url.trim()) }),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Emulator default: http://10.0.2.2:3456",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onContinue(url.trim()) },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerPrimaryButtonColors(),
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SchedulerColors.DarkChrome)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun AuthWorkingButton(
    label: String,
    loading: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth(),
        colors = schedulerPrimaryButtonColors(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = SchedulerColors.DarkChrome,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text("Working…")
        } else {
            Text(label)
        }
    }
}

@Composable
fun LoginScreen(
    serverUrl: String,
    loading: Boolean,
    error: String?,
    notice: String? = null,
    lastUsername: String?,
    rememberLogin: Boolean,
    hostReachable: Boolean? = null,
    onRememberLogin: (Boolean) -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    onRegister: () -> Unit,
    onForgot: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var user by remember { mutableStateOf(lastUsername.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    AuthColumn {
        Text("Sign in", color = SchedulerColors.Cream, style = MaterialTheme.typography.headlineMedium)
        Text(serverUrl, color = SchedulerColors.LabelMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            when (hostReachable) {
                true -> "Host online"
                false -> "Host offline — local cache only after sign-in"
                null -> "Checking host…"
            },
            color = when (hostReachable) {
                true -> SchedulerColors.TaskOngoing
                false -> SchedulerColors.TaskOverdue
                null -> SchedulerColors.OnDarkMuted
            },
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { if (!loading) onLogin(user, pass) },
            ),
        )
        if (error != null) {
            Text(error, color = SchedulerColors.Error, modifier = Modifier.padding(top = 8.dp))
        }
        if (notice != null) {
            Text(notice, color = SchedulerColors.Cream, modifier = Modifier.padding(top = 8.dp), fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        RememberLoginRow(rememberLogin, onRememberLogin)
        Spacer(Modifier.height(8.dp))
        AuthWorkingButton("Login", loading) { onLogin(user, pass) }
        TextButton(onClick = onRegister, enabled = !loading) {
            Text("Create an account", color = SchedulerColors.LabelMuted)
        }
        TextButton(onClick = onForgot, enabled = !loading) {
            Text("Forgot password", color = SchedulerColors.LabelMuted)
        }
        TextButton(onClick = onChangeServer) {
            Text("Change server", color = SchedulerColors.LabelMuted)
        }
    }
}

@Composable
private fun RememberLoginRow(rememberLogin: Boolean, onRememberLogin: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onRememberLogin(!rememberLogin) },
    ) {
        Checkbox(
            checked = rememberLogin,
            onCheckedChange = onRememberLogin,
            colors = CheckboxDefaults.colors(
                checkedColor = SchedulerColors.Cream,
                uncheckedColor = SchedulerColors.LabelMuted,
                checkmarkColor = SchedulerColors.DarkChrome,
            ),
        )
        Text("Remember login", color = SchedulerColors.Cream)
    }
    Text(
        "Checked: after a restart this phone signs in and shows titles. Idle lock still returns here.",
        color = SchedulerColors.LabelMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

@Composable
fun RegisterScreen(
    serverUrl: String,
    loading: Boolean,
    error: String?,
    lastUsername: String?,
    rememberLogin: Boolean,
    onRememberLogin: (Boolean) -> Unit,
    onRegister: (username: String, password: String, email: String, question: String, answer: String) -> Unit,
    onBack: () -> Unit,
) {
    var user by remember { mutableStateOf(lastUsername.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    AuthColumn {
        Text("Register", color = SchedulerColors.Cream, style = MaterialTheme.typography.headlineMedium)
        Text(serverUrl, color = SchedulerColors.LabelMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Email and a security question are required. Without them the account cannot be recovered.",
            color = SchedulerColors.LabelMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password (min 8)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Security question (min 8)") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text("Security answer (min 4)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        if (error != null) {
            Text(error, color = SchedulerColors.Error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        RememberLoginRow(rememberLogin, onRememberLogin)
        AuthWorkingButton("Create account", loading) {
            onRegister(user, pass, email, question, answer)
        }
        TextButton(onClick = onBack, enabled = !loading) {
            Text("Back", color = SchedulerColors.LabelMuted)
        }
    }
}

@Composable
fun ForgotPasswordScreen(
    loading: Boolean,
    error: String?,
    notice: String?,
    lastUsername: String?,
    onSend: (username: String, email: String) -> Unit,
    onReset: (token: String, answer: String, newPassword: String) -> Unit,
    onBack: () -> Unit,
) {
    var user by remember { mutableStateOf(lastUsername.orEmpty()) }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var cooldownUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldownUntil) {
        while (now < cooldownUntil) {
            kotlinx.coroutines.delay(400)
            now = System.currentTimeMillis()
        }
    }
    val remainSec = ((cooldownUntil - now) / 1000L).coerceAtLeast(0)
    AuthColumn {
        Text("Forgot password", color = SchedulerColors.Cream, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "The token lasts ${AuthRules.RESET_TOKEN_MINUTES} minutes and can be used once. " +
                "Email alone is not enough; the security answer is required. " +
                "The same Gmail can be sender and receiver — check Spam. " +
                "If mail is late, the host PC Node terminal always printed the token and the question. " +
                "Do not click send again for 60 seconds (server rate-limit; a second click does nothing).",
            color = SchedulerColors.LabelMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email on the account") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Spacer(Modifier.height(12.dp))
        AuthWorkingButton(
            label = if (remainSec > 0) "Wait ${remainSec}s" else "Send reset",
            loading = loading,
            enabled = remainSec == 0L,
        ) {
            cooldownUntil = System.currentTimeMillis() + AuthRules.FORGOT_COOLDOWN_MS
            now = System.currentTimeMillis()
            onSend(user, email)
        }
        if (notice != null) {
            Text(notice, color = SchedulerColors.Cream, modifier = Modifier.padding(top = 12.dp), fontSize = 14.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("Then type the token from email or the host Node terminal.", color = SchedulerColors.LabelMuted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Reset token") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text("Security answer") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newPass,
            onValueChange = { newPass = it },
            label = { Text("New password (min 8)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        if (error != null) {
            Text(error, color = SchedulerColors.Error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        AuthWorkingButton("Set new password", loading) {
            onReset(token, answer, newPass)
        }
        TextButton(onClick = onBack, enabled = !loading) {
            Text("Back", color = SchedulerColors.LabelMuted)
        }
    }
}

@Composable
fun SecurityChallengeDialog(
    question: String,
    loading: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var answer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        containerColor = SchedulerColors.Sheet,
        titleContentColor = SchedulerColors.Cream,
        textContentColor = SchedulerColors.Cream,
        title = { Text("Security check") },
        text = {
            Column {
                Text(question, color = SchedulerColors.Cream)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("Answer") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerOutlinedFieldColors(),
                    singleLine = true,
                )
                if (error != null) {
                    Text(error, color = SchedulerColors.Error, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(answer) }, enabled = !loading && answer.isNotBlank()) {
                Text(if (loading) "Working…" else "Confirm", color = SchedulerColors.Cream)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSchedulerScreen(
    state: SchedulerUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleMonth: () -> Unit,
    onAddTask: () -> Unit,
    onRunScheduler: () -> Unit,
    onBlocked: () -> Unit,
    onBlockedSlot: (LocalDate, Int) -> Unit,
    onLogout: () -> Unit,
    onAccount: () -> Unit,
    onTab: (MainTab) -> Unit,
    onTaskClick: (Task) -> Unit,
    onDismissTask: () -> Unit,
    onFinish: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onSaveDescription: (Task, String, Boolean) -> Unit,
    onSaveIdentity: (Task, String, Double) -> Unit,
    onSaveDeadline: (Task, LocalDate?, LocalTime?) -> Unit,
    onMovePlacement: (Task, LocalDate, LocalTime) -> Unit,
    onUnschedule: (Task) -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
    onGoToday: () -> Unit,
    onGoNow: () -> Unit,
    onRefresh: () -> Unit,
    onEmptySlot: (LocalDate, Int) -> Unit,
    onClearError: () -> Unit,
    onClearNotice: () -> Unit,
    onTypewriterChange: (com.opwait.ashoreprospect.domain.TypewriterBundle) -> Unit,
    onAttachTypewriterImage: (diaryDate: String?, pageId: String?, name: String, mime: String, bytes: ByteArray) -> Unit,
    onPreviewTypewriterImage: (sha: String) -> Unit,
    onClearImagePreview: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showUnscheduled by remember { mutableStateOf(false) }
    var showOverdue by remember { mutableStateOf(false) }
    var showJumpDate by remember { mutableStateOf(false) }
    var jumpIso by remember { mutableStateOf("") }
    var jumpError by remember { mutableStateOf<String?>(null) }
    val weekStart = AppTimeReference.currentWeekStart().plusDays(state.weekOffset * 7L)
    val weekEnd = weekStart.plusDays(6)
    val weekLabel = "${englishDayMonth(weekStart)} – ${englishDayMonth(weekEnd)}"

    LaunchedEffect(state.nowJump, state.tab, state.monthMode, state.scrollSlot, state.loading) {
        if (state.loading) return@LaunchedEffect
        if (state.monthMode || state.tab != MainTab.SCHEDULER) return@LaunchedEffect
        val slot = if (state.scrollSlot >= 0) state.scrollSlot else state.nowSlot
        delay(16)
        listState.animateScrollToItem(maxOf(0, slot - 2))
    }
    LaunchedEffect(state.error, state.notice) {
        val msg = state.error ?: state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearError()
        onClearNotice()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = SchedulerColors.Sheet,
                drawerContentColor = SchedulerColors.Cream,
            ) {
                Column(Modifier.fillMaxHeight()) {
                Text(
                    "Ashore Prospect",
                    style = MaterialTheme.typography.titleLarge,
                    color = SchedulerColors.Cream,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
                MainTab.entries.forEach { tab ->
                    NavigationDrawerItem(
                        label = {
                            Text(tab.widgetLabel(state.typewriterDirty && tab == MainTab.TYPEWRITER))
                        },
                        selected = state.tab == tab,
                        onClick = {
                            onTab(tab)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = SchedulerColors.ChipOnDark,
                            selectedTextColor = SchedulerColors.Cream,
                            unselectedTextColor = SchedulerColors.LabelMuted,
                            unselectedContainerColor = Color.Transparent,
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                NavigationDrawerItem(
                    icon = {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    },
                    label = { Text("Account") },
                    selected = false,
                    onClick = {
                        onAccount()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).padding(bottom = 16.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = SchedulerColors.ChipOnDark,
                        selectedTextColor = SchedulerColors.Cream,
                        unselectedTextColor = SchedulerColors.LabelMuted,
                        unselectedContainerColor = Color.Transparent,
                        unselectedIconColor = SchedulerColors.LabelMuted,
                        selectedIconColor = SchedulerColors.Cream,
                    ),
                )
                }
            }
        },
        scrimColor = Color.Black.copy(alpha = 0.45f),
    ) {
    Scaffold(
        containerColor = SchedulerColors.DarkChrome,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SchedulerColors.Cream,
                    contentColor = SchedulerColors.DarkChrome,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Ashore Prospect",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Tools")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SchedulerColors.DarkChrome,
                    titleContentColor = SchedulerColors.Cream,
                    actionIconContentColor = SchedulerColors.Cream,
                ),
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).size(22.dp),
                            color = SchedulerColors.Cream,
                            strokeWidth = 2.dp,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (state.hostReachable) {
                                        true -> SchedulerColors.TaskOngoing
                                        false -> SchedulerColors.TaskOverdue
                                        null -> SchedulerColors.OnDarkMuted
                                    },
                                ),
                        )
                        Text(
                            when (state.hostReachable) {
                                true -> "Online"
                                false -> "Offline"
                                null -> "Host…"
                            },
                            color = when (state.hostReachable) {
                                true -> SchedulerColors.TaskOngoing
                                false -> SchedulerColors.TaskOverdue
                                null -> SchedulerColors.OnDarkMuted
                            },
                            fontSize = 12.sp,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (state.tab == MainTab.SCHEDULER) {
                                DropdownMenuItem(
                                    text = { Text(if (state.monthMode) "Week view" else "Month view") },
                                    onClick = { menuOpen = false; onToggleMonth() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Jump to date") },
                                    onClick = {
                                        menuOpen = false
                                        jumpIso = AppTimeReference.currentColumnDate().toString()
                                        jumpError = null
                                        showJumpDate = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("View/edit blocked") },
                                    onClick = { menuOpen = false; onBlocked() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Run scheduler") },
                                    onClick = { menuOpen = false; onRunScheduler() },
                                )
                                DropdownMenuItem(
                                    text = {
                                        val n = state.tasks.count { it.isUnscheduledPending() }
                                        Text("View unscheduled ($n)")
                                    },
                                    onClick = { menuOpen = false; showUnscheduled = true },
                                )
                                DropdownMenuItem(
                                    text = {
                                        val n = state.tasks.count { TaskDue.isOverdue(it) }
                                        Text("View overdue ($n)")
                                    },
                                    onClick = { menuOpen = false; showOverdue = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = { menuOpen = false; confirmLogout = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = { },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.tab == MainTab.TYPEWRITER) {
                TypewriterScreen(
                    bundle = state.typewriter,
                    onChange = onTypewriterChange,
                    onAttachImage = onAttachTypewriterImage,
                    onPreviewImage = onPreviewTypewriterImage,
                    onShowCalendar = {
                        onTab(MainTab.SCHEDULER)
                        onGoToday()
                    },
                    contentRev = state.typewriterRev,
                )
                return@Column
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (state.monthMode) monthTitle(state.monthOffset) else weekLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = SchedulerColors.Cream,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
                TextButton(onClick = onGoNow) { Text("Jump to Now") }
            }
            state.username?.let { name ->
                val pendingTasks = state.tasks.filter { it.isUnscheduledPending() }
                val overdueTasks = state.tasks.filter { TaskDue.isOverdue(it) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Logged in as $name",
                        color = SchedulerColors.LabelMuted,
                        fontSize = 12.sp,
                    )
                    if (pendingTasks.isNotEmpty()) {
                        Text(" · ", color = SchedulerColors.LabelMuted, fontSize = 12.sp)
                        Text(
                            "${pendingTasks.size} unscheduled",
                            color = SchedulerColors.Cream,
                            fontSize = 12.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                if (pendingTasks.size == 1) {
                                    val first = pendingTasks.first()
                                    onTaskClick(first)
                                    focusTaskDay(first, onJumpToDate)
                                } else {
                                    showUnscheduled = true
                                }
                            },
                        )
                    }
                    if (overdueTasks.isNotEmpty()) {
                        Text(" · ", color = SchedulerColors.LabelMuted, fontSize = 12.sp)
                        Text(
                            "${overdueTasks.size} overdue",
                            color = SchedulerColors.TaskOverdue,
                            fontSize = 12.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                if (overdueTasks.size == 1) {
                                    val first = overdueTasks.first()
                                    onTaskClick(first)
                                    focusTaskDay(first, onJumpToDate)
                                } else {
                                    showOverdue = true
                                }
                            },
                        )
                    }
                }
            }
            UnscheduledBar(state.tasks, onTaskClick, onJumpToDate)
            if (state.tab == MainTab.SCHEDULER && state.tasks.isEmpty() && !state.loading) {
                Text(
                    "No tasks yet — tap an empty slot, +, or pull to sync.",
                    color = SchedulerColors.OnDarkMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    state.loading && state.tasks.isEmpty() && !state.monthMode -> WeekSkeleton()
                    state.monthMode -> MonthGrid(
                        monthOffset = state.monthOffset,
                        tasks = state.tasks,
                        onDayClick = onJumpToDate,
                    )
                    else -> WeekGrid(
                        weekOffset = state.weekOffset,
                        tasks = state.tasks,
                        blocked = state.blocked,
                        nowSlot = state.nowSlot,
                        listState = listState,
                        nowJump = state.nowJump,
                        focusDate = state.focusDate,
                        onTaskClick = onTaskClick,
                        onEmptySlot = onEmptySlot,
                        onBlockedSlot = onBlockedSlot,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(
                        onClick = onPrev,
                        containerColor = SchedulerColors.Cream,
                        contentColor = SchedulerColors.DarkChrome,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = if (state.monthMode) "Previous month" else "Previous week",
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = onNext,
                        containerColor = SchedulerColors.Cream,
                        contentColor = SchedulerColors.DarkChrome,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (state.monthMode) "Next month" else "Next week",
                        )
                    }
                }
                FloatingActionButton(
                    onClick = onAddTask,
                    containerColor = SchedulerColors.Cream,
                    contentColor = SchedulerColors.DarkChrome,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add task")
                }
            }
        }
    }

    if (showJumpDate) {
        val tryJump = {
            val d = runCatching { LocalDate.parse(jumpIso.trim()) }.getOrNull()
            if (d != null) {
                jumpError = null
                showJumpDate = false
                onJumpToDate(d)
            } else {
                jumpError = "Use YYYY-MM-DD"
            }
        }
        AlertDialog(
            onDismissRequest = { showJumpDate = false },
            containerColor = SchedulerColors.Paper,
            titleContentColor = SchedulerColors.PaperInk,
            textContentColor = SchedulerColors.PaperInk,
            title = { Text("Jump to date") },
            text = {
                Column {
                    OutlinedTextField(
                        jumpIso,
                        {
                            jumpIso = it
                            jumpError = null
                        },
                        label = { Text("Date YYYY-MM-DD") },
                        supportingText = {
                            val hint = englishDateHint(jumpIso)
                            when {
                                jumpError != null -> Text(jumpError!!, color = SchedulerColors.Error)
                                hint != null -> Text(hint)
                            }
                        },
                        isError = jumpError != null,
                        colors = schedulerCreamFieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { tryJump() }),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = tryJump) { Text("Jump", color = SchedulerColors.PaperInk) }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDate = false }) {
                    Text("Cancel", color = SchedulerColors.PaperInk)
                }
            },
        )
    }
    if (showUnscheduled) {
        TaskPickDialog(
            title = "Unscheduled",
            intro = "Tap a row for the task sheet. Deadline tasks also open that due day’s week.",
            empty = "Nothing waiting. Deadline and Flexible tasks land here when they have no slot.",
            tasks = state.tasks.filter { it.isUnscheduledPending() },
            onTaskClick = { task ->
                showUnscheduled = false
                onTaskClick(task)
                focusTaskDay(task, onJumpToDate)
            },
            onDismiss = { showUnscheduled = false },
        )
    }
    if (showOverdue) {
        TaskPickDialog(
            title = "Overdue",
            intro = "Deadline has passed and the task is not finished. Tap a row for the sheet and that day’s week.",
            empty = "No overdue deadlines.",
            tasks = state.tasks.filter { TaskDue.isOverdue(it) },
            onTaskClick = { task ->
                showOverdue = false
                onTaskClick(task)
                focusTaskDay(task, onJumpToDate)
            },
            onDismiss = { showOverdue = false },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = SchedulerColors.Paper,
            titleContentColor = SchedulerColors.PaperInk,
            textContentColor = SchedulerColors.PaperInk,
            title = { Text("Log out?") },
            text = { Text("Typewriter will be saved if possible, then you return to sign-in.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("Log out", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text("Stay", color = SchedulerColors.PaperInk)
                }
            },
        )
    }
    state.selectedTask?.let { task ->
        ModalBottomSheet(
            onDismissRequest = onDismissTask,
            sheetState = sheetState,
            containerColor = SchedulerColors.Sheet,
            contentColor = SchedulerColors.Cream,
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(SchedulerColors.Cream.copy(alpha = 0.35f)),
                )
            },
        ) {
            TaskInfoPanel(
                NestedTasks.mainTask(state.tasks, task),
                nested = NestedTasks.ofParent(state.tasks, NestedTasks.mainTask(state.tasks, task)),
                onFinish = onFinish,
                onDelete = onDelete,
                onSaveDescription = onSaveDescription,
                onSaveIdentity = onSaveIdentity,
                onSaveDeadline = onSaveDeadline,
                onMovePlacement = onMovePlacement,
                onUnschedule = onUnschedule,
                onShowOnGrid = { date ->
                    onDismissTask()
                    onJumpToDate(date)
                },
            )
        }
    }
    val previewBytes = state.previewImageBytes
    if (previewBytes != null) {
        val bmp = remember(previewBytes) {
            BitmapFactory.decodeByteArray(previewBytes, 0, previewBytes.size)
        }
            AlertDialog(
            onDismissRequest = onClearImagePreview,
            containerColor = SchedulerColors.Paper,
            title = { Text(state.previewImageName ?: "Image", color = SchedulerColors.PaperInk) },
            text = {
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = state.previewImageName,
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("Could not decode image", color = SchedulerColors.PaperInk)
                }
            },
            confirmButton = {
                TextButton(onClick = onClearImagePreview) { Text("Close") }
            },
        )
    }
    }
}

private val monthYearFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

private fun ordinalDay(day: Int): String {
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}

private fun englishDayMonth(date: LocalDate): String {
    val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
    return "${ordinalDay(date.dayOfMonth)} $month"
}

private fun englishWeekdayDayMonth(date: LocalDate): String {
    val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)
    return "$weekday ${englishDayMonth(date)}"
}

private fun englishDateHint(iso: String): String? =
    runCatching { LocalDate.parse(iso.trim()) }.getOrNull()?.let(::englishWeekdayDayMonth)

private fun monthTitle(monthOffset: Int): String {
    val month = AppTimeReference.currentMonth().plusMonths(monthOffset.toLong())
    return month.format(monthYearFmt)
}

@Composable
private fun WeekSkeleton() {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(SchedulerColors.Skeleton, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.height(8.dp))
        repeat(12) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .height(18.dp)
                    .background(SchedulerColors.Skeleton, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun WeekGrid(
    weekOffset: Int,
    tasks: List<Task>,
    blocked: List<BlockedTime>,
    nowSlot: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    nowJump: Int,
    focusDate: LocalDate?,
    onTaskClick: (Task) -> Unit,
    onEmptySlot: (LocalDate, Int) -> Unit,
    onBlockedSlot: (LocalDate, Int) -> Unit,
) {
    val weekStart = AppTimeReference.currentWeekStart().plusDays(weekOffset * 7L)
    val hScroll = rememberScrollState()
    val slotH = 36.dp
    val colW = 128.dp
    val days = (0 until 7).map { weekStart.plusDays(it.toLong()) }
    val todayColumn = AppTimeReference.currentColumnDate()
    val blockedPeriod = remember(blocked) {
        BlockedTimePeriod().also { it.replaceAll(blocked) }
    }
    val view = LocalView.current
    val density = LocalDensity.current
    var nowTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowTick += 1
        }
    }
    val thisWeekHasNow = days.contains(todayColumn)
    val nowFraction = remember(nowTick, nowSlot) {
        nowTick
        nowLineFraction()
    }
    LaunchedEffect(nowJump, focusDate, weekStart) {
        val date = focusDate ?: todayColumn
        val index = days.indexOf(date)
        if (index < 0) return@LaunchedEffect
        val px = with(density) { (colW * index).roundToPx() }
        hScroll.animateScrollTo(px)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(56.dp)
                    .height(28.dp)
                    .background(SchedulerColors.DarkChrome)
                    .zIndex(1f),
            )
            Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                days.forEach { columnDate ->
                    val isToday = columnDate == todayColumn
                    val isFocus = focusDate != null && columnDate == focusDate
                    Box(
                        modifier = Modifier
                            .width(colW)
                            .height(28.dp)
                            .padding(horizontal = 2.dp)
                            .background(
                                when {
                                    isToday -> SchedulerColors.SlotHighlight
                                    isFocus -> SchedulerColors.ChipOnDark
                                    columnDate.isBefore(todayColumn) -> SchedulerColors.SlotPast
                                    else -> Color.Transparent
                                },
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            englishWeekdayDayMonth(columnDate),
                            fontSize = 11.sp,
                            color = if (isToday || isFocus) SchedulerColors.Cream else SchedulerColors.LabelMuted,
                        )
                    }
                }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(AppTimeReference.SLOTS_PER_DAY) { slot ->
                val hourStart = slot % 2 == 0
                val showNowLine = thisWeekHasNow && slot == nowSlot
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeekTimeGutter(
                        slot = slot,
                        nowSlot = nowSlot,
                        slotH = slotH,
                        hourStart = hourStart,
                        nowFraction = if (showNowLine) nowFraction else null,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(slotH)
                            .nowHairline(showNowLine, nowFraction),
                    ) {
                    Row(
                        Modifier.fillMaxSize().horizontalScroll(hScroll),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    days.forEach { columnDate ->
                        val task = taskAt(tasks, columnDate, slot)
                        val slotTime = AppTimeReference.slotToLocalTime(slot)
                        val calendarDate = AppTimeReference.calendarDateForSlot(columnDate, slot)
                        val covering = blockedPeriod.covering(calendarDate, slotTime)
                        val isBlocked = covering != null
                        val prevCovering = if (slot > 0) {
                            blockedPeriod.covering(
                                AppTimeReference.calendarDateForSlot(columnDate, slot - 1),
                                AppTimeReference.slotToLocalTime(slot - 1),
                            )
                        } else {
                            null
                        }
                        val blockedLabelStart = covering != null && covering.id != prevCovering?.id
                        val sameTaskPrev = task != null && slot > 0 &&
                            taskAt(tasks, columnDate, slot - 1)?.id == task.id
                        val sameTaskNext = task != null &&
                            slot + 1 < AppTimeReference.SLOTS_PER_DAY &&
                            taskAt(tasks, columnDate, slot + 1)?.id == task.id
                        Box(
                            modifier = Modifier
                                .width(colW)
                                .height(slotH)
                                .padding(
                                    start = 1.dp,
                                    end = 1.dp,
                                    top = if (sameTaskPrev) 0.dp else 1.dp,
                                    bottom = if (sameTaskNext) 0.dp else 1.dp,
                                )
                                .then(if (task == null) Modifier.hourHairline(hourStart) else Modifier)
                                .clip(weekCellShape(sameTaskPrev, sameTaskNext))
                                .background(
                                    when {
                                        task != null -> taskChipFill(task)
                                        isBlocked -> SchedulerColors.BlockedSlot
                                        else -> emptySlotColor(columnDate, slot)
                                    },
                                )
                                .clickable {
                                    if (task != null) {
                                        onTaskClick(task)
                                    } else {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onEmptySlot(columnDate, slot)
                                    }
                                },
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (task != null && isSegmentStart(tasks, columnDate, slot, task)) {
                                val nested = NestedTasks.ofParent(tasks, task)
                                val lines = maxOf(2, minOf(4, task.durationSlots))
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(3.dp),
                                ) {
                                    Text(
                                        VaultSeal.displayTitle(task.title),
                                        fontSize = 11.sp,
                                        lineHeight = 13.sp,
                                        maxLines = if (nested.isEmpty()) lines else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = taskTitleColor(task),
                                    )
                                    nested.forEach { q ->
                                        val clock = q.placedTime
                                            ?.format(DateTimeFormatter.ofPattern("HH:mm"))
                                            ?.let { "$it  " }
                                            .orEmpty()
                                        val mins = kotlin.math.round(q.durationMinutes).toInt()
                                        Text(
                                            "$clock${VaultSeal.displayTitle(q.title)}  ·  $mins min",
                                            fontSize = 9.sp,
                                            lineHeight = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = taskTitleColor(task),
                                        )
                                    }
                                }
                            } else if (task == null && covering != null && blockedLabelStart) {
                                val opened = VaultSeal.displayTitle(covering.title).trim()
                                val name = opened.ifBlank { "blocked" }
                                Text(
                                    if (name.equals("blocked", ignoreCase = true)) name
                                    else "blocked: $name",
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = SchedulerColors.LabelMuted,
                                    modifier = Modifier.padding(3.dp),
                                )
                            }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekTimeGutter(
    slot: Int,
    nowSlot: Int,
    slotH: androidx.compose.ui.unit.Dp,
    hourStart: Boolean,
    nowFraction: Float? = null,
) {
    Box(
        Modifier
            .width(56.dp)
            .height(slotH)
            .zIndex(1f)
            .background(SchedulerColors.DarkChrome)
            .hourHairline(hourStart)
            .nowHairline(nowFraction != null, nowFraction ?: 0f),
        contentAlignment = Alignment.TopStart,
    ) {
        if (hourStart || slot == nowSlot) {
            Text(
                AppTimeReference.formatSlotLabel(slot),
                fontSize = 11.sp,
                color = if (slot == nowSlot) SchedulerColors.NowLine else SchedulerColors.Cream,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else {
            Box(
                Modifier
                    .padding(start = 18.dp, top = 2.dp)
                    .width(6.dp)
                    .height(1.dp)
                    .background(SchedulerColors.Cream.copy(alpha = 0.22f)),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    monthOffset: Int,
    tasks: List<Task>,
    onDayClick: (LocalDate) -> Unit,
) {
    val today = AppTimeReference.currentColumnDate()
    val month = AppTimeReference.currentMonth().plusMonths(monthOffset.toLong())
    val first = month.atDay(1)
    val start = AppTimeReference.getStartOfWeek(first)
    val days = (0 until 42).map { start.plusDays(it.toLong()) }
    Column(Modifier.padding(8.dp)) {
        Text(
            "${month.month.name} ${month.year}",
            style = MaterialTheme.typography.titleMedium,
            color = SchedulerColors.Cream,
        )
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = SchedulerColors.LabelMuted,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val dayTasks = MonthCellTasks.onDay(tasks, date)
                    val inMonth = date.month == month.month
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .padding(2.dp)
                            .border(1.dp, SchedulerColors.GridLine)
                            .background(
                                when {
                                    date == today -> SchedulerColors.SlotHighlight
                                    date.isBefore(today) -> SchedulerColors.SlotPast
                                    else -> Color.Transparent
                                },
                            )
                            .clickable { onDayClick(date) }
                            .padding(4.dp),
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            fontSize = 12.sp,
                            color = when {
                                !inMonth -> SchedulerColors.OnDarkMuted
                                date == today -> SchedulerColors.NowLine
                                date.isBefore(today) -> SchedulerColors.TaskPastText
                                else -> SchedulerColors.Cream
                            },
                        )
                        if (dayTasks.isNotEmpty()) {
                            Box(
                                Modifier
                                    .padding(top = 2.dp, bottom = 2.dp)
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(
                                        if (dayTasks.any { TaskDue.isOverdue(it) }) {
                                            SchedulerColors.TaskOverdue
                                        } else if (inMonth) {
                                            SchedulerColors.Cream
                                        } else {
                                            SchedulerColors.OnDarkMuted
                                        },
                                    ),
                            )
                        }
                        dayTasks.take(2).forEach { t ->
                            Text(
                                VaultSeal.displayTitle(t.title).take(8),
                                fontSize = 8.sp,
                                maxLines = 1,
                                color = when {
                                    TaskDue.isOverdue(t) -> SchedulerColors.TaskOverdue
                                    date.isBefore(today) -> SchedulerColors.TaskPastText
                                    else -> SchedulerColors.Cream
                                },
                            )
                        }
                        if (dayTasks.size > 2) {
                            Text("+${dayTasks.size - 2}", fontSize = 8.sp, color = SchedulerColors.OnDarkMuted)
                        }
                    }
                }
            }
        }
    }
}

private fun taskAt(tasks: List<Task>, columnDate: LocalDate, slot: Int): Task? {
    for (t in tasks) {
        if (t.isQuick()) continue
        val pd = t.placedDate ?: continue
        val pt = t.placedTime ?: continue
        val col = AppTimeReference.columnDateFor(pd, pt) ?: continue
        if (col != columnDate) continue
        val start = AppTimeReference.localTimeToSlot(pt)
        val end = start + t.durationSlots
        if (slot in start until end) return t
    }
    return null
}

@Composable
private fun TaskPickDialog(
    title: String,
    intro: String,
    empty: String,
    tasks: List<Task>,
    onTaskClick: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = tasks.sortedWith(
        compareByDescending<Task> { TaskDue.isOverdue(it) }
            .thenBy { it.deadlineDate ?: LocalDate.MAX }
            .thenBy { it.deadlineTime ?: LocalTime.MAX },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SchedulerColors.Paper,
        titleContentColor = SchedulerColors.PaperInk,
        textContentColor = SchedulerColors.PaperInk,
        title = { Text("$title (${rows.size})") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rows.isEmpty()) {
                    Text(empty)
                } else {
                    Text(
                        intro,
                        fontSize = 13.sp,
                    )
                    rows.forEach { task ->
                        val due = task.deadlineDate?.let { d ->
                            val t = task.deadlineTime?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
                            " · due ${englishDayMonth(d)}${if (t.isBlank()) "" else " $t"}"
                        }.orEmpty()
                        val overdue = if (TaskDue.isOverdue(task)) " · overdue" else ""
                        Text(
                            "${VaultSeal.displayTitle(task.title)} · ${task.state.apiName} · ${formatDurationHours(task.durationHours)}h$due$overdue",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (TaskDue.isOverdue(task)) SchedulerColors.TaskOverdue
                                    else SchedulerColors.DarkChrome,
                                )
                                .clickable { onTaskClick(task) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            color = SchedulerColors.Cream,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = SchedulerColors.PaperInk) }
        },
    )
}

@Composable
private fun UnscheduledBar(
    tasks: List<Task>,
    onTaskClick: (Task) -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
) {
    val unscheduled = tasks.filter { it.isUnscheduledPending() }
        .sortedWith(
            compareByDescending<Task> { TaskDue.isOverdue(it) }
                .thenBy { it.deadlineDate ?: LocalDate.MAX }
                .thenBy { it.deadlineTime ?: LocalTime.MAX },
        )
    if (unscheduled.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            "Unscheduled (${unscheduled.size})",
            style = MaterialTheme.typography.labelLarge,
            color = SchedulerColors.LabelMuted,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            unscheduled.forEach { task ->
                Text(
                    buildString {
                        append(VaultSeal.displayTitle(task.title).take(14))
                        append(" · ${task.state.apiName.take(1)} · ${task.durationHours}h")
                        task.deadlineDate?.let { append(" · ${englishDayMonth(it)}") }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (TaskDue.isOverdue(task)) SchedulerColors.TaskOverdue else SchedulerColors.Cream)
                        .clickable {
                            onTaskClick(task)
                            focusTaskDay(task, onJumpToDate)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (TaskDue.isOverdue(task)) SchedulerColors.Cream else SchedulerColors.DarkChrome,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun focusTaskDay(task: Task, onJumpToDate: (LocalDate) -> Unit) {
    val d = AppTimeReference.columnDateFor(task.placedDate, task.placedTime)
        ?: task.placedDate
        ?: task.deadlineDate
    if (d != null) onJumpToDate(d)
}

private fun taskChipColor(task: Task): Color = when {
    task.workflow == Workflow.FINISHED -> SchedulerColors.TaskFinished
    TaskDue.isOverdue(task) -> SchedulerColors.TaskOverdue
    task.lifeCycle == TaskLifeCycle.PAST -> SchedulerColors.TaskPast
    else -> SchedulerColors.TaskCurrent
}

/** One fill for the whole placed block — never per-slot past/future. */
private fun taskChipFill(task: Task): Color = taskChipColor(task)

private fun taskTitleColor(task: Task): Color = when {
    task.workflow == Workflow.FINISHED -> SchedulerColors.OnDarkMuted
    TaskDue.isOverdue(task) -> SchedulerColors.Cream
    task.lifeCycle == TaskLifeCycle.PAST -> SchedulerColors.TaskPastText
    else -> SchedulerColors.DarkChrome
}

private fun emptySlotColor(columnDate: LocalDate, slot: Int): Color {
    return if (AppTimeReference.isSlotInPast(columnDate, slot)) SchedulerColors.SlotPast
    else Color.Transparent
}

private fun weekCellShape(sameTaskPrev: Boolean, sameTaskNext: Boolean): RoundedCornerShape {
    return RoundedCornerShape(
        topStart = if (sameTaskPrev) 0.dp else 4.dp,
        topEnd = if (sameTaskPrev) 0.dp else 4.dp,
        bottomStart = if (sameTaskNext) 0.dp else 4.dp,
        bottomEnd = if (sameTaskNext) 0.dp else 4.dp,
    )
}

private fun Modifier.hourHairline(hourStart: Boolean): Modifier {
    if (!hourStart) return this
    return drawBehind {
        drawLine(
            color = SchedulerColors.Cream.copy(alpha = 0.14f),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun Modifier.nowHairline(show: Boolean, fraction: Float): Modifier {
    if (!show) return this
    return drawWithContent {
        drawContent()
        val y = size.height * fraction.coerceIn(0f, 1f)
        drawLine(
            color = SchedulerColors.NowLine,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

private fun nowLineFraction(): Float {
    val t = AppTimeReference.getCurrentTime()
    val start = AppTimeReference.slotToLocalTime(AppTimeReference.localTimeToSlot(t))
    var deltaSec = t.toSecondOfDay() - start.toSecondOfDay()
    if (deltaSec < 0) deltaSec += 24 * 3600
    val span = AppTimeReference.MINUTES_PER_SLOT * 60
    return (deltaSec.coerceIn(0, span)).toFloat() / span
}

private fun isSegmentStart(tasks: List<Task>, columnDate: LocalDate, slot: Int, task: Task): Boolean {
    val t = taskAt(tasks, columnDate, slot) ?: return false
    return t.id == task.id && (slot == 0 || taskAt(tasks, columnDate, slot - 1)?.id != task.id)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskInfoPanel(
    task: Task,
    nested: List<Task> = emptyList(),
    onFinish: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onSaveDescription: (Task, String, Boolean) -> Unit = { _, _, _ -> },
    onSaveIdentity: (Task, String, Double) -> Unit = { _, _, _ -> },
    onSaveDeadline: (Task, LocalDate?, LocalTime?) -> Unit = { _, _, _ -> },
    onMovePlacement: (Task, LocalDate, LocalTime) -> Unit = { _, _, _ -> },
    onUnschedule: (Task) -> Unit = {},
    onShowOnGrid: (LocalDate) -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnschedule by remember { mutableStateOf(false) }
    var confirmClearDesc by remember { mutableStateOf(false) }
    var pendingDeleteLine by remember { mutableStateOf<Int?>(null) }
    var flash by remember { mutableStateOf(false) }
    val view = LocalView.current
    val finished = task.workflow == Workflow.FINISHED
    val workflowColor by animateColorAsState(
        targetValue = if (finished) SchedulerColors.LabelMuted else SchedulerColors.TaskOngoing,
        animationSpec = tween(220),
        label = "workflowColor",
    )
    val finishScale by animateFloatAsState(
        targetValue = if (flash) 0.92f else 1f,
        animationSpec = tween(120),
        finishedListener = { if (flash) flash = false },
        label = "finishScale",
    )
    val lines = remember(task.id, task.description) {
        mutableStateListOf(*DescriptionChecklist.parse(task.description).toTypedArray())
    }
    var hideDescDone by remember(task.id) { mutableStateOf(false) }
    val saveDescriptionCb by rememberUpdatedState(onSaveDescription)
    val saveIdentityCb by rememberUpdatedState(onSaveIdentity)
    val saveDeadlineCb by rememberUpdatedState(onSaveDeadline)
    val movePlacementCb by rememberUpdatedState(onMovePlacement)
    val taskNow by rememberUpdatedState(task)
    val titleSealed = VaultSeal.isSealed(task.title)
    var titleDraft by remember(task.id, task.title) {
        mutableStateOf(VaultSeal.displayTitle(task.title))
    }
    val storedMins = remember(task.id, task.durationMinutes) {
        maxOf(1, kotlin.math.round(task.durationMinutes).toInt())
    }
    var durationDraft by remember(task.id, storedMins) {
        mutableStateOf(formatExactHours(storedMins / 60.0))
    }
    var minutesDraft by remember(task.id, storedMins) { mutableStateOf(storedMins.toString()) }
    var minuteMode by remember(task.id, storedMins) { mutableStateOf(storedMins % 30 != 0) }
    var endMode by remember(task.id) { mutableStateOf(false) }
    val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    var deadlineDateDraft by remember(task.id, task.deadlineDate) {
        mutableStateOf(task.deadlineDate?.toString().orEmpty())
    }
    var deadlineTimeDraft by remember(task.id, task.deadlineTime) {
        mutableStateOf(task.deadlineTime?.format(clockFmt).orEmpty())
    }
    var placedDateDraft by remember(task.id, task.placedDate) {
        mutableStateOf(task.placedDate?.toString().orEmpty())
    }
    var placedTimeDraft by remember(task.id, task.placedTime) {
        mutableStateOf(task.placedTime?.format(clockFmt).orEmpty())
    }
    var endTimeDraft by remember(task.id, task.placedTime, storedMins) {
        mutableStateOf(
            task.placedTime?.plusMinutes(storedMins.toLong())?.format(clockFmt).orEmpty(),
        )
    }
    var refactoring by remember(task.id) { mutableStateOf<TaskRefactor?>(null) }
    fun updateEndFromMinutes(mins: Int) {
        val start = parseClock(placedTimeDraft) ?: task.placedTime ?: return
        endTimeDraft = start.plusMinutes(mins.toLong()).format(clockFmt)
    }
    fun applyHours(raw: String) {
        durationDraft = raw
        raw.toDoubleOrNull()?.let { h ->
            val m = AppTimeReference.hoursToMinutes(h)
            minutesDraft = m.toString()
            if (m % 30 != 0) minuteMode = true
            updateEndFromMinutes(m)
        }
    }
    fun applyMinutes(raw: String) {
        minutesDraft = raw
        raw.toIntOrNull()?.let { m ->
            val n = m.coerceIn(1, AppTimeReference.MINUTES_PER_DAY)
            durationDraft = formatExactHours(n / 60.0)
            minuteMode = true
            updateEndFromMinutes(n)
        }
    }
    fun applyEnd(raw: String) {
        endTimeDraft = raw
        val start = parseClock(placedTimeDraft) ?: task.placedTime ?: return
        val end = parseClock(raw) ?: return
        val m = AppTimeReference.minutesBetween(start, end)
        minutesDraft = m.toString()
        durationDraft = formatExactHours(m / 60.0)
        minuteMode = m % 30 != 0
    }
    fun resolvedDurationHours(): Double {
        val start = parseClock(placedTimeDraft) ?: task.placedTime
        val mins = when {
            endMode && start != null -> {
                val end = parseClock(endTimeDraft) ?: return storedMins / 60.0
                AppTimeReference.minutesBetween(start, end)
            }
            minuteMode -> minutesDraft.toIntOrNull()?.coerceAtLeast(1) ?: storedMins
            else -> AppTimeReference.hoursToMinutes(
                durationDraft.toDoubleOrNull() ?: (storedMins / 60.0),
            )
        }
        return mins / 60.0
    }
    fun persistDescription() {
        if (VaultSeal.isSealed(taskNow.description)) return
        val encoded = DescriptionChecklist.encode(lines)
        saveDescriptionCb(taskNow, encoded, DescriptionChecklist.allDone(lines))
    }
    fun persistIdentity() {
        val hours = resolvedDurationHours()
        val title = if (VaultSeal.isSealed(taskNow.title)) taskNow.title else titleDraft
        saveIdentityCb(taskNow, title, hours)
    }
    fun persistDeadline() {
        if (!taskNow.hasDeadline()) return
        saveDeadlineCb(
            taskNow,
            runCatching { LocalDate.parse(deadlineDateDraft.trim()) }.getOrNull(),
            parseClock(deadlineTimeDraft),
        )
    }
    fun persistPlacement() {
        if (taskNow.placedDate == null || taskNow.placedTime == null) return
        val date = runCatching { LocalDate.parse(placedDateDraft.trim()) }.getOrNull() ?: return
        val time = parseClock(placedTimeDraft) ?: return
        movePlacementCb(taskNow, date, time)
    }
    DisposableEffect(task.id) {
        onDispose {
            persistIdentity()
            persistPlacement()
            persistDeadline()
            persistDescription()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(SchedulerColors.Sheet)
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .imePadding(),
    ) {
        Text(
            "Now ${AppTimeReference.getCurrentTime().format(clockFmt)}",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        RefactorLine(
            label = "Title",
            value = titleDraft.ifBlank { "—" },
            key = TaskRefactor.Title,
            editing = refactoring,
            onEdit = { refactoring = it },
            onCommit = { persistIdentity() },
        ) {
            OutlinedTextField(
                value = titleDraft,
                onValueChange = { if (!titleSealed) titleDraft = it },
                enabled = !titleSealed,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                colors = schedulerOutlinedFieldColors(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = SchedulerColors.Cream),
            )
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip(task.state.apiName)
            InfoChip(task.lifeCycle.name.lowercase().replaceFirstChar { it.titlecase() })
            if (finished) {
                InfoChip("Finished", tint = SchedulerColors.LabelMuted)
            } else {
                InfoChip("Unfinished", tint = workflowColor)
            }
            if (TaskDue.isOverdue(task)) InfoChip("Overdue", tint = SchedulerColors.TaskOverdue)
            if (!VaultSeal.isSealed(task.description) && DescriptionChecklist.allDone(lines)) {
                InfoChip("Description done", tint = SchedulerColors.LabelMuted)
            }
        }
        if (nested.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Quick events", color = SchedulerColors.LabelMuted, fontSize = 12.sp)
            nested.forEach { q ->
                val clock = q.placedTime?.format(clockFmt)?.let { "$it  " }.orEmpty()
                val mins = kotlin.math.round(q.durationMinutes).toInt()
                Text(
                    "$clock${VaultSeal.displayTitle(q.title)}  ·  $mins min",
                    color = SchedulerColors.Cream,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        RefactorLine(
            label = "Duration",
            value = "$durationDraft h · $minutesDraft min",
            key = TaskRefactor.Duration,
            editing = refactoring,
            onEdit = { refactoring = it },
            onCommit = { persistIdentity() },
        ) {
            Text(
                "Duration (hours / optional minutes):",
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = durationDraft,
                onValueChange = { applyHours(it) },
                label = { Text("Hours") },
                supportingText = {
                    Text("1.3 hours is 78 min. Grid occupancy still rounds to 30 min.")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = schedulerOutlinedFieldColors(),
                singleLine = true,
                enabled = !endMode,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    minuteMode,
                    {
                        minuteMode = it
                        if (it) applyHours(durationDraft)
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SchedulerColors.Cream,
                        uncheckedColor = SchedulerColors.LabelMuted,
                        checkmarkColor = SchedulerColors.DarkChrome,
                    ),
                )
                Text("Minute duration", color = SchedulerColors.Cream, fontSize = 13.sp)
            }
            if (minuteMode) {
                OutlinedTextField(
                    value = minutesDraft,
                    onValueChange = { applyMinutes(it) },
                    label = { Text("Minutes") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerOutlinedFieldColors(),
                    singleLine = true,
                    enabled = !endMode,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { persistIdentity() }),
                )
            }
            if (task.placedTime != null) {
                Text(
                    "Or set an end time instead of duration:",
                    color = SchedulerColors.LabelMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        endMode,
                        {
                            endMode = it
                            if (it) {
                                val m = minutesDraft.toIntOrNull() ?: storedMins
                                updateEndFromMinutes(m)
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = SchedulerColors.Cream,
                            uncheckedColor = SchedulerColors.LabelMuted,
                            checkmarkColor = SchedulerColors.DarkChrome,
                        ),
                    )
                    Text("End time", color = SchedulerColors.Cream, fontSize = 13.sp)
                }
                if (endMode) {
                    OutlinedTextField(
                        value = endTimeDraft,
                        onValueChange = { applyEnd(it) },
                        label = { Text("End HH:mm") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = schedulerOutlinedFieldColors(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { persistIdentity() }),
                    )
                }
            }
        }
        if (task.placedDate != null && task.placedTime != null) {
            RefactorLine(
                label = "Stored time",
                value = "${placedDateDraft} ${placedTimeDraft}".trim(),
                key = TaskRefactor.Placed,
                editing = refactoring,
                onEdit = { refactoring = it },
                onCommit = { persistPlacement() },
            ) {
                OutlinedTextField(
                    value = placedDateDraft,
                    onValueChange = { placedDateDraft = it },
                    label = { Text("Placed date YYYY-MM-DD") },
                    supportingText = {
                        englishDateHint(placedDateDraft)?.let { Text(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerOutlinedFieldColors(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = placedTimeDraft,
                    onValueChange = {
                        placedTimeDraft = it
                        val m = minutesDraft.toIntOrNull() ?: storedMins
                        parseClock(it)?.let { start ->
                            endTimeDraft = start.plusMinutes(m.toLong()).format(clockFmt)
                        }
                    },
                    label = { Text("Placed time HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerOutlinedFieldColors(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { persistPlacement() }),
                )
            }
        }
        if (task.hasDeadline()) {
            RefactorLine(
                label = "Deadline",
                value = "${deadlineDateDraft} ${deadlineTimeDraft}".trim(),
                key = TaskRefactor.Deadline,
                editing = refactoring,
                onEdit = { refactoring = it },
                onCommit = { persistDeadline() },
            ) {
                OutlinedTextField(
                    value = deadlineDateDraft,
                    onValueChange = { deadlineDateDraft = it },
                    label = { Text("Deadline date YYYY-MM-DD") },
                    supportingText = {
                        englishDateHint(deadlineDateDraft)?.let { Text(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerOutlinedFieldColors(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = deadlineTimeDraft,
                    onValueChange = { deadlineTimeDraft = it },
                    label = { Text("Deadline time HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerOutlinedFieldColors(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { persistDeadline() }),
                )
            }
        }
        val jumpDate = AppTimeReference.columnDateFor(task.placedDate, task.placedTime)
            ?: task.placedDate
            ?: task.deadlineDate
        if (jumpDate != null) {
            val placed = task.placedDate != null && task.placedTime != null
            TextButton(onClick = { onShowOnGrid(jumpDate) }) {
                Text(
                    if (placed) "Show on week grid" else "Show due day",
                    color = SchedulerColors.LabelMuted,
                )
            }
        }
        val canUnschedule = !task.isFixedTime() &&
            task.placedDate != null &&
            task.placedTime != null
        if (canUnschedule) {
            TextButton(onClick = { confirmUnschedule = true }) {
                Text("Unschedule", color = SchedulerColors.LabelMuted)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!VaultSeal.isSealed(task.description)) {
        Text("Description", color = SchedulerColors.LabelMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        val descDone = lines.count { it.done }
        if (descDone > 0) {
            Row {
                TextButton(onClick = { hideDescDone = !hideDescDone }) {
                    Text(
                        if (hideDescDone) "Show completed ($descDone)" else "Hide completed",
                        color = SchedulerColors.LabelMuted,
                    )
                }
                TextButton(onClick = { confirmClearDesc = true }) {
                    Text("Clear completed", color = SchedulerColors.Error)
                }
            }
        }
        val shownDesc = lines.withIndex().filter { !hideDescDone || !it.value.done }
        if (lines.isNotEmpty() && shownDesc.isEmpty()) {
            Text(
                "$descDone completed hidden.",
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        shownDesc.forEach { (index, line) ->
            val shownAt = shownDesc.indexOfFirst { it.index == index }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(
                    checked = line.done,
                    onCheckedChange = { checked ->
                        lines[index] = lines[index].copy(done = checked)
                        persistDescription()
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SchedulerColors.Cream,
                        uncheckedColor = SchedulerColors.LabelMuted,
                        checkmarkColor = SchedulerColors.DarkChrome,
                    ),
                )
                OutlinedTextField(
                    value = line.text,
                    onValueChange = { text -> lines[index] = lines[index].copy(text = text) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = schedulerOutlinedFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = SchedulerColors.Cream,
                        textDecoration = if (line.done) TextDecoration.LineThrough else TextDecoration.None,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { persistDescription() }),
                )
                Column {
                    if (shownAt > 0) {
                        TextButton(onClick = {
                            val other = shownDesc[shownAt - 1].index
                            val tmp = lines[index]
                            lines[index] = lines[other]
                            lines[other] = tmp
                            persistDescription()
                        }) { Text("↑", color = SchedulerColors.LabelMuted) }
                    }
                    if (shownAt >= 0 && shownAt < shownDesc.lastIndex) {
                        TextButton(onClick = {
                            val other = shownDesc[shownAt + 1].index
                            val tmp = lines[index]
                            lines[index] = lines[other]
                            lines[other] = tmp
                            persistDescription()
                        }) { Text("↓", color = SchedulerColors.LabelMuted) }
                    }
                }
                TextButton(
                    onClick = {
                        if (line.text.isBlank()) {
                            lines.removeAt(index)
                            persistDescription()
                        } else {
                            pendingDeleteLine = index
                        }
                    },
                ) { Text("×", color = SchedulerColors.Error) }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { lines.add(DescriptionChecklist.Line(false, "")) },
            ) {
                Text("Add item", color = SchedulerColors.LabelMuted)
            }
            TextButton(onClick = { persistDescription() }) {
                Text("Save description", color = SchedulerColors.Cream)
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        val canFinish = task.placedDate != null && task.placedTime != null
        if (!canFinish) {
            Text(
                "Finish is for placed tasks only.",
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    flash = true
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onFinish(task)
                },
                enabled = canFinish,
                modifier = Modifier.scale(finishScale).weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (finished) SchedulerColors.ChipOnDark else SchedulerColors.Cream,
                    contentColor = if (finished) SchedulerColors.Cream else SchedulerColors.DarkChrome,
                    disabledContainerColor = SchedulerColors.ChipOnDark,
                    disabledContentColor = SchedulerColors.LabelMuted,
                ),
            ) {
                Text(if (finished) "Unfinish" else "Finish")
            }
            Button(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = SchedulerColors.Error,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SchedulerColors.Error.copy(alpha = 0.7f)),
            ) {
                Text("Delete")
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    if (confirmClearDesc) {
        val n = lines.count { it.done }
        AlertDialog(
            onDismissRequest = { confirmClearDesc = false },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Clear completed?") },
            text = {
                Text("This removes $n checked description item${if (n == 1) "" else "s"}.")
            },
            confirmButton = {
                TextButton(onClick = {
                    lines.removeAll { it.done }
                    persistDescription()
                    confirmClearDesc = false
                }) { Text("Clear", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDesc = false }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
    pendingDeleteLine?.let { index ->
        val line = lines.getOrNull(index)
        AlertDialog(
            onDismissRequest = { pendingDeleteLine = null },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Delete description item?") },
            text = {
                Text("This removes \"${line?.text.orEmpty()}\".")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (index in lines.indices) {
                        lines.removeAt(index)
                        persistDescription()
                    }
                    pendingDeleteLine = null
                }) { Text("Delete", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLine = null }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
    if (confirmUnschedule) {
        AlertDialog(
            onDismissRequest = { confirmUnschedule = false },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Unschedule task?") },
            text = {
                Text("Takes it off the week grid. It stays in Unscheduled until you tap Run scheduler or place it again. Fixed tasks cannot unschedule.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnschedule = false
                    onUnschedule(task)
                }) { Text("Unschedule", color = SchedulerColors.Cream) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnschedule = false }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Delete task?") },
            text = { Text("This removes \"${VaultSeal.displayTitle(task.title, fallback = "this task")}\" from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(task)
                }) { Text("Delete", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
}

@Composable
private fun InfoChip(label: String, tint: Color = SchedulerColors.Cream) {
    Text(
        label,
        fontSize = 11.sp,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SchedulerColors.ChipOnDark)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun parseClock(raw: String): LocalTime? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    return runCatching {
        LocalTime.parse(if (t.length == 5) "$t:00" else t)
    }.getOrNull()
}

private fun formatDurationHours(hours: Double): String = formatExactHours(hours)

private fun formatExactHours(hours: Double): String {
    val mins = AppTimeReference.hoursToMinutes(hours)
    if (mins % 60 == 0) return (mins / 60).toString()
    return (mins / 60.0).toString().trimEnd('0').trimEnd('.')
}

private enum class TaskRefactor { Title, Duration, Placed, Deadline }

@Composable
private fun RefactorLine(
    label: String,
    value: String,
    key: TaskRefactor,
    editing: TaskRefactor?,
    onEdit: (TaskRefactor?) -> Unit,
    onCommit: () -> Unit,
    field: @Composable () -> Unit,
) {
    if (editing == key) {
        field()
        TextButton(onClick = {
            onCommit()
            onEdit(null)
        }) { Text("Done", color = SchedulerColors.Cream) }
    } else {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                InfoFact(label, value)
            }
            TextButton(onClick = { onEdit(key) }) {
                Text("Refactor", color = SchedulerColors.LabelMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoFact(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = SchedulerColors.LabelMuted, fontSize = 13.sp)
        Text(value, color = SchedulerColors.Cream, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTaskDialog(
    prefill: AddTaskPrefill? = null,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        durationHours: Double,
        state: TaskStateKind,
        startDate: LocalDate?,
        startTime: LocalTime?,
        deadlineDate: LocalDate?,
        deadlineTime: LocalTime?,
        placeDate: LocalDate?,
        placeTime: LocalTime?,
        description: String,
        descriptionDone: Boolean,
    ) -> Unit,
) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val nowClock = AppTimeReference.getCurrentTime().withSecond(0).withNano(0)
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var descriptionDone by remember { mutableStateOf(false) }
    val seedHours = prefill?.durationHours?.takeIf { it > 0 }
    val seedMins = seedHours?.let { AppTimeReference.hoursToMinutes(it) }
    var duration by remember {
        mutableStateOf(seedHours?.let { formatExactHours(it) } ?: "1")
    }
    var minuteMode by remember {
        mutableStateOf(seedMins != null && seedMins % 30 != 0)
    }
    var minutes by remember {
        mutableStateOf(seedMins?.toString() ?: "60")
    }
    var endMode by remember { mutableStateOf(false) }
    var endTime by remember { mutableStateOf("10:00") }
    var stateIdx by remember {
        mutableStateOf(prefill?.state?.ordinal ?: TaskStateKind.FIXED.ordinal)
    }
    var startDate by remember {
        mutableStateOf(
            (prefill?.date ?: AppTimeReference.nextOpenDateFor(nowClock)).toString(),
        )
    }
    var startTime by remember {
        mutableStateOf((prefill?.time ?: nowClock).format(timeFmt))
    }
    var deadlineDate by remember {
        mutableStateOf(AppTimeReference.nextOpenDateFor(LocalTime.of(17, 0)).toString())
    }
    var deadlineTime by remember { mutableStateOf("17:00") }

    fun syncEndFromDuration() {
        val start = parseClock(startTime) ?: return
        val mins = if (minuteMode) {
            minutes.toIntOrNull() ?: 60
        } else {
            AppTimeReference.hoursToMinutes(duration.toDoubleOrNull() ?: 1.0)
        }
        val end = start.plusMinutes(mins.toLong())
        endTime = end.format(timeFmt)
    }

    val creamFields = schedulerCreamFieldColors()
    val checkColors = CheckboxDefaults.colors(
        checkedColor = SchedulerColors.PaperInk,
        uncheckedColor = SchedulerColors.PaperInk,
        checkmarkColor = SchedulerColors.Paper,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SchedulerColors.Paper,
        titleContentColor = SchedulerColors.PaperInk,
        textContentColor = SchedulerColors.PaperInk,
        title = {
            Text(
                if (prefill != null) {
                    "New Task @ ${prefill.date} ${prefill.time.format(timeFmt)}"
                } else {
                    "Add task"
                },
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("Task name") },
                    colors = creamFields,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("what to do exactly") },
                    colors = creamFields,
                    minLines = 3,
                    maxLines = 6,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(descriptionDone, { descriptionDone = it }, colors = checkColors)
                    Text("Description done", color = SchedulerColors.PaperInk, fontSize = 13.sp)
                }
                Text("Duration (hours / optional minutes):", color = SchedulerColors.PaperInk, fontSize = 13.sp)
                OutlinedTextField(
                    duration,
                    {
                        duration = it
                        it.toDoubleOrNull()?.let { h ->
                            val m = AppTimeReference.hoursToMinutes(h)
                            minutes = m.toString()
                            if (m % 30 != 0) minuteMode = true
                            if (endMode) syncEndFromDuration()
                        }
                    },
                    label = { Text("Hours") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = creamFields,
                    singleLine = true,
                    enabled = !endMode,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        minuteMode,
                        {
                            minuteMode = it
                            if (it) {
                                duration.toDoubleOrNull()?.let { h ->
                                    minutes = AppTimeReference.hoursToMinutes(h).toString()
                                }
                            }
                        },
                        colors = checkColors,
                    )
                    Text("Minute duration", color = SchedulerColors.PaperInk, fontSize = 13.sp)
                }
                if (minuteMode) {
                    OutlinedTextField(
                        minutes,
                        {
                            minutes = it
                            it.toIntOrNull()?.let { m ->
                                duration = formatExactHours(m / 60.0)
                                minuteMode = true
                                if (endMode) syncEndFromDuration()
                            }
                        },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = creamFields,
                        singleLine = true,
                        enabled = !endMode,
                    )
                }
                Text("Or set an end time instead of duration:", color = SchedulerColors.PaperInk, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        endMode,
                        {
                            endMode = it
                            if (it) syncEndFromDuration()
                        },
                        colors = checkColors,
                    )
                    Text("End time", color = SchedulerColors.PaperInk, fontSize = 13.sp)
                }
                if (endMode) {
                    OutlinedTextField(
                        endTime,
                        { raw ->
                            endTime = raw
                            val start = parseClock(startTime) ?: return@OutlinedTextField
                            val end = parseClock(raw) ?: return@OutlinedTextField
                            val m = AppTimeReference.minutesBetween(start, end)
                            minutes = m.toString()
                            duration = formatExactHours(m / 60.0)
                            minuteMode = m % 30 != 0
                        },
                        label = { Text("End HH:mm") },
                        colors = creamFields,
                        singleLine = true,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskStateKind.entries.forEachIndexed { i, kind ->
                        FilterChip(
                            selected = stateIdx == i,
                            onClick = { stateIdx = i },
                            label = { Text(kind.apiName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SchedulerColors.PaperInk,
                                selectedLabelColor = SchedulerColors.Paper,
                                containerColor = SchedulerColors.Paper,
                                labelColor = SchedulerColors.PaperInk,
                            ),
                        )
                    }
                }
                val kind = TaskStateKind.entries[stateIdx]
                val showClock = prefill != null || kind == TaskStateKind.FIXED || endMode
                if (showClock) {
                    OutlinedTextField(
                        startDate,
                        { startDate = it },
                        label = { Text("Date YYYY-MM-DD") },
                        supportingText = {
                            englishDateHint(startDate)?.let { Text(it) }
                        },
                        colors = creamFields,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        startTime,
                        {
                            startTime = it
                            if (endMode) syncEndFromDuration()
                        },
                        label = { Text("Start HH:mm") },
                        supportingText = {
                            Text("Typed clock is stored. Grid occupancy still uses 30-minute cells.")
                        },
                        colors = creamFields,
                        singleLine = true,
                    )
                }
                if (kind == TaskStateKind.DEADLINE) {
                    OutlinedTextField(
                        deadlineDate,
                        { deadlineDate = it },
                        label = { Text("Deadline date") },
                        supportingText = {
                            englishDateHint(deadlineDate)?.let { Text(it) }
                        },
                        colors = creamFields,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        deadlineTime,
                        { deadlineTime = it },
                        label = { Text("Deadline time") },
                        colors = creamFields,
                        singleLine = true,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SchedulerColors.ChipOnDark,
                            contentColor = SchedulerColors.Cream,
                        ),
                    ) { Text("Cancel") }
                    Button(
                        onClick = {
                            val kindNow = TaskStateKind.entries[stateIdx]
                            val typedDate = runCatching { LocalDate.parse(startDate) }.getOrNull()
                            val typedTime = parseClock(startTime)
                            val mins = when {
                                endMode -> {
                                    val start = typedTime ?: return@Button
                                    val end = parseClock(endTime) ?: return@Button
                                    AppTimeReference.minutesBetween(start, end)
                                }
                                minuteMode -> minutes.toIntOrNull()?.coerceAtLeast(1) ?: return@Button
                                else -> {
                                    val h = duration.toDoubleOrNull() ?: return@Button
                                    AppTimeReference.hoursToMinutes(h)
                                }
                            }
                            val dur = mins / 60.0
                            onSave(
                                title,
                                dur,
                                kindNow,
                                if (kindNow == TaskStateKind.FIXED) typedDate else null,
                                if (kindNow == TaskStateKind.FIXED) typedTime else null,
                                if (kindNow == TaskStateKind.DEADLINE) {
                                    runCatching { LocalDate.parse(deadlineDate) }.getOrNull()
                                } else {
                                    null
                                },
                                if (kindNow == TaskStateKind.DEADLINE) {
                                    parseClock(deadlineTime)
                                } else {
                                    null
                                },
                                if (prefill != null) typedDate ?: prefill.date else null,
                                if (prefill != null) typedTime ?: prefill.time else null,
                                description,
                                descriptionDone,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = schedulerPrimaryButtonColors(),
                    ) { Text("Save Task") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

@Composable
fun SlotPlaceDialog(
    prefill: AddTaskPrefill,
    unscheduled: List<Task>,
    onAddNew: () -> Unit,
    onPlace: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    val whenLabel = "${englishDayMonth(prefill.date)} ${prefill.time.format(timeFmt)}"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SchedulerColors.Paper,
        titleContentColor = SchedulerColors.PaperInk,
        textContentColor = SchedulerColors.PaperInk,
        title = { Text("Place at $whenLabel") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Add a new task here, or put an unscheduled one on this slot.",
                    color = SchedulerColors.PaperInk,
                    fontSize = 13.sp,
                )
                Button(
                    onClick = onAddNew,
                    modifier = Modifier.fillMaxWidth(),
                    colors = schedulerPrimaryButtonColors(),
                ) { Text("Add new task") }
                if (unscheduled.isNotEmpty()) {
                    Text(
                        "Unscheduled",
                        color = SchedulerColors.PaperInk,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    unscheduled.sortedWith(
                        compareByDescending<Task> { TaskDue.isOverdue(it) }
                            .thenBy { it.deadlineDate ?: LocalDate.MAX }
                            .thenBy { it.deadlineTime ?: LocalTime.MAX },
                    ).forEach { task ->
                        val due = task.deadlineDate?.let { d ->
                            val t = task.deadlineTime?.format(timeFmt).orEmpty()
                            " · due ${englishDayMonth(d)}${if (t.isBlank()) "" else " $t"}"
                        }.orEmpty()
                        val overdue = if (TaskDue.isOverdue(task)) " · overdue" else ""
                        Text(
                            "${VaultSeal.displayTitle(task.title)} · ${task.state.apiName} · ${formatDurationHours(task.durationHours)}h$due$overdue",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (TaskDue.isOverdue(task)) SchedulerColors.TaskOverdue
                                    else SchedulerColors.PaperInk,
                                )
                                .clickable { onPlace(task) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            color = SchedulerColors.Paper,
                            fontSize = 13.sp,
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = SchedulerColors.PaperInk)
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
