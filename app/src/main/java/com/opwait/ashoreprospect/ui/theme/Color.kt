package com.opwait.ashoreprospect.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** JavaFX `BuiltinThemes` packs. Widgets read [SchedulerColors], not raw hex. */
data class SchedulerPalette(
    val DarkChrome: Color,
    val Cream: Color,
    val CreamMuted: Color,
    val OnDarkMuted: Color,
    val GridLine: Color,
    val TaskOngoing: Color,
    val TaskCurrent: Color,
    val DarkInk: Color,
    val TaskFinished: Color,
    val TaskPast: Color,
    val TaskPastText: Color,
    val TaskOverdue: Color,
    val NowLine: Color,
    val SlotHighlight: Color,
    val ChipOnDark: Color,
    val BlockedSlot: Color,
    val SlotPast: Color,
    val Skeleton: Color,
    val Sheet: Color,
    val SheetLine: Color,
    val Error: Color,
    val LabelMuted: Color,
    val Paper: Color,
    val PaperInk: Color,
    val PaperMuted: Color,
    val isDark: Boolean,
)

/** Current Android chrome — cream ink on `#1C2430`. */
val SchedulerDarkPalette = SchedulerPalette(
    DarkChrome = Color(0xFF1C2430),
    Cream = Color(0xFFF3EEE4),
    CreamMuted = Color(0xFF5C564C),
    OnDarkMuted = Color(0xFFC9C2B6),
    GridLine = Color(0x22000000),
    TaskOngoing = Color(0xFF2E7D32),
    TaskCurrent = Color(0xFF2F92E0),
    DarkInk = Color(0xFFE6E8EA),
    TaskFinished = Color(0xFF3E4A54),
    TaskPast = Color(0xFF5C636C),
    TaskPastText = Color(0xFF9AA3AD),
    TaskOverdue = Color(0xFFC62828),
    NowLine = Color(0xFFE53935),
    SlotHighlight = Color(0x33E53935),
    ChipOnDark = Color(0xFF2E3848),
    BlockedSlot = Color(0x553A2A24),
    SlotPast = Color(0xFF10151C),
    Skeleton = Color(0x33263A48),
    Sheet = Color(0xFF222A36),
    SheetLine = Color(0x33F3EEE4),
    Error = Color(0xFF8B1E1E),
    LabelMuted = Color(0xFFD7DDE6),
    Paper = Color(0xFFF3EEE4),
    PaperInk = Color(0xFF1C2430),
    PaperMuted = Color(0xFF5C564C),
    isDark = true,
)

/** JavaFX `BuiltinThemes.LIGHT` — cream page, dark ink, white cards. */
val SchedulerLightPalette = SchedulerPalette(
    DarkChrome = Color(0xFFF3EEE4),
    Cream = Color(0xFF1C2430),
    CreamMuted = Color(0xFF5C564C),
    OnDarkMuted = Color(0xFF5C564C),
    GridLine = Color(0x22000000),
    TaskOngoing = Color(0xFF2E7D32),
    TaskCurrent = Color(0xFF2F92E0),
    DarkInk = Color(0xFF1C2430),
    TaskFinished = Color(0xFFBDC8C8),
    TaskPast = Color(0xFF8A9AAA),
    TaskPastText = Color(0xFF5C564C),
    TaskOverdue = Color(0xFFC62828),
    NowLine = Color(0xFFE53935),
    SlotHighlight = Color(0x3370D9F1),
    ChipOnDark = Color(0xFFE0D6C6),
    BlockedSlot = Color(0x55E0D6C6),
    SlotPast = Color(0xFFBDC8C8),
    Skeleton = Color(0x33E0D6C6),
    Sheet = Color(0xFFFBF7EF),
    SheetLine = Color(0x331C2430),
    Error = Color(0xFF8B1E1E),
    LabelMuted = Color(0xFF5C564C),
    Paper = Color(0xFFFFFFFF),
    PaperInk = Color(0xFF1C2430),
    PaperMuted = Color(0xFF5C564C),
    isDark = false,
)

object AppThemePack {
    const val DARK = "dark"
    const val LIGHT = "light"

    var id by mutableStateOf(DARK)
        private set

    val isDark: Boolean get() = id != LIGHT

    fun normalize(raw: String?): String =
        if (raw.equals(LIGHT, ignoreCase = true)) LIGHT else DARK

    fun apply(pack: String) {
        id = normalize(pack)
    }

    fun toggle() {
        apply(if (isDark) LIGHT else DARK)
    }
}

object SchedulerColors {
    private val p: SchedulerPalette
        get() = if (AppThemePack.isDark) SchedulerDarkPalette else SchedulerLightPalette

    val DarkChrome get() = p.DarkChrome
    val Cream get() = p.Cream
    val CreamMuted get() = p.CreamMuted
    val OnDarkMuted get() = p.OnDarkMuted
    val GridLine get() = p.GridLine
    val TaskOngoing get() = p.TaskOngoing
    val TaskCurrent get() = p.TaskCurrent
    val DarkInk get() = p.DarkInk
    val TaskFinished get() = p.TaskFinished
    val TaskPast get() = p.TaskPast
    val TaskPastText get() = p.TaskPastText
    val TaskOverdue get() = p.TaskOverdue
    val NowLine get() = p.NowLine
    val SlotHighlight get() = p.SlotHighlight
    val ChipOnDark get() = p.ChipOnDark
    val BlockedSlot get() = p.BlockedSlot
    val SlotPast get() = p.SlotPast
    val Skeleton get() = p.Skeleton
    val Sheet get() = p.Sheet
    val SheetLine get() = p.SheetLine
    val Error get() = p.Error
    val LabelMuted get() = p.LabelMuted
    val Paper get() = p.Paper
    val PaperInk get() = p.PaperInk
    val PaperMuted get() = p.PaperMuted
    val isDark get() = p.isDark
}

fun Color.cssHex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
