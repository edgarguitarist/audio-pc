package com.edgarguitarist.audiopc.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import com.edgarguitarist.audiopc.tile.MuteTileService
import com.edgarguitarist.audiopc.tile.NextOutputTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Punto de contacto entre la app y los widgets/mosaicos. La capa de datos lo llama cuando el
 * estado cambia (acción del usuario o evento del WebSocket, como mucho una vez por segundo).
 */
object Widgets {
    /** Outlives any caller (receivers, tiles, view models), so an update is never cancelled midway. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _version = MutableStateFlow(0)

    /**
     * Bumped on every update. While a Glance session is alive, updateAll() only recomposes and
     * does not re-run provideGlance, so the widgets reload their data when this changes.
     */
    internal val version: StateFlow<Int> = _version.asStateFlow()

    fun update(context: Context) {
        val app = context.applicationContext
        scope.launch { updateAll(app) }
    }

    /** Suspending variant for callers that must wait (action callbacks, the refresh worker). */
    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        _version.value++
        // One failing widget must not stop the other from updating.
        runCatching { NowPlayingWidget().updateAll(app) }
        runCatching { OutputButtonWidget().updateAll(app) }
        refreshTiles(app)
    }

    private fun refreshTiles(context: Context) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, MuteTileService::class.java))
            TileService.requestListeningState(context, ComponentName(context, NextOutputTileService::class.java))
        }
    }

    /** True while at least one instance of either widget is on a home screen. */
    fun hasInstances(context: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        return listOf(NowPlayingWidgetReceiver::class.java, OutputButtonWidgetReceiver::class.java).any {
            mgr.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
        }
    }
}
