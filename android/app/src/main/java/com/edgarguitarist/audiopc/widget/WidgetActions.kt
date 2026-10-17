package com.edgarguitarist.audiopc.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.edgarguitarist.audiopc.data.AudioPcRepository
import com.edgarguitarist.audiopc.data.MuteState
import com.edgarguitarist.audiopc.data.ServiceLocator

internal val OutputIdKey = ActionParameters.Key<String>("outputId")
internal val DeltaKey = ActionParameters.Key<Int>("delta")

/**
 * Runs a repository call, records whether the PC answered, then redraws every widget and tile.
 * Failures keep the last known state on screen; only the "offline" mark changes.
 */
private suspend fun perform(context: Context, call: suspend (AudioPcRepository) -> Result<*>) {
    val result = runCatching { call(ServiceLocator.repository(context)) }.getOrElse { Result.failure<Unit>(it) }
    WidgetStatus.setOffline(context, result.isFailure)
    Widgets.updateAll(context)
}

class ToggleMuteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        perform(context) { it.setMute(MuteState.TOGGLE) }
}

class VolumeDeltaAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DeltaKey] ?: return
        perform(context) { it.changeVolume(delta) }
    }
}

class SetOutputAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[OutputIdKey] ?: return
        perform(context) { it.setOutput(id) }
    }
}

/** Tap on the "offline" label, or on an output button whose device is not connected. */
class RetryAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        perform(context) { it.refresh() }
}
