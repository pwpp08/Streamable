package com.streamflixreborn.streamflix.sync.pairing

// PENDING: not compiled yet. Requires
//   `implementation 'com.journeyapps:zxing-android-embedded:4.3.0'`
//   `implementation 'com.google.zxing:core:3.5.3'`
// To enable: move this file to app/src/main/java/... and set
// `QrScanners.default = ZxingEmbeddedQrScanner` in QrSupport.kt. It scans with
// the camera inside the app and does not need Google Play services.

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
