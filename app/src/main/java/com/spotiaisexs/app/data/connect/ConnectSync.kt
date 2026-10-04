package com.spotiaisexs.app.data.connect

import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spotiaisexs.app.data.local.readSafely
import com.spotiaisexs.app.data.local.recoverPreferences
import com.spotiaisexs.app.playback.MusicPlayer
import com.spotiaisexs.app.playback.MusicPlayerState
import com.spotiaisexs.app.playback.PlayableTrack
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "SpotiAisexsConnect"

/** One song as it travels between devices. Mirrors the web app's track objects. */
@Serializable
data class ConnectTrack(
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUrl: String? = null,
    val videoId: String? = null,
    val durationMs: Long? = null,
)

/**
 * The shared "now playing" document at users/{uid}/playback. Only the active
 * device writes it; every other device reads it and acts as a remote.
 */
@Serializable
data class ConnectPlayback(
    val activeDevice: String? = null,
    val activeDeviceName: String? = null,
    val activeDeviceType: String? = null,
    val track: ConnectTrack? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Server timestamp of the write, so remotes can extrapolate the position. */
    val updatedAt: Long = 0,
    /** Upcoming songs starting with the current one (queueIndex points at it). */
    val queue: List<ConnectTrack> = emptyList(),
    val queueIndex: Int = 0,
) {
    fun estimatedPositionMs(now: Long = System.currentTimeMillis()): Long {
        val elapsed = if (isPlaying && updatedAt > 0) (now - updatedAt).coerceAtLeast(0) else 0
        val pos = positionMs + elapsed
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }
}

/** A remote-control order pushed to users/{uid}/commands/{targetDevice}. */
@Serializable
data class ConnectCommand(
    val type: String = "",
    val positionMs: Long? = null,
    val index: Int? = null,
    val from: String? = null,
    val at: Long? = null,
)

sealed interface ConnectStatus {
    data object Off : ConnectStatus
    data object Connecting : ConnectStatus
    data class Connected(val email: String) : ConnectStatus
    data class Error(val message: String) : ConnectStatus
}

/**
 * Spotify-Connect-style sync between this phone and the SpotiAisexs web app
 * (GitHub Pages), using the user's own free Firebase Realtime Database as the
 * relay. Talks to Firebase over its REST + streaming (SSE) API, so the app
 * needs no Firebase SDK or google-services.json; the database rules restrict
 * every path to the signed-in user (see docs/WEB_SYNC.md).
 *
 * Model: exactly one device is "active" and plays; it publishes the playback
 * document. Any other device is a remote: it shows that document and sends
 * commands. Taking over ("Reproducir aquí") copies the queue and position,
 * claims the active slot, and the previous device pauses itself.
 *
 * Only the Firebase refresh token is stored on the phone, never the password.
 */
@Singleton
class ConnectSync @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val player: MusicPlayer,
    okHttpClient: OkHttpClient,
    private val applicationScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val http = okHttpClient.newBuilder().cache(null).build()
    private val streamHttp = okHttpClient.newBuilder()
        .cache(null)
        .readTimeout(90, TimeUnit.SECONDS) // Firebase sends keep-alives every ~30 s
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val _status = MutableStateFlow<ConnectStatus>(ConnectStatus.Off)
    val status: StateFlow<ConnectStatus> = _status.asStateFlow()

    /** The playback of another device while it is the active one; null when this phone plays. */
    private val _remotePlayback = MutableStateFlow<ConnectPlayback?>(null)
    val remotePlayback: StateFlow<ConnectPlayback?> = _remotePlayback.asStateFlow()

    private val authMutex = Mutex()
    private var session: Job? = null
    private var config: StoredConfig? = null
    private var idToken: String? = null
    private var idTokenExpiresAt = 0L

    @Volatile private var lastPlayback: ConnectPlayback? = null
    @Volatile private var claimPending = false
    @Volatile private var publishedQueueOffset = 0
    private val publishRequests = MutableSharedFlow<MusicPlayerState>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val isActiveHere: Boolean
        get() = lastPlayback?.activeDevice.let { it == null || it == config?.deviceId }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Restores a saved connection at app start. */
    fun start() {
        applicationScope.launch {
            val stored = readConfig() ?: return@launch
            if (stored.refreshToken.isNotBlank()) startSession(stored)
        }
    }

    /** Signs in with the Firebase account created for the web app and starts syncing. */
    suspend fun connect(apiKey: String, databaseUrl: String, email: String, password: String) {
        _status.value = ConnectStatus.Connecting
        try {
            val cleanUrl = normalizeDatabaseUrl(databaseUrl)
            val auth = signIn(apiKey.trim(), email.trim(), password)
            val stored = StoredConfig(
                apiKey = apiKey.trim(),
                databaseUrl = cleanUrl,
                email = email.trim(),
                uid = auth.uid,
                refreshToken = auth.refreshToken,
                deviceId = readConfig()?.deviceId ?: newDeviceId(),
            )
            idToken = auth.idToken
            idTokenExpiresAt = System.currentTimeMillis() + auth.expiresInSec * 1000L
            saveConfig(stored)
            startSession(stored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = ConnectStatus.Error(e.message ?: "No se pudo conectar")
        }
    }

    fun disconnect() {
        session?.cancel()
        session = null
        config = null
        idToken = null
        _remotePlayback.value = null
        _status.value = ConnectStatus.Off
        applicationScope.launch {
            dataStore.edit { it.remove(KEY_CONFIG) }
        }
    }

    suspend fun savedEmail(): String? = readConfig()?.email
    suspend fun savedDatabaseUrl(): String? = readConfig()?.databaseUrl
    suspend fun savedApiKey(): String? = readConfig()?.apiKey

    /** Sends a remote-control command to the device that is currently playing. */
    fun sendCommand(type: String, positionMs: Long? = null, index: Int? = null) {
        val target = lastPlayback?.activeDevice ?: return
        val me = config?.deviceId ?: return
        if (target == me) return
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("type", type)
                    positionMs?.let { put("positionMs", it) }
                    index?.let { put("index", it) }
                    put("from", me)
                    put("at", serverTimestamp())
                }
                rest("POST", "commands/$target", body.toString())
            }.onFailure { Log.w(TAG, "Command $type failed", it) }
        }
    }

    /** "Reproducir aquí": moves playback from the remote device to this phone. */
    fun playHere() {
        val remote = lastPlayback ?: return
        applicationScope.launch { takeOver(remote) }
    }

    // ── Session ────────────────────────────────────────────────────────────

    private fun startSession(stored: StoredConfig) {
        session?.cancel()
        config = stored
        _status.value = ConnectStatus.Connecting
        session = applicationScope.launch(Dispatchers.IO + SupervisorJob()) {
            try {
                validToken() // fail fast on a revoked account
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = ConnectStatus.Error(e.message ?: "Sesión caducada, vuelve a conectar")
                return@launch
            }
            _status.value = ConnectStatus.Connected(stored.email)
            launch { heartbeatLoop(stored) }
            launch { publishLoop() }
            launch { observePlayer() }
            launch {
                streamLoop("playback") { _, _ -> refreshPlaybackDocument() }
            }
            launch {
                streamLoop("commands/${stored.deviceId}") { path, data -> onCommandEvent(path, data) }
            }
        }
    }

    private suspend fun heartbeatLoop(stored: StoredConfig) {
        while (currentScopeActive()) {
            runCatching {
                val body = buildJsonObject {
                    put("name", deviceName())
                    put("type", "phone")
                    put("lastSeen", serverTimestamp())
                }
                rest("PUT", "devices/${stored.deviceId}", body.toString())
            }.onFailure { Log.w(TAG, "Heartbeat failed", it) }
            delay(HEARTBEAT_MS)
        }
    }

    // ── Publishing (this phone is the active device) ──────────────────────

    private suspend fun observePlayer() {
        var lastSignature: String? = null
        var lastPublishedAt = 0L
        var lastPublishedPosition = 0L
        var wasPlaying = player.state.value.isPlaying
        player.state.collect { st ->
            val now = System.currentTimeMillis()
            val startedHere = st.isPlaying && !wasPlaying
            wasPlaying = st.isPlaying
            if (st.current == null) return@collect
            // Pressing play on the phone while another device is active claims playback back.
            if (startedHere && !isActiveHere) claimPending = true
            if (!isActiveHere && !claimPending) return@collect

            val signature = "${st.current.title}|${st.current.artist}|${st.isPlaying}|${st.currentIndex}|${st.queue.size}"
            val expected = lastPublishedPosition + if (st.isPlaying) now - lastPublishedAt else 0
            val seeked = abs(st.positionMs - expected) > SEEK_DETECT_MS
            val stale = st.isPlaying && now - lastPublishedAt > POSITION_REFRESH_MS
            if (signature != lastSignature || seeked || stale || claimPending) {
                lastSignature = signature
                lastPublishedAt = now
                lastPublishedPosition = st.positionMs
                publishRequests.tryEmit(st)
            }
        }
    }

    private suspend fun publishLoop() {
        publishRequests.collect { st ->
            val stored = config ?: return@collect
            val current = st.current ?: return@collect
            val offset = st.currentIndex.coerceAtLeast(0)
            val upcoming = if (st.queue.isNotEmpty() && offset < st.queue.size) {
                st.queue.subList(offset, minOf(st.queue.size, offset + QUEUE_PUBLISH_LIMIT))
            } else {
                listOf(current)
            }
            val playback = ConnectPlayback(
                activeDevice = stored.deviceId,
                activeDeviceName = deviceName(),
                activeDeviceType = "phone",
                track = current.toConnect(st.durationMs),
                isPlaying = st.isPlaying,
                positionMs = st.positionMs,
                durationMs = st.durationMs,
                queue = upcoming.map { it.toConnect() },
                queueIndex = 0,
            )
            val body = json.encodeToJsonElement(playback).jsonObject.let { obj ->
                JsonObject(obj + ("updatedAt" to serverTimestamp()))
            }
            runCatching { rest("PUT", "playback", body.toString()) }
                .onSuccess {
                    publishedQueueOffset = offset
                    claimPending = false
                    lastPlayback = playback.copy(updatedAt = System.currentTimeMillis())
                    _remotePlayback.value = null
                }
                .onFailure { Log.w(TAG, "Publish failed", it) }
        }
    }

    // ── Incoming ───────────────────────────────────────────────────────────

    private suspend fun refreshPlaybackDocument() {
        val raw = runCatching { rest("GET", "playback", null) }.getOrNull() ?: return
        val playback = runCatching {
            val element = json.parseToJsonElement(raw)
            if (element is JsonNull) null else json.decodeFromJsonElement<ConnectPlayback>(element)
        }.getOrNull()
        lastPlayback = playback
        val me = config?.deviceId
        if (playback?.activeDevice != null && playback.activeDevice != me && !claimPending) {
            // Another device took over: stop here and become a remote.
            if (player.state.value.isPlaying) player.pause()
            _remotePlayback.value = playback
        } else {
            _remotePlayback.value = null
        }
    }

    private suspend fun onCommandEvent(path: String, data: JsonElement?) {
        if (data == null || data is JsonNull) return
        val commands: Map<String, JsonElement> = if (path == "/") {
            (data as? JsonObject)?.toMap().orEmpty()
        } else {
            mapOf(path.trim('/') to data)
        }
        for ((id, element) in commands) {
            val command = runCatching { json.decodeFromJsonElement<ConnectCommand>(element) }.getOrNull()
            runCatching { rest("DELETE", "commands/${config?.deviceId}/$id", null) }
            if (command == null) continue
            val age = command.at?.let { System.currentTimeMillis() - it } ?: 0
            if (age > COMMAND_MAX_AGE_MS) continue
            withContext(Dispatchers.Main) { execute(command) }
        }
    }

    private suspend fun execute(command: ConnectCommand) {
        when (command.type) {
            "play" -> player.resume()
            "pause" -> player.pause()
            "toggle" -> player.togglePlayPause()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> command.positionMs?.let { player.seekTo(it) }
            "playIndex" -> command.index?.let { player.seekToQueueItem(publishedQueueOffset + it) }
            "transfer" -> lastPlayback?.let { takeOver(it) }
        }
    }

    private suspend fun takeOver(remote: ConnectPlayback) {
        val tracks = remote.queue.ifEmpty { listOfNotNull(remote.track) }.map { it.toPlayable() }
        if (tracks.isEmpty()) return
        val startIndex = remote.queueIndex.coerceIn(0, tracks.lastIndex)
        val startAt = remote.estimatedPositionMs()
        claimPending = true
        _remotePlayback.value = null
        withContext(Dispatchers.Main) {
            player.playQueue(tracks, startIndex, sourceLabel = "SpotiAisexs Connect")
        }
        val target = tracks[startIndex]
        // Seek once the stream is ready (resolution is asynchronous).
        withTimeoutOrNull(TAKEOVER_SEEK_TIMEOUT_MS) {
            player.state.first { st ->
                st.current?.title == target.title && st.durationMs > 0
            }
        }?.let { if (startAt > SEEK_DETECT_MS) withContext(Dispatchers.Main) { player.seekTo(startAt) } }
    }

    // ── Firebase REST / streaming ──────────────────────────────────────────

    private suspend fun rest(method: String, path: String, body: String?): String? = withContext(Dispatchers.IO) {
        val stored = config ?: error("No conectado")
        val url = "${stored.databaseUrl}/users/${stored.uid}/$path.json?auth=${validToken()}"
        val requestBody = body?.toRequestBody(jsonType)
        val request = Request.Builder().url(url).method(method, if (method == "GET" || method == "DELETE") null else requestBody).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) {
                idTokenExpiresAt = 0 // force a refresh on the next call
                error("Firebase rechazó la sesión (401)")
            }
            if (!response.isSuccessful) error("Firebase HTTP ${response.code}")
            response.body?.string()
        }
    }

    /**
     * Keeps a Server-Sent Events stream open on [path] and reconnects with a
     * fresh token when Firebase closes it (tokens last one hour).
     */
    private suspend fun streamLoop(path: String, onChange: suspend (path: String, data: JsonElement?) -> Unit) {
        var backoff = 1_000L
        while (currentScopeActive()) {
            var call: Call? = null
            try {
                val stored = config ?: return
                val url = "${stored.databaseUrl}/users/${stored.uid}/$path.json?auth=${validToken()}"
                val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
                val activeCall = streamHttp.newCall(request)
                call = activeCall
                kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion { activeCall.cancel() }
                withContext(Dispatchers.IO) {
                    activeCall.execute().use { response ->
                        if (response.code == 401) {
                            idTokenExpiresAt = 0
                            return@use
                        }
                        if (!response.isSuccessful) error("Stream HTTP ${response.code}")
                        backoff = 1_000L
                        val source = response.body?.source() ?: return@use
                        var event: String? = null
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
                                    val payload = line.removePrefix("data:").trim()
                                    when (event) {
                                        "put", "patch" -> {
                                            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                                            val eventPath = obj?.get("path")?.jsonPrimitive?.contentOrNull ?: "/"
                                            onChange(eventPath, obj?.get("data"))
                                        }
                                        "auth_revoked" -> {
                                            idTokenExpiresAt = 0
                                            break
                                        }
                                        "cancel" -> error("Permiso denegado por las reglas de Firebase")
                                    }
                                }
                                line.isEmpty() -> event = null
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                call?.cancel()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Stream $path dropped", e)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun validToken(): String = authMutex.withLock {
        val now = System.currentTimeMillis()
        idToken?.takeIf { now < idTokenExpiresAt - 5 * 60_000 }?.let { return it }
        val stored = config ?: error("No conectado")
        val refreshed = refresh(stored.apiKey, stored.refreshToken)
        idToken = refreshed.idToken
        idTokenExpiresAt = now + refreshed.expiresInSec * 1000L
        if (refreshed.refreshToken != stored.refreshToken) {
            val updated = stored.copy(refreshToken = refreshed.refreshToken)
            config = updated
            saveConfig(updated)
        }
        refreshed.idToken
    }

    private data class AuthResult(val idToken: String, val refreshToken: String, val uid: String, val expiresInSec: Long)

    private suspend fun signIn(apiKey: String, email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            put("returnSecureToken", true)
        }.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$apiKey")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(friendlyAuthError(text))
            val obj = json.parseToJsonElement(text).jsonObject
            AuthResult(
                idToken = obj.getValue("idToken").jsonPrimitive.content,
                refreshToken = obj.getValue("refreshToken").jsonPrimitive.content,
                uid = obj.getValue("localId").jsonPrimitive.content,
                expiresInSec = obj["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600,
            )
        }
    }

    private suspend fun refresh(apiKey: String, refreshToken: String): AuthResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://securetoken.googleapis.com/v1/token?key=$apiKey")
            .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).build())
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(friendlyAuthError(text))
            val obj = json.parseToJsonElement(text).jsonObject
            AuthResult(
                idToken = obj.getValue("id_token").jsonPrimitive.content,
                refreshToken = obj.getValue("refresh_token").jsonPrimitive.content,
                uid = obj.getValue("user_id").jsonPrimitive.content,
                expiresInSec = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600,
            )
        }
    }

    private fun friendlyAuthError(body: String): String = when {
        "INVALID_LOGIN_CREDENTIALS" in body || "INVALID_PASSWORD" in body || "EMAIL_NOT_FOUND" in body ->
            "Correo o contraseña incorrectos"
        "API_KEY_INVALID" in body || "API key not valid" in body -> "La API key de Firebase no es válida"
        "OPERATION_NOT_ALLOWED" in body -> "Activa el acceso con correo y contraseña en Firebase Authentication"
        "TOKEN_EXPIRED" in body || "INVALID_REFRESH_TOKEN" in body -> "La sesión ha caducado, vuelve a conectar"
        else -> "Error de Firebase: ${body.take(120)}"
    }

    // ── Storage & helpers ──────────────────────────────────────────────────

    @Serializable
    private data class StoredConfig(
        val apiKey: String,
        val databaseUrl: String,
        val email: String,
        val uid: String,
        val refreshToken: String,
        val deviceId: String,
    )

    private suspend fun readConfig(): StoredConfig? =
        dataStore.data.recoverPreferences(TAG).first().readSafely(KEY_CONFIG)
            ?.let { runCatching { json.decodeFromString<StoredConfig>(it) }.getOrNull() }

    private suspend fun saveConfig(stored: StoredConfig) {
        dataStore.edit { it[KEY_CONFIG] = json.encodeToString(StoredConfig.serializer(), stored) }
    }

    private fun normalizeDatabaseUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        require(url.startsWith("https://") && (".firebaseio.com" in url || ".firebasedatabase.app" in url)) {
            "La URL de la base de datos debe ser la de Realtime Database (https://…firebaseio.com o …firebasedatabase.app)"
        }
        return url
    }

    private fun newDeviceId(): String = "phone-" + UUID.randomUUID().toString().take(8)

    private fun deviceName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() }?.let { "Teléfono · $it" } ?: "Teléfono"

    private fun serverTimestamp(): JsonObject = buildJsonObject { put(".sv", "timestamp") }

    private suspend fun currentScopeActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private fun PlayableTrack.toConnect(knownDurationMs: Long? = null) = ConnectTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
        durationMs = durationMs ?: knownDurationMs?.takeIf { it > 0 },
    )

    private fun ConnectTrack.toPlayable() = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        videoId = videoId,
        durationMs = durationMs,
    )

    private companion object {
        val KEY_CONFIG = stringPreferencesKey("connect_sync_config")
        const val HEARTBEAT_MS = 30_000L
        const val POSITION_REFRESH_MS = 15_000L
        const val SEEK_DETECT_MS = 3_000L
        const val QUEUE_PUBLISH_LIMIT = 50
        const val COMMAND_MAX_AGE_MS = 30_000L
        const val TAKEOVER_SEEK_TIMEOUT_MS = 20_000L
    }
}
