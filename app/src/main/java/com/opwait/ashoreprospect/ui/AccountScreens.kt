package com.opwait.ashoreprospect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opwait.ashoreprospect.data.ReminderPrefs
import com.opwait.ashoreprospect.domain.AuthRules
import com.opwait.ashoreprospect.data.SessionStore
import com.opwait.ashoreprospect.data.remote.MeResponse
import com.opwait.ashoreprospect.data.remote.SessionDto
import com.opwait.ashoreprospect.ui.theme.AppThemePack
import com.opwait.ashoreprospect.ui.theme.AshoreThemeSwitch
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import com.opwait.ashoreprospect.ui.theme.schedulerOutlinedFieldColors
import com.opwait.ashoreprospect.ui.theme.schedulerPrimaryButtonColors
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

private enum class AccountSection { User, Security, Device, Notifications }

@Composable
private fun AccountSwipePane(
    onPrev: () -> Unit,
    onNext: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(onPrev, onNext) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        when {
                            drag < -56f -> onNext?.invoke()
                            drag > 56f -> onPrev()
                        }
                        drag = 0f
                    },
                )
            },
    ) { content() }
}

@Composable
fun AccountScreen(
    account: MeResponse?,
    loading: Boolean,
    error: String?,
    notice: String?,
    reminderPrefs: ReminderPrefs,
    randomChallenge: Boolean,
    sessions: List<SessionDto> = emptyList(),
    onSaveUsername: (String) -> Unit,
    onSaveEmail: (String) -> Unit,
    onSaveSecurity: (question: String, answer: String) -> Unit,
    onChangePassword: (old: String, new: String) -> Unit,
    onRandomChallenge: (Boolean) -> Unit,
    onSaveReminders: (ReminderPrefs) -> Unit,
    onLoadSessions: () -> Unit = {},
    onRevokeSession: (String) -> Unit = {},
    onRevokeOthers: () -> Unit = {},
    onDeleteAccount: (String) -> Unit = {},
    onClearCache: () -> Unit = {},
    deviceLabel: String = "",
    serverUrl: String = "",
    hostReachable: Boolean? = null,
    onToggleTheme: () -> Unit = {},
    onBack: () -> Unit,
) {
    var section by remember { mutableStateOf<AccountSection?>(null) }
    when (section) {
        null -> AccountSwipePane(
            onPrev = onBack,
            onNext = { section = AccountSection.User },
        ) {
            AccountHub(
                account = account,
                loading = loading,
                error = error,
                notice = notice,
                onOpen = { section = it },
                onClearCache = onClearCache,
                onToggleTheme = onToggleTheme,
                onBack = onBack,
            )
        }
        AccountSection.User -> AccountSwipePane(
            onPrev = { section = null },
            onNext = { section = AccountSection.Security },
        ) {
            AccountUserSub(
                account = account,
                loading = loading,
                error = error,
                notice = notice,
                deviceLabel = deviceLabel,
                serverUrl = serverUrl,
                onSaveUsername = onSaveUsername,
                onSaveEmail = onSaveEmail,
                onBack = { section = null },
            )
        }
        AccountSection.Security -> AccountSwipePane(
            onPrev = { section = AccountSection.User },
            onNext = { section = AccountSection.Device },
        ) {
            AccountSecurityPage(
                account = account,
                loading = loading,
                error = error,
                notice = notice,
                randomChallenge = randomChallenge,
                sessions = sessions,
                onSaveSecurity = onSaveSecurity,
                onChangePassword = onChangePassword,
                onRandomChallenge = onRandomChallenge,
                onLoadSessions = onLoadSessions,
                onRevokeSession = onRevokeSession,
                onRevokeOthers = onRevokeOthers,
                onDeleteAccount = onDeleteAccount,
                onBack = { section = null },
            )
        }
        AccountSection.Device -> AccountSwipePane(
            onPrev = { section = AccountSection.Security },
            onNext = { section = AccountSection.Notifications },
        ) {
            AccountDevicePage(
                hostReachable = hostReachable,
                onToggleTheme = onToggleTheme,
                onBack = { section = null },
            )
        }
        AccountSection.Notifications -> AccountSwipePane(
            onPrev = { section = AccountSection.Device },
            onNext = null,
        ) {
            AccountNotificationsSub(
                prefs = reminderPrefs,
                onSave = onSaveReminders,
                onBack = { section = null },
            )
        }
    }
}

@Composable
private fun AccountHub(
    account: MeResponse?,
    loading: Boolean,
    error: String?,
    notice: String?,
    onOpen: (AccountSection) -> Unit,
    onClearCache: () -> Unit,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
) {
    AccountChrome(title = "Account", onBack = onBack) {
        Text(
            "Ashore Prospect ${com.opwait.ashoreprospect.AppVersion.LABEL}",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            account?.username ?: "…",
            color = SchedulerColors.Cream,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            buildString {
                val mail = account?.email?.trim()
                if (!mail.isNullOrEmpty()) append(mail) else append("No recovery email")
                append(" · ")
                append(if (account?.hasSecurity == true) "Security question set" else "No security question")
            },
            color = SchedulerColors.LabelMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        if (error != null) {
            Text(error, color = SchedulerColors.Error, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (notice != null) {
            Text(notice, color = SchedulerColors.Cream, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (loading && account == null) {
            CircularProgressIndicator(
                color = SchedulerColors.Cream,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        DarkThemeRow(onToggleTheme)
        Spacer(Modifier.height(8.dp))
        AccountHubRow("User info", "Username, recovery email") { onOpen(AccountSection.User) }
        AccountHubRow("Security", "Password, sessions, delete account") { onOpen(AccountSection.Security) }
        AccountHubRow("This phone", "Host online check") { onOpen(AccountSection.Device) }
        AccountHubRow("Notifications", "Lead times and sound on this phone") { onOpen(AccountSection.Notifications) }
        Spacer(Modifier.height(24.dp))
        var confirmClear by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmClear = true }) {
            Text("Clear this phone’s cache", color = SchedulerColors.LabelMuted)
        }
        Text(
            "Deletes the offline copy on this phone, then reloads from the server. You stay signed in. SMTP is not on Android.",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
        )
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                containerColor = SchedulerColors.Sheet,
                titleContentColor = SchedulerColors.Cream,
                textContentColor = SchedulerColors.LabelMuted,
                title = { Text("Clear this phone’s cache?") },
                text = {
                    Text("Local tasks, blocked times, and Typewriter on this phone are removed, then filled again from the server.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        onClearCache()
                    }) { Text("Clear", color = SchedulerColors.Error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text("Cancel", color = SchedulerColors.LabelMuted)
                    }
                },
            )
        }
    }
}

@Composable
private fun AccountDevicePage(
    hostReachable: Boolean?,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
) {
    AccountChrome(title = "This phone", onBack = onBack) {
        Text("Host", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        Text(
            when (hostReachable) {
                true -> "Online — auto-sync every 4 seconds while signed in."
                false -> "Offline — this phone uses its local copy. Auto-sync retries on its own."
                null -> "Checking whether the host answers…"
            },
            color = when (hostReachable) {
                true -> SchedulerColors.TaskOngoing
                false -> SchedulerColors.TaskOverdue
                null -> SchedulerColors.LabelMuted
            },
            fontSize = 13.sp,
        )
        Text(
            "The Online / Offline mark in the top bar is a ping. This phone pulls on its own; there is no Sync button.",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Remember me keeps this phone signed in after you swipe it out of memory. Local cache stays so the week still opens offline.",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
        DarkThemeRow(onToggleTheme)
    }
}

@Composable
private fun DarkThemeRow(onToggleTheme: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Dark theme", color = SchedulerColors.Cream, fontSize = 13.sp)
            Text(
                if (AppThemePack.isDark) "On" else "Off — cream paper like desktop Light",
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
            )
        }
        AshoreThemeSwitch(dark = AppThemePack.isDark, onToggle = onToggleTheme)
    }
}

@Composable
private fun AccountExpandSection(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SchedulerColors.ChipOnDark)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = SchedulerColors.LabelMuted, fontSize = 12.sp)
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = SchedulerColors.LabelMuted,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun AccountHubRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SchedulerColors.ChipOnDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = SchedulerColors.LabelMuted, fontSize = 12.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = SchedulerColors.LabelMuted,
        )
    }
}

@Composable
private fun AccountUserSub(
    account: MeResponse?,
    loading: Boolean,
    error: String?,
    notice: String?,
    deviceLabel: String,
    serverUrl: String,
    onSaveUsername: (String) -> Unit,
    onSaveEmail: (String) -> Unit,
    onBack: () -> Unit,
) {
    var user by remember(account?.username) { mutableStateOf(account?.username.orEmpty()) }
    var email by remember(account?.email) { mutableStateOf(account?.email.orEmpty()) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AccountChrome(title = "User info", onBack = onBack) {
        AccountFieldMessages(error, notice)
        Text("Signed in as ${account?.username ?: "…"}", color = SchedulerColors.Cream)
        Spacer(Modifier.height(12.dp))
        Text("Device", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        Text(
            deviceLabel.ifBlank { "This phone" },
            color = SchedulerColors.LabelMuted,
            fontSize = 13.sp,
        )
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(deviceLabel.ifBlank { "This phone" }))
                copied = true
            },
        ) {
            Text(if (copied) "Copied" else "Copy", color = SchedulerColors.Cream)
        }
        if (serverUrl.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("Server", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
            Text(serverUrl, color = SchedulerColors.LabelMuted, fontSize = 13.sp)
            Text(
                "Emulator uses 10.0.2.2 for this PC. Change the URL on the sign-in server screen.",
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("Change username", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        Text("This is the name you type at login.", color = SchedulerColors.LabelMuted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        AccountWorkingButton("Save username", loading) { onSaveUsername(user) }
        Spacer(Modifier.height(20.dp))
        Text("Change email", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        Text("Required. Forgot password will not work without a real email.", color = SchedulerColors.LabelMuted, fontSize = 12.sp)
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
        AccountWorkingButton("Save email", loading) { onSaveEmail(email) }
    }
}

@Composable
private fun AccountSecurityPage(
    account: MeResponse?,
    loading: Boolean,
    error: String?,
    notice: String?,
    randomChallenge: Boolean,
    sessions: List<SessionDto>,
    onSaveSecurity: (question: String, answer: String) -> Unit,
    onChangePassword: (old: String, new: String) -> Unit,
    onRandomChallenge: (Boolean) -> Unit,
    onLoadSessions: () -> Unit,
    onRevokeSession: (String) -> Unit,
    onRevokeOthers: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onBack: () -> Unit,
) {
    var question by remember(account?.securityQuestion) { mutableStateOf(account?.securityQuestion.orEmpty()) }
    var answer by remember { mutableStateOf("") }
    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newPass2 by remember { mutableStateOf("") }
    var deletePass by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var passOpen by remember { mutableStateOf(false) }
    var questionOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onLoadSessions() }
    AccountChrome(title = "Security", onBack = onBack) {
        AccountFieldMessages(error, notice)
        Text("Open a row to edit it. SMTP is host-only — not on this phone.", color = SchedulerColors.LabelMuted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        AccountExpandSection(
            title = "Change password",
            subtitle = if (passOpen) "Other devices sign out. This phone stays signed in." else "Tap to change",
            expanded = passOpen,
            onToggle = { passOpen = !passOpen },
        ) {
            OutlinedTextField(
                value = oldPass,
                onValueChange = { oldPass = it },
                label = { Text("Current password") },
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
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newPass2,
                onValueChange = { newPass2 = it },
                label = { Text("Repeat new password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = schedulerOutlinedFieldColors(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            AccountWorkingButton("Change password", loading, enabled = newPass == newPass2) {
                onChangePassword(oldPass, newPass)
            }
        }
        Spacer(Modifier.height(12.dp))
        AccountExpandSection(
            title = "Security question",
            subtitle = if (questionOpen) "The answer is not stored on this phone." else "Tap to edit",
            expanded = questionOpen,
            onToggle = { questionOpen = !questionOpen },
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Question (min 8 characters)") },
                modifier = Modifier.fillMaxWidth(),
                colors = schedulerOutlinedFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("New answer (min 4 characters)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = schedulerOutlinedFieldColors(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            AccountWorkingButton("Save security question", loading) { onSaveSecurity(question, answer) }
        }
        Spacer(Modifier.height(20.dp))
        Text("Login check", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        AccountCheckRow(
            checked = randomChallenge,
            label = "Random security check on Remember-me login",
            onChecked = onRandomChallenge,
        )
        Text(
            "Off by default (same as desktop). When on, about 18% of remember-me launches ask the question. Wrong answer wipes this phone’s cache only.",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))
        Text("Sessions", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        Text(
            "Revoke signs that device out. A 401 from revoke is not account deletion.",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text("No sessions loaded.", color = SchedulerColors.LabelMuted, fontSize = 13.sp)
        }
        sessions.forEach { row ->
            val sid = row.id.orEmpty()
            val here = row.current == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SchedulerColors.ChipOnDark)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (here) "This phone" else (row.device ?: "Unknown device"),
                        color = SchedulerColors.Cream,
                    )
                    if (here && !row.device.isNullOrBlank()) {
                        Text(row.device, color = SchedulerColors.LabelMuted, fontSize = 12.sp)
                    }
                    Text(
                        "Last seen ${shortInstant(row.lastSeenAt)}",
                        color = SchedulerColors.LabelMuted,
                        fontSize = 12.sp,
                    )
                }
                if (!here && sid.isNotBlank()) {
                    TextButton(onClick = { onRevokeSession(sid) }) {
                        Text("Revoke", color = SchedulerColors.Error)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        AccountWorkingButton("Revoke other devices", loading) { onRevokeOthers() }
        Spacer(Modifier.height(24.dp))
        Text("Delete this account", color = SchedulerColors.Error, style = MaterialTheme.typography.titleSmall)
        Text(
            "Password then confirm. Node wipes the account; this phone clears its cache. SMTP is not on Android.",
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = deletePass,
            onValueChange = { deletePass = it },
            label = { Text("Account password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { confirmDelete = true },
            enabled = deletePass.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SchedulerColors.Error,
                contentColor = SchedulerColors.Cream,
            ),
        ) {
            Text("Delete this account")
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = SchedulerColors.Sheet,
                titleContentColor = SchedulerColors.Cream,
                textContentColor = SchedulerColors.LabelMuted,
                title = { Text("Delete this account?") },
                text = { Text("Tasks, Typewriter, and sessions are removed on the server. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        onDeleteAccount(deletePass)
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
}

private fun shortInstant(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown"
    return raw.replace('T', ' ').take(16)
}

@Composable
private fun AccountNotificationsSub(
    prefs: ReminderPrefs,
    onSave: (ReminderPrefs) -> Unit,
    onBack: () -> Unit,
) {
    var enabled by remember(prefs.enabled) { mutableStateOf(prefs.enabled) }
    var sound by remember(prefs.sound) { mutableStateOf(prefs.sound) }
    var leads by remember(prefs.leads) { mutableStateOf(prefs.leads) }
    var customLead by remember { mutableStateOf("") }
    val extraLeads = leads.filter { it !in SessionStore.REMINDER_PRESETS }.sorted()
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val notifsOn = remember(lifecycleState) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val exactOn = remember(lifecycleState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }
    }
    AccountChrome(title = "Notifications", onBack = onBack) {
        Text(
            "Device-local. Not synced. Choose how far ahead to be reminded for placed unfinished tasks. Sound is optional.",
            color = SchedulerColors.LabelMuted,
            fontSize = 13.sp,
        )
        if (enabled && !notifsOn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Banners are off for this app in Android settings.",
                color = SchedulerColors.TaskOverdue,
                fontSize = 13.sp,
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                },
            ) { Text("Open notification settings", color = SchedulerColors.Cream) }
        }
        if (enabled && !exactOn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Exact alarms are off, so leads may fire late.",
                color = SchedulerColors.TaskOverdue,
                fontSize = 13.sp,
            )
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            },
                        )
                    }
                },
            ) { Text("Allow exact alarms", color = SchedulerColors.Cream) }
        }
        Spacer(Modifier.height(8.dp))
        AccountCheckRow(enabled, "Reminders on") { enabled = it }
        AccountCheckRow(sound, "Sound") { sound = it }
        Spacer(Modifier.height(8.dp))
        Text("Lead times", color = SchedulerColors.Cream, style = MaterialTheme.typography.titleSmall)
        SessionStore.REMINDER_PRESETS.forEach { minutes ->
            AccountCheckRow(
                checked = minutes in leads,
                label = reminderLeadLabel(minutes),
                onChecked = { on ->
                    leads = if (on) leads + minutes else leads - minutes
                },
            )
        }
        extraLeads.forEach { minutes ->
            AccountCheckRow(
                checked = true,
                label = reminderLeadLabel(minutes),
                onChecked = { on ->
                    leads = if (on) leads + minutes else leads - minutes
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customLead,
            onValueChange = { customLead = it.filter { ch -> ch.isDigit() }.take(5) },
            label = { Text("Custom minutes (1–20160)") },
            modifier = Modifier.fillMaxWidth(),
            colors = schedulerOutlinedFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                val n = customLead.toIntOrNull() ?: return@TextButton
                if (n in 1..20160) {
                    leads = leads + n
                    customLead = ""
                }
            },
        ) {
            Text("Add custom lead", color = SchedulerColors.Cream)
        }
        Spacer(Modifier.height(12.dp))
        AccountWorkingButton("Save on this phone", loading = false) {
            onSave(ReminderPrefs(enabled = enabled, sound = sound, leads = leads))
        }
    }
}

@Composable
private fun AccountCheckRow(checked: Boolean, label: String, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            colors = CheckboxDefaults.colors(
                checkedColor = SchedulerColors.Cream,
                uncheckedColor = SchedulerColors.LabelMuted,
                checkmarkColor = SchedulerColors.DarkChrome,
            ),
        )
        Text(label, color = SchedulerColors.Cream)
    }
}

private fun reminderLeadLabel(minutes: Int): String = when (minutes) {
    0 -> "When it starts"
    5 -> "5 min"
    10 -> "10 min"
    30 -> "30 min"
    60 -> "1 hour"
    120 -> "2 hours"
    1440 -> "1 day"
    else -> "$minutes min"
}

@Composable
private fun AccountFieldMessages(error: String?, notice: String?) {
    if (error != null) {
        Text(error, color = SchedulerColors.Error, modifier = Modifier.padding(bottom = 8.dp))
    }
    if (notice != null) {
        Text(notice, color = SchedulerColors.Cream, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun AccountChrome(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SchedulerColors.DarkChrome)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("Back", color = SchedulerColors.Cream)
            }
            Text(
                title,
                color = SchedulerColors.Cream,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            content = content,
        )
    }
}

@Composable
private fun AccountWorkingButton(
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
