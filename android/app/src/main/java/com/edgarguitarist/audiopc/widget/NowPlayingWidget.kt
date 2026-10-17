package com.edgarguitarist.audiopc.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.edgarguitarist.audiopc.MainActivity
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.AudioState
import com.edgarguitarist.audiopc.data.Output
import com.edgarguitarist.audiopc.ui.Palette

/** Theme-aware colors for Glance (day = light theme, night = dark theme). */
internal object WColors {
    val text = ColorProvider(day = Palette.TextLight, night = Palette.TextDark)
    val muted = ColorProvider(day = Palette.MutedLight, night = Palette.MutedDark)
    val accent = ColorProvider(day = Palette.AccentLight, night = Palette.Accent)
    val onAccent = ColorProvider(day = Palette.OnAccentLight, night = Palette.OnAccentDark)
    val danger = ColorProvider(day = Palette.Danger, night = Palette.Danger)
}

/** What NowPlayingWidget draws; photos are pre-rendered because Glance only takes Bitmaps. */
private class Model(
    val snap: Snapshot,
    val photos: Map<String, Bitmap>,
)

class NowPlayingWidget : GlanceAppWidget() {
    // Small (~2x1) shows photo, name and mute; medium (~4x2) adds volume and the output row;
    // large uses the extra height for bigger photos.
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 50.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 160.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetData.snapshot(context)
        val photos = snap.state?.let { WidgetData.photos(context, it.outputs.filter { o -> !o.hidden || o.id == it.currentId }) }
            ?: emptyMap()
        val model = Model(snap, photos)
        provideContent { Content(model) }
    }

    @Composable
    private fun Content(m: Model) {
        val size = LocalSize.current
        val state = m.snap.state
        Box(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state == null -> NotPaired()
                size.width < 190.dp || size.height < 90.dp -> Compact(m, state)
                else -> Full(m, state, big = size.height >= 150.dp)
            }
        }
    }

    @Composable
    private fun NotPaired() {
        val ctx = LocalContext.current
        Box(
            modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ctx.getString(R.string.widget_not_paired),
                style = TextStyle(color = WColors.muted, fontSize = 12.sp),
                maxLines = 3,
            )
        }
    }

    @Composable
    private fun Compact(m: Model, state: AudioState) {
        val ctx = LocalContext.current
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            InfoBlock(ctx, m, state, photoDp = 40, showDetail = false, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(6.dp))
            MuteButton(ctx, state.muted, 34)
        }
    }

    @Composable
    private fun Full(m: Model, state: AudioState, big: Boolean) {
        val ctx = LocalContext.current
        val photoDp = if (big) 64 else 48
        val tileDp = if (big) 52 else 34
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfoBlock(ctx, m, state, photoDp, showDetail = true, modifier = GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(4.dp))
                RoundButton(R.drawable.ic_w_minus, ctx.getString(R.string.widget_cd_volume_down), 32,
                    actionRunCallback<VolumeDeltaAction>(actionParametersOf(DeltaKey to -10)))
                Spacer(GlanceModifier.width(4.dp))
                RoundButton(R.drawable.ic_w_plus, ctx.getString(R.string.widget_cd_volume_up), 32,
                    actionRunCallback<VolumeDeltaAction>(actionParametersOf(DeltaKey to 10)))
                Spacer(GlanceModifier.width(4.dp))
                MuteButton(ctx, state.muted, 32)
            }
            Spacer(GlanceModifier.height(if (big) 8.dp else 4.dp))
            OutputRow(ctx, m, state, tileDp)
        }
    }

    /** Photo + name + secondary line. Tapping it opens the app (or retries when offline). */
    @Composable
    private fun InfoBlock(
        ctx: Context, m: Model, state: AudioState, photoDp: Int, showDetail: Boolean, modifier: GlanceModifier,
    ) {
        val cur = state.current
        val offline = m.snap.offline
        val density = ctx.resources.displayMetrics.density
        val px = (photoDp * density).toInt().coerceAtLeast(48)
        val art = TileArt.render(ctx, cur?.let { m.photos[it.id] }, cur?.kind ?: "speaker", px, dimmed = offline)
        Row(
            modifier = modifier.fillMaxHeight().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(ImageProvider(art), contentDescription = cur?.name, modifier = GlanceModifier.size(photoDp.dp))
            Spacer(GlanceModifier.width(8.dp))
            Column(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cur?.name ?: ctx.getString(R.string.widget_no_output),
                    style = TextStyle(color = WColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                val secondary = when {
                    m.snap.unauthorized -> ctx.getString(R.string.widget_unauthorized)
                    offline -> ctx.getString(R.string.widget_offline)
                    state.muted -> if (showDetail && cur != null) ctx.getString(R.string.widget_muted_detail, cur.detail)
                        else ctx.getString(R.string.widget_muted)
                    showDetail && cur != null -> ctx.getString(R.string.widget_volume_detail, state.volume, cur.detail)
                    else -> ctx.getString(R.string.widget_volume, state.volume)
                }
                Text(
                    text = secondary,
                    style = TextStyle(
                        color = if (offline) WColors.danger else if (state.muted) WColors.accent else WColors.muted,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                    modifier = if (offline && !m.snap.unauthorized) GlanceModifier.clickable(actionRunCallback<RetryAction>())
                        else GlanceModifier,
                )
            }
        }
    }

    @Composable
    private fun MuteButton(ctx: Context, muted: Boolean, sizeDp: Int) {
        RoundButton(
            icon = if (muted) R.drawable.ic_w_volume_off else R.drawable.ic_w_volume_up,
            description = ctx.getString(if (muted) R.string.widget_cd_unmute else R.string.widget_cd_mute),
            sizeDp = sizeDp,
            action = actionRunCallback<ToggleMuteAction>(),
            active = muted,
        )
    }

    @Composable
    private fun RoundButton(icon: Int, description: String, sizeDp: Int, action: Action, active: Boolean = false) {
        Box(
            modifier = GlanceModifier.size(sizeDp.dp)
                .background(ImageProvider(if (active) R.drawable.widget_btn_bg_active else R.drawable.widget_btn_bg))
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                colorFilter = ColorFilter.tint(if (active) WColors.onAccent else WColors.text),
                modifier = GlanceModifier.size((sizeDp * 0.56f).dp),
            )
        }
    }

    @Composable
    private fun OutputRow(ctx: Context, m: Model, state: AudioState, tileDp: Int) {
        val outputs = WidgetData.rowOutputs(state, 4)
        val px = (tileDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(40)
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            outputs.forEach { o -> OutputTile(ctx, m, o, o.id == state.currentId, tileDp, px) }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.OutputTile(
        ctx: Context, m: Model, o: Output, current: Boolean, tileDp: Int, px: Int,
    ) {
        val art = TileArt.render(ctx, m.photos[o.id], o.kind, px, highlighted = current, dimmed = m.snap.offline)
        Box(modifier = GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(art),
                contentDescription = o.name,
                modifier = GlanceModifier.size(tileDp.dp)
                    .clickable(actionRunCallback<SetOutputAction>(actionParametersOf(OutputIdKey to o.id))),
            )
        }
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.sync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.sync(context)
    }
}
