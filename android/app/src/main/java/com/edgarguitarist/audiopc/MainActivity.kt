package com.edgarguitarist.audiopc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.edgarguitarist.audiopc.ui.AppViewModel
import com.edgarguitarist.audiopc.ui.AudioPcTheme
import com.edgarguitarist.audiopc.ui.HomeScreen
import com.edgarguitarist.audiopc.ui.PairScreen
import com.edgarguitarist.audiopc.ui.Screen
import com.edgarguitarist.audiopc.ui.ServersScreen
import com.edgarguitarist.audiopc.ui.SettingsScreen
import com.edgarguitarist.audiopc.ui.errorMessage

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AudioPcTheme { Root(vm) } }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(errorMessage(ctx, it)) } }

    val screen = vm.current
    BackHandler(enabled = vm.stack.size > 1) { vm.pop() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Crossfade(targetState = screen, label = "screen") { s ->
            when (s) {
                Screen.Home -> HomeScreen(vm, onSettings = { vm.push(Screen.Settings) })
                Screen.Servers -> ServersScreen(vm, canGoBack = vm.stack.size > 1, onBack = vm::pop)
                Screen.Settings -> SettingsScreen(vm, onBack = vm::pop)
                is Screen.Pair -> PairScreen(vm, s, onBack = vm::pop)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    }
}
