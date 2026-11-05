package com.opwait.ashoreprospect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.BlockedTime
import com.opwait.ashoreprospect.domain.BlockedTimePeriod
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val blockedFieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SchedulerColors.PaperInk,
        unfocusedTextColor = SchedulerColors.PaperInk,
        focusedBorderColor = SchedulerColors.PaperInk,
        unfocusedBorderColor = SchedulerColors.OnDarkMuted,
    )

data class BlockedSlotPrefill(
    val startSlot: Int,
    val date: LocalDate? = null,
    val weekday: DayOfWeek? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedEditorSheet(
    blocked: List<BlockedTime>,
    error: String?,
    onAdd: (List<BlockedTime>) -> Unit,
    onDelete: (BlockedTime) -> Unit,
    onDismiss: () -> Unit,
    prefill: BlockedSlotPrefill? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        BlockedEditorBody(blocked, error, onAdd, onDelete, prefill)
    }
}

@Composable
private fun BlockedEditorBody(
    blocked: List<BlockedTime>,
    error: String?,
    onAdd: (List<BlockedTime>) -> Unit,
    onDelete: (BlockedTime) -> Unit,
    prefill: BlockedSlotPrefill? = null,
) {
    val period = remember(blocked) {
        BlockedTimePeriod().also { it.replaceAll(blocked) }
    }
    var everyDay by remember { mutableStateOf(true) }
    var weekly by remember { mutableStateOf(false) }
    var exception by remember { mutableStateOf(false) }
    var weekday by remember {
        mutableStateOf(prefill?.weekday ?: AppTimeReference.currentColumnDate().dayOfWeek)
    }
    var title by remember { mutableStateOf("blocked") }
    var fromDate by remember {
        mutableStateOf((prefill?.date ?: AppTimeReference.currentColumnDate()).toString())
    }
    var toDate by remember {
        mutableStateOf((prefill?.date ?: AppTimeReference.currentColumnDate()).toString())
    }
    var startSlot by remember {
        mutableStateOf(
            (prefill?.startSlot ?: 6).coerceIn(0, AppTimeReference.SLOTS_PER_DAY - 2),
        )
    }
    var endSlot by remember {
        val start = (prefill?.startSlot ?: 6).coerceIn(0, AppTimeReference.SLOTS_PER_DAY - 2)
        mutableStateOf((start + 2).coerceAtMost(AppTimeReference.SLOTS_PER_DAY - 1))
    }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<BlockedTime?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SchedulerColors.Paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("View/edit blocked", color = SchedulerColors.PaperInk)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = everyDay && !exception && !weekly,
                onCheckedChange = {
                    everyDay = it
                    if (it) {
                        exception = false
                        weekly = false
                    }
                },
                colors = CheckboxDefaults.colors(checkedColor = SchedulerColors.PaperInk),
            )
            Text("Every day", color = SchedulerColors.PaperInk)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = weekly && !exception,
                onCheckedChange = {
                    weekly = it
                    if (it) {
                        everyDay = false
                        exception = false
                    }
                },
                colors = CheckboxDefaults.colors(checkedColor = SchedulerColors.PaperInk),
            )
            Text("This weekday", color = SchedulerColors.PaperInk)
        }
        if (weekly && !exception) {
            WeekdayPicker(weekday) { weekday = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = exception,
                onCheckedChange = {
                    exception = it
                    if (it) {
                        everyDay = false
                        weekly = false
                    }
                },
                colors = CheckboxDefaults.colors(checkedColor = SchedulerColors.PaperInk),
            )
            Text("Exception (free this date)", color = SchedulerColors.PaperInk)
        }
        OutlinedTextField(
            title,
            { title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            colors = blockedFieldColors,
        )
        if ((!everyDay && !weekly) || exception) {
            OutlinedTextField(
                fromDate,
                { fromDate = it },
                label = { Text("From YYYY-MM-DD") },
                supportingText = {
                    englishDateHint(fromDate)?.let { Text(it) }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = blockedFieldColors,
            )
            if (!exception) {
                OutlinedTextField(
                    toDate,
                    { toDate = it },
                    label = { Text("To YYYY-MM-DD") },
                    supportingText = {
                        englishDateHint(toDate)?.let { Text(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = blockedFieldColors,
                )
            }
        }
        SlotPicker("Start", startSlot) { startSlot = it }
        SlotPicker("End", endSlot) { endSlot = it }
        (localError ?: error)?.let { Text(it, color = SchedulerColors.Error) }
        Button(
            onClick = {
                localError = null
                if (endSlot <= startSlot) {
                    localError = "End must be after start"
                    return@Button
                }
                val start = AppTimeReference.slotToLocalTime(startSlot)
                val end = AppTimeReference.slotToLocalTime(endSlot)
                val rows = when {
                    exception -> {
                        val d = runCatching { LocalDate.parse(fromDate) }.getOrNull()
                        if (d == null) {
                            localError = "Invalid date"
                            return@Button
                        }
                        BlockedTime.dailyRange(d, d, title, start, end, exception = true)
                    }
                    weekly -> listOf(BlockedTime.weekly(weekday, title, start, end))
                    everyDay -> listOf(BlockedTime.daily(title, start, end))
                    else -> {
                        val from = runCatching { LocalDate.parse(fromDate) }.getOrNull()
                        val to = runCatching { LocalDate.parse(toDate) }.getOrNull()
                        if (from == null || to == null || to.isBefore(from)) {
                            localError = "Invalid date range"
                            return@Button
                        }
                        BlockedTime.dailyRange(from, to, title, start, end)
                    }
                }
                val keep = rows.filterNot { it.isDatedExpired() }
                if (keep.isEmpty()) {
                    localError = "Cannot add a blocked time in the past."
                    return@Button
                }
                for (row in keep) {
                    period.conflictMessage(row)?.let {
                        localError = it
                        return@Button
                    }
                }
                onAdd(keep)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SchedulerColors.PaperInk,
                contentColor = SchedulerColors.Paper,
            ),
        ) { Text("Add") }
        Spacer(Modifier.height(12.dp))
        if (blocked.isEmpty()) {
            Text("No blocked times yet.", color = SchedulerColors.OnDarkMuted)
        }
        val sorted = blocked.sortedWith(compareBy({ it.startTime }, { it.title }))
        for (row in sorted) {
            val whenLabel = when {
                row.isException -> "exception ${row.specificDate?.let(::englishDayMonth).orEmpty()}"
                row.isDaily() -> "every day"
                row.weekday != null -> {
                    val name = BlockedTime.weekdayToDayOfWeek(row.weekday!!)
                        .getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    "${name}s"
                }
                else -> row.specificDate?.let(::englishDayMonth) ?: "dated"
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${VaultSeal.displayTitle(row.title).ifBlank { "blocked" }} · $whenLabel · ${slotClock(row.startTime)}–${slotClock(row.endTime)}",
                    modifier = Modifier.weight(1f),
                    color = SchedulerColors.PaperInk,
                )
                TextButton(onClick = { pendingDelete = row }) {
                    Text("Delete", color = SchedulerColors.Error)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = SchedulerColors.Paper,
            titleContentColor = SchedulerColors.PaperInk,
            textContentColor = SchedulerColors.PaperInk,
            title = { Text("Delete blocked time?") },
            text = {
                Text(VaultSeal.displayTitle(row.title).ifBlank { "blocked" })
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(row)
                    pendingDelete = null
                }) { Text("Delete", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = SchedulerColors.PaperInk)
                }
            },
        )
    }
}

@Composable
private fun WeekdayPicker(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = selected.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("Weekday: $label", modifier = Modifier.weight(1f), color = SchedulerColors.PaperInk)
        TextButton(onClick = { open = true }) { Text("Change") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (day in DayOfWeek.entries) {
                DropdownMenuItem(
                    text = { Text(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) },
                    onClick = {
                        onSelect(day)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SlotPicker(label: String, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$label: ${AppTimeReference.formatSlotLabel(selected)}", modifier = Modifier.weight(1f))
        TextButton(onClick = { open = true }) { Text("Change") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (slot in 0 until AppTimeReference.SLOTS_PER_DAY) {
                DropdownMenuItem(
                    text = { Text(AppTimeReference.formatSlotLabel(slot)) },
                    onClick = {
                        onSelect(slot)
                        open = false
                    },
                )
            }
        }
    }
}

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
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return "${ordinalDay(date.dayOfMonth)} $month"
}

private fun englishDateHint(iso: String): String? =
    runCatching { LocalDate.parse(iso.trim()) }.getOrNull()?.let { date ->
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        "$weekday ${englishDayMonth(date)}"
    }

private fun slotClock(time: LocalTime): String =
    AppTimeReference.formatSlotLabel(AppTimeReference.localTimeToSlot(time))
