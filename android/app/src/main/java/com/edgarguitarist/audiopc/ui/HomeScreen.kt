package com.edgarguitarist.audiopc.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.ApiException
import com.edgarguitarist.audiopc.data.AudioState
import com.edgarguitarist.audiopc.data.ConnectionStatus
import com.edgarguitarist.audiopc.data.Output

fun errorMessage(ctx: Context, t: Throwable): String = when {
    t is ApiException && t.status == 401 -> ctx.getString(R.string.err_unauthorized)
    t is ApiException && t.status == 404 -> ctx.getString(R.string.err_not_found)
    t is ApiException -> t.message ?: ctx.getString(R.string.err_generic)
    t is java.io.IOException -> ctx.getString(R.string.err_unreachable)
    else -> ctx.getString(R.string.err_generic)
}

@Composable
fun HomeScreen(vm: AppViewModel, onSettings: () -> Unit) {
    val repo = vm.repo
    val server by repo.activeServer.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val status by repo.connection.collectAsStateWithLifecycle()
    val override by vm.volumeOverride.collectAsStateWithLifecycle()
    val live = status == ConnectionStatus.CONNECTED
    val name = server?.name.orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (name.isNotEmpty()) {
                    Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ConnectionPill(status)
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
        }

        val s = state
        when {
            s == null && status == ConnectionStatus.UNAUTHORIZED -> MessageCard(
                title = stringResource(R.string.unauth_title),
                body = stringResource(R.string.unauth_body, name),
                action = stringResource(R.string.pair_again),
            ) { server?.let { vm.pairAgain(it.id) } }
            s == null && status == ConnectionStatus.OFFLINE -> MessageCard(
                title = stringResource(R.string.offline_title, name),
                body = stringResource(R.string.offline_body),
                action = stringResource(R.string.retry),
            ) { vm.refresh() }
            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            else -> StateContent(vm, s, status, live, override, name)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StateContent(vm: AppViewModel, s: AudioState, status: ConnectionStatus, live: Boolean, override: Int?, serverName: String) {
    val haptic = LocalHapticFeedback.current
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!live && status != ConnectionStatus.CONNECTING) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CloudOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.offline_title, serverName),
                            Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = vm::refresh) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
            // Cached state while reconnecting is shown dimmed and inert.
            item {
                Box(Modifier.alpha(if (live) 1f else 0.55f)) {
                    HeroCard(vm, s, override, enabled = live)
                }
            }
            item {
                Text(
                    stringResource(R.string.outputs_title).uppercase(),
                    Modifier.padding(start = 4.dp, top = 8.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val outputs = s.visibleOutputs
            if (outputs.isEmpty()) {
                item { Text(stringResource(R.string.outputs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(outputs, key = { it.id }) { o ->
                Box(Modifier.alpha(if (live) 1f else 0.55f)) {
                    OutputRow(vm, o, selected = o.id == s.currentId, busy = vm.busyOutput == o.id, enabled = live) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectOutput(o.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(vm: AppViewModel, s: AudioState, override: Int?, enabled: Boolean) {
    val ap = MaterialTheme.ap
    val current = s.current
    val on = ap.onAccent
    val shown = override ?: s.volume
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(ap.accent, ap.accent2)))
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.playing_on).uppercase(),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = on.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutputThumb(vm.repo.imageUrl(current?.image), current?.kind ?: "speaker", 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    current?.name ?: stringResource(R.string.no_output),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = on, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!current?.detail.isNullOrBlank()) {
                    Text(current!!.detail, fontSize = 14.sp, color = on.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(on.copy(alpha = 0.18f))
                    .clickable(enabled = enabled, onClickLabel = stringResource(if (s.muted) R.string.unmute else R.string.mute)) { vm.toggleMute() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (s.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    stringResource(if (s.muted) R.string.unmute else R.string.mute),
                    tint = on,
                )
            }
            Slider(
                value = shown.toFloat(),
                onValueChange = { vm.onVolumeChange(it.toInt()) },
                onValueChangeFinished = vm::onVolumeFinished,
                valueRange = 0f..100f,
                enabled = enabled && current != null,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).alpha(if (s.muted) 0.6f else 1f),
                colors = SliderDefaults.colors(
                    thumbColor = on, activeTrackColor = on,
                    inactiveTrackColor = on.copy(alpha = 0.28f),
                    disabledThumbColor = on.copy(alpha = 0.6f),
                    disabledActiveTrackColor = on.copy(alpha = 0.6f),
                    disabledInactiveTrackColor = on.copy(alpha = 0.2f),
                ),
            )
            Text(
                "$shown%", Modifier.width(48.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = on, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

@Composable
private fun OutputRow(vm: AppViewModel, o: Output, selected: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(cs.surface)
            .border(1.dp, cs.outline, shape)
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutputThumb(vm.repo.imageUrl(o.image), o.kind, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(o.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (o.detail.isNotBlank()) {
                Text(o.detail, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = cs.primary)
                selected -> Icon(Icons.Filled.CheckCircle, stringResource(R.string.selected), tint = cs.primary)
                else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = MaterialTheme.ap.border)
            }
        }
    }
}

@Composable
fun MessageCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
