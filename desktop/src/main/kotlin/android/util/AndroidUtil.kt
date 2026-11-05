// JVM stand-ins for the handful of Android framework classes the shared providers and extractors
// use. Each keeps the Android signature and does the plain-Java equivalent.
@file:Suppress("unused", "PackageDirectoryMismatch")

package android.util

import java.util.Base64 as JBase64

object Log {
    @Volatile var verbose = System.getProperty("streamflix.debug") != null

    private fun out(level: String, tag: String?, msg: String?, tr: Throwable? = null): Int {
        if (!verbose && (level == "D" || level == "V")) return 0
        System.err.println("$level/$tag: $msg")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String): Int = out("V", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String): Int = out("D", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String, tr: Throwable?): Int = out("D", tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String): Int = out("I", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String, tr: Throwable?): Int = out("I", tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String): Int = out("W", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = out("W", tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = out("W", tag, tr?.message, tr)
    @JvmStatic fun e(tag: String?, msg: String): Int = out("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = out("E", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""
}

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private fun decoder(flags: Int) = if (flags and URL_SAFE != 0) JBase64.getUrlDecoder() else JBase64.getMimeDecoder()

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        // Android's decoder is lenient: whitespace, missing padding and either alphabet are fine.
        var s = str.trim().replace(Regex("\\s"), "")
        if (flags and URL_SAFE != 0) s = s.replace('+', '-').replace('/', '_')
        val pad = (4 - s.trimEnd('=').length % 4) % 4
        s = s.trimEnd('=') + "=".repeat(pad)
        return try {
            decoder(flags).decode(s)
        } catch (e: IllegalArgumentException) {
            val alt = if (flags and URL_SAFE != 0) s.replace('-', '+').replace('_', '/') else s.replace('-', '+').replace('_', '/')
            JBase64.getMimeDecoder().decode(alt)
        }
    }

    @JvmStatic fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input, Charsets.ISO_8859_1), flags)

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        var encoder = if (flags and URL_SAFE != 0) JBase64.getUrlEncoder() else JBase64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        var out = encoder.encodeToString(input)
        if (flags and NO_WRAP == 0) out = out.chunked(76).joinToString("\n", postfix = "\n")
        return out.toByteArray(Charsets.ISO_8859_1)
    }

    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String = String(encode(input, flags), Charsets.ISO_8859_1)
}
