package com.edgarguitarist.audiopc.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.edgarguitarist.audiopc.MainActivity
import com.edgarguitarist.audiopc.data.AudioPcRepository
import com.edgarguitarist.audiopc.data.ServiceLocator
import com.edgarguitarist.audiopc.widget.Snapshot
import com.edgarguitarist.audiopc.widget.WidgetData
import com.edgarguitarist.audiopc.widget.WidgetStatus
import com.edgarguitarist.audiopc.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shared plumbing: cache-first rendering, a timed refresh while the panel is open, tap handling. */
abstract class BaseTileService : TileService() {
    private var scope: CoroutineScope? = null

    /** Draw [snap] onto [tile] (the caller calls updateTile afterwards). */
    protected abstract fun render(tile: Tile, snap: Snapshot)

    /** Handle a tap for a paired server; the repository is ready to use. */
    protected abstract suspend fun act(repo: AudioPcRepository, snap: Snapshot)

    protected fun repo(): AudioPcRepository? = runCatching { ServiceLocator.repository(this) }.getOrNull()

    private fun draw(snap: Snapshot) {
        val tile = qsTile ?: return
        render(tile, snap)
        tile.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch {
            draw(WidgetData.snapshot(this@BaseTileService, refreshIfEmpty = false))
            val repo = repo() ?: return@launch
            // A bounded refresh: the QS panel must never wait on an unreachable PC.
            val result = withTimeoutOrNull(4_000) { repo.refresh() }
            if (result != null) WidgetStatus.setOffline(applicationContext, result.isFailure)
            else WidgetStatus.setOffline(applicationContext, true)
            draw(WidgetData.snapshot(this@BaseTileService, refreshIfEmpty = false))
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext
        Widgets.scope.launch {
            val snap = WidgetData.snapshot(app, refreshIfEmpty = false)
            val repo = repo()
            if (repo == null || !snap.paired || snap.state == null) {
                // Nothing to control yet: send the user to the app to pair.
                launch(Dispatchers.Main) { openApp() }
                return@launch
            }
            runCatching { act(repo, snap) }
            // Widgets.updateAll also asks the tiles (this one included) to re-render.
            Widgets.updateAll(app)
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated") // only reached below API 34
            startActivityAndCollapse(intent)
        }
    }

    protected fun Tile.setSubtitleCompat(text: CharSequence?) {
        if (Build.VERSION.SDK_INT >= 29) subtitle = text
    }
}
