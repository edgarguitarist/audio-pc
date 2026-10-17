package com.edgarguitarist.audiopc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.DiscoveredServer

@Composable
fun ServersScreen(vm: AppViewModel, canGoBack: Boolean, onBack: () -> Unit) {
    val saved by vm.repo.servers.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }

    // Discovery only runs while this screen is visible.
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    Column(Modifier.fillMaxSize()) {
        if (canGoBack) {
            IconButton(onClick = onBack, Modifier.padding(start = 4.dp, top = 4.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(stringResource(R.string.servers_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.servers_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
            }
            if (vm.searching) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.searching), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (vm.found.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.found_title).uppercase(),
                        Modifier.padding(top = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(vm.found, key = { it.id }) { s ->
                    ServerRow(s, paired = saved.any { it.id == s.id }) { vm.choose(s) }
                }
            }
            if (!vm.searching && vm.found.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)).padding(18.dp),
                    ) {
                        Text(stringResource(R.string.none_found_title), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.none_found_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { manual = true }) { Text(stringResource(R.string.add_manually)) }
                    OutlinedButton(onClick = vm::startDiscovery, enabled = !vm.searching) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.search_again))
                    }
                }
            }
        }
    }

    if (manual) ManualDialog(vm) { manual = false }
}

@Composable
private fun ServerRow(s: DiscoveredServer, paired: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outline, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Computer, null, tint = cs.primary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.host}:${s.port}", fontSize = 13.sp, color = cs.onSurfaceVariant)
        }
        if (paired) {
            Text(
                stringResource(R.string.chip_paired), fontSize = 12.sp, color = cs.primary,
                modifier = Modifier.clip(CircleShape).background(cs.surfaceVariant).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ManualDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearManualError() }
    AlertDialog(
        onDismissRequest = { if (!vm.manualBusy) onDismiss() },
        title = { Text(stringResource(R.string.manual_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; vm.clearManualError() },
                    label = { Text(stringResource(R.string.manual_label)) },
                    placeholder = { Text(stringResource(R.string.manual_hint)) },
                    singleLine = true,
                    isError = vm.manualError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = if (vm.manualError) ({ Text(stringResource(R.string.manual_error)) }) else null,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.addManual(text, onDismiss) }, enabled = text.isNotBlank() && !vm.manualBusy) {
                if (vm.manualBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.manual_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.manualBusy) { Text(stringResource(R.string.cancel)) } },
    )
}
