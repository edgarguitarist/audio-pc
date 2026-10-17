package com.edgarguitarist.audiopc.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.edgarguitarist.audiopc.MainActivity
import com.edgarguitarist.audiopc.R

/** The output a button widget instance is bound to, stored in its Glance (DataStore) state. */
internal object ButtonPrefs {
    val Id = stringPreferencesKey("outputId")
    val Name = stringPreferencesKey("outputName")
    val Kind = stringPreferencesKey("outputKind")
    val Image = stringPreferencesKey("outputImage")
}

class OutputButtonWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState<Preferences>(context, id)
        val outputId = prefs[ButtonPrefs.Id]
        val storedName = prefs[ButtonPrefs.Name]
        val storedKind = prefs[ButtonPrefs.Kind] ?: "speaker"

        val snap = WidgetData.snapshot(context, refreshIfEmpty = false)
        val live = snap.state?.outputs?.firstOrNull { it.id == outputId }
        val name = live?.name ?: storedName ?: context.getString(R.string.widget_no_output)
        val kind = live?.kind ?: storedKind
        val photo = WidgetData.photo(context, live?.image ?: prefs[ButtonPrefs.Image])
        val current = live != null && snap.state?.currentId == live.id
        // Dim when the device is not connected (missing from the list) or the PC is unreachable.
        val dimmed = live == null || snap.offline
        val px = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
        val art = TileArt.render(context, photo, kind, px, highlighted = current && !snap.offline, dimmed = dimmed)

        val action = when {
            !snap.paired || outputId == null -> actionStartActivity<MainActivity>()
            live == null -> actionRunCallback<RetryAction>() // not connected: refresh instead of a doomed switch
            else -> actionRunCallback<SetOutputAction>(actionParametersOf(OutputIdKey to outputId))
        }

        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ImageProvider(R.drawable.widget_card_bg))
                    .padding(4.dp)
                    .clickable(action),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(ImageProvider(art), contentDescription = name, modifier = GlanceModifier.size(40.dp))
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        text = name,
                        maxLines = 1,
                        style = TextStyle(
                            color = when {
                                dimmed -> WColors.muted
                                current -> WColors.accent
                                else -> WColors.text
                            },
                            fontSize = 10.sp,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                }
            }
        }
    }
}

class OutputButtonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OutputButtonWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.sync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.sync(context)
    }
}
