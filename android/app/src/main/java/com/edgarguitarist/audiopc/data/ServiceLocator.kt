package com.edgarguitarist.audiopc.data

import android.content.Context

/** Punto de acceso único a la capa de datos (app, widgets y mosaicos). */
object ServiceLocator {
    @Volatile private var repo: AudioPcRepositoryImpl? = null

    fun repository(context: Context): AudioPcRepository = app(context)

    /** Same instance with the extras only the app UI needs (servers, pairing, discovery). */
    fun app(context: Context): AudioPcRepositoryImpl =
        repo ?: synchronized(this) { repo ?: create(context.applicationContext).also { repo = it } }

    private fun create(appContext: Context) = AudioPcRepositoryImpl(appContext)
}
