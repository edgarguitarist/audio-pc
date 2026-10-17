package com.edgarguitarist.audiopc.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.edgarguitarist.audiopc.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Single source of truth for the app, the widgets and the tiles. Talks to the active server over
 * HTTP, keeps a WebSocket open while the app is in the foreground and persists everything that
 * must survive a process death.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AudioPcRepositoryImpl(context: Context) : AudioPcRepository {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: OkHttpClient = newHttpClient()
    private val store = Store(appContext)
    val discovery = Discovery(appContext, http)

    private val _servers = MutableStateFlow<List<SavedServer>>(emptyList())
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()
    private val _active = MutableStateFlow<SavedServer?>(null)
    override val activeServer: StateFlow<SavedServer?> = _active.asStateFlow()
    private val _state = MutableStateFlow<AudioState?>(null)
    override val state: StateFlow<AudioState?> = _state.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionStatus.NO_SERVER)
    override val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    val deviceId: String
    private val mutateLock = Mutex()
    private val resolveLock = Mutex()
    private val lastResolveFailure = HashMap<String, Long>()

    init {
        // Blocking once at creation keeps activeServer/state synchronous for widgets and the UI's
        // first frame; the preferences file is tiny.
        val snap = runBlocking { store.load() }
        deviceId = snap.deviceId
        _servers.value = snap.servers
        val active = snap.servers.firstOrNull { it.id == snap.activeId } ?: snap.servers.firstOrNull()
        _active.value = active
        if (active != null) {
            _state.value = runBlocking { store.loadState(active.id) }
            _connection.value = ConnectionStatus.CONNECTING
        }

        // Persist the last state (debounced: volume drags produce many updates).
        scope.launch {
            _state.filterNotNull().debounce(500).collect { s -> _active.value?.let { store.saveState(it.id, s) } }
        }
        // Tell widgets/tiles about every change, at most once per second.
        scope.launch {
            combine(_state, _connection, _active) { a, b, c -> Triple(a, b, c) }.collect { scheduleWidgetUpdate() }
        }
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = startLive()
                override fun onStop(owner: LifecycleOwner) = stopLive()
            })
        }
    }

    // --- widget notification (throttled) ---------------------------------------------------

    private var lastWidgetUpdate = 0L
    private var widgetJob: Job? = null

    @Synchronized
    private fun scheduleWidgetUpdate() {
        val wait = 1000 - (System.currentTimeMillis() - lastWidgetUpdate)
        if (wait <= 0) {
            lastWidgetUpdate = System.currentTimeMillis()
            runCatching { Widgets.update(appContext) }
        } else if (widgetJob?.isActive != true) {
            // Trailing update so the last change in a burst is never lost.
            widgetJob = scope.launch {
                delay(wait)
                synchronized(this@AudioPcRepositoryImpl) { lastWidgetUpdate = System.currentTimeMillis() }
                runCatching { Widgets.update(appContext) }
            }
        }
    }

    // --- AudioPcRepository -----------------------------------------------------------------

    override suspend fun cachedState(): AudioState? =
        _state.value ?: _active.value?.let { store.loadState(it.id) }

    override suspend fun refresh(): Result<AudioState> =
        call("GET", "/api/state", null) { Json.decodeFromString<AudioState>(it) }.onSuccess { applyState(it) }

    override suspend fun setOutput(id: String): Result<AudioState> =
        call("POST", "/api/output", Json.encodeToString(mapOf("id" to id))) { Json.decodeFromString<AudioState>(it) }
            .onSuccess { applyState(it) }

    override suspend fun setOutputByMatch(match: String): Result<AudioState> =
        call("POST", "/api/output", Json.encodeToString(mapOf("match" to match))) { Json.decodeFromString<AudioState>(it) }
            .onSuccess { applyState(it) }

    override suspend fun setVolume(level: Int): Result<VolumeState> =
        volumeCall("""{"level":${level.coerceIn(0, 100)}}""")

    override suspend fun changeVolume(delta: Int): Result<VolumeState> = volumeCall("""{"delta":$delta}""")

    override suspend fun setMute(state: MuteState): Result<VolumeState> =
        call("POST", "/api/mute", """{"state":"${state.wire}"}""") { Json.decodeFromString<VolumeState>(it) }
            .onSuccess { applyVolume(it) }

    private suspend fun volumeCall(body: String): Result<VolumeState> =
        call("POST", "/api/volume", body) { Json.decodeFromString<VolumeState>(it) }.onSuccess { applyVolume(it) }

    override fun imageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) return path
        val server = _active.value ?: return null
        return server.baseUrl + path
    }

    private fun applyState(s: AudioState) { _state.value = s }

    private fun applyVolume(v: VolumeState) {
        _state.update { it?.copy(volume = v.volume, muted = v.muted) }
    }

    // --- HTTP with re-resolve ---------------------------------------------------------------

    private suspend fun <T> call(method: String, path: String, body: String?, parse: (String) -> T): Result<T> {
        var server = _active.value ?: return Result.failure(NoServerException())
        var raw = http.exchange(server.baseUrl, server.token, method, path, body)
        if (needsResolve(server, raw)) {
            resolve(server)?.let { moved ->
                server = moved
                raw = http.exchange(server.baseUrl, server.token, method, path, body)
            }
        }
        val stillActive = _active.value?.id == server.id
        return when (val r = raw) {
            is Raw.Ok -> {
                if (stillActive) _connection.value = ConnectionStatus.CONNECTED
                runCatching { parse(r.body) }
            }
            is Raw.Http -> {
                if (stillActive) _connection.value =
                    if (r.status == 401) ConnectionStatus.UNAUTHORIZED else ConnectionStatus.CONNECTED
                Result.failure(ApiException(r.status, r.error?.error, r.error?.message))
            }
            is Raw.Net -> {
                if (stillActive && _connection.value != ConnectionStatus.UNAUTHORIZED) {
                    _connection.value = ConnectionStatus.OFFLINE
                }
                Result.failure(r.cause)
            }
        }
    }

    /** Network failure, a non-Audio-PC answer, or a 401 from a different server at that address. */
    private suspend fun needsResolve(server: SavedServer, raw: Raw): Boolean = when (raw) {
        is Raw.Ok -> false
        is Raw.Net -> true
        is Raw.Http -> when {
            raw.error == null -> true // not our API at all
            raw.status == 401 -> discovery.probe(server.host, server.port)?.id != server.id
            else -> false
        }
    }

    /** Rediscovers [server] by id; returns the updated server if its address changed. */
    private suspend fun resolve(server: SavedServer): SavedServer? = resolveLock.withLock {
        val current = _servers.value.firstOrNull { it.id == server.id } ?: return null
        if (current.host != server.host || current.port != server.port) return current // someone else did it
        // A powered-off PC would make every call pay for a full discovery: back off.
        val last = lastResolveFailure[server.id]
        if (last != null && System.currentTimeMillis() - last < 15_000) return null
        val found = discovery.findById(server.id)
        if (found == null || (found.host == current.host && found.port == current.port)) {
            lastResolveFailure[server.id] = System.currentTimeMillis()
            return null
        }
        lastResolveFailure.remove(server.id)
        val updated = current.copy(host = found.host, port = found.port)
        replaceServer(updated)
        updated
    }

    private suspend fun replaceServer(updated: SavedServer) = mutateLock.withLock {
        val list = _servers.value.map { if (it.id == updated.id) updated else it }
        _servers.value = list
        if (_active.value?.id == updated.id) _active.value = updated
        store.saveServers(list, _active.value?.id)
    }

    // --- live updates ----------------------------------------------------------------------

    private var liveJob: Job? = null

    @Synchronized
    private fun startLive() {
        liveJob?.cancel()
        liveJob = scope.launch {
            _active.collectLatest { server ->
                if (server == null) return@collectLatest
                if (_connection.value != ConnectionStatus.CONNECTED) _connection.value = ConnectionStatus.CONNECTING
                runLive(server)
            }
        }
    }

    @Synchronized
    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    private class WsEnd(val httpCode: Int, val opened: Boolean)

    private suspend fun runLive(server: SavedServer) = coroutineScope {
        val wsUp = MutableStateFlow(false)
        // Polling fallback while the socket is down (API.md section 4).
        launch {
            while (isActive) {
                delay(15_000)
                if (!wsUp.value && _connection.value != ConnectionStatus.UNAUTHORIZED) refresh()
            }
        }
        val backoff = longArrayOf(1000, 2000, 5000, 10_000)
        var fails = 0
        while (isActive) {
            val end = connectWs(server, wsUp)
            wsUp.value = false
            if (end.httpCode == 401) {
                refresh() // verifies it is really our server (or re-resolves it, which restarts us)
                if (_connection.value == ConnectionStatus.UNAUTHORIZED) awaitCancellation()
            }
            if (end.opened) fails = 0 else fails++
            if (_connection.value != ConnectionStatus.UNAUTHORIZED) {
                _connection.value = if (fails >= 2) ConnectionStatus.OFFLINE else ConnectionStatus.CONNECTING
            }
            if (fails == 2) refresh() // lets the HTTP path re-resolve a moved server quickly
            delay(backoff[minOf(fails, backoff.size - 1)])
        }
    }

    private suspend fun connectWs(server: SavedServer, wsUp: MutableStateFlow<Boolean>): WsEnd =
        suspendCancellableCoroutine { cont ->
            var opened = false
            val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(server.baseUrl + "/api/events")
                .header("Authorization", "Bearer ${server.token}").build()
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    wsUp.value = true
                    _connection.value = ConnectionStatus.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { Json.decodeFromString<EventMessage>(text) }.getOrNull() ?: return
                    if (msg.type == "state" && msg.state != null) applyState(msg.state)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (cont.isActive) cont.resume(WsEnd(0, opened))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resume(WsEnd(response?.code ?: 0, opened))
                }
            })
            cont.invokeOnCancellation { ws.cancel() }
        }

    // --- servers and pairing ---------------------------------------------------------------

    /** Validates a hand-typed `host[:port]` against /api/info. */
    suspend fun probeManual(input: String): Result<DiscoveredServer> {
        val cleaned = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("empty"))
        val idx = cleaned.lastIndexOf(':')
        val (host, port) = if (idx > 0 && !cleaned.endsWith("]") && cleaned.count { it == ':' } == 1) {
            cleaned.substring(0, idx) to (cleaned.substring(idx + 1).toIntOrNull() ?: return Result.failure(IllegalArgumentException("port")))
        } else cleaned to Discovery.DEFAULT_PORT
        val found = discovery.probe(host, port, fast = false)
        return if (found != null) Result.success(found) else Result.failure(java.io.IOException("not an Audio PC server"))
    }

    suspend fun pairRequest(host: String, port: Int): Result<PairRequestResponse> {
        val body = Json.encodeToString(PairRequestBody(deviceName(), deviceId))
        return http.exchange("http://$host:$port", null, "POST", "/api/pair/request", body).toResult()
            .mapCatching { Json.decodeFromString<PairRequestResponse>(it) }
    }

    /** On success the server is saved (replacing the old entry/token) and becomes the active one. */
    suspend fun pairConfirm(host: String, port: Int, requestId: String, code: String): Result<SavedServer> {
        val body = Json.encodeToString(PairConfirmBody(requestId, code))
        return http.exchange("http://$host:$port", null, "POST", "/api/pair/confirm", body).toResult()
            .mapCatching { Json.decodeFromString<PairConfirmResponse>(it) }
            .map { r ->
                val saved = SavedServer(r.serverId, r.serverName, host, port, r.token)
                mutateLock.withLock {
                    val list = _servers.value.filter { it.id != saved.id } + saved
                    _servers.value = list
                    store.saveServers(list, saved.id)
                }
                activate(saved)
                saved
            }
    }

    /** A fresh discovery saw a saved server at a new address. */
    suspend fun updateAddress(id: String, host: String, port: Int) {
        val s = _servers.value.firstOrNull { it.id == id } ?: return
        if (s.host != host || s.port != port) replaceServer(s.copy(host = host, port = port))
    }

    suspend fun setActive(id: String) {
        val server = _servers.value.firstOrNull { it.id == id } ?: return
        mutateLock.withLock { store.saveServers(_servers.value, id) }
        activate(server)
    }

    suspend fun forget(id: String) {
        mutateLock.withLock {
            val list = _servers.value.filter { it.id != id }
            _servers.value = list
            store.clearState(id)
            if (_active.value?.id == id) {
                val next = list.firstOrNull()
                _state.value = null
                _active.value = next
                if (next != null) {
                    _state.value = store.loadState(next.id)
                    _connection.value = ConnectionStatus.CONNECTING
                } else _connection.value = ConnectionStatus.NO_SERVER
            }
            store.saveServers(list, _active.value?.id)
        }
        _active.value?.let { scope.launch { refresh() } }
    }

    private suspend fun activate(server: SavedServer) {
        if (_active.value?.id != server.id) {
            _state.value = store.loadState(server.id)
        }
        _connection.value = ConnectionStatus.CONNECTING
        _active.value = server
        scope.launch { refresh() }
    }

    /** "Xiaomi 24069PC21G" style name shown on the PC next to the pairing code. */
    fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val prettyMaker = maker.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return if (model.startsWith(maker, ignoreCase = true)) model.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else "$prettyMaker $model".trim()
    }
}
