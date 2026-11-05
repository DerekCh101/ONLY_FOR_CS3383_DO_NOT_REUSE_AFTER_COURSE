package com.opwait.ashoreprospect.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface NodeApiService {
    @POST("register")
    suspend fun register(@Body body: AuthRequest): AuthResponse

    @POST("login")
    suspend fun login(@Body body: AuthRequest): AuthResponse

    @POST("forgot")
    suspend fun forgot(@Body body: ForgotRequest): OkResponse

    @POST("reset")
    suspend fun reset(@Body body: ResetRequest): OkResponse

    @GET("me")
    suspend fun me(@Header("Authorization") auth: String): MeResponse

    @POST("me/email")
    suspend fun saveEmail(@Header("Authorization") auth: String, @Body body: EmailRequest): OkResponse

    @POST("me/username")
    suspend fun saveUsername(@Header("Authorization") auth: String, @Body body: UsernameRequest): MeResponse

    @POST("me/security")
    suspend fun saveSecurity(@Header("Authorization") auth: String, @Body body: SecurityRequest): MeResponse

    @POST("change-password")
    suspend fun changePassword(
        @Header("Authorization") auth: String,
        @Body body: ChangePasswordRequest,
    ): OkResponse

    @GET("challenge-question")
    suspend fun challengeQuestion(@Header("Authorization") auth: String): ChallengeQuestionResponse

    @POST("challenge-answer")
    suspend fun challengeAnswer(
        @Header("Authorization") auth: String,
        @Body body: ChallengeAnswerRequest,
    ): OkResponse

    @GET("account-status")
    suspend fun accountStatus(@Query("userId") userId: String): AccountStatusResponse

    @GET("sessions")
    suspend fun getSessions(@Header("Authorization") auth: String): SessionsResponse

    @POST("sessions/revoke-others")
    suspend fun revokeOtherSessions(@Header("Authorization") auth: String): OkResponse

    @DELETE("sessions/{id}")
    suspend fun revokeSession(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
    ): RevokeSessionResponse

    @POST("me/delete")
    suspend fun deleteAccount(
        @Header("Authorization") auth: String,
        @Body body: DeleteAccountRequest,
    ): OkResponse

    @GET("tasks")
    suspend fun getTasks(@Header("Authorization") auth: String): TasksResponse

    @POST("tasks")
    suspend fun postTask(@Header("Authorization") auth: String, @Body body: TaskDto): TaskDto

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Header("Authorization") auth: String, @Path("id") id: String): TaskDto

    @GET("blocked")
    suspend fun getBlocked(@Header("Authorization") auth: String): BlockedResponse

    @POST("blocked")
    suspend fun postBlocked(@Header("Authorization") auth: String, @Body body: BlockedDto): BlockedDto

    @DELETE("blocked/{id}")
    suspend fun deleteBlocked(@Header("Authorization") auth: String, @Path("id") id: String): BlockedDto

    @GET("typewriter")
    suspend fun getTypewriter(@Header("Authorization") auth: String): TypewriterDto

    @POST("typewriter")
    suspend fun postTypewriter(@Header("Authorization") auth: String, @Body body: TypewriterDto): TypewriterDto

    @GET("typewriter/images/{sha}")
    suspend fun getTypewriterImage(
        @Header("Authorization") auth: String,
        @Path("sha") sha: String,
        @Query("meta") meta: Int? = null,
    ): TypewriterImageDto

    @POST("typewriter/images")
    suspend fun postTypewriterImage(
        @Header("Authorization") auth: String,
        @Body body: TypewriterImageDto,
    ): TypewriterImageDto
}
