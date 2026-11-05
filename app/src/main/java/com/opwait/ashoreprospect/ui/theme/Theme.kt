package com.opwait.ashoreprospect.ui.theme

import android.app.Activity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun ashoreprospectTheme(
    darkTheme: Boolean = AppThemePack.isDark,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = SchedulerColors.Cream,
            onPrimary = SchedulerColors.DarkChrome,
            secondary = SchedulerColors.LabelMuted,
            background = SchedulerColors.DarkChrome,
            surface = SchedulerColors.DarkChrome,
            onBackground = SchedulerColors.Cream,
            onSurface = SchedulerColors.Cream,
            surfaceVariant = SchedulerColors.Sheet,
            onSurfaceVariant = SchedulerColors.LabelMuted,
            error = SchedulerColors.Error,
        )
    } else {
        lightColorScheme(
            primary = SchedulerColors.Cream,
            onPrimary = SchedulerColors.DarkChrome,
            secondary = SchedulerColors.LabelMuted,
            background = SchedulerColors.DarkChrome,
            surface = SchedulerColors.DarkChrome,
            onBackground = SchedulerColors.Cream,
            onSurface = SchedulerColors.Cream,
            surfaceVariant = SchedulerColors.Sheet,
            onSurfaceVariant = SchedulerColors.LabelMuted,
            error = SchedulerColors.Error,
        )
    }
    val view = LocalView.current
    val bar = SchedulerColors.DarkChrome.toArgb()
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = bar
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = SchedulerTypography,
        content = content,
    )
}

/** JavaFX Account `ThemeSwitch`: pill, knob left = light, right = dark. */
@Composable
fun AshoreThemeSwitch(
    dark: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knobX by animateDpAsState(if (dark) 24.dp else 2.dp, tween(220), label = "theme-knob")
    Box(
        modifier
            .size(width = 52.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (dark) SchedulerColors.ChipOnDark else Color(0xFFE0D6C6))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobX)
                .size(24.dp)
                .clip(CircleShape)
                .background(SchedulerColors.Paper),
        )
    }
}

@Composable
fun schedulerOutlinedFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SchedulerColors.Cream,
    unfocusedTextColor = SchedulerColors.Cream,
    disabledTextColor = SchedulerColors.LabelMuted,
    focusedLabelColor = SchedulerColors.LabelMuted,
    unfocusedLabelColor = SchedulerColors.LabelMuted,
    cursorColor = SchedulerColors.Cream,
    focusedBorderColor = SchedulerColors.Cream,
    unfocusedBorderColor = SchedulerColors.OnDarkMuted,
)

@Composable
fun schedulerCreamFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = SchedulerColors.PaperInk,
    unfocusedTextColor = SchedulerColors.PaperInk,
    focusedLabelColor = SchedulerColors.PaperMuted,
    unfocusedLabelColor = SchedulerColors.PaperMuted,
    cursorColor = SchedulerColors.PaperInk,
    focusedBorderColor = SchedulerColors.PaperInk,
    unfocusedBorderColor = SchedulerColors.PaperMuted,
)

@Composable
fun schedulerPrimaryButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = SchedulerColors.Cream,
    contentColor = SchedulerColors.DarkChrome,
    disabledContainerColor = SchedulerColors.Cream.copy(alpha = 0.4f),
    disabledContentColor = SchedulerColors.DarkChrome.copy(alpha = 0.6f),
)
