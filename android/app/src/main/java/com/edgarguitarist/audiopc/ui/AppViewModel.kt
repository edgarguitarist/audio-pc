package com.edgarguitarist.audiopc.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.edgarguitarist.audiopc.data.ApiException
import com.edgarguitarist.audiopc.data.ConnectionStatus
import com.edgarguitarist.audiopc.data.DiscoveredServer
import com.edgarguitarist.audiopc.data.MuteState
import com.edgarguitarist.audiopc.data.PairStart
import com.edgarguitarist.audiopc.data.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Servers : Screen
    data object Settings : Screen
    data class Pair(val host: String, val port: Int, val name: String, val serverId: String?) : Screen
}

enum class PairPhase { REQUESTING, ENTER, CONFIRMING, FAILED }
enum class PairError { BAD_CODE, EXPIRED, LOCKED, BLOCKED, RATE_LIMITED, NETWORK, OTHER }

data class PairUi(
    val phase: PairPhase = PairPhase.REQUESTING,
    val requestId: String? = null,
    val expiresAtMs: Long = 0,
    val error: PairError? = null,
    val code: String = "",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = ServiceLocator.app(app)

    // --- navigation (kept here so it survives rotation) -----------------------------------
    val stack = mutableStateListOf<Screen>(
        if (repo.activeServer.value == null) Screen.Servers else Screen.Home
    )
    val current: Screen get() = stack.last()

    fun push(s: Screen) { stack.add(s) }
    fun pop() {
        if (current is Screen.Pair) pairFor = null
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    private fun resetTo(s: Screen) { stack.clear(); stack.add(s) }

    private val _messages = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)
    val messages: SharedFlow<Throwable> = _messages.asSharedFlow()

    init {
        // Token revoked: take the user to re-pair that server.
        viewModelScope.launch {
            var last = repo.connection.value
            repo.connection.collect { c ->
                if (c == ConnectionStatus.UNAUTHORIZED && last != c && current == Screen.Home) {
                    repo.activeServer.value?.let { push(Screen.Pair(it.host, it.port, it.name, it.id)) }
                }
                last = c
            }
        }
    }

    // --- discovery ---------------------------------------------------------------------------
    var found by mutableStateOf<List<DiscoveredServer>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var manualBusy by mutableStateOf(false); private set
    var manualError by mutableStateOf(false); private set
    private var discoveryJob: Job? = null

    fun startDiscovery() {
        discoveryJob?.cancel()
        found = emptyList()
        searching = true
        discoveryJob = viewModelScope.launch {
            try {
                repo.discovery.discover().collect { s -> found = found + s }
            } finally {
                searching = false
            }
        }
    }

    fun stopDiscovery() { discoveryJob?.cancel() }

    fun clearManualError() { manualError = false }

    fun addManual(input: String, onDone: () -> Unit) {
        manualBusy = true
        manualError = false
        viewModelScope.launch {
            val r = repo.probeManual(input)
            manualBusy = false
            r.onSuccess { onDone(); choose(it) }.onFailure { manualError = true }
        }
    }

    /** A discovered/typed server was picked: reuse its saved pairing if there is one, else pair. */
    fun choose(s: DiscoveredServer) {
        val saved = repo.servers.value.firstOrNull { it.id == s.id }
        if (saved != null) {
            viewModelScope.launch {
                repo.updateAddress(saved.id, s.host, s.port)
                repo.setActive(saved.id)
                resetTo(Screen.Home)
            }
        } else {
            push(Screen.Pair(s.host, s.port, s.name, s.id))
        }
    }

    // --- pairing -----------------------------------------------------------------------------
    var pair by mutableStateOf(PairUi()); private set
    private var pairJob: Job? = null
    private var pairFor: Screen.Pair? = null

    /** Starts a request when entering the screen; a rotation reuses the one in progress. */
    fun ensurePairing(target: Screen.Pair) {
        if (pairFor != target || pair.phase == PairPhase.FAILED) requestPairing(target)
    }

    fun requestPairing(target: Screen.Pair) {
        pairFor = target
        pairJob?.cancel()
        pair = PairUi(PairPhase.REQUESTING)
        pairJob = viewModelScope.launch {
            repo.pairRequest(target.host, target.port)
                .onSuccess {
                    when (it) {
                        // Open pairing (the default): the PC paired us without a code.
                        is PairStart.Paired -> { pairFor = null; resetTo(Screen.Home) }
                        is PairStart.NeedsCode ->
                            pair = PairUi(PairPhase.ENTER, it.requestId, System.currentTimeMillis() + it.expiresIn * 1000L)
                    }
                }
                .onFailure { e ->
                    pair = PairUi(PairPhase.FAILED, error = when ((e as? ApiException)?.status) {
                        null -> PairError.NETWORK
                        423 -> PairError.BLOCKED // at request time only the PC-wide lock answers 423
                        429 -> PairError.RATE_LIMITED
                        else -> PairError.OTHER
                    })
                }
        }
    }

    fun onCodeChange(target: Screen.Pair, text: String) {
        val digits = text.filter { it.isDigit() }.take(6)
        if (pair.phase != PairPhase.ENTER) return
        pair = pair.copy(code = digits, error = null)
        if (digits.length == 6) confirm(target)
    }

    private fun confirm(target: Screen.Pair) {
        val p = pair
        val id = p.requestId ?: return
        pair = p.copy(phase = PairPhase.CONFIRMING)
        pairJob = viewModelScope.launch {
            repo.pairConfirm(target.host, target.port, id, p.code)
                .onSuccess { pairFor = null; resetTo(Screen.Home) }
                .onFailure { e ->
                    pair = when ((e as? ApiException)?.status) {
                        403 -> p.copy(phase = PairPhase.ENTER, code = "", error = PairError.BAD_CODE)
                        410 -> p.copy(phase = PairPhase.FAILED, error = PairError.EXPIRED)
                        423 -> p.copy(phase = PairPhase.FAILED, error = PairError.LOCKED)
                        null -> p.copy(phase = PairPhase.ENTER, code = "", error = PairError.NETWORK)
                        else -> p.copy(phase = PairPhase.FAILED, error = PairError.OTHER)
                    }
                }
        }
    }

    fun expirePairing() {
        if (pair.phase == PairPhase.ENTER) pair = pair.copy(phase = PairPhase.FAILED, error = PairError.EXPIRED, code = "")
    }

    // --- home --------------------------------------------------------------------------------
    var refreshing by mutableStateOf(false); private set
    var busyOutput by mutableStateOf<String?>(null); private set

    /** Slider value while the user touches it (and 2 s after), so pushed state cannot fight it. */
    private val _volumeOverride = MutableStateFlow<Int?>(null)
    val volumeOverride: StateFlow<Int?> = _volumeOverride.asStateFlow()
    private var pendingLevel: Int? = null
    private var sendJob: Job? = null
    private var releaseJob: Job? = null

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            repo.refresh()
            refreshing = false
        }
    }

    fun selectOutput(id: String) {
        if (busyOutput != null) return
        busyOutput = id
        viewModelScope.launch {
            repo.setOutput(id).onFailure { _messages.tryEmit(it) }
            busyOutput = null
        }
    }

    fun onVolumeChange(level: Int) {
        releaseJob?.cancel()
        _volumeOverride.value = level
        pendingLevel = level
        // One request in flight; whatever the user dragged to meanwhile is sent next (coalesced).
        if (sendJob?.isActive == true) return
        sendJob = viewModelScope.launch {
            while (true) {
                val l = pendingLevel ?: break
                pendingLevel = null
                repo.setVolume(l).onFailure { _messages.tryEmit(it) }
            }
        }
    }

    fun onVolumeFinished() {
        releaseJob?.cancel()
        releaseJob = viewModelScope.launch {
            delay(2000)
            _volumeOverride.value = null
        }
    }

    fun toggleMute() {
        viewModelScope.launch { repo.setMute(MuteState.TOGGLE).onFailure { _messages.tryEmit(it) } }
    }

    // --- settings ----------------------------------------------------------------------------
    fun switchTo(id: String) { viewModelScope.launch { repo.setActive(id); resetTo(Screen.Home) } }

    fun forget(id: String) {
        viewModelScope.launch {
            repo.forget(id)
            if (repo.servers.value.isEmpty()) resetTo(Screen.Servers)
        }
    }

    fun pairAgain(id: String) {
        repo.servers.value.firstOrNull { it.id == id }?.let { push(Screen.Pair(it.host, it.port, it.name, it.id)) }
    }
}
