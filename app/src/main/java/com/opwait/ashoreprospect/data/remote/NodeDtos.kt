package com.opwait.ashoreprospect.data.remote

import com.google.gson.annotations.SerializedName

data class AuthRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val question: String? = null,
    val answer: String? = null,
    val device: String? = null,
)

data class AuthResponse(
    val id: String? = null,
    @SerializedName("user_id") val userIdAlt: String? = null,
    val username: String? = null,
    val token: String? = null,
) {
    fun resolvedUserId(): String? = id ?: userIdAlt
}

data class ForgotRequest(val username: String, val email: String)

data class ResetRequest(val token: String, val answer: String, val newPassword: String)

data class OkResponse(val ok: Boolean? = null)

data class MeResponse(
    val id: String? = null,
    val username: String? = null,
    val email: String? = null,
    val hasSecurity: Boolean? = null,
    val securityQuestion: String? = null,
)

data class EmailRequest(val email: String)

data class UsernameRequest(val username: String)

data class SecurityRequest(val question: String, val answer: String)

data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

data class ChallengeQuestionResponse(
    val hasSecurity: Boolean? = null,
    val securityQuestion: String? = null,
)

data class ChallengeAnswerRequest(val answer: String)

data class AccountStatusResponse(val deleted: Boolean? = null)

data class SessionsResponse(val sessions: List<SessionDto>? = null)

data class SessionDto(
    val id: String? = null,
    val device: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean? = null,
)

data class RevokeSessionResponse(val ok: Boolean? = null, val current: Boolean? = null)

data class DeleteAccountRequest(val password: String)

data class TasksResponse(
    val tasks: List<TaskDto>? = null,
    val nested: List<TaskDto>? = null,
)

data class TaskDto(
    val id: String? = null,
    val title: String? = null,
    val duration: Double? = null,
    val state: String? = null,
    @SerializedName("placed_date") val placedDate: String? = null,
    @SerializedName("placed_time") val placedTime: String? = null,
    @SerializedName("deadline_date") val deadlineDate: String? = null,
    @SerializedName("deadline_time") val deadlineTime: String? = null,
    @SerializedName("starting_date") val startingDate: String? = null,
    @SerializedName("starting_time") val startingTime: String? = null,
    val lifecycle: String? = null,
    val workflow: String? = null,
    @SerializedName("placement_updated_at") val placementUpdatedAt: String? = null,
    @SerializedName("last_updated_at") val lastUpdatedAt: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val description: String? = null,
    @SerializedName("description_done") val descriptionDone: Boolean? = null,
    @SerializedName(value = "parent_id", alternate = ["parentId"]) val parentId: String? = null,
)

data class BlockedResponse(val blocked: List<BlockedDto>?)

data class BlockedDto(
    val id: String? = null,
    val title: String? = null,
    val weekday: Int? = null,
    @SerializedName("specific_date") val specificDate: String? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("last_updated_at") val lastUpdatedAt: String? = null,
    @SerializedName("is_exception") val isException: Boolean? = null,
)

data class TypewriterDto(
    val content: String? = null,
    @SerializedName("last_updated_at") val lastUpdatedAt: String? = null,
)

data class TypewriterImageDto(
    val sha256: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val data: String? = null,
    val exists: Boolean? = null,
    val ok: Boolean? = null,
    val existed: Boolean? = null,
)
