package com.edgarguitarist.audiopc.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore("audiopc")

/** Everything that survives a process death: paired servers, the active one, device id, state cache. */
class Store(context: Context) {
    private val ds = context.applicationContext.dataStore
    private val kServers = stringPreferencesKey("servers")
    private val kActive = stringPreferencesKey("active_id")
    private val kDevice = stringPreferencesKey("device_id")
    private fun kState(serverId: String) = stringPreferencesKey("state_$serverId")

    data class Snapshot(val servers: List<SavedServer>, val activeId: String?, val deviceId: String)

    suspend fun load(): Snapshot {
        var prefs = ds.data.first()
        if (prefs[kDevice] == null) {
            // Stable random id so the server can replace our token instead of piling up devices.
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            ds.edit { it[kDevice] = bytes.joinToString("") { b -> "%02x".format(b) } }
            prefs = ds.data.first()
        }
        val servers = prefs[kServers]?.let {
            runCatching { Json.decodeFromString(ListSerializer(SavedServer.serializer()), it) }.getOrNull()
        } ?: emptyList()
        return Snapshot(servers, prefs[kActive], prefs[kDevice]!!)
    }

    suspend fun saveServers(servers: List<SavedServer>, activeId: String?) {
        ds.edit {
            it[kServers] = Json.encodeToString(ListSerializer(SavedServer.serializer()), servers)
            if (activeId != null) it[kActive] = activeId else it.remove(kActive)
        }
    }

    suspend fun loadState(serverId: String): AudioState? =
        ds.data.first()[kState(serverId)]?.let { runCatching { Json.decodeFromString<AudioState>(it) }.getOrNull() }

    suspend fun saveState(serverId: String, state: AudioState) {
        ds.edit { it[kState(serverId)] = Json.encodeToString(AudioState.serializer(), state) }
    }

    suspend fun clearState(serverId: String) {
        ds.edit { it.remove(kState(serverId)) }
    }
}
