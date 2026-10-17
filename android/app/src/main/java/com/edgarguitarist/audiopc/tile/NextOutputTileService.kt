package com.edgarguitarist.audiopc.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.AudioPcRepository
import com.edgarguitarist.audiopc.data.AudioState
import com.edgarguitarist.audiopc.widget.Snapshot
import com.edgarguitarist.audiopc.widget.WidgetStatus

/** Label is the current output; tap cycles to the next visible one. */
class NextOutputTileService : BaseTileService() {
    private fun nextOf(state: AudioState) = state.visibleOutputs.let { list ->
        if (list.isEmpty()) null else list[(list.indexOfFirst { it.id == state.currentId } + 1) % list.size]
    }

    override fun render(tile: Tile, snap: Snapshot) {
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_output)
        val state = snap.state
        if (state == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_app_name)
            tile.setSubtitleCompat(getString(R.string.tile_not_paired))
            return
        }
        tile.state = Tile.STATE_ACTIVE
        tile.label = state.current?.name ?: getString(R.string.widget_no_output)
        tile.setSubtitleCompat(
            if (snap.offline) getString(R.string.tile_offline)
            else nextOf(state)?.let { getString(R.string.tile_next_subtitle, it.name) },
        )
    }

    override suspend fun act(repo: AudioPcRepository, snap: Snapshot) {
        val next = snap.state?.let { nextOf(it) } ?: return
        val result = repo.setOutput(next.id)
        WidgetStatus.setOffline(applicationContext, result.isFailure)
    }
}
