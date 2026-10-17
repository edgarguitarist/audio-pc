package com.edgarguitarist.audiopc.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.edgarguitarist.audiopc.widget.NowPlayingWidgetReceiver
import com.edgarguitarist.audiopc.widget.OutputButtonWidgetReceiver

/**
 * Debug-only widget host: behaves like a launcher (allocate, bind, configure, size) so the
 * real widgets can be tried on emulators whose launcher can't place widgets.
 *
 * ```
 * adb shell appwidget grantbind --package com.edgarguitarist.audiopc
 * adb shell am start -n com.edgarguitarist.audiopc/.debug.WidgetLabActivity
 * ```
 */
class WidgetLabActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var manager: AppWidgetManager
    private lateinit var list: LinearLayout
    private val prefs by lazy { getSharedPreferences("widget_lab", MODE_PRIVATE) }
    private var pending: Pair<Int, IntArray>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(32), dp(16), dp(16)) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("S") { add(NowPlayingWidgetReceiver::class.java, 180, 70) })
        bar.addView(button("M") { add(NowPlayingWidgetReceiver::class.java, 320, 130) })
        bar.addView(button("L") { add(NowPlayingWidgetReceiver::class.java, 320, 190) })
        bar.addView(button("Btn") { add(OutputButtonWidgetReceiver::class.java, 80, 80) })
        bar.addView(button("Clear") { clear() })
        list.addView(bar)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(24, 32, 56)); addView(list) })
        saved().forEach { (id, size) -> show(id, size[0], size[1]) }
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { super.onStop(); host.stopListening() }

    private fun add(provider: Class<*>, w: Int, h: Int) {
        val id = host.allocateAppWidgetId()
        val cn = ComponentName(this, provider)
        if (!manager.bindAppWidgetIdIfAllowed(id, cn)) {
            host.deleteAppWidgetId(id)
            title = "Run: adb shell appwidget grantbind --package $packageName"
            return
        }
        val info = manager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            pending = id to intArrayOf(w, h)
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(info.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), REQ_CONFIGURE
            )
        } else commit(id, w, h)
    }

    @Deprecated("Activity result API is overkill for a debug screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val (id, size) = pending ?: return
        pending = null
        if (resultCode == RESULT_OK) commit(id, size[0], size[1]) else host.deleteAppWidgetId(id)
    }

    private fun commit(id: Int, w: Int, h: Int) {
        prefs.edit().putString("w$id", "$w,$h").apply()
        show(id, w, h)
    }

    private fun show(id: Int, w: Int, h: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return
        // Same options a launcher sends, so Glance's responsive sizes pick the right layout.
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        })
        val view = host.createView(applicationContext, id, info)
        list.addView(FrameLayout(this).apply {
            setPadding(0, dp(12), 0, 0)
            addView(view, FrameLayout.LayoutParams(dp(w), dp(h)))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun saved(): List<Pair<Int, IntArray>> = prefs.all.keys.filter { it.startsWith("w") }.mapNotNull { k ->
        val id = k.drop(1).toIntOrNull() ?: return@mapNotNull null
        val p = (prefs.getString(k, null) ?: return@mapNotNull null).split(",").map { it.toInt() }
        id to intArrayOf(p[0], p[1])
    }.sortedBy { it.first }

    private fun clear() {
        saved().forEach { host.deleteAppWidgetId(it.first) }
        prefs.edit().clear().apply()
        recreate()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { onClick() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val HOST_ID = 4242; const val REQ_CONFIGURE = 1 }
}
