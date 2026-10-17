package com.edgarguitarist.audiopc.widget

import android.content.Context

/**
 * Punto de contacto entre la app y los widgets/mosaicos. La capa de datos lo llama cuando el
 * estado cambia (acción del usuario o evento del WebSocket, como mucho una vez por segundo).
 * Lo implementa el agente de widgets.
 */
object Widgets {
    fun update(context: Context) {
        // TODO(widgets): actualizar los widgets Glance y pedir refresco de los mosaicos.
    }
}
