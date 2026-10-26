package com.streamflixreborn.streamflix.utils

/**
 * Secure access to secret keys via C++ library compiled with NDK.
 * Strings are XOR-encrypted at compile-time in the .so — not extractable
 * with a standard Java/Kotlin decompiler.
 */
object Keys {
    private val isLoaded = try {
        System.loadLibrary("streamflix-keys")
        true
    } catch (_: Throwable) {
        false
    }

    fun getUprotApiBase(): String = if (isLoaded) runCatching { nativeGetUprotApiBase() }.getOrDefault("") else ""
    fun getUprotDirectApiBase(): String = if (isLoaded) runCatching { nativeGetUprotDirectApiBase() }.getOrDefault("") else ""
    fun getUprotDirectKey(): String = if (isLoaded) runCatching { nativeGetUprotDirectKey() }.getOrDefault("") else ""
    fun getUprotAesKey(): String = if (isLoaded) runCatching { nativeGetUprotAesKey() }.getOrDefault("") else ""

    private external fun nativeGetUprotApiBase(): String
    private external fun nativeGetUprotDirectApiBase(): String
    private external fun nativeGetUprotDirectKey(): String
    private external fun nativeGetUprotAesKey(): String
}

