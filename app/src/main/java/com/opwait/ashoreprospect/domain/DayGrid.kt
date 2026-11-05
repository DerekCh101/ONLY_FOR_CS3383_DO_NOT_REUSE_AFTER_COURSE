package com.opwait.ashoreprospect.domain

import java.time.LocalDate
import java.time.LocalDateTime

class DayGrid {
    private val slots = arrayOfNulls<Task>(AppTimeReference.SLOTS_PER_DAY)
    var overloadFlag: Boolean = false
        private set
    var availableSlots: Int = AppTimeReference.SLOTS_PER_DAY
        private set

    fun returnDayGrid(): Array<Task?> = slots

    fun canPlace(
        task: Task,
        columnDate: LocalDate,
        slotIndex: Int,
        ignoreOverload: Boolean,
        ignoreStartInPast: Boolean = false,
    ): Boolean {
        val slotCount = maxOf(1, task.durationSlots)
        if (slotIndex < 0 || slotIndex + slotCount > AppTimeReference.SLOTS_PER_DAY) return false
        val slotStart = AppTimeReference.dateTimeForSlot(columnDate, slotIndex)
        if (!ignoreStartInPast && !slotStart.isAfter(AppTimeReference.getCurrentDateTime())) return false
        if (!ignoreOverload && overloadFlag) return false
        for (i in 0 until slotCount) {
            val existing = slots[slotIndex + i]
            if (existing != null && existing.id != task.id) return false
        }
        return true
    }

    fun placeTask(slotIndex: Int, task: Task) {
        if (slotIndex < 0 || slotIndex >= slots.size) return
        var slotCount = task.durationSlots
        if (slotCount < 1) slotCount = 1
        if (slotIndex + slotCount > slots.size) {
            slotCount = slots.size - slotIndex
        }
        for (i in 0 until slotCount) {
            slots[slotIndex + i] = task
        }
        recomputeLoadFromGrid()
    }

    fun recomputeLoadFromGrid() {
        val used = slots.count { it != null }
        availableSlots = AppTimeReference.SLOTS_PER_DAY - used
        overloadFlag = availableSlots <= AppTimeReference.SLOTS_PER_HOUR
    }

    fun clearTaskFromGrid(task: Task) {
        for (i in slots.indices) {
            if (slots[i]?.id == task.id) slots[i] = null
        }
        recomputeLoadFromGrid()
    }
}
