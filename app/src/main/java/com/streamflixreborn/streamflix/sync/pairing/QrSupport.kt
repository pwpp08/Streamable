package com.streamflixreborn.streamflix.sync.pairing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

/**
 * Renders a QR code bitmap. [BuiltInQrRenderer] is always available; a zxing
 * based renderer lives in app/src/pending until com.google.zxing:core is added.
 */
fun interface QrRenderer {
    fun render(text: String, sizePx: Int): Bitmap
}

object BuiltInQrRenderer : QrRenderer {
    override fun render(text: String, sizePx: Int): Bitmap = QrEncoder.toBitmap(QrEncoder.encode(text), sizePx)
}

/**
 * Seam for scanning a QR code on the phone. The default asks an installed
 * barcode scanner app (ZXing "SCAN" intent); [QrScanners.default] uses the
 * embedded zxing scanner instead.
 */
interface QrScanner {
    /** Intent to launch for a result, or null when no scanner is available. */
    fun createScanIntent(context: Context): Intent?

    /** Extracts the scanned text from the activity result. */
    fun parseResult(resultCode: Int, data: Intent?): String?
}

/**
 * Uses any installed app that handles `com.google.zxing.client.android.SCAN`.
 * Package visibility rules hide such apps from queries on Android 11+, so the
 * caller simply launches the intent and handles ActivityNotFoundException.
 */
object IntentQrScanner : QrScanner {
    private const val ACTION_SCAN = "com.google.zxing.client.android.SCAN"

    override fun createScanIntent(context: Context): Intent = Intent(ACTION_SCAN).apply {
        putExtra("SCAN_MODE", "QR_CODE_MODE")
        putExtra("SAVE_HISTORY", false)
    }

    override fun parseResult(resultCode: Int, data: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) data?.getStringExtra("SCAN_RESULT") else null
}

object QrScanners {
    val default: QrScanner = ZxingEmbeddedQrScanner
}
