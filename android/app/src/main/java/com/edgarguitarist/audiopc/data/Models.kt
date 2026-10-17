package com.edgarguitarist.audiopc.data

import kotlinx.serialization.Serializable

// Modelos de la API v1 (docs/API.md). Mantener en sincronía con el contrato.

@Serializable
data class ServerInfo(
    val service: String,
    val apiVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    /** Solo viene en la respuesta del broadcast UDP. */
    val port: Int? = null,
)

@Serializable
data class Output(
    val id: String,
    val rawName: String,
    val name: String,
    val detail: String,
    /** speaker, headphones, headset, earbuds, tv, virtual */
    val kind: String,
    /** Ruta relativa al servidor ("/api/images/x.webp?v=1") o null. */
    val image: String? = null,
    val isDefault: Boolean,
    val hidden: Boolean,
)

@Serializable
data class AudioState(
    val currentId: String? = null,
    val volume: Int,
    val muted: Boolean,
    val outputs: List<Output>,
) {
    val current: Output? get() = outputs.firstOrNull { it.id == currentId }
    /** Las que se muestran en listas: sin las ocultas. */
    val visibleOutputs: List<Output> get() = outputs.filter { !it.hidden }
}

@Serializable
data class VolumeState(val volume: Int, val muted: Boolean)

@Serializable
data class PairRequestBody(val deviceName: String, val deviceId: String)

/**
 * Respuesta de POST /api/pair/request. Con el emparejamiento abierto (por defecto) trae
 * [token]/[serverId]/[serverName] y ya está emparejado; si la PC pide código trae
 * [requestId]/[expiresIn]. Todo opcional para tolerar ambos modos.
 */
@Serializable
data class PairRequestResponse(
    val requestId: String? = null,
    val expiresIn: Int = 120,
    val token: String? = null,
    val serverId: String? = null,
    val serverName: String? = null,
)

/** Resultado de pedir emparejar: ya emparejado, o hace falta el código que muestra la PC. */
sealed interface PairStart {
    data class Paired(val server: SavedServer) : PairStart
    data class NeedsCode(val requestId: String, val expiresIn: Int) : PairStart
}

@Serializable
data class PairConfirmBody(val requestId: String, val code: String)

@Serializable
data class PairConfirmResponse(val token: String, val serverId: String, val serverName: String)

@Serializable
data class ApiError(val error: String, val message: String? = null)

/** Mensaje del WebSocket /api/events. */
@Serializable
data class EventMessage(val type: String, val state: AudioState? = null)

/** Un servidor emparejado, recordado por su id (la IP puede cambiar). */
@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 7799,
    val token: String,
) {
    val baseUrl: String get() = "http://$host:$port"
}

enum class MuteState(val wire: String) { ON("on"), OFF("off"), TOGGLE("toggle") }

enum class ConnectionStatus { NO_SERVER, CONNECTING, CONNECTED, OFFLINE, UNAUTHORIZED }
