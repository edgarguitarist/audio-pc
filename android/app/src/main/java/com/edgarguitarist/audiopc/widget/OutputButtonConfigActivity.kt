package com.edgarguitarist.audiopc.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.edgarguitarist.audiopc.MainActivity
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.AudioPcRepository
import com.edgarguitarist.audiopc.data.AudioState
import com.edgarguitarist.audiopc.data.Output
import com.edgarguitarist.audiopc.data.ServiceLocator
import com.edgarguitarist.audiopc.ui.Palette
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Shown when a 1x1 output button is placed (or reconfigured): pick which output it controls. */
class OutputButtonConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Cancelled unless an output is picked, so backing out does not leave an unbound widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val repo: AudioPcRepository? = runCatching { ServiceLocator.repository(this) }.getOrNull()
        setContent { ConfigScreen(repo) }
    }

    private fun choose(output: Output) {
        lifecycleScope.launch {
            val ctx = applicationContext
            val glanceId = GlanceAppWidgetManager(ctx).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(ctx, glanceId) {
                it[ButtonPrefs.Id] = output.id
                it[ButtonPrefs.Name] = output.name
                it[ButtonPrefs.Kind] = output.kind
                if (output.image != null) it[ButtonPrefs.Image] = output.image else it.remove(ButtonPrefs.Image)
            }
            OutputButtonWidget().update(ctx, glanceId)
            WidgetRefreshWorker.sync(ctx)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }

    @Composable
    private fun ConfigScreen(repo: AudioPcRepository?) {
        val dark = isSystemInDarkTheme()
        val bg = if (dark) Palette.BgDark else Palette.BgLight
        val surface = if (dark) Palette.SurfaceDark else Palette.SurfaceLight
        val border = if (dark) Palette.BorderDark else Palette.BorderLight
        val text = if (dark) Palette.TextDark else Palette.TextLight
        val muted = if (dark) Palette.MutedDark else Palette.MutedLight
        val accent = if (dark) Palette.Accent else Palette.AccentLight

        var state by remember { mutableStateOf<AudioState?>(null) }
        var loading by remember { mutableStateOf(true) }
        var reload by remember { mutableStateOf(0) }
        var hasServer by remember { mutableStateOf(false) }
        val paired = state != null || hasServer

        // Cached list first so the screen is instant, then a short refresh for the live one.
        LaunchedEffect(reload) {
            loading = true
            if (repo != null) {
                hasServer = repo.activeServer.value != null
                state = repo.state.value ?: runCatching { repo.cachedState() }.getOrNull()
                withTimeoutOrNull(4_000) { repo.refresh() }?.getOrNull()?.let { state = it }
            }
            loading = false
        }

        Column(
            Modifier.fillMaxSize().background(bg).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                stringResource(R.string.config_title), color = text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            val outputs = state?.visibleOutputs.orEmpty()
            when {
                outputs.isNotEmpty() -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(outputs, key = { it.id }) { o ->
                        OutputRow(repo, o, o.id == state?.currentId, surface, border, text, muted, accent)
                    }
                }
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(if (paired) R.string.config_empty else R.string.config_not_paired),
                        color = muted, fontSize = 15.sp,
                    )
                    val colors = ButtonDefaults.buttonColors(
                        containerColor = accent, contentColor = if (dark) Palette.OnAccentDark else Palette.OnAccentLight,
                    )
                    if (paired) {
                        Button(onClick = { reload++ }, colors = colors) { Text(stringResource(R.string.config_retry)) }
                    } else {
                        val ctx = LocalContext.current
                        Button(onClick = {
                            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }, colors = colors) { Text(stringResource(R.string.config_open_app)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun OutputRow(
        repo: AudioPcRepository?, o: Output, current: Boolean,
        surface: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color,
        text: androidx.compose.ui.graphics.Color, muted: androidx.compose.ui.graphics.Color,
        accent: androidx.compose.ui.graphics.Color,
    ) {
        val shape = RoundedCornerShape(16.dp)
        val icon = painterResource(TileArt.kindIcon(o.kind))
        Row(
            Modifier.fillMaxWidth().clip(shape).background(surface)
                .then(if (current) Modifier.background(accent.copy(alpha = 0.12f)) else Modifier)
                .clickable { choose(o) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(listOf(Palette.ThumbTop, Palette.ThumbBottom))),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = repo?.imageUrl(o.image),
                    contentDescription = o.name,
                    contentScale = ContentScale.Fit,
                    placeholder = icon, error = icon, fallback = icon,
                    modifier = Modifier.size(44.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(o.name, color = text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(o.detail, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (current) {
                Text(stringResource(R.string.config_current), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
