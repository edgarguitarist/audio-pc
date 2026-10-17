package com.edgarguitarist.audiopc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.data.ConnectionStatus

fun kindIcon(kind: String): ImageVector = when (kind) {
    "headphones", "earbuds" -> Icons.Outlined.Headphones
    "headset" -> Icons.Outlined.HeadsetMic
    "tv" -> Icons.Outlined.Tv
    "virtual" -> Icons.Outlined.GraphicEq
    else -> Icons.Outlined.Speaker
}

/** Light rounded tile behind a device photo (same look as the web panel), icon when there is none. */
@Composable
fun OutputThumb(imageUrl: String?, kind: String, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Palette.ThumbTop, Palette.ThumbBottom))),
        contentAlignment = Alignment.Center,
    ) {
        val fallback: @Composable () -> Unit = {
            Icon(kindIcon(kind), null, Modifier.size(size * 0.5f), tint = Color(0xFF3A4452))
        }
        if (imageUrl == null) fallback()
        else SubcomposeAsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size).padding(size * 0.1f),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

@Composable
fun ConnectionPill(status: ConnectionStatus) {
    val ap = MaterialTheme.ap
    val (label, dot) = when (status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.conn_connected) to ap.accent
        ConnectionStatus.CONNECTING, ConnectionStatus.NO_SERVER -> stringResource(R.string.conn_connecting) to ap.warn
        ConnectionStatus.OFFLINE -> stringResource(R.string.conn_offline) to MaterialTheme.colorScheme.error
        ConnectionStatus.UNAUTHORIZED -> stringResource(R.string.conn_unauthorized) to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
