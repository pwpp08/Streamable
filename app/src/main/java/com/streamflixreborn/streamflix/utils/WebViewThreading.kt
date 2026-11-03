package com.streamflixreborn.streamflix.utils

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/**
 * Stops and destroys this WebView on the main thread, whichever thread calls it.
 *
 * Coroutine cancellation handlers run on the thread that cancels the coroutine, which is often
 * not the main one (for example when a faster server wins and the others are cancelled).
 * WebView methods throw off the main thread, and that exception is fatal inside a
 * cancellation handler, so every WebView teardown from a suspend extractor goes through here.
 */
fun WebView.teardownOnMain() {
    val webView = this
    val action = Runnable {
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
}
