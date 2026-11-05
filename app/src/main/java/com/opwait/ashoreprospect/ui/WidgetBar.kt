package com.opwait.ashoreprospect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opwait.ashoreprospect.ui.theme.SchedulerColors

@Composable
fun WidgetBar(
    selected: MainTab,
    typewriterDirty: Boolean,
    onSelect: (MainTab) -> Unit,
) {
    val tabs = MainTab.entries
    var drag by remember { mutableFloatStateOf(0f) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SchedulerColors.DarkChrome)
            .pointerInput(selected) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        val idx = tabs.indexOf(selected)
                        when {
                            drag < -56f && idx < tabs.lastIndex -> onSelect(tabs[idx + 1])
                            drag > 56f && idx > 0 -> onSelect(tabs[idx - 1])
                        }
                        drag = 0f
                    },
                )
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val on = tab == selected
            Text(
                tab.widgetLabel(typewriterDirty && tab == MainTab.TYPEWRITER),
                fontSize = 13.sp,
                color = if (on) SchedulerColors.DarkChrome else SchedulerColors.LabelMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) SchedulerColors.Cream else SchedulerColors.ChipOnDark)
                    .then(
                        if (on) Modifier else Modifier.border(
                            1.dp,
                            SchedulerColors.SheetLine,
                            RoundedCornerShape(20.dp),
                        ),
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
