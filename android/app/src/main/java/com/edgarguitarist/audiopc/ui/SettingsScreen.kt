package com.edgarguitarist.audiopc.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.SavedServer

private const val GITHUB = "https://github.com/edgarguitarist/audio-pc"

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val servers by vm.repo.servers.collectAsStateWithLifecycle()
    val active by vm.repo.activeServer.collectAsStateWithLifecycle()
    var toForget by remember { mutableStateOf<SavedServer?>(null) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.settings), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionLabel(stringResource(R.string.settings_servers)) }
            items(servers, key = { it.id }) { s ->
                val isActive = s.id == active?.id
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(cs.surface)
                        .border(1.dp, if (isActive) cs.primary else cs.outline, shape)
                        .clickable(enabled = !isActive, onClickLabel = stringResource(R.string.settings_switch)) { vm.switchTo(s.id) }
                        .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isActive) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        if (isActive) stringResource(R.string.settings_active) else null,
                        tint = if (isActive) cs.primary else MaterialTheme.ap.border,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.host}:${s.port}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { toForget = s }) {
                        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.settings_forget), tint = cs.onSurfaceVariant)
                    }
                }
            }
            item {
                TextButton(onClick = { vm.push(Screen.Servers) }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_pair_another))
                }
            }
            item { Spacer(Modifier.height(8.dp)); SectionLabel(stringResource(R.string.settings_about)) }
            item {
                val shape = RoundedCornerShape(18.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outline, shape)
                        .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB))) }
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(stringResource(R.string.settings_version, version), fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_github), color = cs.primary, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp), tint = cs.primary)
                    }
                }
            }
        }
    }

    toForget?.let { s ->
        AlertDialog(
            onDismissRequest = { toForget = null },
            title = { Text(stringResource(R.string.forget_title, s.name)) },
            text = { Text(stringResource(R.string.forget_body)) },
            confirmButton = {
                TextButton(onClick = { vm.forget(s.id); toForget = null }) {
                    Text(stringResource(R.string.settings_forget), color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { toForget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), Modifier.padding(start = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
