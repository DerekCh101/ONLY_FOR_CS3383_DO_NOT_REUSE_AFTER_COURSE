package com.opwait.ashoreprospect.data.local

data class TaskEntity(
    val id: String,
    val title: String,
    val duration: Double,
    val state: String,
    val placedDate: String?,
    val placedTime: String?,
    val deadlineDate: String?,
    val deadlineTime: String?,
    val startingDate: String?,
    val startingTime: String?,
    val lifecycle: String,
    val workflow: String,
    val placementUpdatedAt: String?,
    val lastUpdatedAt: String?,
    val userId: String?,
    val description: String = "",
    val category: String = "",
    val subcategory: String = "",
    val descriptionDone: Boolean = false,
    val parentId: String = "",
)
