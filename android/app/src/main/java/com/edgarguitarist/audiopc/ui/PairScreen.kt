package com.edgarguitarist.audiopc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edgarguitarist.audiopc.R
import kotlinx.coroutines.delay

@Composable
fun PairScreen(vm: AppViewModel, target: Screen.Pair, onBack: () -> Unit) {
    val ui = vm.pair
    val cs = MaterialTheme.colorScheme

    // One pairing request per visit; rotation keeps the ViewModel's request alive.
    LaunchedEffect(target) {
        vm.ensurePairing(target)
    }

    // Countdown to expiry.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.expiresAtMs, ui.phase) {
        while (ui.phase == PairPhase.ENTER || ui.phase == PairPhase.CONFIRMING) {
            now = System.currentTimeMillis()
            if (ui.phase == PairPhase.ENTER && now >= ui.expiresAtMs) { vm.expirePairing(); break }
            delay(500)
        }
    }
    val remaining = ((ui.expiresAtMs - now) / 1000).coerceAtLeast(0)

    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack, Modifier.padding(start = 4.dp, top = 4.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pair_title, target.name), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            // Only once the PC asked for a code: with open pairing the request already pairs us,
            // and "enter the code" would flash for nothing.
            if (ui.phase == PairPhase.ENTER || ui.phase == PairPhase.CONFIRMING) {
                Text(
                    stringResource(R.string.pair_subtitle), color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(32.dp))

            when (ui.phase) {
                PairPhase.REQUESTING -> Status(stringResource(R.string.pair_requesting), busy = true)
                PairPhase.ENTER, PairPhase.CONFIRMING -> {
                    CodeBoxes(
                        code = ui.code, enabled = ui.phase == PairPhase.ENTER, isError = ui.error == PairError.BAD_CODE,
                        onChange = { vm.onCodeChange(target, it) },
                    )
                    Spacer(Modifier.height(20.dp))
                    when {
                        ui.phase == PairPhase.CONFIRMING -> Status(stringResource(R.string.pair_confirming), busy = true)
                        ui.error != null -> Text(pairErrorText(ui.error), color = cs.error, textAlign = TextAlign.Center)
                        else -> Text(
                            stringResource(R.string.pair_expires_in, "%d:%02d".format(remaining / 60, remaining % 60)),
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.pair_hint), color = cs.onSurfaceVariant, fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButtonLike(stringResource(R.string.pair_new_code)) { vm.requestPairing(target) }
                }
                PairPhase.FAILED -> {
                    Text(pairErrorText(ui.error ?: PairError.OTHER), color = cs.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    // No requestId: the request itself failed, so there was never a code to renew.
                    Button(onClick = { vm.requestPairing(target) }) {
                        Text(stringResource(if (ui.requestId == null) R.string.retry else R.string.pair_new_code))
                    }
                }
            }
        }
    }
}

@Composable
private fun pairErrorText(e: PairError) = stringResource(
    when (e) {
        PairError.BAD_CODE -> R.string.pair_bad_code
        PairError.EXPIRED -> R.string.pair_expired
        PairError.LOCKED -> R.string.pair_locked
        PairError.BLOCKED -> R.string.pair_blocked
        PairError.RATE_LIMITED -> R.string.pair_rate_limited
        PairError.NETWORK -> R.string.pair_network
        PairError.OTHER -> R.string.pair_other
    }
)

@Composable
private fun TextButtonLike(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun Status(text: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One hidden numeric field drawn as six boxes: paste, autofill and the IME all just work. */
@Composable
private fun CodeBoxes(code: String, enabled: Boolean, isError: Boolean, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(enabled) { if (enabled) { focus.requestFocus(); keyboard?.show() } }
    BasicTextField(
        value = code,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
        textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.focusRequester(focus),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { i ->
                    val active = enabled && i == code.length.coerceAtMost(5)
                    val border = when {
                        isError -> cs.error
                        active -> cs.primary
                        else -> cs.outline
                    }
                    Box(
                        Modifier.width(46.dp).height(60.dp).clip(RoundedCornerShape(14.dp))
                            .background(cs.surface).border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(code.getOrNull(i)?.toString().orEmpty(), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    )
}
