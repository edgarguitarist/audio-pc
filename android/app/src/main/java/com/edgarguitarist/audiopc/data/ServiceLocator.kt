package com.edgarguitarist.audiopc.data

import android.content.Context

/** Punto de acceso único a la capa de datos (app, widgets y mosaicos). */
object ServiceLocator {
    @Volatile private var repo: AudioPcRepository? = null

    fun repository(context: Context): AudioPcRepository =
        repo ?: synchronized(this) { repo ?: create(context.applicationContext).also { repo = it } }

    // Lo implementa la capa de datos (agente de la app).
    private fun create(appContext: Context): AudioPcRepository =
        TODO("Implementar en la capa de datos")
}
