package com.opwait.ashoreprospect.data

import android.content.Context
import com.opwait.ashoreprospect.data.local.SimpleTaskDb
import android.os.Build
import com.opwait.ashoreprospect.data.remote.AuthRequest
import com.opwait.ashoreprospect.data.remote.AuthResponse
import com.opwait.ashoreprospect.data.remote.ChallengeAnswerRequest
import com.opwait.ashoreprospect.data.remote.ChangePasswordRequest
import com.opwait.ashoreprospect.data.remote.DeleteAccountRequest
import com.opwait.ashoreprospect.data.remote.EmailRequest
import com.opwait.ashoreprospect.data.remote.RevokeSessionResponse
import com.opwait.ashoreprospect.data.remote.SessionDto
import com.opwait.ashoreprospect.data.remote.ForgotRequest
import com.opwait.ashoreprospect.data.remote.MeResponse
import com.opwait.ashoreprospect.data.remote.NodeApiService
import android.util.Base64
import com.opwait.ashoreprospect.data.remote.ResetRequest
import com.opwait.ashoreprospect.data.remote.SecurityRequest
import com.opwait.ashoreprospect.data.remote.TypewriterDto
import com.opwait.ashoreprospect.data.remote.TypewriterImageDto
import com.opwait.ashoreprospect.data.remote.UsernameRequest
import com.opwait.ashoreprospect.domain.AuthRules
import com.opwait.ashoreprospect.domain.BlockedTime
import com.opwait.ashoreprospect.domain.Task
import com.opwait.ashoreprospect.domain.TypewriterBundle
import com.opwait.ashoreprospect.domain.TypewriterHtml
import com.opwait.ashoreprospect.domain.ImageMetadataStrip
import com.opwait.ashoreprospect.domain.VaultCrypto
import com.opwait.ashoreprospect.domain.VaultSeal
import com.opwait.ashoreprospect.domain.TypewriterImageRef
import java.security.MessageDigest
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

data class WorkspaceSnapshot(
    val tasks: List<Task>,
    val blocked: List<BlockedTime>,
)

class TaskRepository(context: Context) {
    private val db = SimpleTaskDb(context.applicationContext)
    private val session = SessionStore(context.applicationContext)
    private var api: NodeApiService? = null
    private var lastBase: String? = null
    private var mailApi: NodeApiService? = null
    private var lastMailBase: String? = null
    private var imageApi: NodeApiService? = null
    private var lastImageBase: String? = null

    private fun loggingClient(readSeconds: Long, connectSeconds: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .apply {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
            .build()
    }

    private suspend fun retrofit(readSeconds: Long, connectSeconds: Long): NodeApiService {
        val base = session.getServerUrl() ?: SessionStore.DEFAULT_SERVER
        return Retrofit.Builder()
            .baseUrl(if (base.endsWith("/")) base else "$base/")
            .client(loggingClient(readSeconds, connectSeconds))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NodeApiService::class.java)
    }

    private suspend fun api(): NodeApiService {
        val base = session.getServerUrl() ?: SessionStore.DEFAULT_SERVER
        val existing = api
        if (existing != null && lastBase == base) return existing
        lastBase = base
        return retrofit(readSeconds = 8, connectSeconds = 8).also { api = it }
    }

    /** JavaFX image GET/POST uses 60s. */
    private suspend fun imageApi(): NodeApiService {
        val base = session.getServerUrl() ?: SessionStore.DEFAULT_SERVER
        val existing = imageApi
        if (existing != null && lastImageBase == base) return existing
        lastImageBase = base
        return retrofit(readSeconds = 60, connectSeconds = 15).also { imageApi = it }
    }

    /** /forgot and /reset wait on SMTP; Node can take longer than the 8s default. */
    private suspend fun mailApi(): NodeApiService {
        val base = session.getServerUrl() ?: SessionStore.DEFAULT_SERVER
        val existing = mailApi
        if (existing != null && lastMailBase == base) return existing
        lastMailBase = base
        return retrofit(readSeconds = 20, connectSeconds = 15).also { mailApi = it }
    }

    private suspend fun <T> nodeCall(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: HttpException) {
            error(httpErrorMessage(e))
        }
    }

    private suspend fun <T> imageCall(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: HttpException) {
            error(imageHttpError(e))
        }
    }

    fun deviceLabel(): String {
        val model = Build.MODEL.orEmpty().trim()
        return ("Android " + model).trim().take(40)
    }

    suspend fun saveServer(url: String) = session.saveServer(url)

    class AccountSwitchException(
        val userId: String,
        val username: String,
        val token: String,
    ) : Exception("This account is not the one stored on this device")

    suspend fun login(username: String, password: String, remember: Boolean = true) {
        val resp = nodeCall {
            api().login(AuthRequest(username.trim(), password, device = deviceLabel()))
        }
        finishAuth(resp, username, remember)
    }

    suspend fun register(
        username: String,
        password: String,
        email: String,
        question: String,
        answer: String,
        remember: Boolean = true,
    ) {
        AuthRules.registerError(username, password, email, question, answer)?.let { error(it) }
        val resp = nodeCall {
            api().register(
                AuthRequest(
                    username = username.trim(),
                    password = password,
                    email = email.trim().lowercase(),
                    question = question.trim(),
                    answer = answer.trim(),
                    device = deviceLabel(),
                ),
            )
        }
        finishAuth(resp, username, remember)
    }

    private suspend fun finishAuth(
        resp: AuthResponse,
        fallbackName: String,
        remember: Boolean,
    ) {
        val uid = resp.resolvedUserId() ?: error("login response had no user id")
        val tok = resp.token ?: error("login response had no token")
        val name = resp.username ?: fallbackName
        val prev = session.getUserId()
        if (prev != null && prev != uid) {
            throw AccountSwitchException(uid, name, tok)
        }
        commitLogin(uid, name, tok, wipe = false, remember = remember)
    }

    suspend fun requestPasswordReset(username: String, email: String) {
        nodeCall {
            mailApi().forgot(ForgotRequest(username.trim(), email.trim().lowercase()))
        }
    }

    suspend fun resetPassword(token: String, answer: String, newPassword: String) {
        AuthRules.resetError(token, answer, newPassword)?.let { error(it) }
        nodeCall {
            mailApi().reset(
                ResetRequest(
                    token = token.trim(),
                    answer = answer.trim(),
                    newPassword = newPassword,
                ),
            )
        }
    }

    suspend fun fetchMe(): MeResponse {
        val tok = session.getToken() ?: error("login required")
        return nodeCall { api().me("Bearer $tok") }
    }

    suspend fun saveEmail(email: String) {
        if (!AuthRules.emailLooksReal(email)) {
            error("a real email is required to recover the account")
        }
        val tok = session.getToken() ?: error("login required")
        nodeCall { api().saveEmail("Bearer $tok", EmailRequest(email.trim().lowercase())) }
    }

    suspend fun saveUsername(username: String) {
        val name = username.trim()
        if (name.isEmpty()) error("username required")
        if (name.length > AuthRules.USERNAME_MAX) error("username max ${AuthRules.USERNAME_MAX} characters")
        val tok = session.getToken() ?: error("login required")
        val resp = nodeCall { api().saveUsername("Bearer $tok", UsernameRequest(name)) }
        val uid = session.getUserId() ?: return
        session.saveAuth(uid, resp.username ?: name, tok, session.getRememberLogin())
    }

    suspend fun saveSecurity(question: String, answer: String) {
        if (question.trim().length < AuthRules.QUESTION_MIN) error("security question too short")
        if (answer.trim().length < AuthRules.ANSWER_MIN) error("security answer too short")
        val tok = session.getToken() ?: error("login required")
        nodeCall {
            api().saveSecurity(
                "Bearer $tok",
                SecurityRequest(question.trim(), answer.trim()),
            )
        }
    }

    suspend fun listSessions(): List<SessionDto> {
        val tok = session.getToken() ?: error("login required")
        return nodeCall { api().getSessions("Bearer $tok").sessions.orEmpty() }
    }

    suspend fun revokeOtherSessions() {
        val tok = session.getToken() ?: error("login required")
        nodeCall { api().revokeOtherSessions("Bearer $tok") }
    }

    suspend fun revokeSession(sessionId: String): RevokeSessionResponse {
        val tok = session.getToken() ?: error("login required")
        return nodeCall { api().revokeSession("Bearer $tok", sessionId) }
    }

    suspend fun deleteAccount(password: String) {
        if (password.isBlank()) error("password required")
        val tok = session.getToken() ?: error("login required")
        nodeCall { api().deleteAccount("Bearer $tok", DeleteAccountRequest(password)) }
        wipeLocalCacheAndLogout()
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        if (newPassword.length < AuthRules.PASSWORD_MIN) {
            error("new password min ${AuthRules.PASSWORD_MIN} characters")
        }
        val tok = session.getToken() ?: error("login required")
        nodeCall {
            api().changePassword("Bearer $tok", ChangePasswordRequest(oldPassword, newPassword))
        }
    }

    suspend fun challengeQuestion(): Pair<Boolean, String>? {
        val tok = session.getToken() ?: return null
        val resp = runCatching { api().challengeQuestion("Bearer $tok") }.getOrNull() ?: return null
        return (resp.hasSecurity == true) to (resp.securityQuestion.orEmpty())
    }

    suspend fun challengeAnswer(answer: String) {
        val tok = session.getToken() ?: error("login required")
        nodeCall { api().challengeAnswer("Bearer $tok", ChallengeAnswerRequest(answer)) }
    }

    suspend fun wipeLocalCacheAndLogout() {
        db.deleteAllUserData()
        session.clearAuth()
    }

    /** SQLite only. Login stays. Next pull fills from Node. */
    suspend fun wipeLocalWorkspaceKeepAuth() {
        db.deleteAllUserData()
    }

    /** Reachability of the Node host. Does not mean the account is logged in. */
    suspend fun pingHost(): Boolean = withContext(Dispatchers.IO) {
        val uid = session.getUserId().orEmpty().ifBlank { "ping" }
        runCatching {
            retrofit(readSeconds = 2, connectSeconds = 2).accountStatus(uid)
            true
        }.getOrDefault(false)
    }

    /** Unauthenticated. Tombstone only — a 401 elsewhere is not deletion. */
    suspend fun isRemoteAccountDeleted(): Boolean {
        val uid = session.getUserId() ?: return false
        return runCatching { api().accountStatus(uid).deleted == true }.getOrDefault(false)
    }

    suspend fun commitLogin(
        userId: String,
        username: String,
        token: String,
        wipe: Boolean,
        remember: Boolean,
    ) {
        if (wipe) db.deleteAllUserData()
        session.saveAuth(userId, username, token, remember)
        runCatching { pullFromNode() }
    }

    suspend fun logout() {
        session.clearAuth()
    }

    suspend fun loadLocalWorkspace(): WorkspaceSnapshot = withContext(Dispatchers.IO) {
        revealWorkspace(
            WorkspaceSnapshot(
                tasks = db.getAllTasks().map { it.toDomain() },
                blocked = db.getAllBlocked().map { it.toDomain() },
            ),
        )
    }

    suspend fun pullFromNode(): WorkspaceSnapshot = withContext(Dispatchers.IO) {
        val tok = session.getToken() ?: return@withContext loadLocalWorkspace()
        val uid = session.getUserId()
        val lastSync = db.getMeta(KEY_LAST_NODE_SYNC)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val tasksAcked = mergeNodeTasks(tok, uid, lastSync)
        mergeNodeBlocked(tok, uid, lastSync)
        if (tasksAcked) {
            db.setMeta(KEY_LAST_NODE_SYNC, Instant.now().toString())
        }
        loadLocalWorkspace()
    }

    private fun revealWorkspace(snap: WorkspaceSnapshot): WorkspaceSnapshot {
        for (t in snap.tasks) VaultCrypto.openTask(t)
        for (b in snap.blocked) VaultCrypto.openBlocked(b)
        return snap
    }

    private suspend fun mergeNodeTasks(tok: String, uid: String?, lastSync: Instant?): Boolean {
        val payload = api().getTasks("Bearer $tok")
        val remote = (payload.tasks.orEmpty() + payload.nested.orEmpty())
            .mapNotNull { it.toDomain() }
            .associateBy { it.id }
            .values
        val remoteIds = remote.map { it.id }.toSet()
        val locals = db.getAllTasks().associateBy { it.id }
        for (task in remote) {
            if (db.isTaskDeleted(task.id)) {
                runCatching { api().deleteTask("Bearer $tok", task.id) }
                continue
            }
            val existing = locals[task.id]?.toDomain()
            val winner = if (existing == null) task else mergeTask(existing, task)
            db.upsertTask(winner.toEntity(uid))
        }
        var unacked = false
        for (local in locals.values) {
            if (local.id in remoteIds) continue
            val localEdit = parseInstant(local.lastUpdatedAt) ?: parseInstant(local.placementUpdatedAt)
            val stale = lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)
            if (stale) {
                db.deleteTaskById(local.id)
                db.recordTaskDelete(local.id, local.title, uid)
                continue
            }
            val posted = runCatching { api().postTask("Bearer $tok", local.toDomain().toDto()) }
            if (posted.isFailure) unacked = true
        }
        return !unacked
    }

    private suspend fun mergeNodeBlocked(tok: String, uid: String?, lastSync: Instant?) {
        val remote = api().getBlocked("Bearer $tok").blocked.orEmpty().mapNotNull { it.toDomain() }
        val remoteIds = remote.map { it.id }.toSet()
        val locals = db.getAllBlocked().associateBy { it.id }
        for (row in remote) {
            if (db.isBlockedDeleted(row.id)) {
                runCatching { api().deleteBlocked("Bearer $tok", row.id) }
                continue
            }
            val existing = locals[row.id]?.toDomain()
            val winner = if (existing == null) row else mergeBlocked(existing, row)
            db.upsertBlocked(winner.toEntity(uid))
        }
        for (local in locals.values) {
            if (local.id in remoteIds) continue
            val localEdit = parseInstant(local.lastUpdatedAt)
            val stale = lastSync != null && localEdit != null && !localEdit.isAfter(lastSync)
            if (stale) {
                db.deleteBlockedById(local.id)
                db.recordBlockedDelete(local.id, local.title)
            }
        }
    }

    /**
     * Phone is never the host device. Last-write-wins; on equal timestamps Node/remote wins
     * so a same-second split heals. Host-wins-on-tie lives only on JavaFX host.
     */
    private fun nodeWinsOnTie(local: Instant?, remote: Instant?): Boolean {
        if (remote == null) return false
        if (local == null) return true
        return !remote.isBefore(local)
    }

    private fun mergeTask(local: Task, remote: Task): Task {
        val remoteWins = nodeWinsOnTie(local.lastUpdatedAt, remote.lastUpdatedAt)
        val base = if (remoteWins) remote else local
        val placeSrc = if (nodeWinsOnTie(local.placementUpdatedAt, remote.placementUpdatedAt)) remote else local
        return base.copy(
            placedDate = placeSrc.placedDate,
            placedTime = placeSrc.placedTime,
            placementUpdatedAt = placeSrc.placementUpdatedAt,
            parentId = keepParentId(local.parentId, remote.parentId, remoteWins),
        )
    }

    /** Never blank a nested parent link — that would promote a quick event to a standalone task. */
    private fun keepParentId(local: String, remote: String, remoteWins: Boolean): String {
        if (local.isBlank()) return remote
        if (remote.isBlank()) return local
        return if (remoteWins) remote else local
    }

    private fun mergeBlocked(local: BlockedTime, remote: BlockedTime): BlockedTime {
        return if (nodeWinsOnTie(local.lastUpdatedAt, remote.lastUpdatedAt)) remote else local
    }

    private fun parseInstant(raw: String?): Instant? =
        raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

    suspend fun upsertTask(task: Task): String? = withContext(Dispatchers.IO) {
        if (db.isTaskDeleted(task.id)) return@withContext "Task was deleted"
        val uid = session.getUserId()
        task.touchLastUpdated()
        val wire = VaultCrypto.sealedCopy(task)
        db.upsertTask(wire.toEntity(uid))
        val tok = session.getToken()
        if (tok.isNullOrBlank()) return@withContext null
        try {
            api().postTask("Bearer $tok", wire.toDto())
            null
        } catch (e: HttpException) {
            httpErrorMessage(e)
        } catch (e: Exception) {
            e.message
        }
    }

    suspend fun deleteTask(task: Task) = withContext(Dispatchers.IO) {
        val uid = session.getUserId()
        db.deleteTaskById(task.id)
        db.recordTaskDelete(task.id, task.title, uid)
        val tok = session.getToken()
        if (!tok.isNullOrBlank()) {
            runCatching { api().deleteTask("Bearer $tok", task.id) }
        }
    }

    suspend fun upsertBlocked(row: BlockedTime) = withContext(Dispatchers.IO) {
        if (db.isBlockedDeleted(row.id)) return@withContext
        val uid = session.getUserId()
        row.touch()
        val wire = VaultCrypto.sealedBlocked(row)
        db.upsertBlocked(wire.toEntity(uid))
        val tok = session.getToken()
        if (!tok.isNullOrBlank()) {
            runCatching { api().postBlocked("Bearer $tok", wire.toDto()) }
        }
    }

    suspend fun deleteBlocked(row: BlockedTime) = withContext(Dispatchers.IO) {
        db.deleteBlockedById(row.id)
        db.recordBlockedDelete(row.id, row.title)
        val tok = session.getToken()
        if (!tok.isNullOrBlank()) {
            runCatching { api().deleteBlocked("Bearer $tok", row.id) }
        }
    }

    suspend fun loadTypewriter(): TypewriterBundle = withContext(Dispatchers.IO) {
        val uid = session.getUserId() ?: return@withContext TypewriterBundle()
        val cached = db.getTypewriter(uid)
        val localBundle = parseTypewriterMaybeSealed(cached?.first, cached?.second)
            ?: TypewriterBundle()
        val tok = session.getToken()
        if (tok.isNullOrBlank()) return@withContext localBundle
        val remote = runCatching { api().getTypewriter("Bearer $tok") }.getOrNull()
            ?: return@withContext localBundle
        val remoteParsed = parseTypewriterMaybeSealed(remote.content, remote.lastUpdatedAt)
        if (remoteParsed == null) {
            return@withContext localBundle
        }
        val remoteTs = parseInstant(remote.lastUpdatedAt)
        val localTs = parseInstant(localBundle.lastUpdatedAt)
        if (remoteParsed.hasSubstance() && !localBundle.hasSubstance()) {
            db.saveTypewriter(uid, remote.content ?: remoteParsed.toJson(), remote.lastUpdatedAt)
            return@withContext remoteParsed
        }
        // Typewriter is local-first while dirty (caller must not pull then). When clean:
        // host wins if strictly newer, or same stamp / missing local stamp with different body.
        val contentDiffers = !TypewriterHtml.sameStoredContent(localBundle, remoteParsed)
        if (remoteParsed.hasSubstance() && contentDiffers && remoteTs != null &&
            (localTs == null || !remoteTs.isBefore(localTs))
        ) {
            db.saveTypewriter(uid, remote.content ?: remoteParsed.toJson(), remote.lastUpdatedAt)
            return@withContext remoteParsed
        }
        if (localBundle.hasSubstance() && contentDiffers &&
            (remoteTs == null || (localTs != null && localTs.isAfter(remoteTs)))
        ) {
            runCatching { pushTypewriterToHost(localBundle) }
        }
        localBundle
    }

    suspend fun uploadTypewriterImage(
        name: String,
        mime: String,
        bytes: ByteArray,
    ): TypewriterImageRef = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) error("Empty image")
        val (clean, cleanMime) = ImageMetadataStrip.strip(bytes, mime)
        if (clean.size > 4 * 1024 * 1024) error("Image larger than 4 MB")
        val sha = sha256Hex(clean)
        val tok = session.getToken() ?: error("Not logged in")
        val fileName = ImageMetadataStrip.safeFileName(name, cleanMime)
        val resp = imageCall {
            imageApi().postTypewriterImage(
                "Bearer $tok",
                TypewriterImageDto(
                    sha256 = sha,
                    name = fileName,
                    mime = cleanMime,
                    data = Base64.encodeToString(clean, Base64.NO_WRAP),
                ),
            )
        }
        TypewriterImageRef(
            id = resp.sha256 ?: sha,
            name = resp.name ?: fileName,
        )
    }

    /** Re-upload Typewriter photos that still carry GPS/camera EXIF. Host keeps old blobs. */
    suspend fun scrubTypewriterImages(bundle: TypewriterBundle): Pair<TypewriterBundle, Int> =
        withContext(Dispatchers.IO) {
            val tok = session.getToken() ?: return@withContext bundle to 0
            val ids = linkedSetOf<String>()
            for (e in bundle.diary) e.images.forEach { ids.add(it.id) }
            for (p in bundle.pages) p.images.forEach { ids.add(it.id) }
            if (ids.isEmpty()) return@withContext bundle to 0
            val remap = HashMap<String, TypewriterImageRef>()
            for (sha in ids) {
                if (sha.isBlank()) continue
                val dto = runCatching {
                    imageApi().getTypewriterImage("Bearer $tok", sha.lowercase())
                }.getOrNull()
                    ?: continue
                val raw = dto.data ?: continue
                val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull() ?: continue
                if (bytes.isEmpty() || !ImageMetadataStrip.hasPrivacyMetadata(bytes)) continue
                val ref = runCatching {
                    uploadTypewriterImage(
                        dto.name ?: "image.jpg",
                        dto.mime ?: ImageMetadataStrip.guessMime(bytes),
                        bytes,
                    )
                }.getOrNull() ?: continue
                if (ref.id != sha) remap[sha] = ref
            }
            if (remap.isEmpty()) return@withContext bundle to 0
            fun rewrite(images: MutableList<TypewriterImageRef>) {
                for (i in images.indices) {
                    val next = remap[images[i].id] ?: continue
                    images[i] = TypewriterImageRef(id = next.id, name = next.name)
                }
            }
            for (e in bundle.diary) rewrite(e.images)
            for (p in bundle.pages) rewrite(p.images)
            bundle to remap.size
        }

    suspend fun downloadTypewriterImage(sha: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val tok = session.getToken() ?: error("Not logged in")
        val dto = imageCall {
            imageApi().getTypewriterImage("Bearer $tok", sha.trim().lowercase())
        }
        val raw = dto.data ?: error("Image had no data")
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        (dto.name ?: "image") to bytes
    }

    /**
     * Write Typewriter draft to SQLite and bump [TypewriterBundle.lastUpdatedAt].
     * Does not talk to the host — phone keeps the words even if Wi‑Fi is down.
     */
    suspend fun persistTypewriterLocal(bundle: TypewriterBundle) = withContext(Dispatchers.IO) {
        val uid = session.getUserId() ?: return@withContext
        val cached = db.getTypewriter(uid)
        if (VaultSeal.isSealed(cached?.first) && !VaultCrypto.unlocked()) {
            error("Sign in to save Typewriter")
        }
        // Do not normalizeBundle() here — that rewrote page/diary body under the live WebView
        // and Compose reloaded the document (jump to top / letters "delete"). Wire format already
        // goes through storedHtmlOrSealed inside toJson().
        bundle.pruneEmptyDiary()
        if (!bundle.hasSubstance() && !cached?.first.isNullOrBlank()) {
            error("Typewriter on the host is not empty — this phone will not replace it with a blank document")
        }
        val cachedParsed = parseTypewriterMaybeSealed(cached?.first, cached?.second)
        if (cachedParsed != null &&
            TypewriterHtml.sameStoredContent(bundle, cachedParsed) &&
            !bundle.lastUpdatedAt.isNullOrBlank()
        ) {
            return@withContext
        }
        val content = bundle.toJson()
        val wire = if (VaultCrypto.active()) VaultCrypto.seal(content) else content
        // Keep the last host-acked stamp on disk. Bumping Instant.now() here made the phone
        // look newer than the host after a local-only draft, so host edits never applied.
        val stamp = cached?.second?.takeIf { it.isNotBlank() }
            ?: bundle.lastUpdatedAt?.takeIf { it.isNotBlank() }
            ?: Instant.now().toString()
        db.saveTypewriter(uid, wire, stamp)
        if (bundle.lastUpdatedAt.isNullOrBlank()) bundle.lastUpdatedAt = stamp
    }

    /**
     * POST the current draft to the host. Throws on HTTP failure so the caller keeps dirty.
     * Returns false when offline (no token) — local draft stays, dirty stays.
     *
     * Node upsert is LWW on [last_updated_at] and still returns HTTP 200 when it refuses an
     * older stamp (body is the previous host row). Treat that as failure so dirty stays set.
     */
    suspend fun pushTypewriterToHost(bundle: TypewriterBundle): Boolean = withContext(Dispatchers.IO) {
        val uid = session.getUserId() ?: return@withContext false
        val tok = session.getToken()
        if (tok.isNullOrBlank()) return@withContext false
        bundle.pruneEmptyDiary()
        val at = Instant.now().toString()
        val content = bundle.toJson()
        val wire = if (VaultCrypto.active()) VaultCrypto.seal(content) else content
        if (wire.isBlank()) return@withContext false
        db.saveTypewriter(uid, wire, at)
        bundle.lastUpdatedAt = at
        val resp = api().postTypewriter(
            "Bearer $tok",
            TypewriterDto(content = wire, lastUpdatedAt = at),
        )
        val sent = runCatching { Instant.parse(at) }.getOrNull()
        val got = runCatching { Instant.parse(resp.lastUpdatedAt ?: "") }.getOrNull()
        // Accepted rows echo our stamp (or newer). Rejected LWW returns the older host stamp.
        if (sent != null && got != null && got.isBefore(sent)) {
            error("Host kept an older Typewriter revision — will retry")
        }
        if (got != null) bundle.lastUpdatedAt = resp.lastUpdatedAt
        true
    }

    /**
     * Local persist then host push (logout / leave-tab flush).
     * Offline (no token): local only. Online: HTTP failure propagates so dirty stays set.
     */
    suspend fun saveTypewriter(bundle: TypewriterBundle) {
        persistTypewriterLocal(bundle)
        pushTypewriterToHost(bundle)
    }

    companion object {
        private const val KEY_LAST_NODE_SYNC = "last_node_sync_at"

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun httpErrorMessage(e: HttpException): String {
            val raw = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            val fromJson = raw?.let {
                runCatching {
                    val o = JsonParser.parseString(it).asJsonObject
                    o.get("error")?.asString ?: o.get("message")?.asString
                }.getOrNull()
            }
            return fromJson?.takeIf { it.isNotBlank() } ?: "HTTP ${e.code()}"
        }

        private fun imageHttpError(e: HttpException): String {
            if (e.code() == 404) {
                return "Photo is not on the host. Open it on the desktop once so it can upload."
            }
            return httpErrorMessage(e)
        }
    }
}
