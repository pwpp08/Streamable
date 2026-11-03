package com.streamflixreborn.streamflix.sync.pairing

// Scans with the camera inside the app; does not need Google Play services.

import android.content.Context
import android.content.Intent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

object ZxingEmbeddedQrScanner : QrScanner {
    override fun createScanIntent(context: Context): Intent =
        ScanContract().createIntent(
            context,
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setPrompt(""),
        )

    override fun parseResult(resultCode: Int, data: Intent?): String? =
        ScanIntentResult.parseActivityResult(resultCode, data).contents
}
