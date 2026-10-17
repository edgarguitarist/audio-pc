package com.edgarguitarist.audiopc.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que la app y los widgets necesitan del servidor emparejado activo. Lo implementa la capa
 * de datos; los widgets y mosaicos lo obtienen con [ServiceLocator.repository].
 * Todas las funciones `suspend` son seguras de llamar desde cualquier hilo.
 */
interface AudioPcRepository {
    /** Servidor activo (null si todavía no hay ninguno emparejado). */
    val activeServer: StateFlow<SavedServer?>

    /** Último estado conocido (en vivo mientras el WebSocket esté conectado). */
    val state: StateFlow<AudioState?>

    val connection: StateFlow<ConnectionStatus>

    /** Estado guardado en disco de la última vez (para pintar widgets sin red). */
    suspend fun cachedState(): AudioState?

    /** Pide GET /api/state, actualiza [state] y la caché. */
    suspend fun refresh(): Result<AudioState>

    suspend fun setOutput(id: String): Result<AudioState>
    suspend fun setOutputByMatch(match: String): Result<AudioState>
    suspend fun setVolume(level: Int): Result<VolumeState>
    suspend fun changeVolume(delta: Int): Result<VolumeState>
    suspend fun setMute(state: MuteState): Result<VolumeState>

    /** URL absoluta de una imagen de [Output.image], o null. */
    fun imageUrl(path: String?): String?
}
