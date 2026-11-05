@file:Suppress("unused")

package android.os

/** Marker only: desktop never parcels anything. */
interface Parcelable

/** No UI looper on desktop worker threads: [myLooper] is null, so "on main thread" checks are false. */
class Looper private constructor() {
    companion object {
        private val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper? = if (java.awt.EventQueue.isDispatchThread()) main else null
    }
}
