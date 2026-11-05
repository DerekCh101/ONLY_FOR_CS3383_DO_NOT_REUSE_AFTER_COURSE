package com.opwait.ashoreprospect.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.opwait.ashoreprospect.domain.AppTimeReference
import com.opwait.ashoreprospect.domain.VaultCrypto
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.TypewriterBundle
import com.opwait.ashoreprospect.domain.TypewriterDiaryEntry
import com.opwait.ashoreprospect.domain.TypewriterImageRef
import com.opwait.ashoreprospect.domain.TypewriterList
import com.opwait.ashoreprospect.domain.TypewriterListItem
import com.opwait.ashoreprospect.domain.TypewriterHtml
import com.opwait.ashoreprospect.domain.TypewriterPage
import com.opwait.ashoreprospect.ui.theme.SchedulerColors
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

private enum class DiaryFindLevel { Year, Month, Day }

private enum class TypewriterSection { Pages, Diary, Lists }

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = SchedulerColors.Cream,
        unfocusedTextColor = SchedulerColors.Cream,
        focusedBorderColor = SchedulerColors.Cream,
        unfocusedBorderColor = SchedulerColors.OnDarkMuted,
        focusedLabelColor = SchedulerColors.LabelMuted,
        unfocusedLabelColor = SchedulerColors.OnDarkMuted,
        cursorColor = SchedulerColors.Cream,
    )

@Composable
fun TypewriterScreen(
    bundle: TypewriterBundle,
    onChange: (TypewriterBundle) -> Unit,
    onAttachImage: (diaryDate: String?, pageId: String?, name: String, mime: String, bytes: ByteArray) -> Unit,
    onPreviewImage: (sha: String) -> Unit,
    onShowCalendar: () -> Unit = {},
    /** Host / vault replace counter — forces open WebViews to reload without leaving the tab. */
    contentRev: Int = 0,
) {
    var section by remember { mutableStateOf(TypewriterSection.Pages) }
    var folderTreeOpen by remember { mutableStateOf(false) }
    var diaryFindOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var pendingDiaryDate by remember { mutableStateOf<String?>(null) }
    var pendingPageId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val diary = pendingDiaryDate
        val page = pendingPageId
        pendingDiaryDate = null
        pendingPageId = null
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        var name = "image.png"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: name
            }
        }
        val mime = resolver.getType(uri) ?: "image/*"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        onAttachImage(diary, page, name, mime, bytes)
    }
    val requestPick: (String?, String?) -> Unit = { diary, page ->
        pendingDiaryDate = diary
        pendingPageId = page
        picker.launch("image/*")
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(SchedulerColors.DarkChrome)
            .padding(12.dp),
    ) {
        val tabs = listOf(
            TypewriterSection.Pages,
            TypewriterSection.Diary,
            TypewriterSection.Lists,
        )
        val tabIndex = tabs.indexOf(section).coerceAtLeast(0)
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = SchedulerColors.DarkChrome,
            contentColor = SchedulerColors.Cream,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[tabIndex]),
                    color = SchedulerColors.Cream,
                )
            },
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = section == tab,
                    onClick = {
                        if (tab == TypewriterSection.Pages) {
                            diaryFindOpen = false
                            if (section == TypewriterSection.Pages) {
                                folderTreeOpen = !folderTreeOpen
                            } else {
                                section = TypewriterSection.Pages
                                folderTreeOpen = true
                            }
                        } else if (tab == TypewriterSection.Diary) {
                            folderTreeOpen = false
                            if (section == TypewriterSection.Diary) {
                                diaryFindOpen = !diaryFindOpen
                            } else {
                                section = TypewriterSection.Diary
                                diaryFindOpen = true
                            }
                        } else {
                            folderTreeOpen = false
                            diaryFindOpen = false
                            section = tab
                        }
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (tab) {
                                    TypewriterSection.Pages -> "Pages"
                                    TypewriterSection.Diary -> "Diary"
                                    TypewriterSection.Lists -> "Lists"
                                },
                            )
                            if (tab == TypewriterSection.Pages || tab == TypewriterSection.Diary) {
                                val open = if (tab == TypewriterSection.Pages) {
                                    folderTreeOpen && section == TypewriterSection.Pages
                                } else {
                                    diaryFindOpen && section == TypewriterSection.Diary
                                }
                                Icon(
                                    if (open) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                                    contentDescription = if (open) {
                                        if (tab == TypewriterSection.Pages) "Close folders" else "Close diary search"
                                    } else {
                                        if (tab == TypewriterSection.Pages) "Open folders" else "Open diary search"
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (section) {
                TypewriterSection.Diary -> DiaryPane(
                    bundle,
                    onChange,
                    findOpen = diaryFindOpen,
                    onFindOpenChange = { diaryFindOpen = it },
                    onPickImage = { requestPick(it, null) },
                    onPreviewImage = onPreviewImage,
                    contentRev = contentRev,
                )
                TypewriterSection.Pages -> PagesPane(
                    bundle,
                    onChange,
                    treeOpen = folderTreeOpen,
                    onPickImage = { requestPick(null, it) },
                    onPreviewImage = onPreviewImage,
                    contentRev = contentRev,
                )
                TypewriterSection.Lists -> ListsPane(bundle, onChange)
            }
        }
    }
}

@Composable
private fun DiaryPane(
    bundle: TypewriterBundle,
    onChange: (TypewriterBundle) -> Unit,
    findOpen: Boolean,
    onFindOpenChange: (Boolean) -> Unit,
    onPickImage: (date: String) -> Unit,
    onPreviewImage: (sha: String) -> Unit,
    contentRev: Int = 0,
) {
    val today = AppTimeReference.currentColumnDate().toString()
    var date by remember { mutableStateOf(today) }
    val entry = bundle.diaryAt(date)
    if (findOpen) {
        DiaryFindDirectory(
            bundle = bundle,
            selectedDate = date,
            onSelect = { date = it },
            onEdit = { onFindOpenChange(false) },
            contentRev = contentRev,
        )
        return
    }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (!imeOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
            val prevHas = parsedDate != null &&
                bundle.diary.any { it.date == parsedDate.minusDays(1).toString() && it.hasContent() }
            val nextHas = parsedDate != null &&
                bundle.diary.any { it.date == parsedDate.plusDays(1).toString() && it.hasContent() }
            TextButton(onClick = {
                val parsed = parsedDate ?: return@TextButton
                date = parsed.minusDays(1).toString()
            }) { Text(if (prevHas) "◀·" else "◀", color = SchedulerColors.Cream) }
            Text(
                englishDateHint(date) ?: date,
                color = SchedulerColors.Cream,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                textDecoration = if (entry?.hasContent() == true) {
                    TextDecoration.Underline
                } else {
                    TextDecoration.None
                },
            )
            TextButton(onClick = {
                val parsed = parsedDate ?: return@TextButton
                date = parsed.plusDays(1).toString()
            }) { Text(if (nextHas) "·▶" else "▶", color = SchedulerColors.Cream) }
            TextButton(onClick = {
                date = today
            }) {
                val todayEntry = bundle.diary.firstOrNull { it.date == today }
                Text(
                    "Today",
                    color = SchedulerColors.Cream,
                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (todayEntry?.hasContent() == true) {
                        TextDecoration.Underline
                    } else {
                        TextDecoration.None
                    },
                )
            }
        }
        if (entry == null || (entry.title.isBlank() && entry.body.isBlank())) {
            Text(
                "Empty day — write a title or diary entry.",
                color = SchedulerColors.OnDarkMuted,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        }
        val diarySealed = entry != null && (VaultSeal.isSealed(entry.title) || VaultSeal.isSealed(entry.body))
        if (!imeOpen) {
        OutlinedTextField(
            VaultSeal.displayTitle(entry?.title.orEmpty()),
            {
                val row = bundle.diaryFor(date)
                if (!VaultSeal.isSealed(row.title) && it != row.title) {
                    row.title = it
                    onChange(bundle.copy())
                }
            },
            enabled = !diarySealed,
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )
        }
        var diaryCmd by remember { mutableStateOf("") }
        var diaryCmdTick by remember { mutableIntStateOf(0) }
        var diaryUndoTick by remember { mutableIntStateOf(0) }
        var diaryRedoTick by remember { mutableIntStateOf(0) }
        var diaryCanUndo by remember { mutableStateOf(false) }
        var diaryCanRedo by remember { mutableStateOf(false) }
        var diarySizePt by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(date) {
            diaryCmd = ""
            diaryCmdTick = 0
            diaryUndoTick = 0
            diaryRedoTick = 0
            diaryCanUndo = false
            diaryCanRedo = false
            diarySizePt = null
        }
        if (!diarySealed) {
            PageEditTools(
                pageSealed = false,
                canUndo = diaryCanUndo,
                canRedo = diaryCanRedo,
                currentSizePt = diarySizePt,
                onUndo = { diaryUndoTick += 1 },
                onRedo = { diaryRedoTick += 1 },
                onCommand = { js ->
                    diaryCmd = js
                    diaryCmdTick += 1
                },
                onSizePicked = { diarySizePt = it },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            key(date) {
                HtmlEditorField(
                    html = entry?.body.orEmpty(),
                    onHtmlChange = { next ->
                        val existing = bundle.diaryAt(date)
                        if (existing == null && TypewriterHtml.plainText(next).isBlank()) return@HtmlEditorField
                        val row = existing ?: bundle.diaryFor(date)
                        if (TypewriterHtml.storedText(row.body) == next) return@HtmlEditorField
                        if (TypewriterHtml.plainText(row.body).isBlank() && TypewriterHtml.plainText(next).isBlank()) {
                            return@HtmlEditorField
                        }
                        row.body = next
                        onChange(bundle.copy())
                    },
                    editable = !diarySealed,
                    modifier = Modifier.fillMaxSize(),
                    contentRev = contentRev,
                    commandTick = diaryCmdTick,
                    commandJs = diaryCmd,
                    undoTick = diaryUndoTick,
                    redoTick = diaryRedoTick,
                    onHistoryState = { u, r ->
                        diaryCanUndo = u
                        diaryCanRedo = r
                    },
                    onSelectionFontSizePt = { diarySizePt = it },
                )
            }
        }
        if (!imeOpen) {
        Text(
            textStats(entry?.body.orEmpty()),
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        ImageRefStrip(
            images = entry?.images ?: mutableListOf(),
            onChange = { onChange(bundle.copy()) },
            onAttach = { onPickImage(date) },
            onPreview = onPreviewImage,
        )
        }
    }
}

@Composable
private fun DiaryFindDirectory(
    bundle: TypewriterBundle,
    selectedDate: String,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    contentRev: Int = 0,
) {
    val today = AppTimeReference.currentColumnDate()
    val selected = runCatching { LocalDate.parse(selectedDate) }.getOrNull() ?: today
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var findLevel by remember { mutableStateOf(DiaryFindLevel.Year) }
    var findYear by remember { mutableStateOf<Int?>(null) }
    var findMonth by remember { mutableStateOf<Month?>(null) }
    LaunchedEffect(selectedDate) {
        val d = runCatching { LocalDate.parse(selectedDate) }.getOrNull() ?: return@LaunchedEffect
        month = YearMonth.from(d)
    }
    val marked = remember(bundle.diary, bundle.typewriterRevKey()) {
        bundle.diary.filter { it.hasContent() }.mapNotNull {
            runCatching { LocalDate.parse(it.date) }.getOrNull()
        }.toSet()
    }
    val index = remember(bundle.diary, bundle.typewriterRevKey()) { indexDiaryEntries(bundle) }
    val browse: @Composable (Modifier) -> Unit = { mod ->
        Column(
            mod
                .padding(end = 10.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) {
                    Text("◀", color = SchedulerColors.Cream)
                }
                Text(
                    "${month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${month.year}",
                    color = SchedulerColors.Cream,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { month = month.plusMonths(1) }) {
                    Text("▶", color = SchedulerColors.Cream)
                }
            }
            DiaryMonthCalendar(
                month = month,
                today = today,
                selected = selected,
                marked = marked,
                onDay = { picked ->
                    onSelect(picked.toString())
                    findYear = picked.year
                    findMonth = picked.month
                    findLevel = DiaryFindLevel.Day
                },
            )
            Text(
                "Find written days",
                color = SchedulerColors.Cream,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            Text(
                "This list only shows days that already have text or photos. Click in order: year → month → day. Use Back to go up one level.",
                color = SchedulerColors.OnDarkMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FindStepPill(
                    label = "Find Year",
                    selected = findLevel == DiaryFindLevel.Year,
                    enabled = true,
                    onClick = {
                        findLevel = DiaryFindLevel.Year
                        findYear = null
                        findMonth = null
                    },
                )
                Text(" → ", color = SchedulerColors.OnDarkMuted, fontSize = 12.sp)
                FindStepPill(
                    label = "Find Month",
                    selected = findLevel == DiaryFindLevel.Month,
                    enabled = findYear != null,
                    onClick = {
                        findLevel = DiaryFindLevel.Month
                        findMonth = null
                    },
                )
                Text(" → ", color = SchedulerColors.OnDarkMuted, fontSize = 12.sp)
                FindStepPill(
                    label = "Find Day",
                    selected = findLevel == DiaryFindLevel.Day,
                    enabled = findYear != null && findMonth != null,
                    onClick = { findLevel = DiaryFindLevel.Day },
                )
            }
            Text(
                when (findLevel) {
                    DiaryFindLevel.Year -> "Years with entries"
                    DiaryFindLevel.Month -> "${findYear ?: ""} — months with entries"
                    DiaryFindLevel.Day -> {
                        val name = findMonth?.getDisplayName(
                            java.time.format.TextStyle.FULL,
                            java.util.Locale.ENGLISH,
                        ).orEmpty()
                        "$name ${findYear ?: ""}".trim()
                    }
                },
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            when (findLevel) {
                DiaryFindLevel.Year -> {
                    if (index.isEmpty()) {
                        Text("(none yet)", color = SchedulerColors.OnDarkMuted, fontSize = 13.sp)
                    } else {
                        index.forEach { (year, months) ->
                            val n = months.values.sumOf { it.size }
                            val here = year == today.year
                            FindRow(
                                "$year  ($n)" + if (here) "  · this year" else "",
                                markToday = here,
                            ) {
                                findYear = year
                                findMonth = null
                                findLevel = DiaryFindLevel.Month
                            }
                        }
                    }
                }
                DiaryFindLevel.Month -> {
                    val months = index[findYear].orEmpty()
                    if (months.isEmpty()) {
                        Text("(none)", color = SchedulerColors.OnDarkMuted, fontSize = 13.sp)
                    } else {
                        months.forEach { (m, days) ->
                            val name = m.getDisplayName(
                                java.time.format.TextStyle.FULL,
                                java.util.Locale.ENGLISH,
                            )
                            val here = findYear == today.year && m == today.month
                            FindRow(
                                "$name  (${days.size})" + if (here) "  · this month" else "",
                                markToday = here,
                            ) {
                                findMonth = m
                                findLevel = DiaryFindLevel.Day
                                month = YearMonth.of(findYear ?: today.year, m)
                            }
                        }
                    }
                }
                DiaryFindLevel.Day -> {
                    val days = index[findYear].orEmpty()[findMonth].orEmpty()
                    if (days.isEmpty()) {
                        Text("(none)", color = SchedulerColors.OnDarkMuted, fontSize = 13.sp)
                    } else {
                        days.forEach { e ->
                            val d = runCatching { LocalDate.parse(e.date) }.getOrNull()
                            val weekday = d?.dayOfWeek?.getDisplayName(
                                java.time.format.TextStyle.SHORT,
                                java.util.Locale.ENGLISH,
                            ).orEmpty()
                            val dayNo = d?.dayOfMonth ?: 0
                            val t = VaultSeal.displayTitle(e.title.trim()).ifBlank { "entry" }
                            val imgs = e.images.size
                            val imgMark = if (imgs > 0) "  ($imgs img)" else ""
                            val todayMark = if (e.date == today.toString()) "  · today" else ""
                            FindRow(
                                "$weekday ${ordinalDay(dayNo)}  —  $t$imgMark$todayMark",
                                markToday = e.date == today.toString(),
                            ) { onSelect(e.date) }
                        }
                    }
                }
            }
            if (findLevel != DiaryFindLevel.Year) {
                TextButton(
                    onClick = {
                        if (findLevel == DiaryFindLevel.Day) {
                            findLevel = DiaryFindLevel.Month
                            findMonth = null
                        } else {
                            findLevel = DiaryFindLevel.Year
                            findYear = null
                            findMonth = null
                        }
                    },
                ) {
                    Text("Back", color = SchedulerColors.Cream)
                }
            }
        }
    }
    val preview: @Composable (Modifier) -> Unit = { mod ->
        Column(
            mod
                .fillMaxSize()
                .padding(start = 4.dp),
        ) {
            DiaryPreviewPane(
                bundle = bundle,
                date = selectedDate,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentRev = contentRev,
            )
            TextButton(onClick = onEdit) {
                Text("Edit this day", color = SchedulerColors.Cream)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val sideBySide = maxWidth >= 560.dp
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1.8f).fillMaxHeight()) {
                    browse(Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(SchedulerColors.SheetLine),
                    )
                }
                preview(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                browse(Modifier.weight(1.15f).fillMaxWidth())
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(SchedulerColors.SheetLine),
                )
                preview(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun DiaryMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    marked: Set<LocalDate>,
    onDay: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val start = AppTimeReference.getStartOfWeek(first)
    val days = (0 until 42).map { start.plusDays(it.toLong()) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                Text(
                    it,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = SchedulerColors.LabelMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val inMonth = date.month == month.month
                    val has = date in marked
                    val isToday = date == today
                    val isSelected = date == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    isSelected -> SchedulerColors.ChipOnDark
                                    isToday -> SchedulerColors.SlotHighlight
                                    else -> Color.Transparent
                                },
                            )
                            .clickable { onDay(date) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            fontSize = 12.sp,
                            fontWeight = if (isToday || has) FontWeight.Bold else FontWeight.Normal,
                            textDecoration = if (has) TextDecoration.Underline else TextDecoration.None,
                            color = when {
                                !inMonth -> SchedulerColors.OnDarkMuted
                                isToday -> SchedulerColors.NowLine
                                has -> SchedulerColors.Cream
                                else -> SchedulerColors.LabelMuted
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiaryPreviewPane(
    bundle: TypewriterBundle,
    date: String,
    modifier: Modifier = Modifier,
    contentRev: Int = 0,
) {
    val entry = bundle.diaryAt(date)
    val hint = englishDateHint(date) ?: date
    Column(modifier.fillMaxSize()) {
        Text(
            hint,
            color = SchedulerColors.LabelMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (entry == null || !entry.hasContent()) {
            Text(
                "Empty day — close Diary ▾ to write.",
                color = SchedulerColors.OnDarkMuted,
            )
            return@Column
        }
        Text(
            VaultSeal.displayTitle(entry.title).ifBlank { "Diary" },
            color = SchedulerColors.Cream,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val opened = VaultCrypto.open(entry.body)
        if (VaultSeal.isSealed(opened) || VaultSeal.isSealed(entry.title)) {
            return@Column
        }
        if (TypewriterHtml.plainText(opened).isBlank()) {
            Text("No body yet.", color = SchedulerColors.OnDarkMuted)
        } else {
            HtmlEditorField(
                html = opened,
                onHtmlChange = {},
                editable = false,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentRev = contentRev,
            )
        }
        if (entry.images.isNotEmpty()) {
            Text(
                entry.images.joinToString(" · ") { it.name },
                color = SchedulerColors.OnDarkMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun indexDiaryEntries(
    bundle: TypewriterBundle,
): Map<Int, Map<Month, List<TypewriterDiaryEntry>>> {
    val years = sortedMapOf<Int, MutableMap<Month, MutableList<TypewriterDiaryEntry>>>(
        compareByDescending { it },
    )
    for (e in bundle.diary) {
        if (!e.hasContent()) continue
        val d = runCatching { LocalDate.parse(e.date) }.getOrNull() ?: continue
        years.getOrPut(d.year) { sortedMapOf() }
            .getOrPut(d.month) { mutableListOf() }
            .add(e)
    }
    for (months in years.values) {
        for (days in months.values) days.sortBy { it.date }
    }
    return years
}

@Composable
private fun FindStepPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        label,
        color = when {
            selected -> SchedulerColors.DarkChrome
            enabled -> SchedulerColors.Cream
            else -> SchedulerColors.OnDarkMuted
        },
        fontSize = 13.sp,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) SchedulerColors.Cream else Color.Transparent)
            .border(
                1.dp,
                when {
                    selected -> SchedulerColors.Cream
                    enabled -> SchedulerColors.Cream.copy(alpha = 0.55f)
                    else -> SchedulerColors.OnDarkMuted.copy(alpha = 0.45f)
                },
                shape,
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun FindRow(label: String, markToday: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = SchedulerColors.Cream,
        fontSize = 15.sp,
        fontWeight = if (markToday) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (markToday) TextDecoration.Underline else TextDecoration.None,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
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

private fun englishDateHint(iso: String): String? {
    val date = runCatching { java.time.LocalDate.parse(iso.trim()) }.getOrNull() ?: return null
    val weekday = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    val month = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    return "$weekday ${ordinalDay(date.dayOfMonth)} $month"
}

private fun textStats(raw: String): String {
    val readable = TypewriterHtml.toReadable(raw)
    val lines = if (readable.isEmpty()) 0 else readable.split('\n').size
    val words = readable.split(Regex("\\s+")).count { it.isNotBlank() }
    val letters = readable.count { it.isLetter() }
    return "$lines lines · $words words · $letters letters"
}

internal fun lineNumberGutter(raw: String): String {
    val n = if (raw.isEmpty()) 1 else raw.split('\n').size
    return (1..n).joinToString("\n") { it.toString() }
}

internal const val EDITOR_TAB_SPACES = 4

/** VS Code-style indent: Tab inserts four spaces; Shift+Tab removes a leading indent. */
internal fun applyEditorTab(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    outdent: Boolean,
    tabSize: Int = EDITOR_TAB_SPACES,
): Triple<String, Int, Int> {
    val n = text.length
    val a = selectionStart.coerceIn(0, n)
    val b = selectionEnd.coerceIn(0, n)
    val from = minOf(a, b)
    val to = maxOf(a, b)
    val pad = " ".repeat(tabSize.coerceAtLeast(1))
    if (!outdent && from == to) {
        val next = text.substring(0, from) + pad + text.substring(from)
        val c = from + pad.length
        return Triple(next, c, c)
    }
    var coverEnd = to
    if (to > from && to > 0 && text[to - 1] == '\n') coverEnd = to - 1
    val first = if (from <= 0) 0 else {
        val p = text.lastIndexOf('\n', from - 1)
        if (p < 0) 0 else p + 1
    }
    val last = text.indexOf('\n', coverEnd).let { if (it < 0) n else it }
    val block = text.substring(first, last)
    val edited = block.split('\n').map { line ->
        if (outdent) {
            when {
                line.startsWith("\t") -> line.removePrefix("\t")
                else -> line.drop(line.takeWhile { it == ' ' }.length.coerceAtMost(tabSize))
            }
        } else {
            pad + line
        }
    }
    val nextBlock = edited.joinToString("\n")
    val next = text.substring(0, first) + nextBlock + text.substring(last)
    return Triple(next, first, first + nextBlock.length)
}

internal fun applyInlineWrap(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    open: String,
    close: String,
): Triple<String, Int, Int> {
    if (open.isEmpty() && close.isEmpty()) return Triple(text, selectionStart, selectionEnd)
    val n = text.length
    val a = selectionStart.coerceIn(0, n)
    val b = selectionEnd.coerceIn(0, n)
    val from = minOf(a, b)
    val to = maxOf(a, b)
    val inner = text.substring(from, to)
    if (from >= open.length && to + close.length <= n &&
        text.regionMatches(from - open.length, open, 0, open.length) &&
        text.regionMatches(to, close, 0, close.length)
    ) {
        val next = text.substring(0, from - open.length) + inner + text.substring(to + close.length)
        val c0 = from - open.length
        return Triple(next, c0, c0 + inner.length)
    }
    if (inner.startsWith(open) && inner.endsWith(close) && inner.length >= open.length + close.length) {
        val peeled = inner.substring(open.length, inner.length - close.length)
        val next = text.substring(0, from) + peeled + text.substring(to)
        return Triple(next, from, from + peeled.length)
    }
    val next = text.substring(0, from) + open + inner + close + text.substring(to)
    val innerStart = from + open.length
    return Triple(next, innerStart, innerStart + inner.length)
}

internal fun applyLinePrefix(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    prefix: String,
): Triple<String, Int, Int> {
    val n = text.length
    val a = selectionStart.coerceIn(0, n)
    val b = selectionEnd.coerceIn(0, n)
    val from = minOf(a, b)
    val to = maxOf(a, b)
    var coverEnd = to
    if (to > from && to > 0 && text[to - 1] == '\n') coverEnd = to - 1
    val first = if (from <= 0) 0 else {
        val p = text.lastIndexOf('\n', from - 1)
        if (p < 0) 0 else p + 1
    }
    val last = text.indexOf('\n', coverEnd).let { if (it < 0) n else it }
    val heading = Regex("^(#{1,6} )")
    val block = text.substring(first, last)
    val edited = block.split('\n').map { line ->
        val stripped = heading.replaceFirst(line, "")
        val current = heading.find(line)?.value.orEmpty()
        when {
            prefix.isEmpty() -> stripped
            current == prefix -> stripped
            else -> prefix + stripped
        }
    }
    val nextBlock = edited.joinToString("\n")
    val next = text.substring(0, first) + nextBlock + text.substring(last)
    return Triple(next, first, first + nextBlock.length)
}

private fun TypewriterBundle.typewriterRevKey(): Int =
    diary.size + diary.sumOf { it.body.length + it.title.length } +
        pages.size + pages.sumOf { it.body.length + it.title.length }

@Composable
private fun PagesPane(
    bundle: TypewriterBundle,
    onChange: (TypewriterBundle) -> Unit,
    treeOpen: Boolean,
    onPickImage: (pageId: String) -> Unit,
    onPreviewImage: (sha: String) -> Unit,
    contentRev: Int = 0,
) {
    var selectedId by remember {
        mutableStateOf(bundle.pages.firstOrNull { it.kind != "folder" }?.id ?: bundle.pages.firstOrNull()?.id)
    }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var pickMoveFolder by remember { mutableStateOf(false) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var cmdJs by remember { mutableStateOf("") }
    var cmdTick by remember { mutableIntStateOf(0) }
    var undoTick by remember { mutableIntStateOf(0) }
    var redoTick by remember { mutableIntStateOf(0) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var selectionSizePt by remember { mutableStateOf<Int?>(null) }
    val page = bundle.pages.firstOrNull { it.id == selectedId }
    val confirmPage = bundle.pages.firstOrNull { it.id == confirmDeleteId }
    LaunchedEffect(selectedId) {
        cmdJs = ""
        cmdTick = 0
        undoTick = 0
        redoTick = 0
        canUndo = false
        canRedo = false
        selectionSizePt = null
    }
    Box(Modifier.fillMaxSize()) {
    if (treeOpen) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1.8f)
                    .fillMaxHeight(),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row {
                        TextButton(onClick = {
                            val parent = nestParent(page)
                            val note = TypewriterPage(
                                title = "Note ${bundle.pages.size + 1}",
                                parentId = parent,
                                pos = bundle.pages.size,
                            )
                            bundle.pages.add(note)
                            selectedId = note.id
                            onChange(bundle.copy())
                        }) { Text("Add note", color = SchedulerColors.Cream, fontSize = 13.sp) }
                        TextButton(onClick = {
                            val parent = nestParent(page)
                            val folder = TypewriterPage(
                                title = "Folder ${bundle.pages.size + 1}",
                                kind = "folder",
                                parentId = parent,
                                pos = bundle.pages.size,
                            )
                            bundle.pages.add(folder)
                            selectedId = folder.id
                            onChange(bundle.copy())
                        }) {
                            Text(
                                if (page?.kind == "folder") "Add subfolder" else "Add folder",
                                color = SchedulerColors.Cream,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    if (bundle.pages.isEmpty()) {
                        Text(
                            "No pages yet.",
                            color = SchedulerColors.OnDarkMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    } else {
                        PageTree(
                            pages = bundle.pages,
                            selectedId = selectedId,
                            onSelect = { selectedId = it },
                            onRenameFolder = { id ->
                                val folder = bundle.pages.firstOrNull { it.id == id } ?: return@PageTree
                                if (VaultSeal.isSealed(folder.title)) return@PageTree
                                renameFolderId = id
                                renameDraft = folder.title
                            },
                            onDelete = { confirmDeleteId = it },
                        )
                    }
                    page?.let { sel ->
                        TreeMoveButtons(bundle, sel, onChange, onMoveInto = { pickMoveFolder = true })
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(SchedulerColors.SheetLine),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 10.dp),
            ) {
                NotePreviewPane(bundle, page, modifier = Modifier.fillMaxSize(), contentRev = contentRev)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize(),
        ) {
            if (bundle.pages.isEmpty()) {
                Text(
                    "No pages yet — tap Pages ▾ to open folders.",
                    color = SchedulerColors.OnDarkMuted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else if (page == null || page.kind == "folder") {
                Text(
                    "Tap Pages ▾ to pick a note.",
                    color = SchedulerColors.OnDarkMuted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                val path = pagePath(bundle.pages, page.id)
                if (path.isNotBlank() && !imeOpen) {
                    Text(
                        path,
                        color = SchedulerColors.LabelMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }
                val pageSealed = VaultSeal.isSealed(page.title) || VaultSeal.isSealed(page.body)
                if (!imeOpen) {
                    OutlinedTextField(
                        VaultSeal.displayTitle(page.title),
                        {
                            if (!VaultSeal.isSealed(page.title) && it != page.title) {
                                page.title = it
                                onChange(bundle.copy())
                            }
                        },
                        enabled = !pageSealed,
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                    )
                }
                PageEditTools(
                    pageSealed = pageSealed,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    currentSizePt = selectionSizePt,
                    onUndo = { undoTick += 1 },
                    onRedo = { redoTick += 1 },
                    onCommand = { js ->
                        cmdJs = js
                        cmdTick += 1
                    },
                    onSizePicked = { selectionSizePt = it },
                )
                Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                    key(page.id) {
                        HtmlEditorField(
                            html = page.body,
                            onHtmlChange = { next ->
                                if (TypewriterHtml.storedText(page.body) == next) return@HtmlEditorField
                                if (TypewriterHtml.plainText(page.body).isBlank() && TypewriterHtml.plainText(next).isBlank()) {
                                    return@HtmlEditorField
                                }
                                page.body = next
                                onChange(bundle.copy())
                            },
                            editable = !pageSealed,
                            modifier = Modifier.fillMaxSize(),
                            contentRev = contentRev,
                            commandTick = cmdTick,
                            commandJs = cmdJs,
                            undoTick = undoTick,
                            redoTick = redoTick,
                            onHistoryState = { u, r ->
                                canUndo = u
                                canRedo = r
                            },
                            onSelectionFontSizePt = { selectionSizePt = it },
                        )
                    }
                }
                if (!imeOpen) {
                Text(
                    textStats(page.body),
                    color = SchedulerColors.LabelMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ImageRefStrip(
                    images = page.images,
                    onChange = { onChange(bundle.copy()) },
                    onAttach = { onPickImage(page.id) },
                    onPreview = onPreviewImage,
                )
                TextButton(onClick = {
                    val sealed = VaultSeal.isSealed(page.title)
                    val copy = TypewriterPage(
                        title = if (sealed) page.title
                        else "${page.title.trim().ifBlank { "Note" }} copy",
                        parentId = page.parentId,
                        kind = page.kind,
                        body = page.body,
                        images = page.images.map { TypewriterImageRef(it.id, it.name) }.toMutableList(),
                        pos = (bundle.pages.maxOfOrNull { it.pos } ?: 0) + 1,
                    )
                    bundle.pages.add(copy)
                    selectedId = copy.id
                    onChange(bundle.copy())
                }) { Text("Duplicate note", color = SchedulerColors.Cream) }
                }
            }
        }
    }
        if (pickMoveFolder && page != null) {
            val nestedIds = pageDescendants(bundle.pages, page.id)
            val moveTargets = bundle.pages.filter { f ->
                f.kind == "folder" &&
                    f.id != page.id &&
                    f.id !in nestedIds &&
                    f.id != page.treeParent()
            }.sortedBy { pagePath(bundle.pages, it.id) }
            AlertDialog(
                onDismissRequest = { pickMoveFolder = false },
                containerColor = SchedulerColors.Sheet,
                titleContentColor = SchedulerColors.Cream,
                textContentColor = SchedulerColors.LabelMuted,
                title = { Text("Move into folder?") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Place \"${VaultSeal.displayTitle(page.title)}\" inside:",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        moveTargets.forEach { folder ->
                            TextButton(onClick = {
                                page.parentId = folder.id
                                pickMoveFolder = false
                                onChange(bundle.copy())
                            }) {
                                Text(
                                    pagePath(bundle.pages, folder.id),
                                    color = SchedulerColors.Cream,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { pickMoveFolder = false }) {
                        Text("Cancel", color = SchedulerColors.LabelMuted)
                    }
                },
            )
        }
        if (confirmPage != null) {
            val folder = confirmPage.kind == "folder"
            val name = VaultSeal.displayTitle(confirmPage.title)
            AlertDialog(
                onDismissRequest = { confirmDeleteId = null },
                containerColor = SchedulerColors.Sheet,
                titleContentColor = SchedulerColors.Cream,
                textContentColor = SchedulerColors.LabelMuted,
                title = { Text(if (folder) "Delete folder?" else "Delete note?") },
                text = {
                    val nested = pageDescendants(bundle.pages, confirmPage.id).size
                    Text(
                        if (folder) {
                            val extra = if (nested == 0) ""
                            else " This also removes $nested nested page${if (nested == 1) "" else "s"}."
                            "Are you sure to delete folder $name?$extra"
                        } else {
                            "Are you sure to delete $name?"
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val removeIds = pageDescendants(bundle.pages, confirmPage.id) + confirmPage.id
                        bundle.pages.removeAll { it.id in removeIds }
                        if (selectedId in removeIds) {
                            selectedId = bundle.pages.firstOrNull { it.kind != "folder" }?.id
                                ?: bundle.pages.firstOrNull()?.id
                        }
                        confirmDeleteId = null
                        onChange(bundle.copy())
                    }) { Text("Delete", color = SchedulerColors.Error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteId = null }) {
                        Text("Cancel", color = SchedulerColors.LabelMuted)
                    }
                },
            )
        }
        renameFolderId?.let { id ->
            AlertDialog(
                onDismissRequest = { renameFolderId = null },
                containerColor = SchedulerColors.Sheet,
                titleContentColor = SchedulerColors.Cream,
                textContentColor = SchedulerColors.LabelMuted,
                title = { Text("Rename folder") },
                text = {
                    OutlinedTextField(
                        renameDraft,
                        { renameDraft = it },
                        label = { Text("Folder name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val folder = bundle.pages.firstOrNull { it.id == id }
                        if (folder != null && !VaultSeal.isSealed(folder.title)) {
                            folder.title = renameDraft.trim().ifBlank { folder.title }
                            onChange(bundle.copy())
                        }
                        renameFolderId = null
                    }) { Text("Rename", color = SchedulerColors.Cream) }
                },
                dismissButton = {
                    TextButton(onClick = { renameFolderId = null }) {
                        Text("Cancel", color = SchedulerColors.LabelMuted)
                    }
                },
            )
        }
    }
}

@Composable
private fun NotePreviewPane(
    bundle: TypewriterBundle,
    page: TypewriterPage?,
    modifier: Modifier = Modifier,
    contentRev: Int = 0,
) {
    Column(modifier.fillMaxSize()) {
        if (page == null) {
            Text(
                "Tap a note to preview it.",
                color = SchedulerColors.OnDarkMuted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }
        val path = pagePath(bundle.pages, page.id)
        if (path.isNotBlank()) {
            Text(
                path,
                color = SchedulerColors.LabelMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (page.kind == "folder") {
            Text(
                "Folder — tap a note to preview, or close Pages ▾ to edit.",
                color = SchedulerColors.OnDarkMuted,
            )
            return@Column
        }
        Text(
            VaultSeal.displayTitle(page.title).ifBlank { "Note" },
            color = SchedulerColors.Cream,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val opened = VaultCrypto.open(page.body)
        if (VaultSeal.isSealed(opened)) {
            return@Column
        }
        if (TypewriterHtml.plainText(opened).isBlank()) {
            Text("Empty note.", color = SchedulerColors.OnDarkMuted)
        } else {
            HtmlEditorField(
                html = opened,
                onHtmlChange = {},
                editable = false,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentRev = contentRev,
            )
        }
        if (page.images.isNotEmpty()) {
            Text(
                page.images.joinToString(" · ") { it.name },
                color = SchedulerColors.OnDarkMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TreeMoveButtons(
    bundle: TypewriterBundle,
    page: TypewriterPage,
    onChange: (TypewriterBundle) -> Unit,
    onMoveInto: () -> Unit,
) {
    val parent = page.treeParent()
    val siblings = bundle.pages.filter { it.treeParent() == parent }.sortedBy { it.pos }
    val idx = siblings.indexOfFirst { it.id == page.id }
    val nestedIds = pageDescendants(bundle.pages, page.id)
    val moveTargets = bundle.pages.filter { f ->
        f.kind == "folder" &&
            f.id != page.id &&
            f.id !in nestedIds &&
            f.id != page.treeParent()
    }
    Column(Modifier.padding(top = 8.dp)) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (idx > 0) {
                TextButton(onClick = {
                    shiftPageAmongSiblings(bundle.pages, page.id, -1)
                    onChange(bundle.copy())
                }) { Text("Move up", color = SchedulerColors.Cream, fontSize = 13.sp) }
            }
            if (idx >= 0 && idx < siblings.lastIndex) {
                TextButton(onClick = {
                    shiftPageAmongSiblings(bundle.pages, page.id, 1)
                    onChange(bundle.copy())
                }) { Text("Move down", color = SchedulerColors.Cream, fontSize = 13.sp) }
            }
            if (parent != null) {
                TextButton(onClick = {
                    page.parentId = null
                    onChange(bundle.copy())
                }) { Text("Move to top level", color = SchedulerColors.Cream, fontSize = 13.sp) }
            }
            if (moveTargets.isNotEmpty()) {
                TextButton(onClick = onMoveInto) {
                    Text("Move into folder", color = SchedulerColors.Cream, fontSize = 13.sp)
                }
            }
        }
    }
}

private val editorInkColors = listOf(
    "#F3EEE4",
    "#FFFFFF",
    "#1A222C",
    "#E53935",
    "#FB8C00",
    "#FDD835",
    "#43A047",
    "#2F92E0",
    "#8E24AA",
)

private val editorFontSizes = listOf(8, 10, 12, 14, 18, 24, 36, 48, 56)

@Composable
private fun PageEditTools(
    pageSealed: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    currentSizePt: Int? = null,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCommand: (String) -> Unit,
    onSizePicked: (Int) -> Unit = {},
) {
    val live = !pageSealed
    val cream = SchedulerColors.Cream
    val muted = SchedulerColors.OnDarkMuted
    fun ink(on: Boolean) = if (on) cream else muted
    fun cmd(js: String) { if (live) onCommand(js) }
    var headingOpen by remember { mutableStateOf(false) }
    var sizeOpen by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }
    val noFocus = Modifier.focusProperties { canFocus = false }
    val sizeLabel = currentSizePt?.let { "$it pt" } ?: "Size"
    // One compact strip so the editor keeps the remaining height (no stacked tool rows).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .then(noFocus),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onUndo,
            enabled = canUndo && live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                tint = ink(canUndo && live),
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(
            onClick = onRedo,
            enabled = canRedo && live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                tint = ink(canRedo && live),
                modifier = Modifier.size(22.dp),
            )
        }
        TextButton(
            onClick = { cmd("document.execCommand('insertHTML',false,'&nbsp;&nbsp;&nbsp;&nbsp;')") },
            enabled = live,
            modifier = noFocus,
        ) {
            Text("Tab", color = ink(live))
        }
        IconButton(
            onClick = { cmd("document.execCommand('bold')") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatBold, "Bold", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = { cmd("document.execCommand('italic')") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatItalic, "Italic", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = { cmd("ashoreUnderline()") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatUnderlined, "Underline", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = { cmd("ashoreStrikeThrough()") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatStrikethrough, "Strikethrough", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = { cmd("document.execCommand('justifyLeft')") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatAlignLeft, "Align left", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = { cmd("document.execCommand('justifyCenter')") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatAlignCenter, "Align center", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        IconButton(
            onClick = { cmd("document.execCommand('justifyRight')") },
            enabled = live,
            modifier = Modifier.size(40.dp).then(noFocus),
        ) {
            Icon(Icons.Filled.FormatAlignRight, "Align right", tint = ink(live), modifier = Modifier.size(22.dp))
        }
        Box {
            IconButton(
                onClick = { headingOpen = true },
                enabled = live,
                modifier = Modifier.size(40.dp).then(noFocus),
            ) {
                Icon(Icons.Filled.Title, "Headings", tint = ink(live), modifier = Modifier.size(22.dp))
            }
            DropdownMenu(
                expanded = headingOpen,
                onDismissRequest = { headingOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                DropdownMenuItem(
                    text = { Text("Paragraph") },
                    onClick = { headingOpen = false; cmd("document.execCommand('formatBlock',false,'p')") },
                )
                DropdownMenuItem(
                    text = { Text("Heading 1") },
                    onClick = { headingOpen = false; cmd("document.execCommand('formatBlock',false,'h1')") },
                )
                DropdownMenuItem(
                    text = { Text("Heading 2") },
                    onClick = { headingOpen = false; cmd("document.execCommand('formatBlock',false,'h2')") },
                )
                DropdownMenuItem(
                    text = { Text("Heading 3") },
                    onClick = { headingOpen = false; cmd("document.execCommand('formatBlock',false,'h3')") },
                )
            }
        }
        Box {
            TextButton(
                onClick = { sizeOpen = true },
                enabled = live,
                modifier = noFocus,
            ) {
                Icon(
                    Icons.Filled.FormatSize,
                    contentDescription = "Size",
                    tint = ink(live),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    sizeLabel,
                    color = ink(live),
                    fontSize = 13.sp,
                    fontWeight = if (currentSizePt != null) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            DropdownMenu(
                expanded = sizeOpen,
                onDismissRequest = { sizeOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                editorFontSizes.forEach { pt ->
                    val selected = currentSizePt == pt
                    DropdownMenuItem(
                        text = {
                            Text(
                                "$pt pt",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) cream else Color.Unspecified,
                            )
                        },
                        onClick = {
                            sizeOpen = false
                            onSizePicked(pt)
                            cmd("ashoreFontSize($pt)")
                        },
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { colorOpen = true },
                enabled = live,
                modifier = Modifier.size(40.dp).then(noFocus),
            ) {
                Icon(Icons.Filled.FormatColorText, "Color", tint = ink(live), modifier = Modifier.size(22.dp))
            }
            DropdownMenu(
                expanded = colorOpen,
                onDismissRequest = { colorOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                editorInkColors.forEach { hex ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                        .border(1.dp, SchedulerColors.OnDarkMuted, RoundedCornerShape(3.dp)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(hex)
                            }
                        },
                        onClick = {
                            colorOpen = false
                            cmd("ashoreForeColor('$hex')")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageTree(
    pages: List<TypewriterPage>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val byParent = pages.groupBy { it.treeParent() }
    val ancestors = remember(selectedId, pages) { pageAncestors(pages, selectedId) }
    var expanded by remember { mutableStateOf(ancestors) }
    LaunchedEffect(selectedId) {
        expanded = expanded + ancestors
    }
    PageTreeLevel(
        byParent = byParent,
        parentId = null,
        depth = 0,
        selectedId = selectedId,
        expanded = expanded,
        onToggle = { id ->
            expanded = if (id in expanded) expanded - id else expanded + id
        },
        onSelect = onSelect,
        onRenameFolder = onRenameFolder,
        onDelete = onDelete,
    )
}

@Composable
private fun PageTreeLevel(
    byParent: Map<String?, List<TypewriterPage>>,
    parentId: String?,
    depth: Int,
    selectedId: String?,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    for (p in (byParent[parentId] ?: emptyList()).sortedBy { it.pos }) {
        val kids = byParent[p.id].orEmpty()
        val isFolder = p.kind == "folder"
        val open = isFolder && p.id in expanded
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 12).dp),
        ) {
            Text(
                when {
                    isFolder && open -> "▾"
                    isFolder -> "▸"
                    else -> " "
                },
                color = SchedulerColors.Cream,
                fontSize = 14.sp,
                modifier = Modifier
                    .width(20.dp)
                    .clickable(enabled = isFolder) { onToggle(p.id) },
            )
            Text(
                buildString {
                    append(VaultSeal.displayTitle(p.title))
                    if (isFolder && kids.isNotEmpty()) append(" · ${kids.size}")
                    else if (p.images.isNotEmpty()) append(" · ${p.images.size}")
                },
                color = if (p.id == selectedId) SchedulerColors.Cream else SchedulerColors.OnDarkMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(p.id) }
                    .padding(vertical = 6.dp),
            )
            if (isFolder && !VaultSeal.isSealed(p.title)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Rename folder",
                    tint = SchedulerColors.OnDarkMuted,
                    modifier = Modifier
                        .clickable { onRenameFolder(p.id) }
                        .padding(8.dp)
                        .size(16.dp),
                )
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = if (isFolder) "Delete folder" else "Delete note",
                tint = SchedulerColors.Error,
                modifier = Modifier
                    .clickable { onDelete(p.id) }
                    .padding(8.dp)
                    .size(16.dp),
            )
        }
        if (!isFolder || open) {
            PageTreeLevel(
                byParent, p.id, depth + 1, selectedId, expanded,
                onToggle, onSelect, onRenameFolder, onDelete,
            )
        }
    }
}

private fun pageAncestors(pages: List<TypewriterPage>, id: String?): Set<String> {
    if (id == null) return emptySet()
    val byId = pages.associateBy { it.id }
    val out = HashSet<String>()
    var cur = byId[id]?.treeParent()
    val seen = HashSet<String>()
    while (cur != null && seen.add(cur)) {
        out.add(cur)
        cur = byId[cur]?.treeParent()
    }
    return out
}

private fun TypewriterPage.treeParent(): String? {
    val p = parentId
    return if (p.isNullOrBlank() || p.equals("root", ignoreCase = true)) null else p
}

private fun swapLists(lists: MutableList<TypewriterList>, a: Int, b: Int) {
    if (a !in lists.indices || b !in lists.indices || a == b) return
    val tmp = lists[a]
    lists[a] = lists[b]
    lists[b] = tmp
}

private fun swapListItems(items: MutableList<TypewriterListItem>, a: Int, b: Int) {
    if (a !in items.indices || b !in items.indices || a == b) return
    val tmp = items[a]
    items[a] = items[b]
    items[b] = tmp
}

private fun shiftPageAmongSiblings(pages: MutableList<TypewriterPage>, id: String, delta: Int) {
    val page = pages.firstOrNull { it.id == id } ?: return
    val parent = page.treeParent()
    val siblings = pages.filter { it.treeParent() == parent }.sortedBy { it.pos }.toMutableList()
    val idx = siblings.indexOfFirst { it.id == id }
    val dest = idx + delta
    if (idx < 0 || dest !in siblings.indices) return
    siblings.add(dest, siblings.removeAt(idx))
    siblings.forEachIndexed { i, p -> p.pos = i }
}

private fun nestParent(page: TypewriterPage?): String? = when {
    page == null -> null
    page.kind == "folder" -> page.id
    else -> page.parentId
}

private fun pageDescendants(pages: List<TypewriterPage>, id: String): Set<String> {
    val kids = pages.filter { it.treeParent() == id }
    return kids.flatMap { pageDescendants(pages, it.id) + it.id }.toSet()
}

private fun pagePath(pages: List<TypewriterPage>, id: String): String {
    val byId = pages.associateBy { it.id }
    val names = ArrayList<String>()
    var cur: TypewriterPage? = byId[id]
    val seen = HashSet<String>()
    while (cur != null && seen.add(cur.id)) {
        names.add(0, VaultSeal.displayTitle(cur.title))
        cur = cur.treeParent()?.let { byId[it] }
    }
    return names.joinToString(" / ")
}

@Composable
private fun ListsPane(bundle: TypewriterBundle, onChange: (TypewriterBundle) -> Unit) {
    var pendingDelete by remember { mutableStateOf<TypewriterList?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<Pair<TypewriterList, TypewriterListItem>?>(null) }
    var pendingClear by remember { mutableStateOf<TypewriterList?>(null) }
    var selectedListId by remember { mutableStateOf(bundle.lists.firstOrNull()?.id) }
    val list = bundle.lists.firstOrNull { it.id == selectedListId }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(176.dp)
                    .padding(end = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = {
                    val created = TypewriterList(title = "List ${bundle.lists.size + 1}")
                    bundle.lists.add(created)
                    selectedListId = created.id
                    onChange(bundle.copy())
                }) { Text("Add list", color = SchedulerColors.Cream, fontSize = 13.sp) }
                if (bundle.lists.isEmpty()) {
                    Text(
                        "No lists yet.",
                        color = SchedulerColors.OnDarkMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                bundle.lists.forEach { row ->
                    Text(
                        VaultSeal.displayTitle(row.title).ifBlank { "List" },
                        color = if (row.id == selectedListId) SchedulerColors.Cream else SchedulerColors.OnDarkMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedListId = row.id }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 4.dp),
        ) {
        if (list == null) {
            Text(
                if (bundle.lists.isEmpty()) "Add a list from the column on the left."
                else "Pick a list from the column on the left.",
                color = SchedulerColors.OnDarkMuted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            key(list.id) {
            val done = list.items.count { it.done }
            var hideDone by remember { mutableStateOf(false) }
            val visible = if (hideDone) list.items.filter { !it.done } else list.items
            val listSealed = VaultSeal.isSealed(list.title) || list.items.any { VaultSeal.isSealed(it.text) }
            OutlinedTextField(
                VaultSeal.displayTitle(list.title),
                {
                    if (!VaultSeal.isSealed(list.title)) {
                        list.title = it
                        onChange(bundle.copy())
                    }
                },
                enabled = !listSealed,
                label = {
                    Text(
                        if (list.items.isEmpty()) "List title"
                        else "List title · $done/${list.items.size} done",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors,
            )
            if (done > 0) {
                Row {
                    TextButton(onClick = { hideDone = !hideDone }) {
                        Text(
                            if (hideDone) "Show completed ($done)" else "Hide completed",
                            color = SchedulerColors.Cream,
                        )
                    }
                    if (!listSealed) {
                        TextButton(onClick = { pendingClear = list }) {
                            Text("Clear completed", color = SchedulerColors.Error)
                        }
                    }
                }
            }
            if (list.items.isEmpty()) {
                Text(
                    "No items — tap Add item.",
                    color = SchedulerColors.OnDarkMuted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else if (visible.isEmpty()) {
                Text(
                    "$done completed hidden.",
                    color = SchedulerColors.OnDarkMuted,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            val shownItems = list.items.withIndex().filter { !hideDone || !it.value.done }
            shownItems.forEach { (index, item) ->
                val shownAt = shownItems.indexOfFirst { it.index == index }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        item.done,
                        {
                            if (!VaultSeal.isSealed(item.text)) {
                                item.done = it
                                onChange(bundle.copy())
                            }
                        },
                        enabled = !VaultSeal.isSealed(item.text),
                        colors = CheckboxDefaults.colors(
                            checkedColor = SchedulerColors.Cream,
                            checkmarkColor = SchedulerColors.DarkChrome,
                        ),
                    )
                    OutlinedTextField(
                        if (TypewriterHtml.looksLikeMarkup(item.text)) {
                            TypewriterHtml.plainText(item.text)
                        } else {
                            VaultSeal.displayTitle(item.text)
                        },
                        {
                            if (VaultSeal.isSealed(item.text)) return@OutlinedTextField
                            val shown = if (TypewriterHtml.looksLikeMarkup(item.text)) {
                                TypewriterHtml.plainText(item.text)
                            } else {
                                item.text
                            }
                            if (it == shown || it == item.text) return@OutlinedTextField
                            item.text = it
                            onChange(bundle.copy())
                        },
                        enabled = !VaultSeal.isSealed(item.text),
                        modifier = Modifier.weight(1f),
                        colors = fieldColors,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (item.done) SchedulerColors.OnDarkMuted else SchedulerColors.Cream,
                        ),
                    )
                    if (!listSealed) {
                        Column {
                            if (shownAt > 0) {
                                TextButton(onClick = {
                                    swapListItems(list.items, index, shownItems[shownAt - 1].index)
                                    onChange(bundle.copy())
                                }) { Text("↑", color = SchedulerColors.Cream) }
                            }
                            if (shownAt >= 0 && shownAt < shownItems.lastIndex) {
                                TextButton(onClick = {
                                    swapListItems(list.items, index, shownItems[shownAt + 1].index)
                                    onChange(bundle.copy())
                                }) { Text("↓", color = SchedulerColors.Cream) }
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            if (VaultSeal.isSealed(item.text)) return@TextButton
                            if (item.text.isBlank()) {
                                list.items.remove(item)
                                onChange(bundle.copy())
                            } else {
                                pendingDeleteItem = list to item
                            }
                        },
                        enabled = !VaultSeal.isSealed(item.text),
                    ) { Text("×", color = SchedulerColors.Error) }
                }
            }
            if (!listSealed) {
                TextButton(onClick = {
                    list.items.add(TypewriterListItem())
                    onChange(bundle.copy())
                }) { Text("Add item", color = SchedulerColors.Cream) }
            }
            run {
                val idx = bundle.lists.indexOfFirst { it.id == list.id }
                if (idx > 0 || (idx >= 0 && idx < bundle.lists.lastIndex)) {
                    Row {
                        if (idx > 0) {
                            TextButton(onClick = {
                                swapLists(bundle.lists, idx, idx - 1)
                                onChange(bundle.copy())
                            }) { Text("Move list up", color = SchedulerColors.Cream) }
                        }
                        if (idx >= 0 && idx < bundle.lists.lastIndex) {
                            TextButton(onClick = {
                                swapLists(bundle.lists, idx, idx + 1)
                                onChange(bundle.copy())
                            }) { Text("Move list down", color = SchedulerColors.Cream) }
                        }
                    }
                }
            }
            TextButton(onClick = {
                val sealed = VaultSeal.isSealed(list.title)
                val copy = TypewriterList(
                    title = if (sealed) list.title
                    else "${list.title.trim().ifBlank { "List" }} copy",
                    items = list.items.map { TypewriterListItem(text = it.text, done = it.done) }
                        .toMutableList(),
                )
                bundle.lists.add(copy)
                selectedListId = copy.id
                onChange(bundle.copy())
            }) { Text("Duplicate list", color = SchedulerColors.Cream) }
            TextButton(onClick = { pendingDelete = list }) {
                Text("Delete list", color = SchedulerColors.Error)
            }
            Spacer(Modifier.height(12.dp))
            }
        }
        }
        }
    pendingClear?.let { list ->
        val n = list.items.count { it.done }
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Clear completed?") },
            text = {
                Text(
                    "This removes $n checked item${if (n == 1) "" else "s"} from \"${VaultSeal.displayTitle(list.title, "this list")}\".",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    list.items.removeAll { it.done }
                    pendingClear = null
                    onChange(bundle.copy())
                }) { Text("Clear", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
    pendingDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Delete list?") },
            text = {
                Text("This removes \"${VaultSeal.displayTitle(list.title, "this list")}\" and its items.")
            },
            confirmButton = {
                TextButton(onClick = {
                    bundle.lists.remove(list)
                    selectedListId = bundle.lists.firstOrNull()?.id
                    pendingDelete = null
                    onChange(bundle.copy())
                }) { Text("Delete", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
    pendingDeleteItem?.let { (list, item) ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Delete item?") },
            text = {
                Text("This removes \"${VaultSeal.displayTitle(item.text, "this item")}\".")
            },
            confirmButton = {
                TextButton(onClick = {
                    list.items.remove(item)
                    pendingDeleteItem = null
                    onChange(bundle.copy())
                }) { Text("Delete", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
    }
}

@Composable
private fun ImageRefStrip(
    images: MutableList<TypewriterImageRef>,
    onChange: () -> Unit,
    onAttach: () -> Unit,
    onPreview: (sha: String) -> Unit,
) {
    var pendingDetach by remember { mutableStateOf<TypewriterImageRef?>(null) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (images.isEmpty()) "Images — none attached"
                else "Images (${images.size}) — tap to preview, × to detach",
                color = SchedulerColors.LabelMuted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAttach) { Text("Attach", color = SchedulerColors.Cream) }
        }
        if (images.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                images.toList().forEach { img ->
                    Row(
                        Modifier
                            .background(SchedulerColors.Paper)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            img.name.ifBlank { "image" },
                            color = SchedulerColors.PaperInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 180.dp)
                                .clickable { onPreview(img.id) },
                        )
                        Text(
                            " ×",
                            color = SchedulerColors.Error,
                            modifier = Modifier.clickable { pendingDetach = img },
                        )
                    }
                }
            }
        }
    }
    pendingDetach?.let { img ->
        AlertDialog(
            onDismissRequest = { pendingDetach = null },
            containerColor = SchedulerColors.Sheet,
            titleContentColor = SchedulerColors.Cream,
            textContentColor = SchedulerColors.LabelMuted,
            title = { Text("Detach image?") },
            text = { Text("Remove \"${img.name}\" from this entry. The file stays on the server.") },
            confirmButton = {
                TextButton(onClick = {
                    images.removeAll { it.id == img.id }
                    pendingDetach = null
                    onChange()
                }) { Text("Detach", color = SchedulerColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDetach = null }) {
                    Text("Cancel", color = SchedulerColors.LabelMuted)
                }
            },
        )
    }
}
