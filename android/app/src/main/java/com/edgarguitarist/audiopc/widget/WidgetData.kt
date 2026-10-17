package com.edgarguitarist.audiopc.widget

import android.content.Context
import android.graphics.Bitmap
import com.edgarguitarist.audiopc.data.AudioState
import com.edgarguitarist.audiopc.data.ConnectionStatus
import com.edgarguitarist.audiopc.data.Output
import com.edgarguitarist.audiopc.data.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Remembers across processes whether the last attempt to reach the PC failed. */
object WidgetStatus {
    private const val FILE = "widget_status"
    private const val OFFLINE = "offline"

    fun isOffline(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(OFFLINE, false)

    fun setOffline(context: Context, offline: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(OFFLINE, offline).apply()
    }
}

/** What a surface (widget or tile) needs to draw itself, always available even offline. */
data class Snapshot(
    val state: AudioState?,
    val paired: Boolean,
    val offline: Boolean,
    val unauthorized: Boolean,
)

internal object WidgetData {
    /**
     * Cache-first: live state, else the disk cache. Only when there is nothing at all and a
     * server is paired do we wait (briefly) for the network, so a fresh widget is not blank.
     */
    suspend fun snapshot(context: Context, refreshIfEmpty: Boolean = true): Snapshot {
        val repo = runCatching { ServiceLocator.repository(context) }.getOrNull()
            ?: return Snapshot(null, paired = false, offline = false, unauthorized = false)
        var state = repo.state.value ?: runCatching { repo.cachedState() }.getOrNull()
        if (state == null && refreshIfEmpty && repo.activeServer.value != null) {
            state = withTimeoutOrNull(4_000) { repo.refresh() }?.also {
                WidgetStatus.setOffline(context, it.isFailure)
            }?.getOrNull()
        }
        val conn = repo.connection.value
        if (conn == ConnectionStatus.CONNECTED) WidgetStatus.setOffline(context, false)
        val unauthorized = conn == ConnectionStatus.UNAUTHORIZED
        val offline = unauthorized || conn == ConnectionStatus.OFFLINE || WidgetStatus.isOffline(context)
        return Snapshot(state, paired = state != null || repo.activeServer.value != null, offline, unauthorized)
    }

    /** Photos for the given outputs, loaded in parallel (disk cache first). */
    suspend fun photos(context: Context, outputs: List<Output>): Map<String, Bitmap> {
        val repo = runCatching { ServiceLocator.repository(context) }.getOrNull() ?: return emptyMap()
        return coroutineScope {
            outputs.filter { it.image != null }.map { o ->
                async { repo.imageUrl(o.image)?.let { url -> WidgetImages.load(context, url)?.let { o.id to it } } }
            }.mapNotNull { it.await() }.toMap()
        }
    }

    suspend fun photo(context: Context, image: String?): Bitmap? {
        val repo = runCatching { ServiceLocator.repository(context) }.getOrNull() ?: return null
        return repo.imageUrl(image)?.let { WidgetImages.load(context, it) }
    }

    /** Outputs shown in the widget's row: at most [max], always including the current one. */
    fun rowOutputs(state: AudioState, max: Int): List<Output> {
        val visible = state.visibleOutputs
        val current = state.current
        val first = visible.take(max)
        if (current == null || first.any { it.id == current.id }) return first
        return first.take(max - 1) + current
    }
}
