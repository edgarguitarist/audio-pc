package com.edgarguitarist.audiopc.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.edgarguitarist.audiopc.R
import com.edgarguitarist.audiopc.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Output photos as Bitmaps (Glance cannot load URLs), cached on disk so widgets work offline. */
internal object WidgetImages {
    private const val MAX_FILES = 80

    private fun dir(context: Context) = File(context.filesDir, "widget-images").apply { mkdirs() }

    private fun key(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun load(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(dir(context), key(url) + ".png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { return@withContext it }
            file.delete()
        }
        // The ?v= in the URL busts stale images, so a hit by URL never needs revalidation.
        val bmp = withTimeoutOrNull(3_000) {
            val req = ImageRequest.Builder(context).data(url).allowHardware(false)
                .size(256, 256).scale(Scale.FIT).build()
            (context.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
        } ?: return@withContext null
        runCatching { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        prune(context)
        bmp
    }

    private fun prune(context: Context) {
        val files = dir(context).listFiles() ?: return
        if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}

/** Draws the light rounded tile behind a photo (as in the web panel) into a ready-to-use Bitmap. */
internal object TileArt {
    fun kindIcon(kind: String): Int = when (kind) {
        "headphones" -> R.drawable.ic_kind_headphones
        "headset" -> R.drawable.ic_kind_headset
        "earbuds" -> R.drawable.ic_kind_earbuds
        "tv" -> R.drawable.ic_kind_tv
        "virtual" -> R.drawable.ic_kind_virtual
        else -> R.drawable.ic_kind_speaker
    }

    private fun Color.argb() = toArgb()

    /**
     * Rounded corners are baked into the bitmap because Glance cornerRadius only works on
     * Android 12+, and this also lets "dimmed" and "current" be drawn without extra layers.
     */
    fun render(
        context: Context, photo: Bitmap?, kind: String, px: Int,
        highlighted: Boolean = false, dimmed: Boolean = false,
    ): Bitmap {
        val out = createBitmap(px, px)
        val c = Canvas(out)
        val stroke = px * 0.06f
        val rect = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
        val radius = px * 0.24f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, px.toFloat(), Palette.ThumbTop.argb(), Palette.ThumbBottom.argb(), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        val pad = px * 0.14f
        val inner = RectF(pad, pad, px - pad, px - pad)
        if (photo != null) {
            val s = minOf(inner.width() / photo.width, inner.height() / photo.height)
            val w = photo.width * s
            val h = photo.height * s
            val l = inner.centerX() - w / 2
            val t = inner.centerY() - h / 2
            c.drawBitmap(photo, null, RectF(l, t, l + w, t + h), paint)
        } else {
            ContextCompat.getDrawable(context, kindIcon(kind))?.mutate()?.let { d ->
                d.setTint(0xFF4A5565.toInt())
                val ip = (px * 0.24f).toInt()
                d.setBounds(ip, ip, px - ip, px - ip)
                d.draw(c)
            }
        }
        if (highlighted) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = Palette.Accent.argb()
            c.drawRoundRect(rect, radius, radius, paint)
        }
        if (!dimmed) return out
        // Dimming through alpha lets the card behind show through in both themes.
        val faded = createBitmap(px, px)
        Canvas(faded).drawBitmap(out, 0f, 0f, Paint().apply { alpha = 90 })
        return faded
    }
}
