package com.edgarguitarist.audiopc.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.AudioPcRepository
import com.edgarguitarist.audiopc.data.MuteState
import com.edgarguitarist.audiopc.widget.Snapshot
import com.edgarguitarist.audiopc.widget.WidgetStatus

/** Active = sound on, inactive = muted; tap toggles mute on the PC. */
class MuteTileService : BaseTileService() {
    override fun render(tile: Tile, snap: Snapshot) {
        val state = snap.state
        if (state == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_app_name)
            tile.setSubtitleCompat(getString(R.string.tile_not_paired))
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_volume)
            return
        }
        tile.state = if (state.muted) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_pc_name, state.current?.name ?: getString(R.string.widget_no_output))
        tile.setSubtitleCompat(
            when {
                snap.offline -> getString(R.string.tile_offline)
                state.muted -> getString(R.string.widget_muted)
                else -> getString(R.string.widget_volume, state.volume)
            },
        )
        tile.icon = Icon.createWithResource(this, if (state.muted) R.drawable.ic_tile_volume_off else R.drawable.ic_tile_volume)
    }

    override suspend fun act(repo: AudioPcRepository, snap: Snapshot) {
        val result = repo.setMute(MuteState.TOGGLE)
        WidgetStatus.setOffline(applicationContext, result.isFailure)
    }
}
