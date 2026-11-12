package com.streamflixreborn.streamflix.desktop.player

import android.util.Log
import org.jetbrains.skia.DirectContext
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.Window

/**
 * Every video frame is a fresh image, and Skia keeps the GPU textures of old ones around until
 * its cache budget (256 MB by default) is full; that memory then stays with the app even after the
 * player is closed. A small budget is plenty: a 4K frame is about 12 MB of textures.
 *
 * Compose doesn't expose Skia's context, so it's reached by reflection; if that ever fails
 * (a different Compose version), nothing changes and the app behaves as before.
 */
object GpuMemory {
    private const val TAG = "GpuMemory"
    private const val LIMIT_BYTES = 64L * 1024 * 1024

    @Volatile var window: Window? = null

    fun limit() {
        val w = window ?: return
        runCatching {
            val layer = findLayer(w) ?: return
            val redrawer = SkiaLayer::class.java.methods.firstOrNull { it.name.startsWith("getRedrawer") && it.parameterCount == 0 }
                ?.invoke(layer) ?: return
            val handler = fieldsOf(redrawer.javaClass).firstOrNull { it.name == "contextHandler" }
                ?.apply { isAccessible = true }?.get(redrawer) ?: return
            val context = fieldsOf(handler.javaClass).firstOrNull { DirectContext::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }?.get(handler) as? DirectContext ?: return
            if (context.resourceCacheLimit != LIMIT_BYTES) {
                Log.i(TAG, "GPU cache ${context.resourceCacheLimit / 1048576} MB -> ${LIMIT_BYTES / 1048576} MB")
                context.resourceCacheLimit = LIMIT_BYTES
            }
        }.onFailure { Log.w(TAG, "could not limit the GPU cache: ${it.message}") }
    }

    private fun findLayer(c: Component): SkiaLayer? {
        if (c is SkiaLayer) return c
        if (c is Container) for (child in c.components) findLayer(child)?.let { return it }
        return null
    }

    private fun fieldsOf(type: Class<*>): List<java.lang.reflect.Field> =
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }.toList()
}
